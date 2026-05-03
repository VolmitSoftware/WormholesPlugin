package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.J;

public class ProjectionManager implements Listener {
    private static final long DIAGNOSTIC_INTERVAL_MS = 5_000L;

    private final ProjectionClaimArbiter claimArbiter;
    private final Map<UUID, Map<UUID, PortalProjector>> projectors;
    private final Map<String, Long> interestGraceUntil;
    private long lastDiagnostic;
    private long tickCount;
    private boolean firstTickLogged;
    private int taskId;
    private int currentInterval;
    private int lastInterestedObservers;
    private int lastScheduledProjectors;
    private int lastDeferredProjectors;

    public ProjectionManager() {
        this.claimArbiter = new ProjectionClaimArbiter();
        this.projectors = new HashMap<UUID, Map<UUID, PortalProjector>>();
        this.interestGraceUntil = new HashMap<String, Long>();
        this.lastDiagnostic = 0L;
        this.tickCount = 0L;
        this.firstTickLogged = false;
        this.taskId = -1;
        this.currentInterval = -1;
        this.lastInterestedObservers = 0;
        this.lastScheduledProjectors = 0;
        this.lastDeferredProjectors = 0;
        scheduleTick();
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
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

        lastInterestedObservers = 0;
        lastScheduledProjectors = 0;
        lastDeferredProjectors = 0;
        List<ILocalPortal> active = collectActiveProjectors();
        Map<UUID, ObserverProjectionPlan> plans = collectObserverPlans(active);
        int remainingProjectors = Settings.PROJECTION_MAX_PROJECTORS_PER_TICK;
        boolean updateBlocks = shouldUpdateBlocks();
        boolean updateEntities = shouldUpdateEntities();

        for (ObserverProjectionPlan plan : plans.values()) {
            int scheduled = updateObserver(plan, remainingProjectors, updateBlocks, updateEntities);
            if (updateBlocks) {
                remainingProjectors = Math.max(0, remainingProjectors - scheduled);
            }
        }

        cleanupDeadPortals(active, plans);
        emitDiagnostics(active);
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
                + " interested=" + lastInterestedObservers + " scheduled=" + lastScheduledProjectors + " deferred=" + lastDeferredProjectors);

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

