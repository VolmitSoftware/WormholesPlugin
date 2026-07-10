package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.render.view.RegionSnapshotWorldViewProvider;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.J;

public class ProjectionManager implements Listener {
    private static final long DIAGNOSTIC_INTERVAL_MS = 5_000L;

    private final ProjectionClaimArbiter claimArbiter;
    private final ProjectionWorldViewProvider viewProvider;
    private final Map<UUID, Map<UUID, PortalProjector>> projectors;
    private final Map<UUID, Map<UUID, Long>> interestGraceUntil;
    private final Set<UUID> observerTasksInFlight;
    private final AtomicInteger lastInterestedObservers;
    private final AtomicInteger lastScheduledProjectors;
    private final AtomicInteger lastDeferredProjectors;
    private final AtomicBoolean shutdownFinalized;
    private final AtomicBoolean shutdownStarted;
    private long lastDiagnostic;
    private long tickCount;
    private boolean firstTickLogged;
    private volatile boolean closed;
    private int taskId;
    private int currentInterval;

    public ProjectionManager() {
        this.viewProvider = FoliaScheduler.isFoliaThreading(Bukkit.getServer())
            ? new RegionSnapshotWorldViewProvider(Wormholes.instance)
            : ProjectionWorldViewProvider.live();
        this.claimArbiter = new ProjectionClaimArbiter(viewProvider);
        this.projectors = new ConcurrentHashMap<UUID, Map<UUID, PortalProjector>>();
        this.interestGraceUntil = new ConcurrentHashMap<UUID, Map<UUID, Long>>();
        this.observerTasksInFlight = ConcurrentHashMap.newKeySet();
        this.lastInterestedObservers = new AtomicInteger();
        this.lastScheduledProjectors = new AtomicInteger();
        this.lastDeferredProjectors = new AtomicInteger();
        this.shutdownFinalized = new AtomicBoolean();
        this.shutdownStarted = new AtomicBoolean();
        this.lastDiagnostic = 0L;
        this.tickCount = 0L;
        this.firstTickLogged = false;
        this.closed = false;
        this.taskId = -1;
        this.currentInterval = -1;
        scheduleTick();
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
        observerTasksInFlight.remove(e.getPlayer().getUniqueId());
        removeProjector(e.getPlayer());
    }

    @EventHandler
    public void on(PlayerJoinEvent e) {
        removeProjector(e.getPlayer());
    }

