package art.arcane.wormholes;

import java.util.ArrayList;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.J;

public class ProjectionManager implements Listener {
    private static final int TICK_INTERVAL = 4;
    private static final long DIAGNOSTIC_INTERVAL_MS = 5_000L;

    private final Map<UUID, Map<UUID, PortalProjector>> projectors;
    private long lastDiagnostic;
    private long tickCount;
    private boolean firstTickLogged;

    public ProjectionManager() {
        Wormholes.v("[ProjectionManager] starting (sync interval " + TICK_INTERVAL + "t, range " + Settings.PROJECTION_RANGE + ")");
        this.projectors = new HashMap<UUID, Map<UUID, PortalProjector>>();
        this.lastDiagnostic = 0L;
        this.tickCount = 0L;
        this.firstTickLogged = false;
        int taskId = J.sr(() -> tick(), TICK_INTERVAL);
        Wormholes.v("[ProjectionManager] tick scheduled (taskId=" + taskId + ")");
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
        removeProjector(e.getPlayer());
    }

    @EventHandler
    public void on(PlayerChangedWorldEvent e) {
        removeProjector(e.getPlayer());
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

        List<ILocalPortal> active = collectActiveProjectors();

        for (ILocalPortal portal : active) {
            updatePortal(portal);
        }

        cleanupDeadPortals(active);
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
                + " activeProjectingPortals=" + active.size() + " observers=" + totalObservers + " renderedBlocks=" + totalRendered);

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
            if (!portal.isOpen() || !portal.hasTunnel()) {
                continue;
            }
            active.add(portal);
        }

        return active;
    }

    private void cleanupDeadPortals(List<ILocalPortal> active) {
        Set<UUID> activeIds = new HashSet<UUID>(active.size());
        for (ILocalPortal portal : active) {
            activeIds.add(portal.getId());
        }

        Iterator<Map.Entry<UUID, Map<UUID, PortalProjector>>> it = projectors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<UUID, PortalProjector>> entry = it.next();
            if (activeIds.contains(entry.getKey())) {
                continue;
            }
            for (PortalProjector projector : entry.getValue().values()) {
                Location center = projector.getPortal().getCenter();
                closeOnRegion(projector, center);
            }
            it.remove();
        }
    }

    private void updatePortal(ILocalPortal portal) {
        Location center = portal.getCenter();
        if (center == null || center.getWorld() == null) {
            Wormholes.w("[ProjectionManager] portal " + portal.getName() + " has no valid center/world; skipping");
            return;
        }

        AxisAlignedBB view = portal.getView();
        if (view == null) {
            return;
        }

        Set<UUID> activeObservers = new HashSet<UUID>();
        for (Player observer : center.getWorld().getPlayers()) {
            if (!view.contains(observer.getLocation())) {
                continue;
            }
            activeObservers.add(observer.getUniqueId());
        }

        Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
        if (portalProjectors == null) {
            portalProjectors = new HashMap<UUID, PortalProjector>();
            projectors.put(portal.getId(), portalProjectors);
        }

        Iterator<Map.Entry<UUID, PortalProjector>> it = portalProjectors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PortalProjector> entry = it.next();
            if (!activeObservers.contains(entry.getKey())) {
                closeOnRegion(entry.getValue(), center);
                it.remove();
            }
        }

        FoliaScheduler.runRegion(Wormholes.instance, center, () -> projectActiveObservers(portal, activeObservers));
    }

    private void projectActiveObservers(ILocalPortal portal, Set<UUID> activeObservers) {
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
                projector = new PortalProjector(portal, observer);
                portalProjectors.put(observerId, projector);
                Wormholes.v("[ProjectionManager] new projector portal=" + portal.getName()
                        + " observer=" + observer.getName()
                        + " portalCenter=" + formatLoc(portal.getCenter())
                        + " observerLoc=" + formatLoc(observer.getLocation()));
            }

            try {
                projector.project();
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

    private String describePortal(ILocalPortal portal) {
        StringBuilder sb = new StringBuilder();
        sb.append(portal.getName())
                .append(" type=").append(portal.getType())
                .append(" supportsProjections=").append(portal.supportsProjections())
                .append(" isOpen=").append(portal.isOpen())
                .append(" hasTunnel=").append(portal.hasTunnel())
                .append(" center=").append(formatLoc(portal.getCenter()))
                .append(" direction=").append(portal.getDirection())
                .append(" projecting=").append(portal.isProjecting());
        return sb.toString();
    }

    public String dumpDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ProjectionManager Diagnostics]\n");
        sb.append("  tickCount=").append(tickCount).append('\n');
        sb.append("  firstTickLogged=").append(firstTickLogged).append('\n');
        sb.append("  PROJECTION_RANGE=").append(Settings.PROJECTION_RANGE).append('\n');
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
                            .append(" rendered=").append(projector.getProjectedCount())
                            .append(" closed=").append(projector.isClosed())
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    public void removeProjector(ILocalPortal portal) {
        Map<UUID, PortalProjector> portalProjectors = projectors.remove(portal.getId());
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
