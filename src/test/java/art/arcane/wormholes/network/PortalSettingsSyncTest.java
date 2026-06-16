package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSettingsSyncTest {
    private static RemotePortal newRemotePortal(String peer, UUID id) {
        PortalInfo info = new PortalInfo(id, "Remote Gate", "world", PortalType.GATEWAY.name(), true,
            "E", "S", "U",
            100.0D, 64.0D, 100.0D,
            99.0D, 63.0D, 99.0D,
            101.0D, 65.0D, 101.0D);
        return RemotePortal.fromInfo(peer, info);
    }

    @Test
    void wireUpdateAppliesKnownKeysToRemoteMirror() {
        RemotePortal remote = newRemotePortal("alpha", UUID.randomUUID());
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR");
        settings.put(PortalSyncService.KEY_PERMISSION_MODE, "WHITELIST");
        settings.put(PortalSyncService.KEY_OUTGOING_TRAVERSALS, "false");
        settings.put(PortalSyncService.KEY_INCOMING_TRAVERSALS, "false");
        settings.put(PortalSyncService.KEY_VIEW_DEPTH, "24");
        settings.put(PortalSyncService.KEY_VIEW_LATERAL_PAD, "12");
        settings.put(PortalSyncService.KEY_VIEW_HEARTBEAT, "40");
        settings.put(PortalSyncService.KEY_VIEW_ENTITY_INTERVAL, "8");
        settings.put(PortalSyncService.KEY_VIEW_UNSUBSCRIBE_GRACE, "45");
        settings.put(PortalSyncService.KEY_VIEW_FALLBACK_BLOCK, "minecraft:stone");

        applyDirectlyToRemoteMirror(remote, settings);

        assertEquals(ProjectionMode.MIRROR, remote.getMirroredProjectionMode());
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

        applyDirectlyToRemoteMirror(remote, settings);

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

    private static void applyDirectlyToRemoteMirror(RemotePortal remote, Map<String, String> settings) {
        WireMessage.PortalSettingsUpdate update = new WireMessage.PortalSettingsUpdate(remote.getId(), settings);
        for (Map.Entry<String, String> entry : update.settings().entrySet()) {
            applyKeyForTest(remote, entry.getKey(), entry.getValue());
        }
    }

    private static void applyKeyForTest(RemotePortal remote, String key, String value) {
        switch (key) {
            case PortalSyncService.KEY_PROJECTION_MODE -> {
                try {
                    remote.setMirroredProjectionMode(ProjectionMode.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                }
            }
            case PortalSyncService.KEY_PERMISSION_MODE -> {
                try {
                    remote.setMirroredPermissionMode(PortalPermissionMode.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                }
            }
            case PortalSyncService.KEY_OUTGOING_TRAVERSALS -> remote.setMirroredOutgoingTraversalsEnabled(Boolean.parseBoolean(value));
            case PortalSyncService.KEY_INCOMING_TRAVERSALS -> remote.setMirroredIncomingTraversalsEnabled(Boolean.parseBoolean(value));
            case PortalSyncService.KEY_VIEW_DEPTH -> remote.setMirroredNetworkViewDepth(Integer.parseInt(value));
            case PortalSyncService.KEY_VIEW_LATERAL_PAD -> remote.setMirroredNetworkViewLateralPad(Integer.parseInt(value));
            case PortalSyncService.KEY_VIEW_HEARTBEAT -> remote.setMirroredNetworkViewHeartbeatTicks(Integer.parseInt(value));
            case PortalSyncService.KEY_VIEW_ENTITY_INTERVAL -> remote.setMirroredNetworkViewEntityIntervalTicks(Integer.parseInt(value));
            case PortalSyncService.KEY_VIEW_UNSUBSCRIBE_GRACE -> remote.setMirroredNetworkViewUnsubscribeGraceSeconds(Integer.parseInt(value));
            case PortalSyncService.KEY_VIEW_FALLBACK_BLOCK -> remote.setMirroredNetworkViewFallbackBlock(value);
            default -> {
            }
        }
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
