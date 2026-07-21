package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.util.Direction;

public final class RtpProjectionIntegrationTest {
    @Test
    public void attendanceTouchPrecedesProjectionEnabledFilter() {
        List<String> events = new ArrayList<String>();
        UUID viewerId = uuid("viewer-disabled-portal");
        Player viewer = player(viewerId);
        ILocalPortal portal = portal(uuid("disabled-portal"), false, false, false, false, events);
        RecordingProvider provider = new RecordingProvider(events, ignored -> readyResult(viewerId, true), world("target"));

        ProjectionManager.ProjectionResolution resolution = ProjectionManager.resolveProjection(
                provider, portal, viewer, new RtpRimRenderer());

        assertFalse(resolution.projectable());
        assertEquals(1, provider.touchCount);
        assertTrue(events.indexOf("touch") < events.indexOf("supportsProjections"));
    }

    @Test
    public void projectionDisabledViewerStillTouchesAttendanceWithoutProjector() {
        List<String> events = new ArrayList<String>();
        UUID viewerId = uuid("viewer-projection-disabled");
        Player viewer = player(viewerId);
        ILocalPortal portal = portal(uuid("projection-disabled-viewer-portal"), true, true, true, false, events);
        RecordingProvider provider = new RecordingProvider(events, ignored -> readyResult(viewerId, false), world("target"));

        ProjectionManager.ProjectionResolution resolution = ProjectionManager.resolveProjection(
                provider, portal, viewer, new RtpRimRenderer());

        assertEquals(1, provider.touchCount);
        assertFalse(resolution.projectable());
        assertNull(resolution.target());
    }

    @Test
    public void readyLocalTargetProjectsWithoutPortalOrTunnelDestination() {
        List<String> events = new ArrayList<String>();
        UUID viewerId = uuid("viewer-ready");
        Player viewer = player(viewerId);
        ILocalPortal portal = portal(uuid("ready-portal"), true, true, true, false, events);
        World targetWorld = world("target");
        RecordingProvider provider = new RecordingProvider(events, ignored -> readyResult(viewerId, true), targetWorld);

        ProjectionManager.ProjectionResolution resolution = ProjectionManager.resolveProjection(
                provider, portal, viewer, new RtpRimRenderer());

        assertTrue(resolution.projectable());
        assertTrue(resolution.rtp());
        assertSame(targetWorld, resolution.target().world());
        assertEquals(128.5D, resolution.target().originX());
        assertEquals(72.0D, resolution.target().originY());
        assertEquals(-32.5D, resolution.target().originZ());
        assertEquals(Direction.N, resolution.target().frame().getNormal());
        assertEquals(Direction.E, resolution.target().frame().getRight());
        assertEquals(Direction.U, resolution.target().frame().getUp());
        assertEquals(41L, resolution.target().routeRevision());
    }

    @Test
    public void nonReadyStateSuppressesOnlyThatViewerResolution() {
        List<String> events = new ArrayList<String>();
        UUID readyViewerId = uuid("viewer-ready-independent");
        UUID warmingViewerId = uuid("viewer-warming-independent");
        ILocalPortal portal = portal(uuid("independent-portal"), true, true, true, false, events);
        RecordingProvider provider = new RecordingProvider(events, viewerId -> viewerId.equals(readyViewerId)
                ? readyResult(readyViewerId, true)
                : warmingResult(warmingViewerId, true), world("target"));

        ProjectionManager.ProjectionResolution ready = ProjectionManager.resolveProjection(
                provider, portal, player(readyViewerId), new RtpRimRenderer());
        ProjectionManager.ProjectionResolution warming = ProjectionManager.resolveProjection(
                provider, portal, player(warmingViewerId), new RtpRimRenderer());

        assertTrue(ready.projectable());
        assertFalse(warming.projectable());
        assertNull(warming.target());
    }

    @Test
    public void rimDispatchTargetsOnlyTheAttendingViewerBeforeProjectionSuppression() {
        List<String> events = new ArrayList<String>();
        UUID viewerId = uuid("viewer-rim");
        Player viewer = player(viewerId);
        ILocalPortal portal = portal(uuid("rim-portal"), true, false, true, false, events);
        RecordingProvider provider = new RecordingProvider(events, ignored -> warmingResult(viewerId, true), world("target"));

        ProjectionManager.ProjectionResolution resolution = ProjectionManager.resolveProjection(
                provider, portal, viewer, new RtpRimRenderer());

        assertFalse(resolution.projectable());
        assertSame(portal, provider.rimPortal);
        assertSame(viewer, provider.rimViewer);
        assertEquals(RtpRimRenderer.Color.YELLOW, provider.rimSample.color());
    }

