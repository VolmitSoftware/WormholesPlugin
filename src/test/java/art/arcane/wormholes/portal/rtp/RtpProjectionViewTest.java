package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class RtpProjectionViewTest
{
	@Test
	public void nonReadyStatesNeverExposeRouteOrCoordinatePayloads()
	{
		UUID viewerId = uuid("viewer");
		List<RtpProjectionView> views = List.of(
				RtpProjectionView.none(viewerId, 1L),
				RtpProjectionView.warming(viewerId, 2L),
				RtpProjectionView.denied(viewerId, 3L),
				RtpProjectionView.failed(viewerId, 4L));
		List<RtpProjectionView.State> states = List.of(
				RtpProjectionView.State.NONE,
				RtpProjectionView.State.WARMING,
				RtpProjectionView.State.DENIED,
				RtpProjectionView.State.FAILED);

		for(int index = 0; index < views.size(); index++)
		{
			RtpProjectionView view = views.get(index);
			assertEquals(states.get(index), view.state());
			assertEquals(index + 1L, view.snapshotRevision());
			assertTrue(view.readyFor(viewerId).isEmpty());
			assertTrue(view.readyFor(uuid("other")).isEmpty());
		}
	}

	@Test
	public void readyPayloadIsVisibleOnlyToItsOwningViewer()
	{
		UUID viewerId = uuid("owner");
		RtpProjectionView.ReadyData readyData = readyData("shared", 17L, 120.5D, 70.0D, -44.25D);
		RtpProjectionView view = RtpProjectionView.ready(viewerId, 8L, readyData);

		assertEquals(RtpProjectionView.State.READY, view.state());
		assertEquals(8L, view.snapshotRevision());
		assertTrue(view.isForViewer(viewerId));
		assertFalse(view.isForViewer(uuid("other")));
		assertSame(readyData, view.readyFor(viewerId).orElseThrow());
		assertTrue(view.readyFor(uuid("other")).isEmpty());
	}

	@Test
	public void sharedViewersCanReceiveEqualRoutesWithoutSharingViewOwnership()
	{
		UUID firstViewer = uuid("first-viewer");
		UUID secondViewer = uuid("second-viewer");
		RtpProjectionView.ReadyData sharedRoute = readyData("shared-route", 31L, 18.0D, 81.0D, 92.0D);
		RtpProjectionView firstView = RtpProjectionView.ready(firstViewer, 11L, sharedRoute);
		RtpProjectionView secondView = RtpProjectionView.ready(secondViewer, 12L, sharedRoute);

		assertEquals(firstView.readyFor(firstViewer).orElseThrow(), secondView.readyFor(secondViewer).orElseThrow());
		assertTrue(firstView.readyFor(secondViewer).isEmpty());
		assertTrue(secondView.readyFor(firstViewer).isEmpty());
	}

	@Test
	public void perPlayerRoutesRemainPrivateAndIndependent()
	{
		UUID firstViewer = uuid("private-first");
		UUID secondViewer = uuid("private-second");
		RtpProjectionView.ReadyData firstRoute = readyData("private-route-a", 41L, 10.0D, 70.0D, 20.0D);
		RtpProjectionView.ReadyData secondRoute = readyData("private-route-b", 42L, -80.0D, 90.0D, 140.0D);
		RtpProjectionView firstView = RtpProjectionView.ready(firstViewer, 21L, firstRoute);
		RtpProjectionView secondView = RtpProjectionView.ready(secondViewer, 22L, secondRoute);

		assertEquals(firstRoute, firstView.readyFor(firstViewer).orElseThrow());
		assertEquals(secondRoute, secondView.readyFor(secondViewer).orElseThrow());
		assertFalse(firstRoute.equals(secondRoute));
		assertTrue(firstView.readyFor(secondViewer).isEmpty());
		assertTrue(secondView.readyFor(firstViewer).isEmpty());
	}

	@Test
	public void payloadsRejectInvalidWorldGeometryAndCoordinates()
	{
		RtpProjectionView.Point3 origin = new RtpProjectionView.Point3(0.0D, 64.0D, 0.0D);
		RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
		RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
		RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);

		assertThrows(IllegalArgumentException.class, () -> new RtpProjectionView.Point3(Double.NaN, 0.0D, 0.0D));
		assertThrows(IllegalArgumentException.class, () -> new RtpProjectionView.Vector3(0.0D, Double.POSITIVE_INFINITY, 0.0D));
		assertThrows(IllegalArgumentException.class, () -> new RtpProjectionView.SourceFrame(" ", origin, right, up, forward, 3.0D, 4.0D, 1L));
		assertThrows(IllegalArgumentException.class, () -> new RtpProjectionView.SourceFrame("minecraft:overworld", origin, right, up, forward, 0.0D, 4.0D, 1L));
		assertThrows(IllegalArgumentException.class, () -> new RtpProjectionView.Target("", origin, right, up, forward));
		assertThrows(IllegalArgumentException.class, () -> new RtpProjectionView.ReadyData(uuid("route"), -1L,
				new RtpProjectionView.SourceFrame("minecraft:overworld", origin, right, up, forward, 3.0D, 4.0D, 1L),
				new RtpProjectionView.Target("minecraft:the_nether", origin, right, up, forward)));
		assertThrows(IllegalArgumentException.class, () -> RtpProjectionView.none(uuid("viewer"), -1L));
	}

	private RtpProjectionView.ReadyData readyData(String routeName, long routeRevision, double x, double y, double z)
	{
		RtpProjectionView.Point3 sourceCenter = new RtpProjectionView.Point3(8.5D, 65.5D, -2.5D);
		RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
		RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
		RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);
		RtpProjectionView.SourceFrame sourceFrame = new RtpProjectionView.SourceFrame(
				"minecraft:overworld", sourceCenter, right, up, forward, 3.0D, 4.0D, 6L);
		RtpProjectionView.Target target = new RtpProjectionView.Target(
				"minecraft:the_nether", new RtpProjectionView.Point3(x, y, z), right, up, forward);
		return new RtpProjectionView.ReadyData(uuid(routeName), routeRevision, sourceFrame, target);
	}

	private UUID uuid(String value)
	{
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}
}
