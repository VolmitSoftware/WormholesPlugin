package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PortalSyncService {
    public static final String KEY_PROJECTION_MODE = "projectionMode";
    public static final String KEY_PERMISSION_MODE = "permissionMode";
    public static final String KEY_OUTGOING_TRAVERSALS = "outgoingTraversalsEnabled";
    public static final String KEY_INCOMING_TRAVERSALS = "incomingTraversalsEnabled";
    public static final String KEY_VIEW_DEPTH = "networkViewDepth";
    public static final String KEY_VIEW_LATERAL_PAD = "networkViewLateralPad";
    public static final String KEY_VIEW_HEARTBEAT = "networkViewHeartbeatTicks";
    public static final String KEY_VIEW_ENTITY_INTERVAL = "networkViewEntityIntervalTicks";
    public static final String KEY_VIEW_UNSUBSCRIBE_GRACE = "networkViewUnsubscribeGraceSeconds";
    public static final String KEY_VIEW_FALLBACK_BLOCK = "networkViewFallbackBlock";
    public static final String KEY_SETTINGS_SYNC = "settingsSyncEnabled";

    private static final ThreadLocal<Boolean> APPLYING_REMOTE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final NetworkManager network;
    private final Supplier<List<ILocalPortal>> portalSource;
    private final Consumer<Runnable> globalDispatcher;

    public PortalSyncService(NetworkManager network, Supplier<List<ILocalPortal>> portalSource, Consumer<Runnable> globalDispatcher) {
        this.network = network;
        this.portalSource = portalSource;
        this.globalDispatcher = globalDispatcher;
    }

    public void onPeerStateChanged(String peerName, boolean ready) {
        if (!ready) {
            return;
        }
        globalDispatcher.accept(() -> sendDirectory(peerName));
    }

    public void sendDirectory(String peerName) {
        List<PortalInfo> shared = new ArrayList<>();
        for (ILocalPortal portal : portalSource.get()) {
            if (isShareable(portal)) {
                shared.add(toInfo(portal));
            }
        }
        network.send(peerName, new WireMessage.PortalDirectory(shared));
    }

    public void broadcastPortal(ILocalPortal portal) {
        if (!network.isRunning()) {
            return;
        }
        if (isShareable(portal)) {
            WireMessage.PortalUpsert upsert = new WireMessage.PortalUpsert(toInfo(portal));
            for (NetworkManager.PeerStatus peer : network.status()) {
                network.send(peer.name(), upsert);
            }
        } else {
            broadcastRemove(portal.getId());
        }
    }

    public void broadcastRemove(UUID portalId) {
        if (!network.isRunning()) {
            return;
        }
        WireMessage.PortalRemove remove = new WireMessage.PortalRemove(portalId);
        for (NetworkManager.PeerStatus peer : network.status()) {
            network.send(peer.name(), remove);
        }
    }

    public static boolean isApplyingRemote() {
        return APPLYING_REMOTE.get().booleanValue();
    }

    public void broadcastSettings(ILocalPortal portal) {
        if (portal == null || !network.isRunning() || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!(portal instanceof LocalPortal local) || !local.isSettingsSyncEnabled()) {
            return;
        }
        sendSettings(local);
    }

    public void broadcastSettingsToggle(ILocalPortal portal) {
        if (portal == null || !network.isRunning() || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!(portal instanceof LocalPortal local)) {
            return;
        }
        sendSettings(local);
    }

    private void sendSettings(LocalPortal local) {
        Map<String, String> settings = collectSettings(local);
        WireMessage.PortalSettingsUpdate update = new WireMessage.PortalSettingsUpdate(local.getId(), settings);
        String linkedPeer = linkedPeerName(local);
        for (NetworkManager.PeerStatus peer : network.status()) {
            if (linkedPeer != null && !linkedPeer.equals(peer.name())) {
                continue;
            }
            network.send(peer.name(), update);
        }
    }

    public void applySettingsUpdate(String peerName, WireMessage.PortalSettingsUpdate update) {
        if (update == null) {
            return;
        }
        UUID portalId = update.portalId();
        Map<String, String> settings = update.settings();
        LocalPortal target = findLinkedLocal(peerName, portalId);
        if (target != null) {
            applyToLocal(target, settings);
            target.refreshOpenMenus();
            return;
        }
        if (Wormholes.remotePortalRegistry == null) {
            return;
        }
        RemotePortal remote = Wormholes.remotePortalRegistry.get(peerName, portalId);
        if (remote != null) {
            applyToRemote(remote, settings);
        }
    }

    private static LocalPortal findLinkedLocal(String peerName, UUID senderPortalId) {
        if (peerName == null || senderPortalId == null || Wormholes.portalManager == null) {
            return null;
        }
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (!(portal instanceof LocalPortal localPortal)) {
                continue;
            }
            ITunnel tunnel = localPortal.getTunnel();
            if (!(tunnel instanceof UniversalTunnel universal)) {
                continue;
            }
            if (peerName.equals(universal.getServerName()) && senderPortalId.equals(universal.getDestinationPortalId())) {
                return localPortal;
            }
        }
        return null;
    }

    private static Map<String, String> collectSettings(LocalPortal portal) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(KEY_PROJECTION_MODE, portal.getProjectionMode().name());
        settings.put(KEY_PERMISSION_MODE, portal.getPermissionMode().name());
        settings.put(KEY_OUTGOING_TRAVERSALS, Boolean.toString(portal.isOutgoingTraversalsEnabled()));
        settings.put(KEY_INCOMING_TRAVERSALS, Boolean.toString(portal.isIncomingTraversalsEnabled()));
        settings.put(KEY_VIEW_DEPTH, Integer.toString(portal.getNetworkViewDepth()));
        settings.put(KEY_VIEW_LATERAL_PAD, Integer.toString(portal.getNetworkViewLateralPad()));
        settings.put(KEY_VIEW_HEARTBEAT, Integer.toString(portal.getNetworkViewHeartbeatTicks()));
        settings.put(KEY_VIEW_ENTITY_INTERVAL, Integer.toString(portal.getNetworkViewEntityIntervalTicks()));
        settings.put(KEY_VIEW_UNSUBSCRIBE_GRACE, Integer.toString(portal.getNetworkViewUnsubscribeGraceSeconds()));
        settings.put(KEY_VIEW_FALLBACK_BLOCK, portal.getNetworkViewFallbackBlock());
        settings.put(KEY_SETTINGS_SYNC, Boolean.toString(portal.isSettingsSyncEnabled()));
        return settings;
    }

    private static void applyToLocal(LocalPortal portal, Map<String, String> settings) {
        APPLYING_REMOTE.set(Boolean.TRUE);
        try {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                applyLocalKey(portal, entry.getKey(), entry.getValue());
            }
        } finally {
            APPLYING_REMOTE.set(Boolean.FALSE);
        }
    }

    private static void applyLocalKey(LocalPortal portal, String key, String value) {
        if (value == null) {
            return;
        }
        switch (key) {
            case KEY_PROJECTION_MODE -> {
                ProjectionMode mode = parseProjectionMode(value);
                if (mode != null) {
                    portal.setProjectionMode(mode);
                }
            }
            case KEY_PERMISSION_MODE -> {
                PortalPermissionMode mode = parsePermissionMode(value);
                if (mode != null) {
                    portal.setPermissionMode(mode);
                }
            }
            case KEY_OUTGOING_TRAVERSALS -> portal.setOutgoingTraversalsEnabled(Boolean.parseBoolean(value));
            case KEY_INCOMING_TRAVERSALS -> portal.setIncomingTraversalsEnabled(Boolean.parseBoolean(value));
            case KEY_VIEW_DEPTH -> portal.setNetworkViewDepth(parseIntOr(value, portal.getNetworkViewDepth()));
            case KEY_VIEW_LATERAL_PAD -> portal.setNetworkViewLateralPad(parseIntOr(value, portal.getNetworkViewLateralPad()));
            case KEY_VIEW_HEARTBEAT -> portal.setNetworkViewHeartbeatTicks(parseIntOr(value, portal.getNetworkViewHeartbeatTicks()));
            case KEY_VIEW_ENTITY_INTERVAL -> portal.setNetworkViewEntityIntervalTicks(parseIntOr(value, portal.getNetworkViewEntityIntervalTicks()));
            case KEY_VIEW_UNSUBSCRIBE_GRACE -> portal.setNetworkViewUnsubscribeGraceSeconds(parseIntOr(value, portal.getNetworkViewUnsubscribeGraceSeconds()));
            case KEY_VIEW_FALLBACK_BLOCK -> portal.setNetworkViewFallbackBlock(value);
            case KEY_SETTINGS_SYNC -> portal.setSettingsSyncEnabled(Boolean.parseBoolean(value));
            default -> {
            }
        }
    }

    private static void applyToRemote(RemotePortal remote, Map<String, String> settings) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            switch (key) {
                case KEY_PROJECTION_MODE -> {
                    ProjectionMode mode = parseProjectionMode(value);
                    if (mode != null) {
                        remote.setMirroredProjectionMode(mode);
                    }
                }
                case KEY_PERMISSION_MODE -> {
                    PortalPermissionMode mode = parsePermissionMode(value);
                    if (mode != null) {
                        remote.setMirroredPermissionMode(mode);
                    }
                }
                case KEY_OUTGOING_TRAVERSALS -> remote.setMirroredOutgoingTraversalsEnabled(Boolean.parseBoolean(value));
                case KEY_INCOMING_TRAVERSALS -> remote.setMirroredIncomingTraversalsEnabled(Boolean.parseBoolean(value));
                case KEY_VIEW_DEPTH -> remote.setMirroredNetworkViewDepth(parseIntOr(value, remote.getMirroredNetworkViewDepth()));
                case KEY_VIEW_LATERAL_PAD -> remote.setMirroredNetworkViewLateralPad(parseIntOr(value, remote.getMirroredNetworkViewLateralPad()));
                case KEY_VIEW_HEARTBEAT -> remote.setMirroredNetworkViewHeartbeatTicks(parseIntOr(value, remote.getMirroredNetworkViewHeartbeatTicks()));
                case KEY_VIEW_ENTITY_INTERVAL -> remote.setMirroredNetworkViewEntityIntervalTicks(parseIntOr(value, remote.getMirroredNetworkViewEntityIntervalTicks()));
                case KEY_VIEW_UNSUBSCRIBE_GRACE -> remote.setMirroredNetworkViewUnsubscribeGraceSeconds(parseIntOr(value, remote.getMirroredNetworkViewUnsubscribeGraceSeconds()));
                case KEY_VIEW_FALLBACK_BLOCK -> remote.setMirroredNetworkViewFallbackBlock(value);
                default -> {
                }
            }
        }
    }

    private static ProjectionMode parseProjectionMode(String value) {
        try {
            return ProjectionMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PortalPermissionMode parsePermissionMode(String value) {
        try {
            return PortalPermissionMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String linkedPeerName(LocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        if (!(tunnel instanceof UniversalTunnel universal)) {
            return null;
        }
        return universal.getServerName();
    }

    private static boolean isShareable(ILocalPortal portal) {
        return portal.isGateway()
            && portal.getStructure() != null
            && portal.getStructure().getWorld() != null
            && portal.getStructure().getArea() != null;
    }

    public static PortalInfo toInfo(ILocalPortal portal) {
        PortalFrame frame = portal.getFrame();
        AxisAlignedBB area = portal.getStructure().getArea();
        return new PortalInfo(
            portal.getId(),
            portal.getName(),
            portal.getStructure().getWorld().getName(),
            portal.getType().name(),
            portal.isOpen(),
            frame.getNormal().name(),
            frame.getRight().name(),
            frame.getUp().name(),
            portal.getOrigin().getX(),
            portal.getOrigin().getY(),
            portal.getOrigin().getZ(),
            Math.min(area.getXa(), area.getXb()),
            Math.min(area.getYa(), area.getYb()),
            Math.min(area.getZa(), area.getZb()),
            Math.max(area.getXa(), area.getXb()),
            Math.max(area.getYa(), area.getYb()),
            Math.max(area.getZa(), area.getZb())
        );
    }
}