    @Test
    public void routeRevisionAloneControlsDestinationInvalidation() {
        World targetWorld = world("target");
        PortalProjector.RtpProjectionTarget initial = PortalProjector.RtpProjectionTarget.from(readyData(41L), targetWorld);
        PortalProjector.RtpProjectionTarget sameRoute = PortalProjector.RtpProjectionTarget.from(readyData(41L), targetWorld);
        PortalProjector.RtpProjectionTarget revisedRoute = PortalProjector.RtpProjectionTarget.from(readyData(42L), targetWorld);

        assertFalse(sameRoute.requiresDestinationInvalidation(initial));
        assertTrue(revisedRoute.requiresDestinationInvalidation(initial));
        assertTrue(initial.requiresDestinationInvalidation(null));
    }

    private static ProjectionManager.RtpProjectionResult readyResult(UUID viewerId, boolean projectionEnabled) {
        return new ProjectionManager.RtpProjectionResult(
                RtpProjectionView.ready(viewerId, 7L, readyData(41L)),
                projectionEnabled,
                false,
                true,
                RtpRotationMode.STATIC,
                RtpRimRenderer.Phase.READY,
                0L,
                0L);
    }

    private static ProjectionManager.RtpProjectionResult warmingResult(UUID viewerId, boolean rimEnabled) {
        return new ProjectionManager.RtpProjectionResult(
                RtpProjectionView.warming(viewerId, 8L),
                true,
                rimEnabled,
                true,
                RtpRotationMode.STATIC,
                RtpRimRenderer.Phase.PREPARING,
                0L,
                0L);
    }

    private static RtpProjectionView.ReadyData readyData(long routeRevision) {
        RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
        RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
        RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);
        RtpProjectionView.SourceFrame source = new RtpProjectionView.SourceFrame(
                "source", new RtpProjectionView.Point3(0.5D, 64.5D, 0.5D), right, up, forward, 3.0D, 4.0D, 5L);
        RtpProjectionView.Target target = new RtpProjectionView.Target(
                "target", new RtpProjectionView.Point3(128.5D, 72.0D, -32.5D), right, up, forward);
        return new RtpProjectionView.ReadyData(uuid("route"), routeRevision, source, target);
    }

    private static ILocalPortal portal(UUID portalId, boolean supportsProjections, boolean projecting, boolean open,
                                       boolean hasTunnel, List<String> events) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getId" -> portalId;
            case "getName" -> "rtp-test";
            case "supportsProjections" -> {
                events.add("supportsProjections");
                yield supportsProjections;
            }
            case "isProjecting" -> {
                events.add("isProjecting");
                yield projecting;
            }
            case "isOpen" -> {
                events.add("isOpen");
                yield open;
            }
            case "hasTunnel" -> {
                events.add("hasTunnel");
                yield hasTunnel;
            }
            case "isMirrorMode" -> false;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RtpTestPortal";
            default -> defaultValue(method.getReturnType());
        };
        return (ILocalPortal) Proxy.newProxyInstance(
                RtpProjectionIntegrationTest.class.getClassLoader(), new Class<?>[] {ILocalPortal.class}, handler);
    }

    private static Player player(UUID viewerId) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getUniqueId" -> viewerId;
            case "getName" -> viewerId.toString();
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RtpTestPlayer";
            default -> defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(
                RtpProjectionIntegrationTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    private static World world(String name) {
        UUID worldId = uuid("world-" + name);
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUID" -> worldId;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RtpTestWorld[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(
                RtpProjectionIntegrationTest.class.getClassLoader(), new Class<?>[] {World.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class RecordingProvider implements ProjectionManager.RtpProjectionProvider {
        private final List<String> events;
        private final Function<UUID, ProjectionManager.RtpProjectionResult> results;
        private final World targetWorld;
        private int touchCount;
        private ILocalPortal rimPortal;
        private Player rimViewer;
        private RtpRimRenderer.Sample rimSample;

        private RecordingProvider(List<String> events,
                                  Function<UUID, ProjectionManager.RtpProjectionResult> results,
                                  World targetWorld) {
            this.events = events;
            this.results = results;
            this.targetWorld = targetWorld;
        }

        @Override
        public boolean supports(ILocalPortal portal) {
            return true;
        }

        @Override
        public ProjectionManager.RtpProjectionResult touch(ILocalPortal portal, Player observer) {
            events.add("touch");
            touchCount++;
            return results.apply(observer.getUniqueId());
        }

        @Override
        public World resolveTargetWorld(String worldKey) {
            return targetWorld;
        }

        @Override
        public void dispatchRim(ILocalPortal portal, Player observer, RtpRimRenderer.Sample sample) {
            rimPortal = portal;
            rimViewer = observer;
            rimSample = sample;
        }
    }
}