    @EventHandler
    public void on(PlayerChangedWorldEvent e) {
        removeProjector(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void on(PlayerAnimationEvent e) {
        EntityAnimationType type = e.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING
                ? EntityAnimationType.SWING_OFF_HAND
                : EntityAnimationType.SWING_MAIN_ARM;
        broadcastProjectedEntityAnimation(e.getPlayer().getUniqueId(), type);
    }

    @EventHandler(ignoreCancelled = true)
    public void on(EntityShootBowEvent e) {
        EntityAnimationType type = e.getHand() == EquipmentSlot.OFF_HAND
                ? EntityAnimationType.SWING_OFF_HAND
                : EntityAnimationType.SWING_MAIN_ARM;
        broadcastProjectedEntityAnimation(e.getEntity().getUniqueId(), type);
    }

    @EventHandler(ignoreCancelled = true)
    public void on(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        broadcastProjectedEntityHurt(entity.getUniqueId(), entity.getLocation().getYaw());
        if (!(e instanceof EntityDamageByEntityEvent)) {
            return;
        }
        EntityDamageByEntityEvent damageByEntityEvent = (EntityDamageByEntityEvent) e;
        Entity source = resolveAttackSource(damageByEntityEvent.getDamager());
        if (source != null && !(source instanceof Player)) {
            broadcastProjectedEntityAnimation(source.getUniqueId(), EntityAnimationType.SWING_MAIN_ARM);
        }
    }

    private void tick() {
        if (closed) {
            return;
        }
        tickCount++;

        if (!firstTickLogged) {
            firstTickLogged = true;
            Wormholes.v("[ProjectionManager] first tick fired");
        }

        if (Wormholes.portalManager == null) {
            if (tickCount % 50L == 1L) {
                Wormholes.w("[ProjectionManager] portalManager is null on tick " + tickCount);
            }
            return;
        }

        lastInterestedObservers.set(0);
        lastScheduledProjectors.set(0);
        lastDeferredProjectors.set(0);
        List<ILocalPortal> active = collectActiveProjectors();
        cleanupInactivePortals(active);
        boolean updateBlocks = shouldUpdateBlocks();
        boolean updateEntities = shouldUpdateEntities();
        long frameTick = tickCount;
        List<Player> onlinePlayers = new ArrayList<Player>(Wormholes.instance.getServer().getOnlinePlayers());
        int totalBudget = updateBlocks ? Math.max(0, Settings.PROJECTION_MAX_PROJECTORS_PER_TICK) : 0;
        int perObserverBudget = Math.max(0, Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK);
        int[] reservedBudgets = fairBudgetAllocations(onlinePlayers.size(), totalBudget, perObserverBudget, frameTick);
        int reservedTotal = 0;
        for (int reserved : reservedBudgets) {
            reservedTotal += reserved;
        }
        AtomicInteger remainingProjectors = new AtomicInteger(Math.max(0, totalBudget - reservedTotal));
        int rotationStart = onlinePlayers.isEmpty() ? 0 : (int) Math.floorMod(frameTick, onlinePlayers.size());
        for (int offset = 0; offset < onlinePlayers.size(); offset++) {
            int index = (rotationStart + offset) % onlinePlayers.size();
            Player observer = onlinePlayers.get(index);
            UUID observerId = observer.getUniqueId();
            int reservedBudget = reservedBudgets[index];
            if (!observerTasksInFlight.add(observerId)) {
                remainingProjectors.addAndGet(reservedBudget);
                continue;
            }
            boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                try {
                    if (closed) {
                        remainingProjectors.addAndGet(reservedBudget);
                        return;
                    }
                    projectObserverFrame(observer, active, remainingProjectors, reservedBudget,
                        updateBlocks, updateEntities, frameTick);
                } finally {
                    observerTasksInFlight.remove(observerId);
                }
            });
            if (!scheduled) {
                observerTasksInFlight.remove(observerId);
                remainingProjectors.addAndGet(reservedBudget);
            }
        }
        pruneInterestGrace(frameTick);
        WormholesTelemetry.setProjectionGauges(active.size(), observerTasksInFlight.size(), countSpoofedEntities());
        emitDiagnostics(active);
    }

