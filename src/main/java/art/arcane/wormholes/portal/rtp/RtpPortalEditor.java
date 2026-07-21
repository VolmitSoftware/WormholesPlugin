package art.arcane.wormholes.portal.rtp;

import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_COORDINATE;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_CYCLE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_LEASE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_RADIUS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_RESERVATION_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_COORDINATE;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_CYCLE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_LEASE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_RESERVATION_MILLIS;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.AllocationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CenterModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CustomCenterMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CycleDurationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.LeaseIdleMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RadiiMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ReservationTimeoutMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ResetCenterTargetMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RimMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RotationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetWorldMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.VerticalModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YMutation;
import net.md_5.bungee.api.ChatColor;

public final class RtpPortalEditor
{
	private final Host host;

	public RtpPortalEditor(Host host)
	{
		this.host = Objects.requireNonNull(host, "host");
	}

	public void populate(Window window, UUID viewerId)
	{
		Window requiredWindow = Objects.requireNonNull(window, "window");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		EditorSnapshot snapshot = Objects.requireNonNull(host.snapshot(requiredViewerId), "snapshot");
		SettingsSnapshot settings = snapshot.settings();
		requiredWindow.batch(() ->
		{
			requiredWindow.clearElements();
			requiredWindow.setTitle(snapshot.title());
			requiredWindow.setViewportHeight(6);
			requiredWindow.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
			requiredWindow.setElement(0, 0, statusElement(snapshot));
			requiredWindow.setElement(-4, 1, centerModeElement(snapshot, requiredViewerId));
			requiredWindow.setElement(-2, 1, targetWorldElement(snapshot, requiredViewerId));
			if(settings.centerMode() == RtpCenterMode.CUSTOM)
			{
				requiredWindow.setElement(0, 1, centerCoordinateElement(snapshot, requiredViewerId, true));
				requiredWindow.setElement(2, 1, centerCoordinateElement(snapshot, requiredViewerId, false));
			}
			requiredWindow.setElement(4, 1, resetElement(snapshot, requiredViewerId));
			requiredWindow.setElement(-3, 2, minimumRadiusElement(snapshot, requiredViewerId));
			requiredWindow.setElement(-1, 2, maximumRadiusElement(snapshot, requiredViewerId));
			requiredWindow.setElement(1, 2, verticalModeElement(snapshot, requiredViewerId));
			requiredWindow.setElement(3, 2, yElement(snapshot, requiredViewerId, YField.LOWER));
			requiredWindow.setElement(-3, 3, yElement(snapshot, requiredViewerId, YField.UPPER));
			if(settings.verticalMode() == RtpVerticalMode.PREFERRED_AVERAGE)
			{
				requiredWindow.setElement(-1, 3, yElement(snapshot, requiredViewerId, YField.PREFERRED));
			}
			requiredWindow.setElement(1, 3, allocationElement(snapshot, requiredViewerId));
			if(settings.allocationMode() == RtpAllocationMode.SHARED)
			{
				requiredWindow.setElement(3, 3, rotationElement(snapshot, requiredViewerId));
			}
			if(settings.allocationMode() == RtpAllocationMode.SHARED && settings.rotationMode() == RtpRotationMode.TIMED)
			{
				requiredWindow.setElement(-3, 4, cycleElement(snapshot, requiredViewerId));
			}
			requiredWindow.setElement(-1, 4, leaseElement(snapshot, requiredViewerId));
			requiredWindow.setElement(1, 4, reservationElement(snapshot, requiredViewerId));
			requiredWindow.setElement(3, 4, rimElement(snapshot, requiredViewerId));
			requiredWindow.setElement(-1, 5, manualElement(snapshot, requiredViewerId));
			requiredWindow.setElement(1, 5, backElement(requiredWindow, requiredViewerId));
		});
	}

