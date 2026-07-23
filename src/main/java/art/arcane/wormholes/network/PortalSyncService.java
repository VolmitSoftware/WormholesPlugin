package art.arcane.wormholes.network;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.MirrorRotation;
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
    public static final String KEY_PROJECTION_ENABLED = "projectionEnabled";
    public static final String KEY_MIRROR_MODE = "mirrorMode";
    public static final String KEY_MIRROR_ROTATION = "mirrorRotationDegrees";
    public static final String KEY_PERMISSION_MODE = "permissionMode";
    public static final String KEY_OUTGOING_TRAVERSALS = "outgoingTraversalsEnabled";
    public static final String KEY_INCOMING_TRAVERSALS = "incomingTraversalsEnabled";
    public static final String KEY_VIEW_DEPTH = "networkViewDepth";
    public static final String KEY_VIEW_LATERAL_PAD = "networkViewLateralPad";
    public static final String KEY_VIEW_HEARTBEAT = "networkViewHeartbeatTicks";
    public static final String KEY_VIEW_ENTITY_INTERVAL = "networkViewEntityIntervalTicks";
    public static final String KEY_VIEW_UNSUBSCRIBE_GRACE = "networkViewUnsubscribeGraceSeconds";
    public static final String KEY_VIEW_FALLBACK_BLOCK = "networkViewFallbackBlock";
    public static final String KEY_BLACKOUT_BACKGROUND = "blackoutBackground";
    public static final String KEY_BLACKOUT_COLOR = "blackoutColor";
    public static final String KEY_ACTIVATION_RANGE = "activationRange";
    public static final String KEY_SETTINGS_SYNC = "settingsSyncEnabled";
    static final String KEY_REMOTE_CACHE_ONLY = "remoteCacheOnly";

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
        List<LocalPortal> settingsSources = new ArrayList<>();
        for (ILocalPortal portal : portalSource.get()) {
            if (isShareable(portal)) {
                shared.add(toInfo(portal));
                if (portal instanceof LocalPortal local) {
                    settingsSources.add(local);
                }
            }
        }
        network.send(peerName, new WireMessage.PortalDirectory(shared));
        for (LocalPortal local : settingsSources) {
            network.send(peerName, settingsUpdate(local, true));
        }
    }

    public void broadcastPortal(ILocalPortal portal) {
        if (!network.isRunning()) {
            return;
        }
        if (isShareable(portal)) {
            List<String> peers = peerNames();
            network.sendToPeers(peers, new WireMessage.PortalUpsert(toInfo(portal)));
            if (portal instanceof LocalPortal local) {
                network.sendToPeers(peers, settingsUpdate(local, true));
            }
        } else {
            broadcastRemove(portal.getId());
        }
    }

    public void broadcastRemove(UUID portalId) {
        if (!network.isRunning()) {
            return;
        }
        network.sendToPeers(peerNames(), new WireMessage.PortalRemove(portalId));
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

    public void broadcastRemoteCache(ILocalPortal portal) {
        if (portal == null || network == null || !network.isRunning() || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!(portal instanceof LocalPortal local) || !local.isGateway()) {
            return;
        }
        network.sendToPeers(peerNames(), settingsUpdate(local, true));
    }

    private void sendSettings(LocalPortal local) {
        String linkedPeer = linkedPeerName(local);
        WireMessage.PortalSettingsUpdate update = settingsUpdate(local, false);
        if (linkedPeer != null) {
            network.send(linkedPeer, update);
            return;
        }
        network.sendToPeers(peerNames(), update);
    }

    private List<String> peerNames() {
        List<NetworkManager.PeerStatus> statuses = network.status();
        List<String> names = new ArrayList<>(statuses.size());
        for (NetworkManager.PeerStatus peer : statuses) {
            names.add(peer.name());
        }
        return names;
    }

    public void applySettingsUpdate(String peerName, WireMessage.PortalSettingsUpdate update) {
        if (update == null) {
            return;
        }
        UUID portalId = update.portalId();
        Map<String, String> settings = update.settings();
        RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
        if (registry != null) {
            RemotePortal remote = registry.get(peerName, portalId);
            if (remote != null) {
                applyToRemote(remote, settings);
            }
        }
        if (Boolean.parseBoolean(settings.get(KEY_REMOTE_CACHE_ONLY))) {
            return;
        }
        LocalPortal target = findLinkedLocal(peerName, portalId);
        if (target != null) {
            applyToLocal(target, settings);
            target.refreshOpenMenus();
        }
    }

    private static WireMessage.PortalSettingsUpdate settingsUpdate(LocalPortal local, boolean remoteCacheOnly) {
        Map<String, String> settings = collectSettings(local);
        if (remoteCacheOnly) {
            settings.put(KEY_REMOTE_CACHE_ONLY, Boolean.TRUE.toString());
        }
        return new WireMessage.PortalSettingsUpdate(local.getId(), settings);
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

    static Map<String, String> collectSettings(LocalPortal portal) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(KEY_PROJECTION_MODE, portal.isMirrorMode() ? "MIRROR" : portal.getProjectionMode().name());
        settings.put(KEY_PROJECTION_ENABLED, Boolean.toString(portal.getProjectionMode() == ProjectionMode.ON));
        settings.put(KEY_MIRROR_MODE, Boolean.toString(portal.isMirrorMode()));
        settings.put(KEY_MIRROR_ROTATION, Integer.toString(portal.getMirrorRotation().getDegrees()));
        settings.put(KEY_PERMISSION_MODE, portal.getPermissionMode().name());
        settings.put(KEY_OUTGOING_TRAVERSALS, Boolean.toString(portal.isOutgoingTraversalsEnabled()));
        settings.put(KEY_INCOMING_TRAVERSALS, Boolean.toString(portal.isIncomingTraversalsEnabled()));
        settings.put(KEY_VIEW_DEPTH, Integer.toString(portal.getNetworkViewDepth()));
        settings.put(KEY_VIEW_LATERAL_PAD, Integer.toString(portal.getNetworkViewLateralPad()));
        settings.put(KEY_VIEW_HEARTBEAT, Integer.toString(portal.getNetworkViewHeartbeatTicks()));
        settings.put(KEY_VIEW_ENTITY_INTERVAL, Integer.toString(portal.getNetworkViewEntityIntervalTicks()));
        settings.put(KEY_VIEW_UNSUBSCRIBE_GRACE, Integer.toString(portal.getNetworkViewUnsubscribeGraceSeconds()));
        settings.put(KEY_VIEW_FALLBACK_BLOCK, portal.getNetworkViewFallbackBlock());
        settings.put(KEY_BLACKOUT_BACKGROUND, Boolean.toString(portal.isBlackoutBackground()));
        settings.put(KEY_BLACKOUT_COLOR, portal.getBlackoutColor().name());
        settings.put(KEY_ACTIVATION_RANGE, Integer.toString(portal.getActivationRange()));
        settings.put(KEY_SETTINGS_SYNC, Boolean.toString(portal.isSettingsSyncEnabled()));
        return settings;
    }

    static void applyToLocal(LocalPortal portal, Map<String, String> settings) {
        APPLYING_REMOTE.set(Boolean.TRUE);
        try {
            applyProjectionState(portal, settings);
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (isProjectionStateKey(entry.getKey())) {
                    continue;
                }
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
            case KEY_MIRROR_ROTATION -> portal.setMirrorRotation(
                MirrorRotation.fromDegrees(parseIntOr(value, portal.getMirrorRotation().getDegrees())));
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
            case KEY_BLACKOUT_BACKGROUND -> portal.setBlackoutBackground(Boolean.parseBoolean(value));
            case KEY_BLACKOUT_COLOR -> portal.setBlackoutColor(BlackoutColor.fromName(value, portal.getBlackoutColor()));
            case KEY_ACTIVATION_RANGE -> portal.setActivationRange(parseIntOr(value, portal.getActivationRange()));
            case KEY_SETTINGS_SYNC -> portal.setSettingsSyncEnabled(Boolean.parseBoolean(value));
            default -> {
            }
        }
    }

    static void applyToRemote(RemotePortal remote, Map<String, String> settings) {
        applyProjectionState(remote, settings);
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || isProjectionStateKey(key)) {
                continue;
            }
            switch (key) {
                case KEY_MIRROR_ROTATION -> remote.setMirroredProjectionRotation(
                    MirrorRotation.fromDegrees(parseIntOr(value, remote.getMirroredProjectionRotation().getDegrees())));
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
                case KEY_BLACKOUT_BACKGROUND -> remote.setMirroredBlackoutBackground(Boolean.parseBoolean(value));
                case KEY_BLACKOUT_COLOR -> remote.setMirroredBlackoutColor(BlackoutColor.fromName(value, remote.getMirroredBlackoutColor()));
                case KEY_ACTIVATION_RANGE -> remote.setMirroredActivationRange(parseIntOr(value, remote.getMirroredActivationRange()));
                default -> {
                }
            }
        }
    }

    private static void applyProjectionState(LocalPortal portal, Map<String, String> settings) {
        String legacyValue = settings.get(KEY_PROJECTION_MODE);
        ProjectionMode projection = null;
        Boolean mirror = null;
        if ("MIRROR".equals(legacyValue)) {
            projection = ProjectionMode.ON;
            mirror = Boolean.TRUE;
        } else if (legacyValue != null) {
            projection = parseProjectionMode(legacyValue);
            if (projection != null && !settings.containsKey(KEY_MIRROR_MODE)) {
                mirror = Boolean.FALSE;
            }
        }
        Boolean projectionEnabled = parseBoolean(settings.get(KEY_PROJECTION_ENABLED));
        if (projectionEnabled != null) {
            projection = projectionEnabled.booleanValue() ? ProjectionMode.ON : ProjectionMode.OFF;
        }
        Boolean explicitMirror = parseBoolean(settings.get(KEY_MIRROR_MODE));
        if (explicitMirror != null) {
            mirror = explicitMirror;
        }
        if (projection != null) {
            portal.setProjectionMode(projection);
        }
        if (mirror != null) {
            portal.setMirrorMode(mirror.booleanValue());
        }
    }

    private static void applyProjectionState(RemotePortal remote, Map<String, String> settings) {
        String legacyValue = settings.get(KEY_PROJECTION_MODE);
        ProjectionMode projection = null;
        Boolean mirror = null;
        if ("MIRROR".equals(legacyValue)) {
            projection = ProjectionMode.ON;
            mirror = Boolean.TRUE;
        } else if (legacyValue != null) {
            projection = parseProjectionMode(legacyValue);
            if (projection != null && !settings.containsKey(KEY_MIRROR_MODE)) {
                mirror = Boolean.FALSE;
            }
        }
        Boolean projectionEnabled = parseBoolean(settings.get(KEY_PROJECTION_ENABLED));
        if (projectionEnabled != null) {
            projection = projectionEnabled.booleanValue() ? ProjectionMode.ON : ProjectionMode.OFF;
        }
        Boolean explicitMirror = parseBoolean(settings.get(KEY_MIRROR_MODE));
        if (explicitMirror != null) {
            mirror = explicitMirror;
        }
        if (projection != null) {
            remote.setMirroredProjectionMode(projection);
        }
        if (mirror != null) {
            remote.setMirroredMirrorMode(mirror.booleanValue());
        }
    }

    private static boolean isProjectionStateKey(String key) {
        return KEY_PROJECTION_MODE.equals(key)
            || KEY_PROJECTION_ENABLED.equals(key)
            || KEY_MIRROR_MODE.equals(key);
    }

    private static ProjectionMode parseProjectionMode(String value) {
        try {
            return ProjectionMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if (Boolean.FALSE.toString().equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
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
            WorldIdentity.serialize(portal.getStructure().getWorld()),
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