    private int countSpoofedEntities() {
        int total = 0;
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            for (PortalProjector projector : portalProjectors.values()) {
                total += projector.getSpoofedEntityCount();
            }
        }
        return total;
    }

    private void emitDiagnostics(List<ILocalPortal> active) {
        long now = System.currentTimeMillis();
        if (now - lastDiagnostic < DIAGNOSTIC_INTERVAL_MS) {
            return;
        }
        lastDiagnostic = now;

        int totalPortals = Wormholes.portalManager.getLocalPortals().size();
        int totalObservers = 0;
        int totalRendered = 0;
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            totalObservers += portalProjectors.size();
            for (PortalProjector projector : portalProjectors.values()) {
                totalRendered += projector.getProjectedCount();
            }
        }

        Wormholes.v("[ProjectionManager] tick=" + tickCount + " totalPortals=" + totalPortals
                + " activeProjectingPortals=" + active.size() + " observers=" + totalObservers + " renderedBlocks=" + totalRendered
                + " interested=" + lastInterestedObservers.get() + " scheduled=" + lastScheduledProjectors.get() + " deferred=" + lastDeferredProjectors.get());

        if (active.isEmpty() && totalPortals > 0) {
            for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
                Wormholes.v("[ProjectionManager]   inactive portal: " + describePortal(portal));
            }
        }
    }

    private List<ILocalPortal> collectActiveProjectors() {
        List<ILocalPortal> active = new ArrayList<ILocalPortal>();

        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (!portal.supportsProjections() || !portal.isProjecting()) {
                continue;
            }
            if (!portal.isOpen()) {
                continue;
            }
            if (portal.getProjectionMode() != ProjectionMode.MIRROR && !portal.hasTunnel()) {
                continue;
            }
            active.add(portal);
        }

        return active;
    }

    private void cleanupInactivePortals(List<ILocalPortal> active) {
        Set<UUID> activeIds = new HashSet<UUID>(active.size());
        for (ILocalPortal portal : active) {
            activeIds.add(portal.getId());
        }
        for (Map.Entry<UUID, Map<UUID, PortalProjector>> entry : projectors.entrySet()) {
            if (activeIds.contains(entry.getKey()) || !projectors.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            interestGraceUntil.remove(entry.getKey());
            for (PortalProjector projector : entry.getValue().values()) {
                closeOnEntity(projector);
            }
        }
    }

    private void projectObserverFrame(Player observer,
                                      List<ILocalPortal> active,
                                      AtomicInteger remainingProjectors,
                                      int reservedBudget,
                                      boolean updateBlocks,
                                      boolean updateEntities,
                                      long frameTick) {
        if (!observer.isOnline()) {
            remainingProjectors.addAndGet(reservedBudget);
            return;
        }
        UUID observerId = observer.getUniqueId();
        World observerWorld = observer.getWorld();
        Location observerLocation = observer.getLocation();
        Location eye = observer.getEyeLocation();
        claimArbiter.retryPending(observer, observerWorld);
        List<ILocalPortal> interested = new ArrayList<ILocalPortal>();
        for (ILocalPortal portal : active) {
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            if (!observerWorld.equals(center.getWorld())) {
                continue;
            }
            AxisAlignedBB view = portal.getView();
            if (view == null || !view.contains(observerLocation)) {
                continue;
            }
            boolean liveInterest = isObserverProjectionInterested(eye, center, portal);
            if (!liveInterest && !isInsideInterestGrace(portal, observerId, frameTick)) {
                continue;
            }
            if (liveInterest) {
                refreshInterestGrace(portal, observerId, frameTick);
                if (Wormholes.arrivalWarmer != null) {
                    Wormholes.arrivalWarmer.warmDestinationOf(portal);
                }
            }
            interested.add(portal);
            lastInterestedObservers.incrementAndGet();
        }
        interested.sort(Comparator.comparingDouble(portal -> distanceSquared(eye, portal)));
        Set<UUID> interestedIds = new HashSet<UUID>(interested.size());
        for (ILocalPortal portal : interested) {
            interestedIds.add(portal.getId());
        }
        closeUnplannedForObserver(observerId, interestedIds);
        if ((!updateBlocks && !updateEntities) || interested.isEmpty()) {
            remainingProjectors.addAndGet(reservedBudget);
            return;
        }
        int desiredBlocks = updateBlocks
            ? Math.min(Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK, interested.size())
            : 0;
        int reservedUsed = Math.min(reservedBudget, desiredBlocks);
        int claimedBlocks = reservedUsed + claimProjectorBudget(remainingProjectors, desiredBlocks - reservedUsed);
        if (reservedBudget > reservedUsed) {
            remainingProjectors.addAndGet(reservedBudget - reservedUsed);
        }
        boolean observerUpdatesBlocks = claimedBlocks > 0;
        int limit = observerUpdatesBlocks ? claimedBlocks : (updateEntities ? interested.size() : 0);
        if (limit == 0) {
            lastDeferredProjectors.addAndGet(interested.size());
            return;
        }
        List<ILocalPortal> scheduledPortals = new ArrayList<ILocalPortal>(limit);
        for (int i = 0; i < limit; i++) {
            scheduledPortals.add(interested.get(i));
        }
        int deferred = Math.max(0, interested.size() - scheduledPortals.size());
        if (observerUpdatesBlocks) {
            lastScheduledProjectors.addAndGet(scheduledPortals.size());
            lastDeferredProjectors.addAndGet(deferred);
        }
        projectActiveObserver(observer, scheduledPortals, observerUpdatesBlocks, updateEntities);
    }

    static int[] fairBudgetAllocations(int observerCount, int totalBudget, int perObserverBudget, long frameTick) {
        if (observerCount <= 0 || totalBudget <= 0 || perObserverBudget <= 0) {
            return new int[Math.max(0, observerCount)];
        }
        int[] allocations = new int[observerCount];
        long maximum = (long) observerCount * perObserverBudget;
        int remaining = (int) Math.min(totalBudget, Math.min(Integer.MAX_VALUE, maximum));
        int start = (int) Math.floorMod(frameTick, observerCount);
        while (remaining > 0) {
            boolean allocated = false;
            for (int offset = 0; offset < observerCount && remaining > 0; offset++) {
                int index = (start + offset) % observerCount;
                if (allocations[index] >= perObserverBudget) {
                    continue;
                }
                allocations[index]++;
                remaining--;
                allocated = true;
            }
            if (!allocated) {
                break;
            }
        }
        return allocations;
    }

    private void projectActiveObserver(Player observer, List<ILocalPortal> scheduledPortals, boolean updateBlocks, boolean updateEntities) {
        if (closed || observer == null || !observer.isOnline()) {
            return;
        }
        claimArbiter.beginFrame(observer, observer.getWorld(), false);
        try {
            for (ILocalPortal portal : scheduledPortals) {
                if (!isPortalStillProjectable(portal)) {
                    continue;
                }
                Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
                if (portalProjectors == null) {
                    portalProjectors = new ConcurrentHashMap<UUID, PortalProjector>();
                    Map<UUID, PortalProjector> existing = projectors.putIfAbsent(portal.getId(), portalProjectors);
                    if (existing != null) {
                        portalProjectors = existing;
                    }
                }

                UUID activeObserverId = observer.getUniqueId();
                PortalProjector projector = portalProjectors.get(activeObserverId);
                if (projector == null) {
                    if (closed) {
                        continue;
                    }
                    projector = new PortalProjector(portal, observer, claimArbiter, viewProvider, () -> !closed);
                    portalProjectors.put(activeObserverId, projector);
                    Wormholes.v("[ProjectionManager] new projector portal=" + portal.getName()
                            + " observer=" + observer.getName()
                            + " portalCenter=" + formatLoc(portal.getCenter())
                            + " observerLoc=" + formatLoc(observer.getLocation()));
                }

                try {
                    projector.project(updateBlocks, updateEntities);
                } catch (Throwable ex) {
                    Wormholes.instance.getLogger().log(Level.WARNING,
                            "[ProjectionManager] projection error portal=" + portal.getName() + " observer=" + observer.getName(), ex);
                }
            }
        } finally {
            claimArbiter.flushFrame(observer);
        }
    }

    private int claimProjectorBudget(AtomicInteger remaining, int requested) {
        while (requested > 0) {
            int available = remaining.get();
            if (available <= 0) {
                return 0;
            }
            int claimed = Math.min(available, requested);
            if (remaining.compareAndSet(available, available - claimed)) {
                return claimed;
            }
        }
        return 0;
    }

    private void closeUnplannedForObserver(UUID observerId, Set<UUID> interestedPortalIds) {
        for (Map.Entry<UUID, Map<UUID, PortalProjector>> entry : projectors.entrySet()) {
            if (interestedPortalIds.contains(entry.getKey())) {
                continue;
            }
            PortalProjector projector = entry.getValue().remove(observerId);
            if (projector == null) {
                continue;
            }
            projector.close();
            if (entry.getValue().isEmpty()) {
                projectors.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean isPortalStillProjectable(ILocalPortal portal) {
        if (portal == null || !portal.supportsProjections() || !portal.isProjecting() || !portal.isOpen()) {
            return false;
        }
        if (portal.getProjectionMode() != ProjectionMode.MIRROR && !portal.hasTunnel()) {
            return false;
        }
        return true;
    }

    private boolean isInsideInterestGrace(ILocalPortal portal, UUID observerId, long frameTick) {
        if (!hasProjector(portal, observerId)) {
            return false;
        }
        Map<UUID, Long> byObserver = interestGraceUntil.get(portal.getId());
        if (byObserver == null) {
            return false;
        }
        Long until = byObserver.get(observerId);
        return until != null && until.longValue() >= frameTick;
    }

    private void refreshInterestGrace(ILocalPortal portal, UUID observerId, long frameTick) {
        int graceTicks = Math.max(0, Settings.PROJECTION_INTEREST_GRACE_TICKS);
        if (graceTicks <= 0) {
            return;
        }
        Map<UUID, Long> byObserver = interestGraceUntil.get(portal.getId());
        if (byObserver == null) {
            byObserver = new ConcurrentHashMap<UUID, Long>(4);
            Map<UUID, Long> existing = interestGraceUntil.putIfAbsent(portal.getId(), byObserver);
            if (existing != null) {
                byObserver = existing;
            }
        }
        byObserver.put(observerId, Long.valueOf(frameTick + graceTicks));
    }

    private boolean hasProjector(ILocalPortal portal, UUID observerId) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
        return portalProjectors != null && portalProjectors.containsKey(observerId);
    }

    private void pruneInterestGrace(long frameTick) {
        for (Map.Entry<UUID, Map<UUID, Long>> portalEntry : interestGraceUntil.entrySet()) {
            Map<UUID, Long> byObserver = portalEntry.getValue();
            for (Map.Entry<UUID, Long> observerEntry : byObserver.entrySet()) {
                if (observerEntry.getValue().longValue() < frameTick) {
                    byObserver.remove(observerEntry.getKey(), observerEntry.getValue());
                }
            }
            if (byObserver.isEmpty()) {
                interestGraceUntil.remove(portalEntry.getKey(), byObserver);
            }
        }
    }

    private static double distanceSquared(Location eye, ILocalPortal portal) {
        Location center = portal.getCenter();
        if (center == null || eye == null || center.getWorld() == null || eye.getWorld() == null || !center.getWorld().equals(eye.getWorld())) {
            return Double.MAX_VALUE;
        }
        return center.distanceSquared(eye);
    }

    private String formatLoc(Location loc) {
        if (loc == null) {
            return "null";
        }
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    static boolean isLookingTowardPortal(Location eye, Location center, double minimumDot) {
        if (eye == null || center == null) {
            return false;
        }
        if (eye.getWorld() != null && center.getWorld() != null && !eye.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = center.getX() - eye.getX();
        double dy = center.getY() - eye.getY();
        double dz = center.getZ() - eye.getZ();
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return true;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        Vector direction = eye.getDirection();
        double dot = ((direction.getX() * dx) + (direction.getY() * dy) + (direction.getZ() * dz)) * inverseDistance;
        return dot >= minimumDot;
    }

    static boolean isObserverProjectionInterested(Location eye, Location center, ILocalPortal portal, boolean foveatedUnrendering) {
        if (!foveatedUnrendering) {
            return true;
        }
        return hasStablePortalSide(eye, portal, Settings.PROJECTION_SIDE_GRACE_DOT)
                && isLookingTowardPortal(eye, center, Settings.PROJECTION_OBSERVER_INTEREST_DOT);
    }

    static boolean isObserverProjectionInterested(Location eye, Location center, ILocalPortal portal) {
        return isObserverProjectionInterested(eye, center, portal, Settings.PROJECTION_FOVEATED_UNRENDERING);
    }

    static boolean hasStablePortalSide(Location eye, ILocalPortal portal, double minimumAbsoluteDot) {
        if (eye == null || portal == null || portal.getOrigin() == null || portal.getFrame() == null) {
            return false;
        }
        if (minimumAbsoluteDot <= 0.0D) {
            return true;
        }
        Direction normal = portal.getFrame().getNormal();
        return hasStablePortalSide(eye.getX(), eye.getY(), eye.getZ(),
                portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(),
                normal.x(), normal.y(), normal.z(), minimumAbsoluteDot);
    }

    static boolean hasStablePortalSide(double eyeX,
                                       double eyeY,
                                       double eyeZ,
                                       double originX,
                                       double originY,
                                       double originZ,
                                       double normalX,
                                       double normalY,
                                       double normalZ,
                                       double minimumAbsoluteDot) {
        if (minimumAbsoluteDot <= 0.0D) {
            return true;
        }
        double dx = eyeX - originX;
        double dy = eyeY - originY;
        double dz = eyeZ - originZ;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return true;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        double dot = ((dx * normalX) + (dy * normalY) + (dz * normalZ)) * inverseDistance;
        return Math.abs(dot) >= minimumAbsoluteDot;
    }

    private String describePortal(ILocalPortal portal) {
        StringBuilder sb = new StringBuilder();
        sb.append(portal.getName())
                .append(" type=").append(portal.getType())
                .append(" supportsProjections=").append(portal.supportsProjections())
                .append(" isOpen=").append(portal.isOpen())
                .append(" hasTunnel=").append(portal.hasTunnel())
                .append(" center=").append(formatLoc(portal.getCenter()))
                .append(" direction=").append(portal.getDirection())
                .append(" projecting=").append(portal.isProjecting())
                .append(" mode=").append(portal.getProjectionMode());
        return sb.toString();
    }

    public void removeProjector(ILocalPortal portal) {
        Map<UUID, PortalProjector> portalProjectors = projectors.remove(portal.getId());
        interestGraceUntil.remove(portal.getId());
        if (portalProjectors == null) {
            return;
        }
        for (PortalProjector projector : portalProjectors.values()) {
            closeOnEntity(projector);
        }
    }

    public void removeProjector(Player player) {
        UUID id = player.getUniqueId();
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            PortalProjector projector = portalProjectors.remove(id);
            if (projector == null) {
                continue;
            }
            closeOnEntity(projector);
        }
        claimArbiter.releaseObserver(id);
        for (Map.Entry<UUID, Map<UUID, Long>> graceEntry : interestGraceUntil.entrySet()) {
            Map<UUID, Long> byObserver = graceEntry.getValue();
            byObserver.remove(id);
            if (byObserver.isEmpty()) {
                interestGraceUntil.remove(graceEntry.getKey(), byObserver);
            }
        }
    }

    public void reprimeArrival(Player player) {
        if (player == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, player, () -> removeProjector(player), 20L);
    }

    public void removeProjector(ILocalPortal portal, Player player) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
        if (portalProjectors == null) {
            return;
        }
        PortalProjector projector = portalProjectors.remove(player.getUniqueId());
        if (projector == null) {
            return;
        }
        closeOnEntity(projector);
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        if (taskId >= 0) {
            J.csr(taskId);
            taskId = -1;
        }
        List<PortalProjector> closing = snapshotProjectors();
        AtomicInteger pending = new AtomicInteger(closing.size());
        CountDownLatch completion = new CountDownLatch(closing.size());
        if (closing.isEmpty()) {
            finalizeShutdown();
        } else {
            for (PortalProjector projector : closing) {
                scheduleClose(projector, () -> {
                    completion.countDown();
                    if (pending.decrementAndGet() == 0) {
                        finalizeShutdown();
                    }
                });
            }
        }
        projectors.clear();
        interestGraceUntil.clear();
        observerTasksInFlight.clear();
        try {
            completion.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            finalizeShutdown();
        }
    }

    public void onSettingsReloaded() {
        scheduleTick();
    }

    private boolean shouldUpdateBlocks() {
        int interval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        return (tickCount - 1L) % interval == 0L;
    }

    private boolean shouldUpdateEntities() {
        int interval = Math.max(1, Settings.ENTITY_UPDATE_INTERVAL_TICKS);
        return (tickCount - 1L) % interval == 0L;
    }

    private void broadcastProjectedEntityAnimation(UUID entityId, EntityAnimationType type) {
        if (entityId == null || type == null) {
            return;
        }
        ViewServer viewServer = Wormholes.viewServer;
        if (viewServer != null) {
            viewServer.forwardAnimation(entityId, type);
        }
        dispatchProjectedEntityAnimation(entityId, type);
    }

    public void dispatchProjectedEntityAnimation(UUID entityId, EntityAnimationType type) {
        if (entityId == null || type == null) {
            return;
        }
        for (PortalProjector projector : snapshotProjectors()) {
            Player observer = projector.getObserver();
            if (observer == null) {
                continue;
            }
            FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                if (observer.isOnline() && !projector.isClosed() && projector.hasProjectedEntity(entityId)) {
                    projector.sendProjectedEntityAnimation(entityId, type);
                }
            });
        }
    }

    private void broadcastProjectedEntityHurt(UUID entityId, float yaw) {
        if (entityId == null) {
            return;
        }
        ViewServer viewServer = Wormholes.viewServer;
        if (viewServer != null) {
            viewServer.forwardHurt(entityId, yaw);
        }
        dispatchProjectedEntityHurt(entityId, yaw);
    }

    public void dispatchProjectedEntityHurt(UUID entityId, float yaw) {
        if (entityId == null) {
            return;
        }
        for (PortalProjector projector : snapshotProjectors()) {
            Player observer = projector.getObserver();
            if (observer == null) {
                continue;
            }
            FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                if (observer.isOnline() && !projector.isClosed() && projector.hasProjectedEntity(entityId)) {
                    projector.sendProjectedEntityHurt(entityId, yaw);
                }
            });
        }
    }

    private List<PortalProjector> snapshotProjectors() {
        List<PortalProjector> snapshot = new ArrayList<PortalProjector>();
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            snapshot.addAll(portalProjectors.values());
        }
        return snapshot;
    }

    private Entity resolveAttackSource(Entity damager) {
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity) {
                return (Entity) shooter;
            }
        }
        return damager;
    }

    private void scheduleTick() {
        if (closed) {
            return;
        }
        int interval = 1;
        if (taskId >= 0 && currentInterval == interval) {
            return;
        }
        if (taskId >= 0) {
            J.csr(taskId);
        }
        currentInterval = interval;
        taskId = J.sr(() -> tick(), interval);
        Wormholes.v("[ProjectionManager] tick scheduled (taskId=" + taskId + ", interval=" + interval + "t, blockInterval=" + Settings.PROJECTION_REFRESH_INTERVAL_TICKS + "t, entityInterval=" + Settings.ENTITY_UPDATE_INTERVAL_TICKS + "t, range=" + Settings.PROJECTION_RANGE + ")");
    }

    private void closeOnEntity(PortalProjector projector) {
        scheduleClose(projector, () -> {
        });
    }

    private void scheduleClose(PortalProjector projector, Runnable onComplete) {
        Player observer = projector.getObserver();
        if (observer == null) {
            projector.requestDiscard();
            onComplete.run();
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
            try {
                if (observer.isOnline()) {
                    projector.close();
                } else {
                    projector.discard();
                }
            } finally {
                onComplete.run();
            }
        });
        if (!scheduled) {
            projector.requestDiscard();
            onComplete.run();
        }
    }

    private void finalizeShutdown() {
        if (!shutdownFinalized.compareAndSet(false, true)) {
            return;
        }
        claimArbiter.clear();
        viewProvider.close();
    }
}