	private Element statusElement(EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		StatusSnapshot status = snapshot.status();
		UIElement element = new UIElement("rtp-status");
		element.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Random Destination Status");
		element.setMaterial(new MaterialBlock(statusMaterial(status.state())));
		element.setEnchanted(status.state() == StatusState.READY);
		element.addLore(ChatColor.GRAY + "State: " + statusColor(status.state()) + statusLabel(status.state()));
		if(status.state() == StatusState.BACKOFF)
		{
			element.addLore(ChatColor.GRAY + "Retry in: " + ChatColor.RED + formatDuration(status.remainingBackoffMillis()));
		}
		if(settings.allocationMode() == RtpAllocationMode.SHARED)
		{
			element.addLore(ChatColor.GRAY + "Active: " + readiness(status.activeReady()));
			element.addLore(ChatColor.GRAY + "Standby: " + readiness(status.standbyReady()));
			element.addLore(ChatColor.GRAY + "Rotation: " + ChatColor.AQUA + rotationLabel(settings.rotationMode()));
			if(settings.rotationMode() == RtpRotationMode.TIMED)
			{
				element.addLore(ChatColor.GRAY + "Next cycle: " + ChatColor.AQUA + formatDuration(status.remainingCycleMillis()));
			}
		}
		else
		{
			element.addLore(ChatColor.GRAY + "Free: " + ChatColor.AQUA + status.freeCount()
					+ ChatColor.GRAY + "  Reserved: " + ChatColor.AQUA + status.reservedCount());
			element.addLore(ChatColor.GRAY + "Validating: " + ChatColor.AQUA + status.validatingCount()
					+ ChatColor.GRAY + "  In-flight: " + ChatColor.AQUA + status.inFlightCount());
		}
		if(!status.targetWorldAvailable())
		{
			element.addLore(ChatColor.RED + "Target world is not loaded.");
		}
		if(!status.integrationAvailable())
		{
			element.addLore(ChatColor.RED + "Destination access checks failed closed.");
		}
		return element;
	}

