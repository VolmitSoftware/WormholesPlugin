package art.arcane.wormholes.network;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.util.Cuboid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSettingsSyncTest {
    @Test
    void productionSettingsKeepProjectionAndMirrorIndependent() {
        LocalPortal portal = localPortal();
        portal.setProjectionMode(ProjectionMode.OFF);
        portal.setMirrorMode(true);

        Map<String, String> settings = PortalSyncService.collectSettings(portal);

        assertEquals("MIRROR", settings.get(PortalSyncService.KEY_PROJECTION_MODE));
        assertEquals("false", settings.get(PortalSyncService.KEY_PROJECTION_ENABLED));
        assertEquals("true", settings.get(PortalSyncService.KEY_MIRROR_MODE));
    }

    @Test
    void legacyMirrorSentinelNormalizesToSafeMirrorState() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        remote.setMirroredProjectionMode(ProjectionMode.OFF);

        PortalSyncService.applyToRemote(remote, Map.of(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR"));

        assertEquals(ProjectionMode.ON, remote.getMirroredProjectionMode());
        assertTrue(remote.isMirroredMirrorMode());
        assertFalse(remote.acceptsInboundTraversal());
    }

    @Test
    void explicitProjectionEnabledPreservesMirrorWithProjectionOff() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR");
        settings.put(PortalSyncService.KEY_PROJECTION_ENABLED, "false");
        settings.put(PortalSyncService.KEY_MIRROR_MODE, "true");

        PortalSyncService.applyToRemote(remote, settings);

        assertEquals(ProjectionMode.OFF, remote.getMirroredProjectionMode());
        assertTrue(remote.isMirroredMirrorMode());
        assertFalse(remote.acceptsInboundTraversal());
    }

    @Test
    void explicitProjectionEnabledAppliesIndependentStateToLinkedLocalPortal() {
        LocalPortal portal = localPortal();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR");
        settings.put(PortalSyncService.KEY_PROJECTION_ENABLED, "false");
        settings.put(PortalSyncService.KEY_MIRROR_MODE, "true");

        PortalSyncService.applyToLocal(portal, settings);

        assertEquals(ProjectionMode.OFF, portal.getProjectionMode());
        assertTrue(portal.isMirrorMode());
    }

    @Test
    void mirrorToggleBroadcastsRemoteCacheWhenSettingsSyncIsDisabled(@TempDir Path tempDir) {
        PortalSyncService previousSync = Wormholes.portalSyncService;
        LocalPortal portal = localPortal();
        Wormholes.portalSyncService = null;
        portal.setSettingsSyncEnabled(false);
        RecordingNetworkManager network = new RecordingNetworkManager(tempDir);
        PortalSyncService sync = new PortalSyncService(network, () -> List.of(portal), Runnable::run);
        try {
            Wormholes.portalSyncService = sync;

            portal.setMirrorMode(true);

            WireMessage.PortalSettingsUpdate enabled = assertInstanceOf(
                WireMessage.PortalSettingsUpdate.class, network.singleMessage());
            assertEquals("true", enabled.settings().get(PortalSyncService.KEY_REMOTE_CACHE_ONLY));
            assertEquals("MIRROR", enabled.settings().get(PortalSyncService.KEY_PROJECTION_MODE));
            assertEquals("true", enabled.settings().get(PortalSyncService.KEY_MIRROR_MODE));

            network.clear();
            portal.setMirrorMode(false);

            WireMessage.PortalSettingsUpdate disabled = assertInstanceOf(
                WireMessage.PortalSettingsUpdate.class, network.singleMessage());
            assertEquals("true", disabled.settings().get(PortalSyncService.KEY_REMOTE_CACHE_ONLY));
            assertEquals("ON", disabled.settings().get(PortalSyncService.KEY_PROJECTION_MODE));
            assertEquals("false", disabled.settings().get(PortalSyncService.KEY_MIRROR_MODE));
        } finally {
            Wormholes.portalSyncService = previousSync;
        }
    }

    private static RemotePortal newRemotePortal(String peer, UUID id) {
        return RemotePortal.fromInfo(peer, portalInfo(id, true));
    }

    private static PortalInfo portalInfo(UUID id, boolean open) {
        return new PortalInfo(id, "Remote Gate", "world", PortalType.GATEWAY.name(), open,
            "E", "S", "U",
            100.0D, 64.0D, 100.0D,
            99.0D, 63.0D, 99.0D,
            101.0D, 65.0D, 101.0D);
    }

    private static LocalPortal localPortal() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(64));
        values.put("z1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(0));
        values.put("y2", Integer.valueOf(66));
        values.put("z2", Integer.valueOf(2));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return new LocalPortal(UUID.randomUUID(), PortalType.GATEWAY, structure);
    }

    @Test
    void wireUpdateAppliesKnownKeysToRemoteMirror() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "OFF");
        settings.put(PortalSyncService.KEY_MIRROR_MODE, "true");
        settings.put(PortalSyncService.KEY_MIRROR_ROTATION, "270");
        settings.put(PortalSyncService.KEY_PERMISSION_MODE, "WHITELIST");
        settings.put(PortalSyncService.KEY_OUTGOING_TRAVERSALS, "false");
        settings.put(PortalSyncService.KEY_INCOMING_TRAVERSALS, "false");
        settings.put(PortalSyncService.KEY_VIEW_DEPTH, "24");
        settings.put(PortalSyncService.KEY_VIEW_LATERAL_PAD, "12");
        settings.put(PortalSyncService.KEY_VIEW_HEARTBEAT, "40");
        settings.put(PortalSyncService.KEY_VIEW_ENTITY_INTERVAL, "8");
        settings.put(PortalSyncService.KEY_VIEW_UNSUBSCRIBE_GRACE, "45");
        settings.put(PortalSyncService.KEY_VIEW_FALLBACK_BLOCK, "minecraft:stone");

        PortalSyncService.applyToRemote(remote, settings);

        assertEquals(ProjectionMode.OFF, remote.getMirroredProjectionMode());
        assertTrue(remote.isMirroredMirrorMode());
        assertEquals(MirrorRotation.DEGREES_180, remote.getMirroredProjectionRotation());
        assertEquals(PortalPermissionMode.WHITELIST, remote.getMirroredPermissionMode());
        assertFalse(remote.isMirroredOutgoingTraversalsEnabled());
        assertFalse(remote.isMirroredIncomingTraversalsEnabled());
        assertEquals(24, remote.getMirroredNetworkViewDepth());
        assertEquals(12, remote.getMirroredNetworkViewLateralPad());
        assertEquals(40, remote.getMirroredNetworkViewHeartbeatTicks());
        assertEquals(8, remote.getMirroredNetworkViewEntityIntervalTicks());
        assertEquals(45, remote.getMirroredNetworkViewUnsubscribeGraceSeconds());
        assertEquals("minecraft:stone", remote.getMirroredNetworkViewFallbackBlock());
    }

    @Test
    void wireUpdateIgnoresUnknownKeysAndRemovedProjectionModes() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        remote.setMirroredProjectionMode(ProjectionMode.OFF);
        int originalDepth = remote.getMirroredNetworkViewDepth();
        ProjectionMode originalMode = remote.getMirroredProjectionMode();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("unknownKey1", "garbage");
        settings.put("anotherFutureField", "1234");
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "ONE_WAY");

        PortalSyncService.applyToRemote(remote, settings);

        assertEquals(originalMode, remote.getMirroredProjectionMode());
        assertEquals(originalDepth, remote.getMirroredNetworkViewDepth());
    }

    @Test
    void reEntryGuardSuppressesNestedBroadcast() {
        assertFalse(PortalSyncService.isApplyingRemote());
        runWithReentryGuard(() -> {
            assertTrue(PortalSyncService.isApplyingRemote());
        });
        assertFalse(PortalSyncService.isApplyingRemote());
    }

    @Test
    void malformedMirrorRotationPreservesPreviousValue() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        remote.setMirroredProjectionRotation(MirrorRotation.DEGREES_180);

        PortalSyncService.applyToRemote(remote, Map.of(PortalSyncService.KEY_MIRROR_ROTATION, "not-a-number"));

        assertEquals(MirrorRotation.DEGREES_180, remote.getMirroredProjectionRotation());
    }

    @Test
    void productionUpdatePopulatesRemotePreflightAndSurvivesRegistryRefresh() {
        RemotePortalRegistry previousRegistry = Wormholes.remotePortalRegistry;
        PortalManager previousPortalManager = Wormholes.portalManager;
        RemotePortalRegistry registry = new RemotePortalRegistry();
        UUID portalId = UUID.randomUUID();
        try {
            Wormholes.remotePortalRegistry = registry;
            Wormholes.portalManager = null;
            registry.applyUpsert("alpha", portalInfo(portalId, true));
            PortalSyncService sync = new PortalSyncService(null, List::of, Runnable::run);
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put(PortalSyncService.KEY_PROJECTION_MODE, ProjectionMode.ON.name());
            settings.put(PortalSyncService.KEY_MIRROR_MODE, "true");
            settings.put(PortalSyncService.KEY_MIRROR_ROTATION, "180");
            settings.put(PortalSyncService.KEY_INCOMING_TRAVERSALS, "false");

            sync.applySettingsUpdate("alpha", new WireMessage.PortalSettingsUpdate(portalId, settings));

            assertMirrorPreflight(registry.get("alpha", portalId));
            registry.applyUpsert("alpha", portalInfo(portalId, false));
            assertMirrorPreflight(registry.get("alpha", portalId));
            registry.applyDirectory("alpha", List.of(portalInfo(portalId, true)));
            assertMirrorPreflight(registry.get("alpha", portalId));
        } finally {
            Wormholes.remotePortalRegistry = previousRegistry;
            Wormholes.portalManager = previousPortalManager;
        }
    }

    private static void assertMirrorPreflight(RemotePortal remote) {
        assertNotNull(remote);
        assertEquals(ProjectionMode.ON, remote.getMirroredProjectionMode());
        assertTrue(remote.isMirroredMirrorMode());
        assertEquals(MirrorRotation.DEGREES_180, remote.getMirroredProjectionRotation());
        assertFalse(remote.isMirroredIncomingTraversalsEnabled());
        assertFalse(remote.acceptsInboundTraversal());
    }

    private static void runWithReentryGuard(Runnable inside) {
        try {
            java.lang.reflect.Field guardField = PortalSyncService.class.getDeclaredField("APPLYING_REMOTE");
            guardField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Boolean> guard = (ThreadLocal<Boolean>) guardField.get(null);
            guard.set(Boolean.TRUE);
            try {
                inside.run();
            } finally {
                guard.set(Boolean.FALSE);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class RecordingNetworkManager extends NetworkManager {
        private final List<WireMessage> messages = new ArrayList<>();

        private RecordingNetworkManager(Path dataDirectory) {
            super(Logger.getLogger("PortalSettingsSyncTest"), new NetworkConfig(), "26.2", "test", 25565, dataDirectory);
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public List<PeerStatus> status() {
            return List.of(new PeerStatus("beta", "127.0.0.1", "CONNECTED", true, 1L, 1L, null));
        }

        @Override
        public void sendToPeers(Collection<String> peerNames, WireMessage message) {
            messages.add(message);
        }

        private WireMessage singleMessage() {
            assertEquals(1, messages.size());
            return messages.get(0);
        }

        private void clear() {
            messages.clear();
        }
    }
}
