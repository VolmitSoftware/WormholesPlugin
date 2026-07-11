package art.arcane.wormholes.network;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.MirrorRotation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSettingsSyncTest {
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

    @Test
    void wireUpdateAppliesKnownKeysToRemoteMirror() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR");
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

        assertEquals(ProjectionMode.MIRROR, remote.getMirroredProjectionMode());
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
    void wireUpdateIgnoresUnknownKeys() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        int originalDepth = remote.getMirroredNetworkViewDepth();
        ProjectionMode originalMode = remote.getMirroredProjectionMode();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("unknownKey1", "garbage");
        settings.put("anotherFutureField", "1234");
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "ONE_WAY");

        PortalSyncService.applyToRemote(remote, settings);

        assertEquals(ProjectionMode.ONE_WAY, remote.getMirroredProjectionMode());
        assertEquals(originalDepth, remote.getMirroredNetworkViewDepth());
        assertFalse(originalMode == remote.getMirroredProjectionMode());
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
            settings.put(PortalSyncService.KEY_PROJECTION_MODE, ProjectionMode.MIRROR.name());
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
        assertEquals(ProjectionMode.MIRROR, remote.getMirroredProjectionMode());
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
}