	private Element centerModeElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		UIElement element = new UIElement("rtp-center-mode");
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Center: " + centerLabel(settings.centerMode()));
		element.setMaterial(new MaterialBlock(settings.centerMode() == RtpCenterMode.CUSTOM ? Material.LODESTONE : Material.RECOVERY_COMPASS));
		element.addLore(ChatColor.GRAY + "Choose the annulus center.");
		element.addLore(ChatColor.DARK_GRAY + "Left/Shift-left: next.");
		element.addLore(ChatColor.DARK_GRAY + "Right/Shift-right: previous.");
		CenterModeMutation next = centerModeMutation(snapshot, cycle(settings.centerMode(), 1));
		CenterModeMutation previous = centerModeMutation(snapshot, cycle(settings.centerMode(), -1));
		bindBidirectional(element, snapshot, viewerId, next, previous);
		return element;
	}

	private CenterModeMutation centerModeMutation(EditorSnapshot snapshot, RtpCenterMode mode)
	{
		SettingsSnapshot settings = snapshot.settings();
		Double customX = settings.customCenterX();
		Double customZ = settings.customCenterZ();
		if(mode == RtpCenterMode.CUSTOM && customX == null)
		{
			customX = Double.valueOf(snapshot.sourceCenterX());
			customZ = Double.valueOf(snapshot.sourceCenterZ());
		}
		return new CenterModeMutation(mode, customX, customZ);
	}

	private Element targetWorldElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		WorldOption current = snapshot.world(settings.targetWorldKey());
		UIElement element = new UIElement("rtp-target-world");
		element.setName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Target World");
		element.setMaterial(new MaterialBlock(current == null ? Material.BARRIER : Material.ENDER_EYE));
		element.addLore(ChatColor.GRAY + "Current: " + (current == null ? ChatColor.RED + settings.targetWorldKey() : ChatColor.WHITE + current.displayName()));
		element.addLore(ChatColor.DARK_GRAY + "Left: next loaded world.");
		element.addLore(ChatColor.DARK_GRAY + "Right/Shift-right: previous.");
		element.addLore(ChatColor.DARK_GRAY + "Shift-left: source world.");
		element.onLeftClick(clicked -> dispatch(snapshot, viewerId, new TargetWorldMutation(cycleWorld(snapshot, 1))));
		element.onRightClick(clicked -> dispatch(snapshot, viewerId, new TargetWorldMutation(cycleWorld(snapshot, -1))));
		element.onShiftLeftClick(clicked -> dispatch(snapshot, viewerId, new TargetWorldMutation(settings.sourceWorldKey())));
		element.onShiftRightClick(clicked -> dispatch(snapshot, viewerId, new TargetWorldMutation(cycleWorld(snapshot, -1))));
		return element;
	}

	private String cycleWorld(EditorSnapshot snapshot, int direction)
	{
		List<WorldOption> worlds = snapshot.loadedWorlds();
		if(worlds.isEmpty())
		{
			return snapshot.settings().targetWorldKey();
		}
		String currentKey = snapshot.settings().targetWorldKey();
		int currentIndex = -1;
		for(int i = 0; i < worlds.size(); i++)
		{
			if(worlds.get(i).key().equalsIgnoreCase(currentKey))
			{
				currentIndex = i;
				break;
			}
		}
		if(currentIndex < 0)
		{
			return direction > 0 ? worlds.getFirst().key() : worlds.getLast().key();
		}
		return worlds.get(Math.floorMod(currentIndex + direction, worlds.size())).key();
	}

	private Element centerCoordinateElement(EditorSnapshot snapshot, UUID viewerId, boolean xAxis)
	{
		SettingsSnapshot settings = snapshot.settings();
		double currentX = Objects.requireNonNull(settings.customCenterX(), "customCenterX").doubleValue();
		double currentZ = Objects.requireNonNull(settings.customCenterZ(), "customCenterZ").doubleValue();
		double current = xAxis ? currentX : currentZ;
		CustomCenterMutation left = centerMutation(currentX, currentZ, xAxis, 16.0D);
		CustomCenterMutation right = centerMutation(currentX, currentZ, xAxis, -16.0D);
		CustomCenterMutation shiftLeft = centerMutation(currentX, currentZ, xAxis, 256.0D);
		CustomCenterMutation shiftRight = centerMutation(currentX, currentZ, xAxis, -256.0D);
		return numericElement(snapshot, viewerId, new NumericControl(
				xAxis ? "rtp-center-x" : "rtp-center-z",
				"Center " + (xAxis ? "X" : "Z"),
				formatCoordinate(current),
				"Moves the custom center on this axis.",
				Material.COMPASS,
				"16", "256", left, right, shiftLeft, shiftRight, true));
	}

	private CustomCenterMutation centerMutation(double x, double z, boolean xAxis, double delta)
	{
		double adjusted = clamp((xAxis ? x : z) + delta, MINIMUM_COORDINATE, MAXIMUM_COORDINATE);
		return xAxis ? new CustomCenterMutation(adjusted, z) : new CustomCenterMutation(x, adjusted);
	}

	private Element resetElement(EditorSnapshot snapshot, UUID viewerId)
	{
		UIElement element = new UIElement("rtp-center-reset");
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Reset Center / Target");
		element.setMaterial(new MaterialBlock(Material.COMPASS));
		element.addLore(ChatColor.GRAY + "Use the source world and portal center.");
		element.addLore(ChatColor.GRAY + "Clears retained custom coordinates.");
		element.addLore(ChatColor.DARK_GRAY + "Left click to reset.");
		element.onLeftClick(clicked -> dispatch(snapshot, viewerId, new ResetCenterTargetMutation()));
		return element;
	}

	private Element minimumRadiusElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		return numericElement(snapshot, viewerId, new NumericControl(
				"rtp-minimum-radius", "Minimum Radius", Integer.toString(settings.minimumRadius()),
				"Inner edge of the destination annulus.", Material.SPYGLASS, "128", "1024",
				adjustMinimumRadius(settings, 128), adjustMinimumRadius(settings, -128),
				adjustMinimumRadius(settings, 1024), adjustMinimumRadius(settings, -1024), true));
	}

	private Element maximumRadiusElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		return numericElement(snapshot, viewerId, new NumericControl(
				"rtp-maximum-radius", "Maximum Radius", Integer.toString(settings.maximumRadius()),
				"Outer edge of the destination annulus.", Material.TARGET, "128", "1024",
				adjustMaximumRadius(settings, 128), adjustMaximumRadius(settings, -128),
				adjustMaximumRadius(settings, 1024), adjustMaximumRadius(settings, -1024), true));
	}

	private RadiiMutation adjustMinimumRadius(SettingsSnapshot settings, int delta)
	{
		int selected = clamp(settings.minimumRadius() + delta, 0, MAXIMUM_RADIUS);
		if(selected < settings.maximumRadius())
		{
			return new RadiiMutation(selected, settings.maximumRadius());
		}
		if(selected < MAXIMUM_RADIUS)
		{
			return new RadiiMutation(selected, selected + 1);
		}
		return new RadiiMutation(MAXIMUM_RADIUS - 1, MAXIMUM_RADIUS);
	}

	private RadiiMutation adjustMaximumRadius(SettingsSnapshot settings, int delta)
	{
		int selected = clamp(settings.maximumRadius() + delta, 0, MAXIMUM_RADIUS);
		if(selected > settings.minimumRadius())
		{
			return new RadiiMutation(settings.minimumRadius(), selected);
		}
		if(selected > 0)
		{
			return new RadiiMutation(selected - 1, selected);
		}
		return new RadiiMutation(0, 1);
	}

	private Element verticalModeElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		UIElement element = new UIElement("rtp-vertical-mode");
		element.setName(ChatColor.GREEN + "" + ChatColor.BOLD + "Vertical: " + verticalLabel(settings.verticalMode()));
		element.setMaterial(new MaterialBlock(settings.verticalMode() == RtpVerticalMode.SURFACE ? Material.GRASS_BLOCK : Material.AMETHYST_SHARD));
		element.addLore(ChatColor.GRAY + "Choose surface or preferred-average search.");
		element.addLore(ChatColor.DARK_GRAY + "Left/Shift-left: next.");
		element.addLore(ChatColor.DARK_GRAY + "Right/Shift-right: previous.");
		VerticalModeMutation next = new VerticalModeMutation(cycle(settings.verticalMode(), 1));
		VerticalModeMutation previous = new VerticalModeMutation(cycle(settings.verticalMode(), -1));
		bindBidirectional(element, snapshot, viewerId, next, previous);
		return element;
	}

	private Element yElement(EditorSnapshot snapshot, UUID viewerId, YField field)
	{
		SettingsSnapshot settings = snapshot.settings();
		WorldOption target = snapshot.world(settings.targetWorldKey());
		int current = switch(field)
		{
			case LOWER -> settings.lowerY();
			case UPPER -> settings.upperY();
			case PREFERRED -> settings.preferredY();
		};
		String id = switch(field)
		{
			case LOWER -> "rtp-lower-y";
			case UPPER -> "rtp-upper-y";
			case PREFERRED -> "rtp-preferred-y";
		};
		String label = switch(field)
		{
			case LOWER -> "Lower Y";
			case UPPER -> "Upper Y";
			case PREFERRED -> "Preferred Y";
		};
		boolean enabled = target != null;
		Mutation left = enabled ? adjustY(settings, target, field, 1) : null;
		Mutation right = enabled ? adjustY(settings, target, field, -1) : null;
		Mutation shiftLeft = enabled ? adjustY(settings, target, field, 8) : null;
		Mutation shiftRight = enabled ? adjustY(settings, target, field, -8) : null;
		return numericElement(snapshot, viewerId, new NumericControl(id, label, Integer.toString(current),
				enabled ? "Legal feet-height search bound." : "Load the target world to edit Y values.",
				field == YField.PREFERRED ? Material.AMETHYST_SHARD : Material.LADDER,
				"1", "8", left, right, shiftLeft, shiftRight, enabled));
	}

	private YMutation adjustY(SettingsSnapshot settings, WorldOption target, YField field, int delta)
	{
		int lower = settings.lowerY();
		int upper = settings.upperY();
		int preferred = settings.preferredY();
		switch(field)
		{
			case LOWER ->
			{
				lower = Math.min(clamp(lower + delta, target.minimumFeetY(), target.maximumFeetY()), upper);
				preferred = Math.max(preferred, lower);
			}
			case UPPER ->
			{
				upper = Math.max(clamp(upper + delta, target.minimumFeetY(), target.maximumFeetY()), lower);
				preferred = Math.min(preferred, upper);
			}
			case PREFERRED -> preferred = clamp(preferred + delta, lower, upper);
		}
		return new YMutation(lower, upper, preferred);
	}

	private Element allocationElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		UIElement element = new UIElement("rtp-allocation");
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + "Allocation: " + allocationLabel(settings.allocationMode()));
		element.setMaterial(new MaterialBlock(settings.allocationMode() == RtpAllocationMode.SHARED ? Material.ENDER_CHEST : Material.PLAYER_HEAD));
		element.addLore(ChatColor.GRAY + "Shared route or private reservations.");
		element.addLore(ChatColor.DARK_GRAY + "Left/Shift-left: next.");
		element.addLore(ChatColor.DARK_GRAY + "Right/Shift-right: previous.");
		AllocationMutation next = new AllocationMutation(cycle(settings.allocationMode(), 1));
		AllocationMutation previous = new AllocationMutation(cycle(settings.allocationMode(), -1));
		bindBidirectional(element, snapshot, viewerId, next, previous);
		return element;
	}

	private Element rotationElement(EditorSnapshot snapshot, UUID viewerId)
	{
		SettingsSnapshot settings = snapshot.settings();
		UIElement element = new UIElement("rtp-shared-rotation");
		element.setName(ChatColor.GOLD + "" + ChatColor.BOLD + "Rotation: " + rotationLabel(settings.rotationMode()));
		element.setMaterial(new MaterialBlock(Material.CLOCK));
		element.addLore(ChatColor.GRAY + "Controls shared destination rotation.");
		element.addLore(ChatColor.DARK_GRAY + "Left/Shift-left: next.");
		element.addLore(ChatColor.DARK_GRAY + "Right/Shift-right: previous.");
		RotationMutation next = new RotationMutation(cycle(settings.rotationMode(), 1));
		RotationMutation previous = new RotationMutation(cycle(settings.rotationMode(), -1));
		bindBidirectional(element, snapshot, viewerId, next, previous);
		return element;
	}

	private Element cycleElement(EditorSnapshot snapshot, UUID viewerId)
	{
		long current = snapshot.settings().cycleDurationMillis();
		return numericElement(snapshot, viewerId, new NumericControl(
				"rtp-cycle-duration", "Cycle Duration", formatDuration(current),
				"Time between shared timed rotations.", Material.CLOCK, "30s", "5m",
				new CycleDurationMutation(clamp(current + 30_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)),
				new CycleDurationMutation(clamp(current - 30_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)),
				new CycleDurationMutation(clamp(current + 300_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)),
				new CycleDurationMutation(clamp(current - 300_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)), true));
	}

	private Element leaseElement(EditorSnapshot snapshot, UUID viewerId)
	{
		long current = snapshot.settings().leaseIdleMillis();
		return numericElement(snapshot, viewerId, new NumericControl(
				"rtp-lease-idle", "Lease Idle", formatDuration(current),
				"Delay before unattended destination leases release.", Material.LEAD, "5s", "30s",
				new LeaseIdleMutation(clamp(current + 5_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)),
				new LeaseIdleMutation(clamp(current - 5_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)),
				new LeaseIdleMutation(clamp(current + 30_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)),
				new LeaseIdleMutation(clamp(current - 30_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)), true));
	}

	private Element reservationElement(EditorSnapshot snapshot, UUID viewerId)
	{
		long current = snapshot.settings().reservationTimeoutMillis();
		return numericElement(snapshot, viewerId, new NumericControl(
				"rtp-reservation-timeout", "Reservation Leave", formatDuration(current),
				"Delay before an unused private reservation releases.", Material.TRIPWIRE_HOOK, "5s", "30s",
				new ReservationTimeoutMutation(clamp(current + 5_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)),
				new ReservationTimeoutMutation(clamp(current - 5_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)),
				new ReservationTimeoutMutation(clamp(current + 30_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)),
				new ReservationTimeoutMutation(clamp(current - 30_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)), true));
	}

	private Element rimElement(EditorSnapshot snapshot, UUID viewerId)
	{
		boolean enabled = snapshot.settings().rimEnabled();
		UIElement element = new UIElement("rtp-rim");
		element.setName((enabled ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + "Rim: " + (enabled ? "On" : "Off"));
		element.setMaterial(new MaterialBlock(enabled ? Material.GLOWSTONE_DUST : Material.GUNPOWDER));
		element.setEnchanted(enabled);
		element.addLore(ChatColor.GRAY + "Show private RTP readiness on the rim.");
		element.addLore(ChatColor.DARK_GRAY + "Any left/right click toggles.");
		RimMutation mutation = new RimMutation(!enabled);
		bindBidirectional(element, snapshot, viewerId, mutation, mutation);
		return element;
	}

	private Element manualElement(EditorSnapshot snapshot, UUID viewerId)
	{
		boolean shared = snapshot.settings().allocationMode() == RtpAllocationMode.SHARED;
		ManualAction action = shared ? ManualAction.REROLL : ManualAction.REBUILD_POOL;
		UIElement element = new UIElement(shared ? "rtp-manual-reroll" : "rtp-rebuild-pool");
		element.setName(ChatColor.RED + "" + ChatColor.BOLD + (shared ? "Manual Reroll" : "Rebuild Pool"));
		element.setMaterial(new MaterialBlock(shared ? Material.FIRE_CHARGE : Material.BLAZE_POWDER));
		element.addLore(ChatColor.GRAY + (shared ? "Prepare a replacement shared route." : "Rebuild free candidates without removing reservations."));
		element.addLore(" ");
		element.addLore(ChatColor.RED + "" + ChatColor.UNDERLINE + "Shift + Left Click to confirm");
		element.onShiftLeftClick(clicked -> host.manual(viewerId, snapshot.configurationRevision(), action));
		return element;
	}

	private Element backElement(Window window, UUID viewerId)
	{
		UIElement element = new UIElement("rtp-back");
		element.setName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Back");
		element.setMaterial(new MaterialBlock(Material.ARROW));
		element.addLore(ChatColor.GRAY + "Return to the portal menu.");
		element.onLeftClick(clicked ->
		{
			window.close();
			host.back(viewerId);
		});
		return element;
	}

	private Element numericElement(EditorSnapshot snapshot, UUID viewerId, NumericControl control)
	{
		UIElement element = new UIElement(control.id());
		element.setName(ChatColor.AQUA + "" + ChatColor.BOLD + control.label() + " " + ChatColor.WHITE + control.value());
		element.setMaterial(new MaterialBlock(control.material()));
		element.addLore(ChatColor.GRAY + control.description());
		element.addLore(" ");
		element.addLore(ChatColor.GRAY + "Current: " + ChatColor.AQUA + control.value());
		element.addLore(" ");
		if(!control.enabled())
		{
			element.addLore(ChatColor.RED + "Unavailable until the target world is loaded.");
			return element;
		}
		element.addLore(ChatColor.DARK_GRAY + "Left: +" + control.normalStep());
		element.addLore(ChatColor.DARK_GRAY + "Right: -" + control.normalStep());
		element.addLore(ChatColor.DARK_GRAY + "Shift-left: +" + control.largeStep());
		element.addLore(ChatColor.DARK_GRAY + "Shift-right: -" + control.largeStep());
		element.onLeftClick(clicked -> dispatch(snapshot, viewerId, control.left()));
		element.onRightClick(clicked -> dispatch(snapshot, viewerId, control.right()));
		element.onShiftLeftClick(clicked -> dispatch(snapshot, viewerId, control.shiftLeft()));
		element.onShiftRightClick(clicked -> dispatch(snapshot, viewerId, control.shiftRight()));
		return element;
	}

	private void bindBidirectional(UIElement element, EditorSnapshot snapshot, UUID viewerId, Mutation next, Mutation previous)
	{
		element.onLeftClick(clicked -> dispatch(snapshot, viewerId, next));
		element.onShiftLeftClick(clicked -> dispatch(snapshot, viewerId, next));
		element.onRightClick(clicked -> dispatch(snapshot, viewerId, previous));
		element.onShiftRightClick(clicked -> dispatch(snapshot, viewerId, previous));
	}

	private void dispatch(EditorSnapshot snapshot, UUID viewerId, Mutation mutation)
	{
		host.mutate(viewerId, snapshot.configurationRevision(), mutation);
	}

	private static Material statusMaterial(StatusState state)
	{
		return switch(state)
		{
			case READY -> Material.BEACON;
			case WARMING, REROLLING -> Material.CLOCK;
			case BACKOFF, FAILED, INTEGRATION_FAILED, TARGET_WORLD_UNAVAILABLE -> Material.BARRIER;
			case IDLE -> Material.COMPASS;
		};
	}

	private static ChatColor statusColor(StatusState state)
	{
		return switch(state)
		{
			case READY -> ChatColor.GREEN;
			case WARMING, REROLLING -> ChatColor.YELLOW;
			case IDLE -> ChatColor.GRAY;
			case BACKOFF, FAILED, INTEGRATION_FAILED, TARGET_WORLD_UNAVAILABLE -> ChatColor.RED;
		};
	}

	private static String statusLabel(StatusState state)
	{
		return switch(state)
		{
			case READY -> "Ready";
			case WARMING -> "Warming";
			case REROLLING -> "Rerolling";
			case BACKOFF -> "Retry Backoff";
			case TARGET_WORLD_UNAVAILABLE -> "Target World Unavailable";
			case INTEGRATION_FAILED -> "Access Integration Failed";
			case FAILED -> "Failed";
			case IDLE -> "Idle";
		};
	}

	private static String readiness(boolean ready)
	{
		return ready ? ChatColor.GREEN + "Ready" : ChatColor.YELLOW + "Preparing";
	}

	private static String centerLabel(RtpCenterMode mode)
	{
		return mode == RtpCenterMode.PORTAL_RELATIVE ? "Portal Relative" : "Custom";
	}

	private static String verticalLabel(RtpVerticalMode mode)
	{
		return mode == RtpVerticalMode.SURFACE ? "Surface" : "Preferred Average";
	}

	private static String allocationLabel(RtpAllocationMode mode)
	{
		return mode == RtpAllocationMode.SHARED ? "Shared" : "Per-player";
	}

	private static String rotationLabel(RtpRotationMode mode)
	{
		return switch(mode)
		{
			case STATIC -> "Static";
			case TIMED -> "Timed";
			case ON_TRAVERSAL -> "On Traversal";
		};
	}

	private static String formatCoordinate(double coordinate)
	{
		return Double.toString(coordinate);
	}

	private static String formatDuration(long durationMillis)
	{
		if(durationMillis % 3_600_000L == 0L)
		{
			return durationMillis / 3_600_000L + "h";
		}
		if(durationMillis % 60_000L == 0L)
		{
			return durationMillis / 60_000L + "m";
		}
		if(durationMillis % 1_000L == 0L)
		{
			return durationMillis / 1_000L + "s";
		}
		return String.format(Locale.ROOT, "%.1fs", Double.valueOf(durationMillis / 1_000.0D));
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static long clamp(long value, long minimum, long maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static double clamp(double value, double minimum, double maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static <E extends Enum<E>> E cycle(E current, int direction)
	{
		E[] values = current.getDeclaringClass().getEnumConstants();
		return values[Math.floorMod(current.ordinal() + direction, values.length)];
	}

	public interface Host
	{
		EditorSnapshot snapshot(UUID viewerId);

		void mutate(UUID viewerId, long expectedRevision, Mutation mutation);

		void manual(UUID viewerId, long expectedRevision, ManualAction action);

		void back(UUID viewerId);
	}

	private record NumericControl(
			String id,
			String label,
			String value,
			String description,
			Material material,
			String normalStep,
			String largeStep,
			Mutation left,
			Mutation right,
			Mutation shiftLeft,
			Mutation shiftRight,
			boolean enabled)
	{
		private NumericControl
		{
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(value, "value");
			Objects.requireNonNull(description, "description");
			Objects.requireNonNull(material, "material");
			Objects.requireNonNull(normalStep, "normalStep");
			Objects.requireNonNull(largeStep, "largeStep");
			if(enabled)
			{
				Objects.requireNonNull(left, "left");
				Objects.requireNonNull(right, "right");
				Objects.requireNonNull(shiftLeft, "shiftLeft");
				Objects.requireNonNull(shiftRight, "shiftRight");
			}
		}
	}

	private enum YField
	{
		LOWER,
		UPPER,
		PREFERRED
	}

}
