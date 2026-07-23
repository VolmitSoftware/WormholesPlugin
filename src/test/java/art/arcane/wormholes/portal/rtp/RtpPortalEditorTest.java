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
import art.arcane.wormholes.portal.rtp.RtpPortalEditor.Host;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusContext;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;

public final class RtpPortalEditorTest
{
	private static final UUID VIEWER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	public void overviewRoutesToOneClickSubmenusWithoutReconfiguringTheWindow()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status()));
		Rendered rendered = render(host);

		assertEquals("RTP Editor", rendered.window().title);
		assertEquals(6, rendered.window().viewportHeight);
		assertInstanceOf(UIPaneDecorator.class, rendered.window().decorator);
		assertTrue(rendered.window().hasId("rtp-open-destination"));
		assertTrue(rendered.window().hasId("rtp-open-landing"));
		assertTrue(rendered.window().hasId("rtp-open-routing"));
		assertTrue(rendered.window().hasId("rtp-open-effects"));

		click(rendered.window(), "rtp-open-effects", ElementEvent.LEFT);
		assertTrue(rendered.window().hasId("rtp-sound-on"));
		assertTrue(rendered.window().hasId("rtp-sound-off"));
		click(rendered.window(), "rtp-submenu-back", ElementEvent.LEFT);
		assertTrue(rendered.window().hasId("rtp-status"));
		assertEquals(1, rendered.window().titleSetCount);
	}

	@Test
	public void selectorsUseOnlyNormalLeftClicksAndRouteTypedMutations()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status()));
		Rendered rendered = render(host);
		click(rendered.window(), "rtp-open-effects", ElementEvent.LEFT);

		click(rendered.window(), "rtp-sound-off", ElementEvent.RIGHT);
		click(rendered.window(), "rtp-sound-off", ElementEvent.SHIFT_LEFT);
		click(rendered.window(), "rtp-sound-off", ElementEvent.SHIFT_RIGHT);
		assertTrue(host.mutations.isEmpty());

		click(rendered.window(), "rtp-sound-off", ElementEvent.LEFT);
		assertEquals(new RtpPortalEditorModel.SoundMutation(false), host.lastMutation());
		host.mutations.clear();
		click(rendered.window(), "rtp-rim-off", ElementEvent.LEFT);
		assertEquals(new RtpPortalEditorModel.RimMutation(false), host.lastMutation());

		host.mutations.clear();
		click(rendered.window(), "rtp-submenu-back", ElementEvent.LEFT);
		click(rendered.window(), "rtp-open-destination", ElementEvent.LEFT);
		click(rendered.window(), "rtp-center-custom", ElementEvent.LEFT);
		RtpPortalEditorModel.CenterModeMutation custom = assertInstanceOf(
				RtpPortalEditorModel.CenterModeMutation.class,
				host.lastMutation());
		assertEquals(RtpCenterMode.CUSTOM, custom.mode());
		assertEquals(18.75D, custom.customX().doubleValue());
		assertEquals(-42.5D, custom.customZ().doubleValue());
	}

	@Test
	public void numericSubmenuUsesSeparateDecreaseAndIncreaseButtons()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status()));
		Rendered rendered = render(host);
		click(rendered.window(), "rtp-open-destination", ElementEvent.LEFT);
		click(rendered.window(), "rtp-minimum-radius", ElementEvent.LEFT);

		assertTrue(rendered.window().hasId("rtp-numeric-decrease-large"));
		assertTrue(rendered.window().hasId("rtp-numeric-decrease-small"));
		assertTrue(rendered.window().hasId("rtp-numeric-increase-small"));
		assertTrue(rendered.window().hasId("rtp-numeric-increase-large"));

		click(rendered.window(), "rtp-numeric-increase-large", ElementEvent.RIGHT);
		assertTrue(host.mutations.isEmpty());
		click(rendered.window(), "rtp-numeric-increase-large", ElementEvent.LEFT);
		assertEquals(new RtpPortalEditorModel.RadiiMutation(1536, 4096), host.lastMutation());
		assertEquals(41L, host.mutations.getFirst().revision());
	}

	@Test
	public void perPlayerRoutingExposesRotationTimeAndNormalClickConfirmation()
	{
		SettingsSnapshot privateSettings = copy(settings(), RtpAllocationMode.PER_PLAYER, RtpRotationMode.ON_TRAVERSAL, true, true);
		FakeHost host = new FakeHost(snapshot(privateSettings, status()));
		Rendered rendered = render(host);
		click(rendered.window(), "rtp-open-routing", ElementEvent.LEFT);

		assertTrue(rendered.window().hasId("rtp-cycle-duration"));
		click(rendered.window(), "rtp-cycle-duration", ElementEvent.LEFT);
		String titleLore = String.join("\n", rendered.window().element("rtp-page-title").getLore());
		assertTrue(titleLore.contains("Maximum age"));
		click(rendered.window(), "rtp-numeric-back", ElementEvent.LEFT);

		click(rendered.window(), "rtp-rebuild-pool", ElementEvent.LEFT);
		assertNull(host.manualAction);
		assertTrue(rendered.window().hasId("rtp-manual-confirm"));
		click(rendered.window(), "rtp-manual-confirm", ElementEvent.LEFT);
		assertEquals(ManualAction.REBUILD_POOL, host.manualAction);
		assertEquals(41L, host.manualRevision);
	}

	@Test
	public void overviewResetsToDefaultsAndBackClosesImmediately()
	{
		FakeHost host = new FakeHost(snapshot(settings(), status()));
		Rendered rendered = render(host);

		assertFalse(rendered.window().hasId("rtp-apply"));
		assertFalse(rendered.window().hasId("rtp-discard"));
		assertTrue(rendered.window().hasId("rtp-reset-defaults"));
		click(rendered.window(), "rtp-reset-defaults", ElementEvent.LEFT);
		assertEquals(41L, host.resetRevision);
		assertEquals(VIEWER_ID, host.resetViewerId);

		click(rendered.window(), "rtp-back", ElementEvent.LEFT);
		assertTrue(rendered.window().closed);
		assertEquals(VIEWER_ID, host.backViewerId);
	}

	@Test
	public void statusDisplaysRemainingRetryBackoffWithoutCoordinates()
	{
		StatusSnapshot backoff = new StatusSnapshot(RtpPortalEditorModel.StatusState.BACKOFF, true, true,
				false, false, 0L, 45_000L, 0, 0, 0, 0);
		FakeHost host = new FakeHost(snapshot(settings(), backoff));
		Rendered rendered = render(host);
		String lore = String.join("\n", rendered.window().element("rtp-status").getLore());

		assertTrue(lore.contains("Retry in"));
		assertTrue(lore.contains("45s"));
		assertFalse(lore.contains("blockX"));
	}

	@Test
	public void settingsSnapshotAndMutationPreserveSoundPolicy()
	{
		World source = world("overworld", -64, 320, 63);
		SettingsSnapshot defaults = SettingsSnapshot.from(RtpSettings.defaults(source));

		assertTrue(defaults.rimEnabled());
		assertTrue(defaults.soundEnabled());
		RtpSettings muted = RtpPortalEditorModel.applyMutation(
				RtpSettings.defaults(source),
				new RtpPortalEditorModel.SoundMutation(false),
				source,
				key -> source);
		assertFalse(muted.isSoundEnabled());
	}

	@Test
	public void productionMutationApplicationCoversRoutingAndPresentationControls()
	{
		World source = world("overworld", -64, 320, 63);
		World target = world("the_nether", 0, 256, 32);
		RtpSettings.WorldResolver resolver = key -> "minecraft:the_nether".equals(key) ? target : source;
		RtpSettings settings = RtpSettings.defaults(source);

		settings = apply(settings, new RtpPortalEditorModel.TargetWorldMutation("minecraft:the_nether"), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.CenterModeMutation(RtpCenterMode.CUSTOM, 12.5D, -7.25D), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.RadiiMutation(96, 2048), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.VerticalModeMutation(RtpVerticalMode.PREFERRED_AVERAGE), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.YMutation(5, 200, 88), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.AllocationMutation(RtpAllocationMode.PER_PLAYER), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.CycleDurationMutation(600_000L), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.LeaseIdleMutation(45_000L), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.ReservationTimeoutMutation(25_000L), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.RimMutation(false), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.SoundMutation(false), source, resolver);

		assertEquals("minecraft:the_nether", settings.getTargetWorldKey());
		assertEquals(RtpCenterMode.CUSTOM, settings.getCenterMode());
		assertEquals(96, settings.getMinimumRadius());
		assertEquals(2048, settings.getMaximumRadius());
		assertEquals(RtpAllocationMode.PER_PLAYER, settings.getAllocationMode());
		assertEquals(600_000L, settings.getCycleDurationMillis());
		assertEquals(45_000L, settings.getLeaseIdleMillis());
		assertEquals(25_000L, settings.getPrivateReleaseMillis());
		assertFalse(settings.isRimEnabled());
		assertFalse(settings.isSoundEnabled());
	}

	@Test
	public void settingsSnapshotRejectsInvalidCustomState()
	{
		SettingsSnapshot source = settings();
		assertThrows(IllegalArgumentException.class, () -> new SettingsSnapshot(
				source.sourceWorldKey(), source.targetWorldKey(), RtpCenterMode.CUSTOM, null, null,
				source.minimumRadius(), source.maximumRadius(), source.verticalMode(), source.lowerY(), source.upperY(), source.preferredY(),
				source.allocationMode(), source.rotationMode(), source.cycleDurationMillis(), source.leaseIdleMillis(),
				source.reservationTimeoutMillis(), source.rimEnabled(), source.soundEnabled()));
	}

	private static RtpSettings apply(RtpSettings settings, Mutation mutation, World source, RtpSettings.WorldResolver resolver)
	{
		return RtpPortalEditorModel.applyMutation(settings, mutation, source, resolver);
	}

	private static Rendered render(FakeHost host)
	{
		FakeWindow window = new FakeWindow();
		RtpPortalEditor editor = new RtpPortalEditor(host);
		editor.populate(window, VIEWER_ID);
		return new Rendered(editor, window);
	}

	private static void click(FakeWindow window, String id, ElementEvent event)
	{
		Element element = window.element(id);
		element.call(event, element);
	}

	private static EditorSnapshot snapshot(SettingsSnapshot settings, StatusSnapshot status)
	{
		return new EditorSnapshot(41L, "RTP Editor", settings, status, worlds(), 18.75D, -42.5D);
	}

	private static SettingsSnapshot settings()
	{
		return new SettingsSnapshot("minecraft:overworld", "minecraft:overworld", RtpCenterMode.PORTAL_RELATIVE,
				null, null, 512, 4096, RtpVerticalMode.SURFACE, -63, 318, 64,
				RtpAllocationMode.SHARED, RtpRotationMode.STATIC, 300_000L, 30_000L, 15_000L, true, true);
	}

	private static SettingsSnapshot copy(
			SettingsSnapshot source,
			RtpAllocationMode allocationMode,
			RtpRotationMode rotationMode,
			boolean rimEnabled,
			boolean soundEnabled)
	{
		return new SettingsSnapshot(source.sourceWorldKey(), source.targetWorldKey(), source.centerMode(),
				source.customCenterX(), source.customCenterZ(), source.minimumRadius(), source.maximumRadius(), source.verticalMode(),
				source.lowerY(), source.upperY(), source.preferredY(), allocationMode, rotationMode,
				source.cycleDurationMillis(), source.leaseIdleMillis(), source.reservationTimeoutMillis(), rimEnabled, soundEnabled);
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

	private record Rendered(RtpPortalEditor editor, FakeWindow window)
	{
	}

	private record CapturedMutation(UUID viewerId, long revision, Mutation mutation)
	{
	}

	private static final class FakeHost implements Host
	{
		private final EditorSnapshot snapshot;
		private final List<CapturedMutation> mutations;
		private ManualAction manualAction;
		private long manualRevision;
		private long resetRevision;
		private UUID resetViewerId;
		private UUID backViewerId;

		private FakeHost(EditorSnapshot snapshot)
		{
			this.snapshot = snapshot;
			mutations = new ArrayList<CapturedMutation>();
		}

		private Mutation lastMutation()
		{
			assertEquals(1, mutations.size());
			return mutations.getFirst().mutation();
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
		public void reset(UUID viewerId, long expectedRevision)
		{
			resetViewerId = viewerId;
			resetRevision = expectedRevision;
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
		private int titleSetCount;

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
			return elements.values().stream().filter(element -> id.equals(element.getId())).findFirst().orElseThrow();
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
			titleSetCount++;
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
