package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.PortalType;

public final class PortalFactoryOneWayTest
{
	@Test
	public void oneWayLinkOnlyAllowsTravelTowardReceiver()
	{
		PortalState sourceState = new PortalState();
		PortalState destinationState = new PortalState();
		ILocalPortal source = sourceState.proxy();
		ILocalPortal destination = destinationState.proxy();

		assertTrue(PortalFactory.linkOneWay(source, destination));

		assertSame(destination, sourceState.destination);
		assertNull(destinationState.destination);
		assertEquals(destinationState.id, sourceState.dimensionalCounterpartId);
		assertEquals(sourceState.id, destinationState.dimensionalCounterpartId);
		assertTrue(sourceState.outgoing);
		assertFalse(sourceState.incoming);
		assertFalse(destinationState.outgoing);
		assertTrue(destinationState.incoming);
		assertSame(ProjectionMode.OFF, destinationState.projectionMode);
		assertTrue(destinationState.unlinked);
	}

	@Test
	public void oneWayLinkDoesNotStealAnExistingPair()
	{
		PortalState sourceState = new PortalState();
		PortalState destinationState = new PortalState();
		destinationState.dimensionalCounterpartId = UUID.randomUUID();

		assertFalse(PortalFactory.linkOneWay(sourceState.proxy(), destinationState.proxy()));
		assertFalse(destinationState.unlinked);
		assertEquals(0, sourceState.mutations);
		assertEquals(0, destinationState.mutations);
	}

	@Test
	public void oneWayLinkDoesNotStealAnUnresolvedTunnel()
	{
		PortalState sourceState = new PortalState();
		PortalState destinationState = new PortalState();
		destinationState.tunnel = unresolvedTunnel();

		assertFalse(PortalFactory.linkOneWay(sourceState.proxy(), destinationState.proxy()));
		assertFalse(destinationState.unlinked);
		assertEquals(0, sourceState.mutations);
		assertEquals(0, destinationState.mutations);
	}

	@Test
	public void bidirectionalLinkConnectsBothCleanEndpoints()
	{
		PortalState firstState = new PortalState();
		PortalState secondState = new PortalState();
		ILocalPortal first = firstState.proxy();
		ILocalPortal second = secondState.proxy();

		assertTrue(PortalFactory.linkBidirectional(first, second));
		assertSame(second, firstState.destination);
		assertSame(first, secondState.destination);
		assertEquals(secondState.id, firstState.dimensionalCounterpartId);
		assertEquals(firstState.id, secondState.dimensionalCounterpartId);
	}

	@Test
	public void dimensionalLinksRejectSelfLinks()
	{
		PortalState state = new PortalState();
		ILocalPortal portal = state.proxy();

		assertFalse(PortalFactory.linkOneWay(portal, portal));
		assertFalse(PortalFactory.linkBidirectional(portal, portal));
	}

	@Test
	public void factoriesRejectRtpSourceBeforeAnyMutation()
	{
		PortalState sourceState = new PortalState();
		PortalState destinationState = new PortalState();
		sourceState.type = PortalType.RTP;
		ILocalPortal source = sourceState.proxy();
		ILocalPortal destination = destinationState.proxy();

		assertFalse(PortalFactory.linkOneWay(source, destination));
		assertFalse(PortalFactory.linkBidirectional(source, destination));
		assertEquals(0, sourceState.mutations);
		assertEquals(0, destinationState.mutations);
	}

	@Test
	public void factoriesRejectRtpDestinationBeforeAnyMutation()
	{
		PortalState sourceState = new PortalState();
		PortalState destinationState = new PortalState();
		destinationState.type = PortalType.RTP;
		ILocalPortal source = sourceState.proxy();
		ILocalPortal destination = destinationState.proxy();

		assertFalse(PortalFactory.linkOneWay(source, destination));
		assertFalse(PortalFactory.linkBidirectional(source, destination));
		assertEquals(0, sourceState.mutations);
		assertEquals(0, destinationState.mutations);
	}

	private static ITunnel unresolvedTunnel()
	{
		return (ITunnel) Proxy.newProxyInstance(ITunnel.class.getClassLoader(), new Class<?>[] { ITunnel.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getDestination" -> null;
			case "toString" -> "UnresolvedTunnel";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static final class PortalState implements InvocationHandler
	{
		private IPortal destination;
		private final UUID id = UUID.randomUUID();
		private UUID dimensionalCounterpartId;
		private boolean outgoing;
		private boolean incoming;
		private boolean unlinked;
		private ProjectionMode projectionMode = ProjectionMode.ON;
		private PortalType type = PortalType.PORTAL;
		private ITunnel tunnel;
		private int mutations;

		private ILocalPortal proxy()
		{
			return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class }, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments)
		{
			return switch(method.getName())
			{
				case "setDestination" -> assignDestination(arguments);
				case "getId" -> id;
				case "getType" -> type;
				case "isDestroyed" -> Boolean.FALSE;
				case "hasTunnel" -> Boolean.valueOf(tunnel != null && tunnel.getDestination() != null);
				case "getTunnel" -> tunnel;
				case "getDimensionalCounterpartId" -> dimensionalCounterpartId;
				case "setDimensionalCounterpartId" -> assignDimensionalCounterpart(arguments);
				case "setOutgoingTraversalsEnabled" -> assignOutgoing(arguments);
				case "setIncomingTraversalsEnabled" -> assignIncoming(arguments);
				case "setProjectionMode" -> assignProjectionMode(arguments);
				case "unlink" -> markUnlinked();
				case "toString" -> "PortalFactoryOneWayTestPortal";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> throw new UnsupportedOperationException(method.getName());
			};
		}

		private Object assignDestination(Object[] arguments)
		{
			mutations++;
			destination = (IPortal) arguments[0];
			return null;
		}

		private Object assignDimensionalCounterpart(Object[] arguments)
		{
			mutations++;
			dimensionalCounterpartId = (UUID) arguments[0];
			return null;
		}

		private Object assignOutgoing(Object[] arguments)
		{
			mutations++;
			outgoing = ((Boolean) arguments[0]).booleanValue();
			return null;
		}

		private Object assignIncoming(Object[] arguments)
		{
			mutations++;
			incoming = ((Boolean) arguments[0]).booleanValue();
			return null;
		}

		private Object assignProjectionMode(Object[] arguments)
		{
			mutations++;
			projectionMode = (ProjectionMode) arguments[0];
			return null;
		}

		private Object markUnlinked()
		{
			mutations++;
			unlinked = true;
			return null;
		}
	}
}