    private Map<UUID, ObserverProjectionPlan> collectObserverPlans(List<ILocalPortal> active) {
        Map<UUID, ObserverProjectionPlan> plans = new HashMap<UUID, ObserverProjectionPlan>();
        for (ILocalPortal portal : active) {
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                Wormholes.w("[ProjectionManager] portal " + portal.getName() + " has no valid center/world; skipping");
                continue;
            }
            AxisAlignedBB view = portal.getView();
            if (view == null) {
                continue;
            }
            for (Player observer : center.getWorld().getPlayers()) {
                Location observerLocation = observer.getLocation();
                if (!view.contains(observerLocation)) {
                    continue;
                }
                boolean liveInterest = isObserverProjectionInterested(observer.getEyeLocation(), center, portal);
                if (!liveInterest && !isInsideInterestGrace(portal, observer)) {
                    continue;
                }
                if (liveInterest) {
                    refreshInterestGrace(portal, observer);
                }
                ObserverProjectionPlan plan = plans.get(observer.getUniqueId());
                if (plan == null) {
                    plan = new ObserverProjectionPlan(observer);
                    plans.put(observer.getUniqueId(), plan);
                }
                plan.add(portal);
                lastInterestedObservers++;
            }
        }
        return plans;
    }

    private void cleanupDeadPortals(List<ILocalPortal> active, Map<UUID, ObserverProjectionPlan> plans) {
        Set<UUID> activeIds = new HashSet<UUID>(active.size());
        for (ILocalPortal portal : active) {
            activeIds.add(portal.getId());
        }

        Map<UUID, Set<UUID>> plannedByPortal = new HashMap<UUID, Set<UUID>>();
        for (ObserverProjectionPlan plan : plans.values()) {
            for (ILocalPortal portal : plan.portals) {
                Set<UUID> observers = plannedByPortal.get(portal.getId());
                if (observers == null) {
                    observers = new HashSet<UUID>();
                    plannedByPortal.put(portal.getId(), observers);
                }
                observers.add(plan.observer.getUniqueId());
            }
        }

        Iterator<Map.Entry<UUID, Map<UUID, PortalProjector>>> it = projectors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<UUID, PortalProjector>> entry = it.next();
            if (!activeIds.contains(entry.getKey())) {
                for (PortalProjector projector : entry.getValue().values()) {
                    Location center = projector.getPortal().getCenter();
                    closeOnRegion(projector, center);
                }
                it.remove();
                continue;
            }
            Set<UUID> plannedObservers = plannedByPortal.get(entry.getKey());
            Iterator<Map.Entry<UUID, PortalProjector>> projectorIterator = entry.getValue().entrySet().iterator();
            while (projectorIterator.hasNext()) {
                Map.Entry<UUID, PortalProjector> projectorEntry = projectorIterator.next();
                if (plannedObservers != null && plannedObservers.contains(projectorEntry.getKey())) {
                    continue;
                }
                Location center = projectorEntry.getValue().getPortal().getCenter();
                closeOnRegion(projectorEntry.getValue(), center);
                projectorIterator.remove();
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
        pruneInterestGrace(plannedByPortal);
    }

    private int updateObserver(ObserverProjectionPlan plan, int remainingProjectors, boolean updateBlocks, boolean updateEntities) {
        if (!updateBlocks && !updateEntities) {
            return 0;
        }
        Player observer = plan.observer;
        if (observer == null || !observer.isOnline()) {
            return 0;
        }
        if (plan.portals.isEmpty()) {
            return 0;
        }

        plan.sort();
        boolean observerUpdatesBlocks = updateBlocks && remainingProjectors > 0;
        if (!observerUpdatesBlocks && !updateEntities) {
            lastDeferredProjectors += plan.portals.size();
            return 0;
        }
        int observerLimit = observerUpdatesBlocks ? Math.min(Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK, remainingProjectors) : plan.portals.size();
        int limit = Math.min(observerLimit, plan.portals.size());
        List<ILocalPortal> scheduledPortals = new ArrayList<ILocalPortal>(limit);
        for (int i = 0; i < limit; i++) {
            scheduledPortals.add(plan.portals.get(i));
        }
        int deferred = Math.max(0, plan.portals.size() - scheduledPortals.size());
        if (observerUpdatesBlocks) {
            lastScheduledProjectors += scheduledPortals.size();
            lastDeferredProjectors += deferred;
        }
        if (scheduledPortals.isEmpty()) {
            return 0;
        }

        Location observerLocation = observer.getLocation();
        FoliaScheduler.runRegion(Wormholes.instance, observerLocation, () -> projectActiveObserver(observer.getUniqueId(), scheduledPortals, observerUpdatesBlocks, updateEntities));
        return observerUpdatesBlocks ? scheduledPortals.size() : 0;
    }

    private void projectActiveObserver(UUID observerId, List<ILocalPortal> scheduledPortals, boolean updateBlocks, boolean updateEntities) {
        Player observer = Bukkit.getPlayer(observerId);
        if (observer == null || !observer.isOnline()) {
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
                    portalProjectors = new HashMap<UUID, PortalProjector>();
                    projectors.put(portal.getId(), portalProjectors);
                }

                UUID activeObserverId = observer.getUniqueId();
                PortalProjector projector = portalProjectors.get(activeObserverId);
                if (projector == null) {
                    projector = new PortalProjector(portal, observer, claimArbiter);
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

    private boolean isPortalStillProjectable(ILocalPortal portal) {
        if (portal == null || !portal.supportsProjections() || !portal.isProjecting() || !portal.isOpen()) {
            return false;
        }
        return portal.getProjectionMode() == ProjectionMode.MIRROR || portal.hasTunnel();
    }

    private boolean isInsideInterestGrace(ILocalPortal portal, Player observer) {
        if (!hasProjector(portal, observer.getUniqueId())) {
            return false;
        }
        Long until = interestGraceUntil.get(graceKey(portal.getId(), observer.getUniqueId()));
        return until != null && until.longValue() >= tickCount;
    }

    private void refreshInterestGrace(ILocalPortal portal, Player observer) {
        int graceTicks = Math.max(0, Settings.PROJECTION_INTEREST_GRACE_TICKS);
        if (graceTicks <= 0) {
            return;
        }
        interestGraceUntil.put(graceKey(portal.getId(), observer.getUniqueId()), Long.valueOf(tickCount + graceTicks));
    }

    private boolean hasProjector(ILocalPortal portal, UUID observerId) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
        return portalProjectors != null && portalProjectors.containsKey(observerId);
    }

    private void pruneInterestGrace(Map<UUID, Set<UUID>> plannedByPortal) {
        Iterator<Map.Entry<String, Long>> iterator = interestGraceUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue().longValue() >= tickCount) {
                continue;
            }
            iterator.remove();
        }
    }

    private static String graceKey(UUID portalId, UUID observerId) {
        return portalId.toString() + "/" + observerId.toString();
    }

    private static double distanceSquared(Player observer, ILocalPortal portal) {
        Location center = portal.getCenter();
        Location eye = observer.getEyeLocation();
        if (center == null || eye == null || center.getWorld() == null || eye.getWorld() == null || !center.getWorld().equals(eye.getWorld())) {
            return Double.MAX_VALUE;
        }
        return center.distanceSquared(eye);
    }

    private static final class ObserverProjectionPlan {
        private final Player observer;
        private final List<ILocalPortal> portals;

        private ObserverProjectionPlan(Player observer) {
            this.observer = observer;
            this.portals = new ArrayList<ILocalPortal>();
        }

        private void add(ILocalPortal portal) {
            portals.add(portal);
        }

        private void sort() {
            portals.sort(Comparator.comparingDouble(portal -> distanceSquared(observer, portal)));
        }
    }

    private void projectActiveObservers(ILocalPortal portal, Set<UUID> activeObservers, boolean updateBlocks, boolean updateEntities) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
        if (portalProjectors == null) {
            return;
        }

        for (UUID observerId : activeObservers) {
            Player observer = Bukkit.getPlayer(observerId);
            if (observer == null || !observer.isOnline()) {
                continue;
            }

            PortalProjector projector = portalProjectors.get(observerId);
            if (projector == null) {
                projector = new PortalProjector(portal, observer, claimArbiter);
                portalProjectors.put(observerId, projector);
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
    }

    private String formatLoc(Location loc) {
        if (loc == null) {
            return "null";
        }
        return (loc.getWorld() == null ? "null" : loc.getWorld().getName()) + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
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

    public String dumpDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ProjectionManager Diagnostics]\n");
        sb.append("  tickCount=").append(tickCount).append('\n');
        sb.append("  firstTickLogged=").append(firstTickLogged).append('\n');
        sb.append("  PROJECTION_RANGE=").append(Settings.PROJECTION_RANGE).append('\n');
        sb.append("  blockRefreshInterval=").append(Settings.PROJECTION_REFRESH_INTERVAL_TICKS).append('\n');
        sb.append("  entityUpdateInterval=").append(Settings.ENTITY_UPDATE_INTERVAL_TICKS).append('\n');
        sb.append("  foveatedUnrendering=").append(Settings.PROJECTION_FOVEATED_UNRENDERING).append('\n');
        sb.append("  recursivePortalDepth=").append(Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH).append('\n');
        sb.append("  maxProjectorsPerTick=").append(Settings.PROJECTION_MAX_PROJECTORS_PER_TICK).append('\n');
        sb.append("  maxPortalsPerObserverTick=").append(Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK).append('\n');
        sb.append("  interestGraceTicks=").append(Settings.PROJECTION_INTEREST_GRACE_TICKS).append('\n');
        sb.append("  lightingMaxSectionsPerPass=").append(Settings.LIGHTING_MAX_SECTIONS_PER_PASS).append('\n');
        sb.append("  adaptiveLighting=").append(Settings.ADAPTIVE_LIGHTING).append('\n');
        sb.append("  entityCandidateCacheTicks=").append(Settings.ENTITY_CANDIDATE_CACHE_TICKS).append('\n');
        sb.append("  interestedObservers=").append(lastInterestedObservers).append('\n');
        sb.append("  scheduledProjectors=").append(lastScheduledProjectors).append('\n');
        sb.append("  deferredProjectors=").append(lastDeferredProjectors).append('\n');
        sb.append("  ").append(claimArbiter.getDiagnostics()).append('\n');
        if (Wormholes.portalManager != null) {
            sb.append("  ").append(Wormholes.portalManager.getLoadDiagnostics()).append('\n');
        }
        sb.append("  projectorEntries=").append(projectors.size()).append('\n');
        if (Wormholes.portalManager == null) {
            sb.append("  portalManager=null\n");
            return sb.toString();
        }
        sb.append("  totalPortals=").append(Wormholes.portalManager.getLocalPortals().size()).append('\n');
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            sb.append("    - ").append(describePortal(portal)).append('\n');
            Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
            if (portalProjectors == null || portalProjectors.isEmpty()) {
                sb.append("      observers: none\n");
            } else {
                for (PortalProjector projector : portalProjectors.values()) {
                    sb.append("      observer=").append(projector.getObserver().getName())
                            .append(' ').append(projector.getDiagnostics())
                            .append(" closed=").append(projector.isClosed())
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    public void removeProjector(ILocalPortal portal) {
        Map<UUID, PortalProjector> portalProjectors = projectors.remove(portal.getId());
        interestGraceUntil.entrySet().removeIf(entry -> entry.getKey().startsWith(portal.getId().toString() + "/"));
        if (portalProjectors == null) {
            return;
        }
        Location center = portal.getCenter();
        for (PortalProjector projector : portalProjectors.values()) {
            closeOnRegion(projector, center);
        }
    }

    public void removeProjector(Player player) {
        UUID id = player.getUniqueId();
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            PortalProjector projector = portalProjectors.remove(id);
            if (projector == null) {
                continue;
            }
            Location center = projector.getPortal().getCenter();
            closeOnRegion(projector, center);
        }
        claimArbiter.releaseObserver(id);
        interestGraceUntil.entrySet().removeIf(entry -> entry.getKey().endsWith("/" + id.toString()));
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
        Location center = projector.getPortal().getCenter();
        closeOnRegion(projector, center);
    }

    public void shutdown() {
        if (taskId >= 0) {
            J.csr(taskId);
            taskId = -1;
        }
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            for (PortalProjector projector : portalProjectors.values()) {
                try {
                    projector.close();
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            }
        }
        projectors.clear();
        interestGraceUntil.clear();
        claimArbiter.clear();
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
        for (PortalProjector projector : snapshotProjectors()) {
            Player observer = projector.getObserver();
            if (observer == null || !observer.isOnline()) {
                continue;
            }
            FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                if (!projector.isClosed() && projector.hasProjectedEntity(entityId)) {
                    projector.sendProjectedEntityAnimation(entityId, type);
                }
            });
        }
    }

    private void broadcastProjectedEntityHurt(UUID entityId, float yaw) {
        if (entityId == null) {
            return;
        }
        for (PortalProjector projector : snapshotProjectors()) {
            Player observer = projector.getObserver();
            if (observer == null || !observer.isOnline()) {
                continue;
            }
            FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                if (!projector.isClosed() && projector.hasProjectedEntity(entityId)) {
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

    private void closeOnRegion(PortalProjector projector, Location center) {
        if (center == null || center.getWorld() == null) {
            try {
                projector.close();
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
            return;
        }

        FoliaScheduler.runRegion(Wormholes.instance, center, () -> {
            try {
                projector.close();
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        });
    }
}
