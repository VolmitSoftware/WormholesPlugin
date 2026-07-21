package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.ElementEvent;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowDecorator;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.scheduling.Callback;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.AllocationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CenterModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CustomCenterMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditor.Host;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.LeaseIdleMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RadiiMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ReservationTimeoutMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RotationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusContext;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetWorldMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YMutation;

public final class RtpPortalEditorTest
{
	private static final UUID VIEWER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	public void conditionalControlsFollowCenterVerticalAllocationAndRotationModes()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status(), worlds()));
		FakeWindow window = render(host);

		assertEquals("RTP Editor", window.title);
		assertEquals(6, window.viewportHeight);
		assertInstanceOf(UIPaneDecorator.class, window.decorator);
		assertTrue(window.hasId("rtp-shared-rotation"));
		assertFalse(window.hasId("rtp-cycle-duration"));
		assertFalse(window.hasId("rtp-preferred-y"));
		assertFalse(window.hasId("rtp-center-x"));
		assertFalse(window.hasId("rtp-center-z"));
		assertTrue(window.hasId("rtp-manual-reroll"));

		SettingsSnapshot expanded = copy(settings(), RtpCenterMode.CUSTOM, 12.5D, -7.25D,
				RtpVerticalMode.PREFERRED_AVERAGE, RtpAllocationMode.SHARED, RtpRotationMode.TIMED,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		host.snapshot = snapshot(expanded, status(), worlds());
		window = render(host);

		assertTrue(window.hasId("rtp-center-x"));
		assertTrue(window.hasId("rtp-center-z"));
		assertTrue(window.hasId("rtp-preferred-y"));
		assertTrue(window.hasId("rtp-shared-rotation"));
		assertTrue(window.hasId("rtp-cycle-duration"));

		SettingsSnapshot perPlayer = copy(expanded, RtpCenterMode.CUSTOM, 12.5D, -7.25D,
				RtpVerticalMode.PREFERRED_AVERAGE, RtpAllocationMode.PER_PLAYER, RtpRotationMode.TIMED,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		host.snapshot = snapshot(perPlayer, status(), worlds());
		window = render(host);

		assertFalse(window.hasId("rtp-shared-rotation"));
		assertFalse(window.hasId("rtp-cycle-duration"));
		assertFalse(window.hasId("rtp-manual-reroll"));
		assertTrue(window.hasId("rtp-rebuild-pool"));
	}

	@Test
	public void numericControlsUseLeftIncreaseRightDecreaseAndShiftSteps()
	{
		SettingsSnapshot custom = copy(settings(), RtpCenterMode.CUSTOM, 10.5D, -20.25D,
				RtpVerticalMode.PREFERRED_AVERAGE, RtpAllocationMode.SHARED, RtpRotationMode.TIMED,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		FakeHost host = new FakeHost(snapshot(custom, status(), worlds()));
		FakeWindow window = render(host);

		assertEquals(new CustomCenterMutation(26.5D, -20.25D), clickForMutation(host, window, "rtp-center-x", ElementEvent.LEFT));
		assertEquals(new CustomCenterMutation(-5.5D, -20.25D), clickForMutation(host, window, "rtp-center-x", ElementEvent.RIGHT));
		assertEquals(new CustomCenterMutation(266.5D, -20.25D), clickForMutation(host, window, "rtp-center-x", ElementEvent.SHIFT_LEFT));
		assertEquals(new CustomCenterMutation(-245.5D, -20.25D), clickForMutation(host, window, "rtp-center-x", ElementEvent.SHIFT_RIGHT));
		assertEquals(new RadiiMutation(640, 4096), clickForMutation(host, window, "rtp-minimum-radius", ElementEvent.LEFT));
		assertEquals(new RadiiMutation(384, 4096), clickForMutation(host, window, "rtp-minimum-radius", ElementEvent.RIGHT));
		assertEquals(new RadiiMutation(1536, 4096), clickForMutation(host, window, "rtp-minimum-radius", ElementEvent.SHIFT_LEFT));
		assertEquals(new RadiiMutation(0, 4096), clickForMutation(host, window, "rtp-minimum-radius", ElementEvent.SHIFT_RIGHT));
		assertEquals(new YMutation(-62, 318, 64), clickForMutation(host, window, "rtp-lower-y", ElementEvent.LEFT));
		assertEquals(new YMutation(-63, 318, 72), clickForMutation(host, window, "rtp-preferred-y", ElementEvent.SHIFT_LEFT));
		assertEquals(new LeaseIdleMutation(35_000L), clickForMutation(host, window, "rtp-lease-idle", ElementEvent.LEFT));
		assertEquals(new LeaseIdleMutation(5_000L), clickForMutation(host, window, "rtp-lease-idle", ElementEvent.SHIFT_RIGHT));
		assertEquals(new ReservationTimeoutMutation(20_000L), clickForMutation(host, window, "rtp-reservation-timeout", ElementEvent.LEFT));
	}

	@Test
	public void cycleControlsUseExactStepsAndClampToApprovedRange()
	{
		SettingsSnapshot timed = copy(settings(), RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.TIMED,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		FakeHost host = new FakeHost(snapshot(timed, status(), worlds()));
		FakeWindow window = render(host);

		assertEquals(330_000L, cycleValue(clickForMutation(host, window, "rtp-cycle-duration", ElementEvent.LEFT)));
		assertEquals(270_000L, cycleValue(clickForMutation(host, window, "rtp-cycle-duration", ElementEvent.RIGHT)));
		assertEquals(600_000L, cycleValue(clickForMutation(host, window, "rtp-cycle-duration", ElementEvent.SHIFT_LEFT)));
		assertEquals(15_000L, cycleValue(clickForMutation(host, window, "rtp-cycle-duration", ElementEvent.SHIFT_RIGHT)));

		host.snapshot = snapshot(copy(timed, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.TIMED,
				512, 4096, -63, 318, 64, 86_390_000L, 30_000L, 15_000L, true, "minecraft:overworld"), status(), worlds());
		window = render(host);
		assertEquals(86_400_000L, cycleValue(clickForMutation(host, window, "rtp-cycle-duration", ElementEvent.LEFT)));

		host.snapshot = snapshot(copy(timed, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.TIMED,
				512, 4096, -63, 318, 64, 15_000L, 30_000L, 15_000L, true, "minecraft:overworld"), status(), worlds());
		window = render(host);
		assertEquals(15_000L, cycleValue(clickForMutation(host, window, "rtp-cycle-duration", ElementEvent.RIGHT)));
	}

	@Test
	public void radiusControlsPreserveGapAndGiveOuterBoundaryPrecedence()
	{
		SettingsSnapshot high = copy(settings(), RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				29_999_500, 30_000_000, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		FakeHost host = new FakeHost(snapshot(high, status(), worlds()));
		FakeWindow window = render(host);

		assertEquals(new RadiiMutation(29_999_999, 30_000_000),
				clickForMutation(host, window, "rtp-minimum-radius", ElementEvent.SHIFT_LEFT));

		SettingsSnapshot low = copy(high, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				0, 500, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		host.snapshot = snapshot(low, status(), worlds());
		window = render(host);
		assertEquals(new RadiiMutation(0, 1),
				clickForMutation(host, window, "rtp-maximum-radius", ElementEvent.SHIFT_RIGHT));

		SettingsSnapshot crossing = copy(high, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 600, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		host.snapshot = snapshot(crossing, status(), worlds());
		window = render(host);
		assertEquals(new RadiiMutation(640, 641),
				clickForMutation(host, window, "rtp-minimum-radius", ElementEvent.LEFT));
		assertEquals(new RadiiMutation(471, 472),
				clickForMutation(host, window, "rtp-maximum-radius", ElementEvent.RIGHT));
	}

	@Test
	public void yControlsClampWorldBoundsAndPreservePreferredOrdering()
	{
		SettingsSnapshot bounded = copy(settings(), RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.PREFERRED_AVERAGE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 4096, -63, -60, -61, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		FakeHost host = new FakeHost(snapshot(bounded, status(), worlds()));
		FakeWindow window = render(host);

		assertEquals(new YMutation(-63, -60, -61),
				clickForMutation(host, window, "rtp-lower-y", ElementEvent.RIGHT));
		assertEquals(new YMutation(-60, -60, -60),
				clickForMutation(host, window, "rtp-lower-y", ElementEvent.SHIFT_LEFT));
		assertEquals(new YMutation(-63, -63, -63),
				clickForMutation(host, window, "rtp-upper-y", ElementEvent.SHIFT_RIGHT));
	}

	@Test
	public void centerAndTargetControlsUseRetainedDefaultsAndSortedWorldOrder()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status(), worlds()));
		FakeWindow window = render(host);

		CenterModeMutation custom = assertInstanceOf(CenterModeMutation.class,
				clickForMutation(host, window, "rtp-center-mode", ElementEvent.LEFT));
		assertEquals(RtpCenterMode.CUSTOM, custom.mode());
		assertEquals(18.75D, custom.customX().doubleValue());
		assertEquals(-42.5D, custom.customZ().doubleValue());
		assertEquals(new TargetWorldMutation("minecraft:The_End"),
				clickForMutation(host, window, "rtp-target-world", ElementEvent.LEFT));
		assertEquals(new TargetWorldMutation("minecraft:the_nether"),
				clickForMutation(host, window, "rtp-target-world", ElementEvent.RIGHT));
		assertEquals(new TargetWorldMutation("minecraft:overworld"),
				clickForMutation(host, window, "rtp-target-world", ElementEvent.SHIFT_LEFT));
		assertEquals(new TargetWorldMutation("minecraft:the_nether"),
				clickForMutation(host, window, "rtp-target-world", ElementEvent.SHIFT_RIGHT));
		assertInstanceOf(RtpPortalEditorModel.ResetCenterTargetMutation.class,
				clickForMutation(host, window, "rtp-center-reset", ElementEvent.LEFT));

		SettingsSnapshot retained = copy(settings(), RtpCenterMode.PORTAL_RELATIVE, 4.25D, 8.75D,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		host.snapshot = snapshot(retained, status(), worlds());
		window = render(host);
		assertEquals(new CenterModeMutation(RtpCenterMode.CUSTOM, Double.valueOf(4.25D), Double.valueOf(8.75D)),
				clickForMutation(host, window, "rtp-center-mode", ElementEvent.SHIFT_LEFT));
	}

	@Test
	public void enumSettingsIgnoreShiftAndRouteTypedMutations()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status(), worlds()));
		FakeWindow window = render(host);

		assertEquals(new AllocationMutation(RtpAllocationMode.PER_PLAYER),
				clickForMutation(host, window, "rtp-allocation", ElementEvent.SHIFT_LEFT));
		assertEquals(new RotationMutation(RtpRotationMode.ON_TRAVERSAL),
				clickForMutation(host, window, "rtp-shared-rotation", ElementEvent.SHIFT_RIGHT));
		assertEquals(new RtpPortalEditorModel.VerticalModeMutation(RtpVerticalMode.PREFERRED_AVERAGE),
				clickForMutation(host, window, "rtp-vertical-mode", ElementEvent.SHIFT_LEFT));
		assertEquals(new RtpPortalEditorModel.RimMutation(false),
				clickForMutation(host, window, "rtp-rim", ElementEvent.SHIFT_RIGHT));
	}

	@Test
	public void manualActionRequiresShiftLeftConfirmationAndBackUsesHostCallback()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status(), worlds()));
		FakeWindow window = render(host);
		Element manual = window.element("rtp-manual-reroll");

		manual.call(ElementEvent.LEFT, manual);
		manual.call(ElementEvent.RIGHT, manual);
		manual.call(ElementEvent.SHIFT_RIGHT, manual);
		assertNull(host.manualAction);

		manual.call(ElementEvent.SHIFT_LEFT, manual);
		assertEquals(ManualAction.REROLL, host.manualAction);
		assertEquals(41L, host.manualRevision);

		Element back = window.element("rtp-back");
		back.call(ElementEvent.LEFT, back);
		assertTrue(window.closed);
		assertEquals(VIEWER_ID, host.backViewerId);

		SettingsSnapshot perPlayer = copy(settings(), RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.PER_PLAYER, RtpRotationMode.STATIC,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld");
		host.snapshot = snapshot(perPlayer, status(), worlds());
		window = render(host);
		Element rebuild = window.element("rtp-rebuild-pool");
		rebuild.call(ElementEvent.SHIFT_LEFT, rebuild);
		assertEquals(ManualAction.REBUILD_POOL, host.manualAction);
	}

	@Test
	public void statusDisplaysRemainingRetryBackoff()
	{
		StatusSnapshot backoff = new StatusSnapshot(RtpPortalEditorModel.StatusState.BACKOFF, true, true,
				false, false, 0L, 45_000L, 0, 0, 0, 0);
		FakeHost host = new FakeHost(snapshot(settings(), backoff, worlds()));
		FakeWindow window = render(host);
		String lore = String.join("\n", window.element("rtp-status").getLore());

		assertTrue(lore.contains("Retry in"));
		assertTrue(lore.contains("45s"));
	}

	@Test
	public void settingsSnapshotPreservesApprovedDefaults()
	{
		World world = world("overworld", -64, 320, 63);

		SettingsSnapshot defaults = SettingsSnapshot.from(RtpSettings.defaults(world));

		assertEquals("minecraft:overworld", defaults.sourceWorldKey());
		assertEquals("minecraft:overworld", defaults.targetWorldKey());
		assertEquals(RtpCenterMode.PORTAL_RELATIVE, defaults.centerMode());
		assertNull(defaults.customCenterX());
		assertNull(defaults.customCenterZ());
		assertEquals(512, defaults.minimumRadius());
		assertEquals(4096, defaults.maximumRadius());
		assertEquals(RtpVerticalMode.SURFACE, defaults.verticalMode());
		assertEquals(RtpAllocationMode.SHARED, defaults.allocationMode());
		assertEquals(RtpRotationMode.STATIC, defaults.rotationMode());
		assertEquals(300_000L, defaults.cycleDurationMillis());
		assertEquals(30_000L, defaults.leaseIdleMillis());
		assertEquals(15_000L, defaults.reservationTimeoutMillis());
		assertTrue(defaults.rimEnabled());
	}

	@Test
	public void settingsSnapshotRejectsInvalidCustomAndDurationState()
	{
		SettingsSnapshot source = settings();

		assertThrows(IllegalArgumentException.class, () -> copy(source, RtpCenterMode.CUSTOM, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, 15_000L, true, "minecraft:overworld"));
		assertThrows(IllegalArgumentException.class, () -> copy(source, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 4096, -63, 318, 64, Long.MAX_VALUE, 30_000L, 15_000L, true, "minecraft:overworld"));
		assertThrows(IllegalArgumentException.class, () -> copy(source, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 4096, -63, 318, 64, 300_000L, Long.MAX_VALUE, 15_000L, true, "minecraft:overworld"));
		assertThrows(IllegalArgumentException.class, () -> copy(source, RtpCenterMode.PORTAL_RELATIVE, null, null,
				RtpVerticalMode.SURFACE, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
				512, 4096, -63, 318, 64, 300_000L, 30_000L, Long.MAX_VALUE, true, "minecraft:overworld"));
	}

	@Test
	public void statusSnapshotAndLoreNeverExposeRuntimeDestinationCoordinates()
	{
		RtpDestination active = new RtpDestination("minecraft:private_active", 1_234_567, 211, -7_654_321, 3L, 2);
		RtpDestination standby = new RtpDestination("minecraft:private_standby", 2_345_678, 212, -6_543_210, 3L, 3);
		RtpDestination free = new RtpDestination("minecraft:private_free", 3_456_789, 213, -5_432_109, 3L, 4);
		RtpRuntimeSnapshot runtime = new RtpRuntimeSnapshot(3L, RtpAllocationMode.PER_PLAYER, RtpRotationMode.STATIC,
				true, active, standby, 7L, 0L, false, false, false, 3, 4, 2, 1,
				List.of(free), 1, 0, 0, 0, 0);
		StatusSnapshot privateSafe = StatusSnapshot.from(runtime, new StatusContext(true, true, 10_000L, 0L));
		assertEquals(0, privateSafe.validatingCount());
		FakeHost host = new FakeHost(snapshot(settings(), privateSafe, worlds()));
		FakeWindow window = render(host);
		String lore = String.join("\n", window.element("rtp-status").getLore());

		assertFalse(lore.contains("private_active"));
		assertFalse(lore.contains("private_standby"));
		assertFalse(lore.contains("private_free"));
		assertFalse(lore.contains("1234567"));
		assertFalse(lore.contains("7654321"));
		assertFalse(lore.contains("2345678"));
		assertFalse(lore.contains("6543210"));
		assertFalse(lore.contains("3456789"));
		assertFalse(lore.contains("5432109"));
	}

	private static long cycleValue(Mutation mutation)
	{
		return assertInstanceOf(RtpPortalEditorModel.CycleDurationMutation.class, mutation).durationMillis();
	}

	private static Mutation clickForMutation(FakeHost host, FakeWindow window, String id, ElementEvent event)
	{
		host.mutations.clear();
		Element element = window.element(id);
		element.call(event, element);
		assertEquals(1, host.mutations.size());
		assertEquals(VIEWER_ID, host.mutations.getFirst().viewerId());
		assertEquals(41L, host.mutations.getFirst().revision());
		return host.mutations.getFirst().mutation();
	}

	private static FakeWindow render(FakeHost host)
	{
		FakeWindow window = new FakeWindow();
		new RtpPortalEditor(host).populate(window, VIEWER_ID);
		return window;
	}

	private static EditorSnapshot snapshot(SettingsSnapshot settings, StatusSnapshot status, List<WorldOption> worlds)
	{
		return new EditorSnapshot(41L, "RTP Editor", settings, status, worlds, 18.75D, -42.5D);
	}

	private static SettingsSnapshot settings()
	{
		return new SettingsSnapshot("minecraft:overworld", "minecraft:overworld", RtpCenterMode.PORTAL_RELATIVE,
				null, null, 512, 4096, RtpVerticalMode.SURFACE, -63, 318, 64,
				RtpAllocationMode.SHARED, RtpRotationMode.STATIC, 300_000L, 30_000L, 15_000L, true);
	}

	private static SettingsSnapshot copy(SettingsSnapshot source, RtpCenterMode centerMode, Double customX, Double customZ,
			RtpVerticalMode verticalMode, RtpAllocationMode allocationMode, RtpRotationMode rotationMode,
			int minimumRadius, int maximumRadius, int lowerY, int upperY, int preferredY,
			long cycleMillis, long leaseMillis, long reservationMillis, boolean rimEnabled, String targetWorldKey)
	{
		return new SettingsSnapshot(source.sourceWorldKey(), targetWorldKey, centerMode, customX, customZ,
				minimumRadius, maximumRadius, verticalMode, lowerY, upperY, preferredY,
				allocationMode, rotationMode, cycleMillis, leaseMillis, reservationMillis, rimEnabled);
	}

	private static StatusSnapshot status()
	{
		return new StatusSnapshot(RtpPortalEditorModel.StatusState.READY, true, true, true, true, 120_000L, 0L, 2, 1, 0, 0);
	}

	private static List<WorldOption> worlds()
	{
		return List.of(
				new WorldOption("minecraft:the_nether", "The Nether", 1, 254),
				new WorldOption("minecraft:overworld", "Overworld", -63, 318),
				new WorldOption("minecraft:The_End", "The End", 1, 254));
	}

	private static World world(String key, int minimumHeight, int maximumHeight, int seaLevel)
	{
		NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> key;
			case "getKey" -> namespacedKey;
			case "getUID" -> UUID.nameUUIDFromBytes(namespacedKey.toString().getBytes());
			case "getMinHeight" -> Integer.valueOf(minimumHeight);
			case "getMaxHeight" -> Integer.valueOf(maximumHeight);
			case "getSeaLevel" -> Integer.valueOf(seaLevel);
			case "toString" -> "RtpPortalEditorTestWorld[" + namespacedKey + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private record CapturedMutation(UUID viewerId, long revision, Mutation mutation)
	{
	}

	private static final class FakeHost implements Host
	{
		private EditorSnapshot snapshot;
		private final List<CapturedMutation> mutations;
		private ManualAction manualAction;
		private long manualRevision;
		private UUID backViewerId;

		private FakeHost(EditorSnapshot snapshot)
		{
			this.snapshot = snapshot;
			mutations = new ArrayList<CapturedMutation>();
		}

		@Override
		public EditorSnapshot snapshot(UUID viewerId)
		{
			assertEquals(VIEWER_ID, viewerId);
			return snapshot;
		}

		@Override
		public void mutate(UUID viewerId, long expectedRevision, Mutation mutation)
		{
			mutations.add(new CapturedMutation(viewerId, expectedRevision, mutation));
		}

		@Override
		public void manual(UUID viewerId, long expectedRevision, ManualAction action)
		{
			assertEquals(VIEWER_ID, viewerId);
			manualRevision = expectedRevision;
			manualAction = action;
		}

		@Override
		public void back(UUID viewerId)
		{
			backViewerId = viewerId;
		}
	}

	private record Slot(int position, int row)
	{
	}

	private static final class FakeWindow implements Window
	{
		private final Map<Slot, Element> elements;
		private WindowDecorator decorator;
		private WindowResolution resolution;
		private int viewportHeight;
		private String title;
		private boolean visible;
		private boolean closed;

		private FakeWindow()
		{
			elements = new HashMap<Slot, Element>();
		}

		private boolean hasId(String id)
		{
			return elements.values().stream().anyMatch(element -> id.equals(element.getId()));
		}

		private Element element(String id)
		{
			return elements.values().stream()
					.filter(element -> id.equals(element.getId()))
					.findFirst()
					.orElseThrow();
		}

		@Override
		public WindowDecorator getDecorator()
		{
			return decorator;
		}

		@Override
		public Window setDecorator(WindowDecorator decorator)
		{
			this.decorator = decorator;
			return this;
		}

		@Override
		public WindowResolution getResolution()
		{
			return resolution;
		}

		@Override
		public Window setResolution(WindowResolution resolution)
		{
			this.resolution = resolution;
			return this;
		}

		@Override
		public Window clearElements()
		{
			elements.clear();
			return this;
		}

		@Override
		public Window close()
		{
			closed = true;
			visible = false;
			return this;
		}

		@Override
		public Window open()
		{
			visible = true;
			return this;
		}

		@Override
		public Window callClosed()
		{
			return this;
		}

		@Override
		public Window updateInventory()
		{
			return this;
		}

		@Override
		public ItemStack computeItemStack(int viewportSlot)
		{
			return null;
		}

		@Override
		public int getLayoutRow(int viewportSlottedPosition)
		{
			return 0;
		}

		@Override
		public int getLayoutPosition(int viewportSlottedPosition)
		{
			return 0;
		}

		@Override
		public int getRealLayoutPosition(int viewportSlottedPosition)
		{
			return 0;
		}

		@Override
		public int getRealPosition(int position, int row)
		{
			return 0;
		}

		@Override
		public int getRow(int realPosition)
		{
			return 0;
		}

		@Override
		public int getPosition(int realPosition)
		{
			return 0;
		}

		@Override
		public boolean isVisible()
		{
			return visible;
		}

		@Override
		public Window setVisible(boolean visible)
		{
			this.visible = visible;
			return this;
		}

		@Override
		public int getViewportPosition()
		{
			return 0;
		}

		@Override
		public Window setViewportPosition(int position)
		{
			return this;
		}

		@Override
		public int getViewportSlots()
		{
			return resolution == null ? 0 : resolution.getWidth() * viewportHeight;
		}

		@Override
		public int getMaxViewportPosition()
		{
			return 0;
		}

		@Override
		public Window scroll(int direction)
		{
			return this;
		}

		@Override
		public int getViewportHeight()
		{
			return viewportHeight;
		}

		@Override
		public Window setViewportHeight(int height)
		{
			viewportHeight = height;
			return this;
		}

		@Override
		public String getTitle()
		{
			return title;
		}

		@Override
		public Window setTitle(String title)
		{
			this.title = title;
			return this;
		}

		@Override
		public boolean hasElement(int position, int row)
		{
			return elements.containsKey(new Slot(position, row));
		}

		@Override
		public Window setElement(int position, int row, Element element)
		{
			elements.put(new Slot(position, row), element);
			return this;
		}

		@Override
		public Element getElement(int position, int row)
		{
			return elements.get(new Slot(position, row));
		}

		@Override
		public Player getViewer()
		{
			return null;
		}

		@Override
		public Window reopen()
		{
			return this;
		}

		@Override
		public Window onClosed(Callback<Window> window)
		{
			return this;
		}
	}
}
