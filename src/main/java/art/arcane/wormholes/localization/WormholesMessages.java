package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WormholesMessages {
    public static final String ENGLISH_LOCALE = VolmitLocales.ENGLISH;

    private static final List<MessageKey> KEYS = new ArrayList<>();

    public static final TextKey COMMAND_ROOT_DESCRIPTION = text("command.help.root", "Wormholes command root");
    public static final TextKey COMMAND_WAND_DESCRIPTION = text("command.help.wand", "Give yourself the portal wand, or runes with rune=<type>");
    public static final TextKey COMMAND_WAND_RUNE_DESCRIPTION = text("command.help.wand.rune", "portal | wormhole | gateway");
    public static final TextKey COMMAND_WAND_COUNT_DESCRIPTION = text("command.help.wand.count", "How many runes (default 1)");
    public static final TextKey COMMAND_DOOR_DESCRIPTION = text("command.help.door", "Give a survival Dimensional Door item");
    public static final TextKey COMMAND_DOOR_TYPE_DESCRIPTION = text("command.help.door.type", "pair | personal | public");
    public static final TextKey COMMAND_RELOAD_DESCRIPTION = text("command.help.reload", "Reload Wormholes configuration and language files");
    public static final TextKey COMMAND_DEBUG_DESCRIPTION = text("command.help.debug", "Toggle verbose console logs and one-second telemetry");
    public static final TextKey COMMAND_STATS_DESCRIPTION = text("command.help.stats", "Print the live stats-snapshot file path, optionally force a refresh with now=true");
    public static final TextKey COMMAND_STATS_NOW_DESCRIPTION = text("command.help.stats.now", "Force-rebuild the snapshot synchronously");
    public static final TextKey COMMAND_INFO_DESCRIPTION = text("command.help.info", "Show portal building instructions");
    public static final TextKey COMMAND_ADMIN_DESCRIPTION = text("command.help.admin", "Destructive Wormholes maintenance commands");
    public static final TextKey COMMAND_DELETE_PORTALS_DESCRIPTION = text("command.help.admin.delete_portals", "Delete every local portal and saved portal link");
    public static final TextKey COMMAND_DELETE_EVERYTHING_DESCRIPTION = text("command.help.admin.delete_everything", "Reset Wormholes data, config, trust, identity, and network state");
    public static final TextKey COMMAND_NETWORK_DESCRIPTION = text("command.help.network", "Cross-server wormhole network");
    public static final TextKey COMMAND_NETWORK_IMPORT_DESCRIPTION = text("command.help.network.import", "Import a portal code from another server (saves an internal route; link via a gateway's Link menu)");
    public static final TextKey COMMAND_NETWORK_CODE_DESCRIPTION = text("command.help.network.import.code", "Portal code from the other server's Export button");
    public static final TextKey COMMAND_NETWORK_STATUS_DESCRIPTION = text("command.help.network.status", "Show peer connection status");
    public static final TextKey COMMAND_NETWORK_DOCTOR_DESCRIPTION = text("command.help.network.doctor", "Explain why network peers are not connecting");

    public static final TextKey COMMAND_NO_PERMISSION = text("command.error.no_permission", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>You do not have permission.");
    public static final TextKey COMMAND_NO_PERMISSION_USE = text("command.error.no_permission_use", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>You do not have permission to use that command.");
    public static final TextKey COMMAND_ONLY_PLAYERS = text("command.error.only_players", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Only players can receive items.");
    public static final TextKey COMMAND_USAGE_HELP = text("command.error.usage", "<gray>Usage: <white>/wormholes help");
    public static final LinesKey COMMAND_PUBLIC_HELP = lines("command.public_help",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Portal help: <white>/wormholes info",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Use the Portal Wand on a portal to open its destination, view, travel, and access controls.");
    public static final TextKey COMMAND_UNKNOWN_RUNE = text("command.wand.unknown_rune", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Unknown rune type '<white>{rune}<red>'. Use portal, wormhole, or gateway.");
    public static final PluralKey COMMAND_GRANTED_RUNES = plural("command.wand.granted_runes", "count", Map.of(
            "one", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Granted <white>{count} {rune}<green> rune.",
            "other", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Granted <white>{count} {rune}<green> runes."
    ));
    public static final LinesKey COMMAND_GRANTED_STARTER = lines("command.wand.granted_starter",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Portal Wand and 1 Wormhole Rune granted.",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Build TWO wormhole-rune shapes (any connected shape on one flat surface), link them, and stand within 16 blocks to see the projection.",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Run <white>/wormholes info<gray> for the full step-by-step.");
    public static final TextKey COMMAND_DOORS_UNAVAILABLE = text("command.door.unavailable", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Dimensional Doors are unavailable.");
    public static final TextKey COMMAND_UNKNOWN_DOOR = text("command.door.unknown_type", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Unknown door type. Use pair, personal, or public.");
    public static final TextKey COMMAND_EMPTY_DOOR = text("command.door.empty_type", "Door type cannot be empty");
    public static final TextKey COMMAND_GRANTED_DOOR = text("command.door.granted", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Granted a <white>{type}<green> dimensional door item.");
    public static final TextKey COMMAND_RELOADED = text("command.reload.applied", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Wormholes configuration and language files reloaded.");
    public static final TextKey COMMAND_RELOADED_LANGUAGE_RETAINED = text("command.reload.language_retained", "<dark_gray>[<gold>Wormholes<dark_gray>] <yellow>Configuration reloaded, but the language file was rejected. The last valid language remains active; check the console.");
    public static final TextKey COMMAND_STATS_UNAVAILABLE = text("command.stats.unavailable", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Stats snapshot writer is unavailable.");
    public static final TextKey COMMAND_STATS_REFRESHED = text("command.stats.refreshed", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Snapshot refreshed.");
    public static final LinesKey COMMAND_STATS_PATH = lines("command.stats.path",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Snapshot file: <white>{path}",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <dark_gray>Tail this file to share live network/view state. The file is overwritten in place each interval.");
    public static final LinesKey COMMAND_INFO = lines("command.info",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray><bold>How to build a Wormhole</bold>",
            "<dark_gray>1. <gray>Get a Portal Wand and portal runes from your server or an administrator.",
            "<dark_gray>2. <gray>Place the runes in any connected shape on one flat surface.",
            "<gray>   Any connected shape works: rectangles, lines (3x1), single blocks, L-shapes, crosses.",
            "<gray>   The runes must sit flat on one axis-aligned wall, floor, or ceiling.",
            "<dark_gray>3. <gray>Hold the Portal Wand and <white>left-click any rune block<gray> to form the portal.",
            "<gray>   Shapes that do not sit flat on one surface are refunded automatically.",
            "<dark_gray>4. <gray>Build a SECOND portal somewhere else (any distance, any world).",
            "<dark_gray>5. <gray>Click the open portal with the wand to open the main menu.",
            "<gray>   Choose <white>Destination<gray> and select the other portal. Repeat from the other side.",
            "<gray>   Orientation and access controls are grouped into their own simple menus.",
            "<dark_gray>6. <gray>Stand within 16 blocks of either portal — the destination world will project through the frame and walking in teleports you.",
            "<gray>Administrators can create supplies with <white>/wormholes wand rune=\\<portal|wormhole|gateway> count=\\<n>",
            "<gray>Dimensional Doors are crafted with Wormhole Runes. Open a placed door and physically cross its threshold to travel; a closed door never activates.");
    public static final PluralKey COMMAND_DELETED_PORTALS = plural("command.admin.deleted_portals", "count", Map.of(
            "one", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Deleted <white>{count}<green> portal and cleared local portal links.",
            "other", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Deleted <white>{count}<green> portals and cleared local portal links."
    ));
    public static final PluralKey COMMAND_RESET_EVERYTHING = plural("command.admin.reset_everything", "count", Map.of(
            "one", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Wormholes reset to default state. Deleted <white>{count}<green> portal, closed network connections, and regenerated default config files.",
            "other", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>Wormholes reset to default state. Deleted <white>{count}<green> portals, closed network connections, and regenerated default config files."
    ));
    public static final TextKey COMMAND_DELETE_FAILED = text("command.admin.delete_failed", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Failed to delete all portals. Check console for the full stacktrace.");
    public static final TextKey COMMAND_DELETE_SCHEDULE_FAILED = text("command.admin.delete_schedule_failed", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Could not schedule the portal reset.");
    public static final TextKey COMMAND_RESET_FAILED = text("command.admin.reset_failed", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Failed to reset Wormholes. Check console for the full stacktrace.");
    public static final TextKey COMMAND_RESET_SCHEDULE_FAILED = text("command.admin.reset_schedule_failed", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Could not schedule the Wormholes reset.");

    public static final TextKey NETWORK_NOT_INITIALIZED = text("network.not_initialized", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Networking is not initialized.");
    public static final TextKey NETWORK_DISABLED = text("network.status.disabled", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Networking is <red>disabled<gray> (config/wormholes.toml).");
    public static final TextKey NETWORK_NOT_RUNNING = text("network.status.not_running", "<dark_gray>[<gold>Wormholes<dark_gray>] <red>Networking is enabled but not running. Check the identity store and network port.");
    public static final TextKey NETWORK_LISTENING = text("network.status.listening", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>This server: <white>{server}<gray> listening on <white>{address}");
    public static final TextKey NETWORK_OUTBOUND_ONLY = text("network.status.outbound_only", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>This server: <white>{server}<gray> outbound-only Boat mode");
    public static final TextKey NETWORK_PUBLIC_KEY = text("network.status.public_key", "<dark_gray>[<gold>Wormholes<dark_gray>] <dark_gray>Public key: {fingerprint}");
    public static final TextKey NETWORK_NO_ROUTES = text("network.status.no_routes", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>No peer routes linked yet.");
    public static final TextKey NETWORK_PEER = text("network.status.peer", "<dark_gray>[<gold>Wormholes<dark_gray>] <white>{server}<gray> {state} <gray>{address}{rtt}");
    public static final TextKey NETWORK_LAST_ATTEMPT = text("network.status.last_attempt", "<dark_gray>[<gold>Wormholes<dark_gray>] <dark_gray>  last attempt: {error}");
    public static final TextKey NETWORK_DOCTOR_CLEAR = text("network.doctor.clear", "<dark_gray>[<gold>Wormholes<dark_gray>] <green>No network setup issues detected.");
    public static final TextKey NETWORK_DOCTOR_HEADER = text("network.doctor.header", "<dark_gray>[<gold>Wormholes<dark_gray>] <yellow>Network doctor:");
    public static final TextKey NETWORK_DOCTOR_LINE = text("network.doctor.line", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>- {diagnostic}");
    public static final TextKey NETWORK_BUILDING_CODE = text("network.code.building", "<dark_gray>Building portal code...");
    public static final TextKey NETWORK_COPY_CODE = text("network.code.copy", "<gold><bold>[Copy portal code: {portal}]</bold>");
    public static final TextKey NETWORK_COPY_CODE_HOVER = text("network.code.copy_hover", "<gray>Click to copy. Paste it on the other server:\nportal menu > Import, or /wh network import \\<code>");
    public static final TextKey NETWORK_CODE_FINGERPRINT = text("network.code.fingerprint", "<dark_gray>Contains this server's address and public key fingerprint {fingerprint}.");
    public static final TextKey NETWORK_CODE_TOO_LONG = text("network.code.too_long", "<yellow>This code is too long to paste into chat - use /wh network import \\<code> on the other server instead.");
    public static final TextKey NETWORK_CODE_INVALID = text("network.code.invalid", "<red>Invalid portal code. Codes start with {prefix} - if pasted into chat it may have been truncated; try /wh network import \\<code>. Codes from older plugin versions must be re-exported.");
    public static final TextKey NETWORK_CODE_SAME_SERVER = text("network.code.same_server", "<red>That code is from this server.");
    public static final TextKey NETWORK_CODE_SAME_IDENTITY = text("network.code.same_identity", "<red>That code resolved to this server identity ({server}). Re-export from the other server after both servers restart with their own Wormholes identity.");
    public static final TextKey NETWORK_LINKED = text("network.code.linked", "<green>Linked <white>{portal}<gray> -> <white>{destination}<green> on <white>{server}<green>. It opens once the servers connect.");
    public static final TextKey NETWORK_ROUTE_SAVED = text("network.code.route_saved", "<green>Saved route to {server} with public key {fingerprint}. '{portal}' will appear in gateway Link menus once connected.");
    public static final TextKey NETWORK_CHECK_STATUS = text("network.code.check_status", "<dark_gray>Check /wh network status for the connection state.");
    public static final TextKey NETWORK_USING_ADDRESS = text("network.code.using_address", "<gray>Using {address} in this portal code; the public address auto-detects and self-corrects over the signed handshake if it changes.");

    public static final LinesKey ITEM_PORTAL_WAND = lines("item.portal_wand", "<gold><bold>Portal Wand</bold>");
    public static final LinesKey ITEM_PORTAL_RUNE = lines("item.portal_rune", "<gold><bold>Portal Rune</bold>");
    public static final LinesKey ITEM_WORMHOLE_RUNE = lines("item.wormhole_rune", "<gold><bold>Wormhole Rune</bold>");
    public static final LinesKey ITEM_GATEWAY_RUNE = lines("item.gateway_rune", "<red><bold>Gateway Rune</bold>");
    public static final LinesKey ITEM_ENTANGLED_PAIR = lines("item.door.entangled_pair",
            "<gold>Entangled Door Pair",
            "<gray>Contains two automatically linked Wormhole Doors.",
            "<gray>Use it to unpack endpoints A and B.");
    public static final LinesKey ITEM_PAIRED_DOOR = lines("item.door.paired",
            "<gold>Wormhole Door {endpoint}",
            "<gray>Automatically linked to endpoint {other}.",
            "<gray>Open the door and physically cross its threshold.");
    public static final LinesKey ITEM_PERSONAL_DOOR = lines("item.door.personal",
            "<aqua>Personal Dimension Door",
            "<gray>Each traveler enters their own persistent dimension.",
            "<gray>The same traveler always reaches the same place.");
    public static final LinesKey ITEM_PUBLIC_DOOR = lines("item.door.public",
            "<gold>Public Dimension Door",
            "<gray>Every traveler enters this door's shared dimension.",
            "<gray>Breaking and moving it preserves the shared destination.");
    public static final LinesKey ITEM_RETURN_DOOR = lines("item.door.return",
            "<green>Dimensional Exit Door",
            "<gray>Returns travelers from this pocket dimension.",
            "<gray>This door is bound to its pocket.");
    public static final LinesKey ITEM_DOOR_SKIN = lines("item.door.skin",
            "<gold>Dimensional Door Skin",
            "<gray>Combine a dimensional door with a player-operable door.");
    public static final LinesKey ITEM_ENTANGLED_PAIR_RECIPE = lines("item.door.entangled_pair_recipe",
            "<gold>Entangled Door Pair",
            "<gray>Contains two automatically linked Wormhole Doors.");
    public static final LinesKey ITEM_PERSONAL_DOOR_RECIPE = lines("item.door.personal_recipe",
            "<aqua>Personal Dimension Door",
            "<gray>Each traveler enters their own persistent dimension.");
    public static final LinesKey ITEM_PUBLIC_DOOR_RECIPE = lines("item.door.public_recipe",
            "<gold>Public Dimension Door",
            "<gray>Every traveler enters this door's shared dimension.");

    public static final TextKey PORTAL_RTP_RUNE_UNSUPPORTED = text("portal.form.rtp_unsupported", "<red>Random teleport portals cannot be formed from runes.");
    public static final TextKey PORTAL_FORMING = text("portal.form.forming", "<aqua>Forming portal... {type} runes must connect on one flat wall, floor, or ceiling.");
    public static final TextKey PORTAL_MUST_BE_FLAT = text("portal.form.must_be_flat", "<red>Portal must lie flat on one wall, floor, or ceiling.");
    public static final TextKey PORTAL_FORM_INTERRUPTED = text("portal.form.interrupted", "<red>Portal formation was interrupted; the reserved runes were restored.");
    public static final TextKey PORTAL_RUNE_PLACED = text("portal.form.rune_placed", "<aqua>Rune placed. Build any connected shape on one flat surface, then left-click any rune with the Portal Wand.");
    public static final TextKey PORTAL_OPENED = text("portal.form.opened", "<green>Portal opened. Hold the wand and CLICK the portal to configure.");
    public static final TextKey PORTAL_COOLDOWN = text("portal.travel.cooldown", "<gold>Portal cooling down");
    public static final TextKey PORTAL_ACCESS_DENIED = text("portal.travel.access_denied", "<red>Portal access denied");
    public static final TextKey PORTAL_EDIT_DENIED = text("portal.edit.denied", "<red>Only the portal owner or an administrator can edit this portal.");
    public static final TextKey PORTAL_DELETED = text("portal.deleted", "<red>{portal} Deleted");
    public static final TextKey PORTAL_ARRIVAL_FAILED = text("portal.travel.arrival_failed", "<red>Portal arrival could not be placed; you remain at the destination spawn");
    public static final TextKey PORTAL_TRANSFER_COOLDOWN = text("portal.travel.transfer_cooldown", "<gold>Cross-server portal cooling down: {seconds}s");
    public static final TextKey PORTAL_DESTINATION_UNREACHABLE = text("portal.travel.destination_unreachable", "<red>Destination server unreachable");
    public static final TextKey PORTAL_DESTINATION_UNREACHABLE_DETAIL = text("portal.travel.destination_unreachable_detail", "<red>Destination server unreachable: {reason}");
    public static final TextKey PORTAL_TRANSFER_BLOCKED = text("portal.travel.transfer_blocked", "<red>Portal transfer blocked: {reason}");
    public static final TextKey PORTAL_TRANSFER_BLOCKED_RETRY = text("portal.travel.transfer_blocked_retry", "<red>Portal transfer blocked: {reason} (retry in {seconds}s)");

    public static final TextKey WAND_CORNER_A = text("wand.selection.corner_a", "<aqua>Corner A set. Right-click the opposite corner.");
    public static final TextKey WAND_CORNER_B = text("wand.selection.corner_b", "<aqua>Corner B set. Left-click the opposite corner.");
    public static final TextKey WAND_NOT_FLAT = text("wand.selection.not_flat", "<red>Selection must be one block thick. Re-click a corner to flatten it.");
    public static final TextKey WAND_TOO_LARGE = text("wand.selection.too_large", "<red>Selection too large: {count} cells (max {maximum}).");
    public static final PluralKey WAND_SELECTED = plural("wand.selection.selected", "count", Map.of(
            "one", "<aqua>Selected {count} cell. Left-click the glass pane to open the portal.",
            "other", "<aqua>Selected {count} cells. Left-click the glass pane to open the portal."
    ));
    public static final TextKey WAND_OPENING = text("wand.selection.opening", "<aqua>Opening portal...");
    public static final TextKey WAND_OPEN_FAILED = text("wand.selection.open_failed", "<red>The portal could not be opened here.");

    public static final TextKey DOOR_CRAFT_ONE = text("door.craft.one_at_a_time", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Craft dimensional doors one at a time so each receives a unique identity.");
    public static final TextKey DOOR_PAIR_UNPACK_FAILED = text("door.pair.unpack_failed", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>The door pair could not be unpacked; the kit was not consumed.");
    public static final TextKey DOOR_PAIR_UNPACKED = text("door.pair.unpacked", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>The entangled pair separated into linked Wormhole Doors A and B.");
    public static final TextKey DOOR_LEGACY_COMBINE = text("door.legacy.combine", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Combine this legacy dimensional door with a wooden door before placing it.");
    public static final TextKey DOOR_PAIR_MISSING = text("door.pair.missing", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>That paired door has no registered partner identity.");
    public static final TextKey DOOR_ALREADY_PLACED = text("door.place.already_placed", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>That dimensional door is already placed, or its state could not be saved.");
    public static final TextKey DOOR_EXIT_ANCHORED = text("door.break.exit_anchored", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>The dimensional exit is anchored to this pocket.");
    public static final TextKey DOOR_BREAK_FIRST = text("door.break.support", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Break the dimensional door before removing its support block.");
    public static final TextKey DOOR_DISABLE_WARNING = text("door.disable.warning", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Dimensional Doors are being disabled. Leave through the pocket return door now.");
    public static final TextKey DOOR_RESCUE_CANCELLED = text("door.rescue.cancelled", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Your emergency ejection was cancelled; the route was kept.");
    public static final TextKey DOOR_TRANSIT_MESSAGE = text("door.transit.message", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>{message}");
    public static final TextKey DOOR_LINK_NOT_PLACED = text("door.transit.link_not_placed", "The linked Wormhole Door has not been placed yet.");
    public static final TextKey DOOR_LINK_UNAVAILABLE = text("door.transit.link_unavailable", "The linked Wormhole Door is unavailable or obstructed.");
    public static final TextKey DOOR_NESTED_POCKET = text("door.transit.nested_pocket", "A pocket door cannot open another pocket from inside the shared void dimension.");
    public static final TextKey DOOR_POCKET_NOT_READY = text("door.transit.pocket_not_ready", "The pocket dimension is not ready.");
    public static final TextKey DOOR_POCKET_ALLOCATION_FAILED = text("door.transit.pocket_allocation_failed", "The pocket dimension could not be allocated.");
    public static final TextKey DOOR_SAFE_RETURN_NOT_FOUND = text("door.transit.safe_return_not_found", "A safe return route could not be found on this side of the door.");
    public static final TextKey DOOR_POCKET_PREPARE_FAILED = text("door.transit.pocket_prepare_failed", "The pocket could not be prepared safely.");
    public static final TextKey DOOR_RETURN_TICKET_SAVE_FAILED = text("door.transit.return_ticket_save_failed", "A safe return route could not be saved.");
    public static final TextKey DOOR_POCKET_ENTRY_UNSAFE = text("door.transit.pocket_entry_unsafe", "The pocket entry is not safe.");
    public static final TextKey DOOR_POCKET_ENTRY_CHUNK_FAILED = text("door.transit.pocket_entry_chunk_failed", "The pocket dimension could not load its entry chunk.");
    public static final TextKey DOOR_NO_RETURN_ROUTE = text("door.transit.no_return_route", "You do not have a return route stored for this pocket.");
    public static final TextKey DOOR_RETURN_UNAVAILABLE = text("door.transit.return_unavailable", "Your return door is unavailable or obstructed on its corresponding face.");
    public static final TextKey DOOR_RETURN_WORLD_UNLOADED = text("door.transit.return_world_unloaded", "Your return world is not loaded.");
    public static final TextKey DOOR_RETURN_POINT_OBSTRUCTED = text("door.transit.return_point_obstructed", "Your saved return point is obstructed.");
    public static final TextKey DOOR_RETURN_CHUNK_FAILED = text("door.transit.return_chunk_failed", "Your saved return chunk could not be loaded.");
    public static final TextKey DOOR_CLOSED_DURING_TRANSIT = text("door.transit.closed", "The dimensional door closed before transit completed.");
    public static final TextKey DOOR_SOURCE_CLOSE_FAILED = text("door.transit.source_close_failed", "The source door could not close safely.");
    public static final TextKey DOOR_SOURCE_REGION_UNAVAILABLE = text("door.transit.source_region_unavailable", "The source door region is unavailable.");
    public static final TextKey DOOR_TRANSIT_START_FAILED = text("door.transit.start_failed", "The dimensional transit could not start.");
    public static final TextKey DOOR_TRANSIT_CANCELLED = text("door.transit.cancelled", "The dimensional transit was cancelled.");
    public static final TextKey DOOR_RESCUE_NO_ROUTE = text("door.rescue.reason.no_route", "The pocket has no saved return route.");
    public static final TextKey DOOR_RESCUE_RETURN_WORLD_UNAVAILABLE = text("door.rescue.reason.return_world_unavailable", "The saved return world is unavailable.");
    public static final TextKey DOOR_RESCUE_RETURN_POINT_OBSTRUCTED = text("door.rescue.reason.return_point_obstructed", "The saved return point is obstructed.");
    public static final TextKey DOOR_RESCUE_RETURN_CHUNK_FAILED = text("door.rescue.reason.return_chunk_failed", "The saved return chunk could not be loaded.");
    public static final TextKey DOOR_RESCUE_NO_FALLBACK_WORLD = text("door.rescue.fallback.no_world", "No non-pocket fallback world is loaded.");
    public static final TextKey DOOR_RESCUE_FALLBACK_OBSTRUCTED = text("door.rescue.fallback.obstructed", "The fallback spawn is obstructed.");
    public static final TextKey DOOR_RESCUE_FALLBACK_CHUNK_FAILED = text("door.rescue.fallback.chunk_failed", "The fallback spawn could not be loaded.");
    public static final TextKey DOOR_RESCUE_FALLBACK_SCHEDULE_FAILED = text("door.rescue.fallback.schedule_failed", "The fallback ejection could not be scheduled.");
    public static final TextKey DOOR_RESCUE_START_FAILED = text("door.rescue.reason.start_failed", "The emergency ejection could not start.");
    public static final TextKey DOOR_RESCUE_FAILED = text("door.rescue.failed", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>{route} {fallback} You remain protected at one heart.");

    public static final LinesKey PORTAL_MENU_DESTINATION = lines("portal.menu.destination",
            "<gold><bold>Destination</bold>",
            "<gray>Choose a portal to link to.",
            "<gray>Currently linked: {destination}",
            "",
            "<dark_gray>Click to open the destination picker.");
    public static final LinesKey PORTAL_MENU_GATEWAY_DESTINATION = lines("portal.menu.gateway_destination",
            "<gold><bold>Pair & Destination</bold>",
            "<gray>Pair another server or choose a gateway.",
            "<gray>Currently linked: {destination}",
            "",
            "<dark_gray>Click to open the pairing hub.");
    public static final LinesKey PORTAL_MENU_RTP_DESTINATION = lines("portal.menu.rtp_destination",
            "<gold><bold>Random Destination</bold>",
            "<gray>Configure where and when this portal rerolls.",
            "<gray>Rotation: <aqua>{rotation}",
            "",
            "<dark_gray>Click to open random teleport settings.");
    public static final LinesKey PORTAL_MENU_RENAME = lines("portal.menu.rename",
            "<green><bold>Rename Portal</bold>",
            "<gray>Change the name shown on links and menus.",
            "<gray>Current: <white>{portal}",
            "",
            "<dark_gray>Click to type a new name.");
    public static final LinesKey PORTAL_MENU_DELETE = lines("portal.menu.delete",
            "<red><bold>Delete Portal</bold>",
            "<gray>Permanently removes this portal.",
            "",
            "<red><underlined>Shift + Left Click to confirm</underlined>");
    public static final LinesKey PORTAL_MENU_ADVANCED_SETTINGS = lines("portal.menu.advanced_settings",
            "<dark_aqua><bold>Advanced Stream Tuning</bold>",
            "<gray>Direct controls for diagnostics and unusual links.",
            "<gray>Changing one value marks Stream Quality as Custom.");
    public static final LinesKey PORTAL_MENU_BACK_SETTINGS = lines("portal.menu.back_settings",
            "<yellow><bold>Back to Settings</bold>",
            "<gray>Return to portal settings.");
    public static final LinesKey PORTAL_MENU_TRAVEL_MANAGED = lines("portal.menu.travel.managed",
            "<gold><bold>Travel: {direction}</bold>",
            "<gray>Managed dimensional portal direction.",
            "<gray>{detail}");
    public static final LinesKey PORTAL_MENU_TRAVEL_MIRROR = lines("portal.menu.travel.mirror",
            "<red><bold>Travel: Mirror Locked</bold>",
            "<gray>Mirror mode is visual only.",
            "<gray>Entities cannot enter or leave through it.");
    public static final LinesKey PORTAL_MENU_TRAVEL = lines("portal.menu.travel.standard",
            "<aqua><bold>Travel: {mode}</bold>",
            "<gray>Controls travel through this portal.",
            "",
            "<gray>Outgoing: <aqua>{outgoing}<gray>  Incoming: <aqua>{incoming}",
            "",
            "<dark_gray>Click to cycle travel direction.");
    public static final LinesKey PORTAL_MENU_STREAM_QUALITY = lines("portal.menu.stream_quality",
            "<aqua><bold>Stream Quality: {quality}</bold>",
            "<gray>One control for remote view range and cadence.",
            "",
            "<gray>Depth <aqua>{depth}<gray>  Entities <aqua>{entities}t",
            "<gray>Refresh <aqua>{refresh}t<gray>  Grace <aqua>{grace}s",
            "",
            "<dark_gray>Click: cycle quality.",
            "<dark_gray>Shift-click: advanced tuning.");
    public static final LinesKey PORTAL_MENU_ORIENTATION = lines("portal.menu.orientation",
            "<blue><bold>Orientation</bold>",
            "<gray>Facing: <blue>{facing}",
            "<gray>Up: <gold>{up}",
            "",
            "<dark_gray>Click for facing, flip, and rotation.");
    public static final LinesKey PORTAL_MENU_ORIENTATION_PLACARD = lines("portal.menu.orientation_placard",
            "<blue><bold>Portal Orientation</bold>",
            "<gray>Facing: <blue>{facing}",
            "<gray>Screen up: <gold>{up}");
    public static final LinesKey PORTAL_MENU_GATEWAY_CHOOSE = lines("portal.menu.gateway.choose",
            "<gold><bold>Choose Destination</bold>",
            "<gray>Choose from discovered local and remote gateways.",
            "",
            "<dark_gray>Click to open the destination list.");
    public static final LinesKey PORTAL_MENU_GATEWAY_UNPAIRED = lines("portal.menu.gateway.unpaired",
            "<gold><bold>Gateway Pairing</bold>",
            "<gray>Status: <red>Not paired");
    public static final LinesKey PORTAL_MENU_GATEWAY_PAIRED = lines("portal.menu.gateway.paired",
            "<gold><bold>Gateway Pairing</bold>",
            "<gray>Destination: <gold>{destination}");
    public static final TextKey PORTAL_MENU_GATEWAY_SERVER = text("portal.menu.gateway.server", "<gray>Server: <white>{server}");
    public static final TextKey PORTAL_MENU_GATEWAY_LINK = text("portal.menu.gateway.link", "<gray>Link: <aqua>{state}");
    public static final LinesKey PORTAL_MENU_GATEWAY_EXPORT = lines("portal.menu.gateway.export",
            "<gold><bold>Create Invite</bold>",
            "<gray>Create a signed gateway code for another server.",
            "",
            "<dark_gray>Click to copy a fresh code.");
    public static final LinesKey PORTAL_MENU_GATEWAY_IMPORT = lines("portal.menu.gateway.import",
            "<aqua><bold>Use Invite</bold>",
            "<gray>Paste a signed code from another gateway.",
            "",
            "<dark_gray>Click, then paste the code in chat.");
    public static final LinesKey PORTAL_MENU_NETWORK_NUMBER = lines("portal.menu.network_number",
            "<aqua><bold>{label} <white>{value}</bold>",
            "<gray>{description}",
            "",
            "<gray>Currently: <aqua>{value}",
            "",
            "<dark_gray>Left click: +{step}",
            "<dark_gray>Right click: -{step}",
            "<dark_gray>Shift-left: +{large_step}",
            "<dark_gray>Shift-right: -{large_step}");
    public static final LinesKey PORTAL_MENU_FALLBACK_BLOCK = lines("portal.menu.fallback_block",
            "<aqua><bold>Fallback Block</bold>",
            "<gray>Block shown beyond streamed depth.",
            "",
            "<gray>Currently: <white>{block}",
            "",
            "<dark_gray>Left: enter a block state.",
            "<dark_gray>Right: reset to air.");
    public static final LinesKey PORTAL_MENU_PLACARD_RTP = lines("portal.menu.placard.rtp",
            "<gold><bold>{portal}</bold>",
            "<gray>Type: <yellow>{type}",
            "<gray>Facing: <blue>{facing}",
            "<gray>Allocation: <aqua>{allocation}",
            "<gray>Rotation: <aqua>{rotation}",
            "",
            "<dark_gray>Operators bypass white/blacklist.");
    public static final LinesKey PORTAL_MENU_PLACARD_LINKED = lines("portal.menu.placard.linked",
            "<gold><bold>{portal}</bold>",
            "<gray>Type: <yellow>{type}",
            "<gray>Facing: <blue>{facing}",
            "<gray>Linked to: <gold>{destination}",
            "",
            "<dark_gray>Operators bypass white/blacklist.");
    public static final LinesKey PORTAL_MENU_PLACARD_UNLINKED = lines("portal.menu.placard.unlinked",
            "<gold><bold>{portal}</bold>",
            "<gray>Type: <yellow>{type}",
            "<gray>Facing: <blue>{facing}",
            "<gray>Linked to: <red>{none}",
            "",
            "<dark_gray>Operators bypass white/blacklist.");
    public static final LinesKey PORTAL_MENU_SETTINGS_PLACARD_GATEWAY = lines("portal.menu.settings_placard_gateway",
            "<aqua><bold>Portal Settings</bold>",
            "<gray>Access and transfer controls.",
            "<gray>Plus projection view tuning.",
            "",
            "<gray>Larger depth / shorter ticks =",
            "<gray>richer view, more bandwidth.");
    public static final LinesKey PORTAL_MENU_MODE_PLACARD = lines("portal.menu.mode_placard",
            "<yellow><bold>Portal Mode</bold>",
            "<gray>Current: <yellow>{mode}",
            "",
            "<gray>Portal: basic linked portal.",
            "<gray>Wormhole: portal with viewport.",
            "<gray>Gateway: cross-server gateway.",
            "<gray>RTP: configurable random destinations.",
            "<gray>Mirror: reflect this side, no travel.");
    public static final LinesKey PORTAL_MENU_MODE_OPENER = lines("portal.menu.mode_opener",
            "<yellow><bold>Mode</bold>",
            "<gray>{description}",
            "",
            "<gray>Currently: <yellow>{mode}",
            "",
            "<dark_gray>Click to change mode.");
    public static final LinesKey PORTAL_MENU_DIRECTION = lines("portal.menu.direction",
            "<blue><bold>Direction</bold>",
            "<gray>Change the portal facing direction.",
            "",
            "<gray>Currently facing: <blue>{direction}",
            "",
            "<dark_gray>Click then look, left click to apply.");
    public static final LinesKey PORTAL_MENU_FLIP_FACE = lines("portal.menu.flip_face",
            "<aqua><bold>Flip Face</bold>",
            "<gray>Reverse the portal face direction.",
            "<gray>Screen rotation stays aligned.",
            "",
            "<gray>Currently rolling up: <aqua>{up}",
            "",
            "<dark_gray>Click to flip.");
    public static final LinesKey PORTAL_MENU_ROTATE_COUNTERCLOCKWISE = lines("portal.menu.rotate_counterclockwise",
            "<gold><bold>Rotate Counterclockwise</bold>",
            "<gray>Roll the portal viewport 90 degrees",
            "<gray>without changing the face.",
            "",
            "<gray>Currently rolling up: <gold>{up}",
            "",
            "<dark_gray>Click to rotate.");
    public static final LinesKey PORTAL_MENU_ROTATE_CLOCKWISE = lines("portal.menu.rotate_clockwise",
            "<gold><bold>Rotate Clockwise</bold>",
            "<gray>Roll the portal viewport 90 degrees",
            "<gray>without changing the face.",
            "",
            "<gray>Currently rolling up: <gold>{up}",
            "",
            "<dark_gray>Click to rotate.");
    public static final LinesKey PORTAL_MENU_BACK = lines("portal.menu.back", "<yellow><bold>Back</bold>", "<gray>Return to the portal menu.");
    public static final LinesKey PORTAL_MENU_PROJECTION_ON = lines("portal.menu.projection.on",
            "<gold><bold>Projection On</bold>",
            "<gray>Show this portal's live view.",
            "<gray>Destination or mirror imagery is visible.",
            "",
            "<gray>Currently: <aqua>Projection On</aqua>",
            "",
            "<dark_gray>Click to toggle On / Off.");
    public static final LinesKey PORTAL_MENU_PROJECTION_OFF = lines("portal.menu.projection.off",
            "<dark_gray><bold>Projection Off</bold>",
            "<gray>The frame stays empty.",
            "<gray>No destination view, no mirror.",
            "",
            "<gray>Currently: <aqua>Projection Off</aqua>",
            "",
            "<dark_gray>Click to toggle On / Off.");
    public static final LinesKey PORTAL_MENU_SETTINGS_GATEWAY = lines("portal.menu.settings_gateway",
            "<aqua><bold>Settings</bold>",
            "<gray>Permissions, transfers,",
            "<gray>and projection view tuning.",
            "",
            "<gray>Access: <gold>{access}",
            "<gray>Send <aqua>{send}<gray>  Receive <aqua>{receive}",
            "<gray>Depth <aqua>{depth}<gray>  Entity <aqua>{entity}t",
            "",
            "<dark_gray>Click to open settings.");
    public static final LinesKey PORTAL_MENU_SETTINGS_SYNC = lines("portal.menu.settings_sync",
            "<aqua><bold>Settings Sync {state}</bold>",
            "<gray>When On, this portal's settings",
            "<gray>(depth, ticks, projection, permissions,",
            "<gray>transfers) sync to all linked peers.",
            "",
            "<gray>Currently: <aqua>{state}",
            "",
            "<dark_gray>Click to toggle.");
    public static final LinesKey PORTAL_MENU_PERMISSION = lines("portal.menu.permission",
            "<gold><bold>Access {mode}</bold>",
            "<gray>{description}",
            "<gray>Node: <white>{node}",
            "",
            "<gray>Currently: <gold>{mode}",
            "",
            "<dark_gray>Click to toggle whitelist / blacklist.",
            "<dark_gray>Operators always bypass.");
    public static final LinesKey PORTAL_MENU_MODE_OPTION_SELECTED = lines("portal.menu.mode_option.selected",
            "<yellow><bold>{mode}</bold>", "<gray>{description}", "", "<green>Currently Selected");
    public static final LinesKey PORTAL_MENU_MODE_OPTION_AVAILABLE = lines("portal.menu.mode_option.available",
            "<yellow><bold>{mode}</bold>", "<gray>{description}", "", "<gray>Click to select");
    public static final LinesKey PORTAL_MENU_MIRROR_SELECTED = lines("portal.menu.mirror.selected",
            "<yellow><bold>Mirror</bold>",
            "<gray>Reflect the local world back.",
            "<gray>See yourself looking through the frame.",
            "<gray>No travel while mirrored.",
            "",
            "<green>Currently Selected");
    public static final LinesKey PORTAL_MENU_MIRROR_AVAILABLE = lines("portal.menu.mirror.available",
            "<yellow><bold>Mirror</bold>",
            "<gray>Reflect the local world back.",
            "<gray>See yourself looking through the frame.",
            "<gray>No travel while mirrored.",
            "",
            "<gray>Left click to select");
    public static final TextKey PORTAL_MENU_MIRROR_ROTATION = text("portal.menu.mirror.rotation", "<gray>Image rotation: <aqua>{degrees} degrees");
    public static final TextKey PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE = text("portal.menu.mirror.rotate_clockwise", "<dark_gray>Right click: rotate clockwise.");
    public static final TextKey PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE = text("portal.menu.mirror.rotate_counterclockwise", "<dark_gray>Shift + right click: rotate counterclockwise.");
    public static final LinesKey PORTAL_MENU_MIRROR_FLIP = lines("portal.menu.mirror.flip",
            "<gray>Wall mirrors flip in 180 degree steps",
            "<gray>so reflected entities stay aligned.",
            "<dark_gray>Right click: flip the reflected image.");
    public static final LinesKey PORTAL_MENU_LOCAL_DESTINATION = lines("portal.menu.destination.local",
            "<gold>{portal}",
            "<gray>at {x}, {y}, {z} in {world} Facing {direction}");
    public static final LinesKey PORTAL_MENU_REMOTE_DESTINATION = lines("portal.menu.destination.remote",
            "<gold>{portal}",
            "<gray>on server <white>{server}",
            "<gray>at {x}, {y}, {z} in {world} Facing {direction}",
            "<aqua>{state}");
    public static final TextKey PORTAL_PROMPT_INVITE = text("portal.prompt.invite", "<aqua>Paste the portal invite in chat (or '{cancel}'):");
    public static final TextKey PORTAL_PROMPT_BLOCK_STATE = text("portal.prompt.block_state", "<aqua>Enter a block state for this portal's network view edge, or '{cancel}':");
    public static final TextKey PORTAL_PROMPT_NAME = text("portal.prompt.name", "<aqua>Type the new portal name in chat (or '{cancel}'):");
    public static final TextKey PORTAL_INPUT_CANCEL = text("portal.input.cancel", "cancel");
    public static final TextKey PORTAL_RTP_EDITOR_TITLE = text("portal.rtp.editor_title", "RTP: {portal}");
    public static final TextKey PORTAL_DIMENSIONAL_LINK_MANAGED = text("portal.notice.dimensional_link_managed", "This dimensional portal keeps its generated link.");
    public static final TextKey PORTAL_NOT_RTP = text("portal.notice.not_rtp", "This portal is no longer in random teleport mode.");
    public static final TextKey PORTAL_RTP_SETTING_REJECTED = text("portal.rtp.notice.setting_rejected", "Could not apply that setting: {reason}");
    public static final TextKey PORTAL_REGION_UNAVAILABLE = text("portal.notice.region_unavailable", "The portal region is unavailable; try again.");
    public static final TextKey PORTAL_RTP_APPLIED = text("portal.rtp.notice.applied", "Random destination settings applied.");
    public static final TextKey PORTAL_RTP_RESET_DEFAULTS = text("portal.rtp.notice.reset_defaults", "Random teleport settings reset to defaults.");
    public static final TextKey PORTAL_RTP_EDITOR_REFRESHED = text("portal.rtp.notice.editor_refreshed", "Settings changed; the editor was refreshed.");
    public static final TextKey PORTAL_RTP_RUNTIME_UNAVAILABLE = text("portal.rtp.notice.runtime_unavailable", "Random teleport runtime is unavailable.");
    public static final TextKey PORTAL_RTP_REROLL_FAILED = text("portal.rtp.notice.reroll_failed", "Manual reroll failed; see the server log.");
    public static final TextKey PORTAL_RTP_REROLL_PREPARING = text("portal.rtp.notice.reroll_preparing", "Preparing a new random destination.");
    public static final TextKey PORTAL_RTP_REROLL_UNAVAILABLE = text("portal.rtp.notice.reroll_unavailable", "Reroll is unavailable while the route is preparing or in use.");
    public static final TextKey PORTAL_RTP_POOL_REBUILDING = text("portal.rtp.notice.pool_rebuilding", "Rebuilding the private destination pool.");
    public static final TextKey PORTAL_RTP_POOL_FAILED = text("portal.rtp.notice.pool_failed", "Pool rebuild failed; see the server log.");
    public static final TextKey PORTAL_RTP_NOT_READY = text("portal.rtp.notice.not_ready", "<yellow>The portal is still stabilizing its destination.");
    public static final TextKey PORTAL_RTP_TRAVERSAL_FAILED = text("portal.rtp.notice.traversal_failed", "<red>The portal could not stabilize; try again.");
    public static final TextKey PORTAL_TRAVEL_MANAGED = text("portal.notice.travel_managed", "Dimensional portal travel is managed automatically.");
    public static final TextKey PORTAL_TRAVEL_MIRROR_LOCKED = text("portal.notice.travel_mirror_locked", "Mirror mode never allows travel.");
    public static final TextKey PORTAL_TRAVEL_CHANGED = text("portal.notice.travel_changed", "Travel {mode}");
    public static final TextKey PORTAL_STREAM_QUALITY_CHANGED = text("portal.notice.stream_quality_changed", "Stream Quality {quality}");
    public static final TextKey PORTAL_NETWORK_VALUE_CHANGED = text("portal.notice.network_value_changed", "{label} {value}");
    public static final TextKey PORTAL_FALLBACK_SET = text("portal.notice.fallback_set", "Fallback set to {block}");
    public static final TextKey PORTAL_FALLBACK_RESET = text("portal.notice.fallback_reset", "Fallback reset to {block}");
    public static final TextKey PORTAL_MANAGED_MODE = text("portal.notice.managed_mode", "Managed dimensional portals stay in portal mode.");
    public static final TextKey PORTAL_PROJECTION_RECEIVER_INACTIVE = text("portal.notice.projection_receiver_inactive", "The End arrival stays visually inactive.");
    public static final TextKey PORTAL_PROJECTION_CHANGED = text("portal.notice.projection_changed", "Projections: {mode}");
    public static final TextKey PORTAL_SETTINGS_SYNC_CHANGED = text("portal.notice.settings_sync_changed", "Settings Sync {state}");
    public static final TextKey PORTAL_ACCESS_CHANGED = text("portal.notice.access_changed", "Access {mode}");
    public static final TextKey PORTAL_MIRROR_SELECT_FIRST = text("portal.notice.mirror_select_first", "Choose Mirror before rotating the image.");
    public static final TextKey PORTAL_MIRROR_ROTATION_CHANGED = text("portal.notice.mirror_rotation_changed", "Mirror Rotation {degrees} degrees");
    public static final TextKey PORTAL_RTP_CANNOT_LINK = text("portal.notice.rtp_cannot_link", "Random teleport portals do not link to destinations.");
    public static final TextKey PORTAL_LABEL_BOTH_WAYS = text("portal.label.travel.both", "Both Ways");
    public static final TextKey PORTAL_LABEL_OUTBOUND_ONLY = text("portal.label.travel.outbound", "Outbound Only");
    public static final TextKey PORTAL_LABEL_INBOUND_ONLY = text("portal.label.travel.inbound", "Inbound Only");
    public static final TextKey PORTAL_LABEL_LOCKED = text("portal.label.travel.locked", "Locked");
    public static final TextKey PORTAL_LABEL_ARRIVAL_ONLY = text("portal.label.travel.arrival", "Arrival Only");
    public static final TextKey PORTAL_LABEL_DEPARTURE_ONLY = text("portal.label.travel.departure", "Departure Only");
    public static final TextKey PORTAL_LABEL_DIMENSIONAL_BOTH_ACTIVE = text("portal.label.travel.dimensional_both", "Both linked halves stay active.");
    public static final TextKey PORTAL_LABEL_DIMENSIONAL_RETURN_DISABLED = text("portal.label.travel.dimensional_return_disabled", "The return path stays disabled.");
    public static final TextKey PORTAL_LABEL_STANDARD = text("portal.label.quality.standard", "Standard");
    public static final TextKey PORTAL_LABEL_PERFORMANCE = text("portal.label.quality.performance", "Performance");
    public static final TextKey PORTAL_LABEL_BALANCED = text("portal.label.quality.balanced", "Balanced");
    public static final TextKey PORTAL_LABEL_CINEMATIC = text("portal.label.quality.cinematic", "Cinematic");
    public static final TextKey PORTAL_LABEL_CUSTOM = text("portal.label.quality.custom", "Custom");
    public static final TextKey PORTAL_LABEL_PORTAL = text("portal.label.mode.portal", "Portal");
    public static final TextKey PORTAL_LABEL_WORMHOLE = text("portal.label.mode.wormhole", "Wormhole");
    public static final TextKey PORTAL_LABEL_GATEWAY = text("portal.label.mode.gateway", "Gateway");
    public static final TextKey PORTAL_LABEL_RTP = text("portal.label.mode.rtp", "RTP");
    public static final TextKey PORTAL_LABEL_MIRROR = text("portal.label.mode.mirror", "Mirror");
    public static final TextKey PORTAL_LABEL_DIRECTION_UP = text("portal.label.direction.up", "Up");
    public static final TextKey PORTAL_LABEL_DIRECTION_DOWN = text("portal.label.direction.down", "Down");
    public static final TextKey PORTAL_LABEL_DIRECTION_NORTH = text("portal.label.direction.north", "North");
    public static final TextKey PORTAL_LABEL_DIRECTION_SOUTH = text("portal.label.direction.south", "South");
    public static final TextKey PORTAL_LABEL_DIRECTION_EAST = text("portal.label.direction.east", "East");
    public static final TextKey PORTAL_LABEL_DIRECTION_WEST = text("portal.label.direction.west", "West");
    public static final TextKey PORTAL_MODE_DESCRIPTION_PORTAL = text("portal.mode.description.portal", "Basic linkable portal.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_WORMHOLE = text("portal.mode.description.wormhole", "Linkable portal with viewport projection.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_GATEWAY = text("portal.mode.description.gateway", "Reserved for cross-network linking.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_RTP = text("portal.mode.description.rtp", "Local random teleport portal.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_MIRROR = text("portal.mode.description.mirror", "Reflect the local world back with travel locked.");
    public static final TextKey PORTAL_LABEL_SHARED = text("portal.label.allocation.shared", "Shared");
    public static final TextKey PORTAL_LABEL_PER_PLAYER = text("portal.label.allocation.per_player", "Per-player");
    public static final TextKey PORTAL_RTP_ROTATION_PRIVATE = text("portal.rtp.rotation.private", "Private every {duration}");
    public static final TextKey PORTAL_LABEL_WHITELIST = text("portal.label.permission.whitelist", "Whitelist");
    public static final TextKey PORTAL_LABEL_BLACKLIST = text("portal.label.permission.blacklist", "Blacklist");
    public static final TextKey PORTAL_PERMISSION_DESCRIPTION_WHITELIST = text("portal.permission.description.whitelist", "Players need the node to use this portal.");
    public static final TextKey PORTAL_PERMISSION_DESCRIPTION_BLACKLIST = text("portal.permission.description.blacklist", "Players with the node are blocked.");
    public static final TextKey PORTAL_LABEL_DIRECT = text("portal.label.network.direct", "Direct");
    public static final TextKey PORTAL_LABEL_SIDEBAND = text("portal.label.network.sideband", "Sideband");
    public static final TextKey PORTAL_LABEL_RECONNECTING = text("portal.label.network.reconnecting", "Reconnecting");
    public static final TextKey PORTAL_NETWORK_LABEL_CAPTURE_RADIUS = text("portal.network.label.capture_radius", "Capture Radius");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS = text("portal.network.description.capture_radius", "Blocks streamed around the destination portal.");
    public static final TextKey PORTAL_NETWORK_LABEL_FULL_REFRESH = text("portal.network.label.full_refresh", "Full Refresh");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH = text("portal.network.description.full_refresh", "Ticks between full block refreshes.");
    public static final TextKey PORTAL_NETWORK_LABEL_ENTITY_UPDATE = text("portal.network.label.entity_update", "Entity Update");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE = text("portal.network.description.entity_update", "Ticks between entity updates.");
    public static final TextKey PORTAL_NETWORK_LABEL_VIEW_GRACE = text("portal.network.label.view_grace", "View Grace");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE = text("portal.network.description.view_grace", "Seconds to keep a warm stream after looking away.");
    public static final LinesKey PORTAL_PROMPT_DIRECTION = lines("portal.prompt.direction",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Look in a direction then left click to apply.",
            "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Shift-Left click to cancel.");
    public static final TextKey PORTAL_DIRECTION_CANCELLED = text("portal.direction.cancelled", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Cancelled");
    public static final TextKey PORTAL_DIRECTION_SET = text("portal.direction.set", "<dark_gray>[<gold>Wormholes<dark_gray>] <gray>Direction set");
    public static final TextKey PORTAL_SETTING_NOTIFICATION = text("portal.setting.notification", "<green>{portal}: <reset>{message}");
    public static final TextKey PORTAL_LINKED = text("portal.link.linked", "<green>{portal} linked to {destination}.");
    public static final TextKey PORTAL_LINKED_REMOTE = text("portal.link.linked_remote", "<green>{portal} linked to {destination} on {server}.");
    public static final TextKey PORTAL_UNLINKED = text("portal.link.unlinked", "<yellow>{portal} unlinked from {destination}.");
    public static final TextKey PORTAL_FACE_FLIPPED = text("portal.orientation.face_flipped", "<green>{portal} face flipped to {direction}.");
    public static final TextKey PORTAL_ROTATED_COUNTERCLOCKWISE = text("portal.orientation.rotated_counterclockwise", "<green>{portal} rolled counterclockwise.");
    public static final TextKey PORTAL_ROTATED_CLOCKWISE = text("portal.orientation.rotated_clockwise", "<green>{portal} rolled clockwise.");
    public static final TextKey PORTAL_DIRECTION_CHANGED = text("portal.orientation.direction_changed", "<green>{portal}'s direction changed to {direction}.");
    public static final TextKey PORTAL_MODE_CHANGED = text("portal.mode.changed", "<green>{portal} mode set to {mode}.");

    public static final TextKey LABEL_NONE = text("label.none", "None");
    public static final TextKey LABEL_ON = text("label.on", "On");
    public static final TextKey LABEL_OFF = text("label.off", "Off");
    public static final TextKey LABEL_READY = text("label.ready", "Ready");
    public static final TextKey LABEL_PREPARING = text("label.preparing", "Preparing");
    public static final TextKey LABEL_OPEN = text("label.open", "Open");
    public static final TextKey LABEL_CLOSED = text("label.closed", "Closed");
    public static final TextKey LABEL_SELECTED = text("label.selected", "Selected");
    public static final TextKey LABEL_LEFT_CLICK = text("label.left_click", "Left click");
    public static final TextKey LABEL_BACK = text("label.back", "Back");
    public static final TextKey LABEL_OVERVIEW = text("label.overview", "Overview");
    public static final TextKey LABEL_CONFIRM = text("label.confirm", "Confirm");
    public static final TextKey LABEL_CANCEL = text("label.cancel", "Cancel");

    public static final LinesKey RTP_OVERVIEW_DESTINATION = lines("rtp.overview.destination", "<yellow><bold>Destination & Area</bold>", "<gray>World, center, and radius.", "<yellow>Left click");
    public static final LinesKey RTP_OVERVIEW_LANDING = lines("rtp.overview.landing", "<yellow><bold>Landing Rules</bold>", "<gray>Surface and height behavior.", "<yellow>Left click");
    public static final LinesKey RTP_OVERVIEW_ROUTING = lines("rtp.overview.routing", "<yellow><bold>Rotation & Pool</bold>", "<gray>Sharing, rotation, and leases.", "<yellow>Left click");
    public static final LinesKey RTP_OVERVIEW_EFFECTS = lines("rtp.overview.effects", "<yellow><bold>Effects</bold>", "<gray>Projection rim and portal sound.", "<yellow>Left click");
    public static final LinesKey RTP_RESET_DEFAULTS = lines("rtp.overview.reset_defaults", "<yellow><bold>Reset To Defaults</bold>", "<gray>Immediately restore this portal's random teleport settings to their defaults.", "<yellow>Left click");
    public static final LinesKey RTP_BACK_PORTAL = lines("rtp.navigation.back_portal", "<yellow><bold>Back to Portal</bold>", "<gray>Return to the portal menu.", "<yellow>Left click");
    public static final LinesKey RTP_BACK_OVERVIEW = lines("rtp.navigation.overview", "<yellow><bold>Overview</bold>", "<gray>Return to the RTP overview.", "<yellow>Left click");
    public static final LinesKey RTP_BACK_CATEGORY = lines("rtp.navigation.back_category", "<yellow><bold>Back</bold>", "<gray>Return to the previous category.", "<yellow>Left click");
    public static final LinesKey RTP_DESTINATION_HEADER = lines("rtp.destination.header", "<aqua><bold>Destination & Area</bold>", "<gray>Every option uses a normal left click.");
    public static final LinesKey RTP_WORLD_CURRENT = lines("rtp.destination.world_current", "<green><bold>{world}</bold>", "<gray>Current target world.", "<green>Selected");
    public static final LinesKey RTP_WORLD_AVAILABLE = lines("rtp.destination.world_available", "<aqua><bold>{world}</bold>", "<gray>Use this loaded world.", "<yellow>Left click to select");
    public static final LinesKey RTP_PREVIOUS_WORLDS = lines("rtp.destination.previous_worlds", "<yellow><bold>Previous Worlds</bold>", "<gray>Show the previous page.", "<yellow>Left click");
    public static final LinesKey RTP_NEXT_WORLDS = lines("rtp.destination.next_worlds", "<yellow><bold>Next Worlds</bold>", "<gray>Show the next page.", "<yellow>Left click");
    public static final LinesKey RTP_CENTER_PORTAL_SELECTED = lines("rtp.destination.center_portal_selected", "<green><bold>Portal-relative Center</bold>", "<gray>Center the annulus on this portal.", "<green>Selected");
    public static final LinesKey RTP_CENTER_PORTAL_AVAILABLE = lines("rtp.destination.center_portal_available", "<aqua><bold>Portal-relative Center</bold>", "<gray>Center the annulus on this portal.", "<yellow>Left click to select");
    public static final LinesKey RTP_CENTER_CUSTOM_SELECTED = lines("rtp.destination.center_custom_selected", "<green><bold>Custom Center</bold>", "<gray>Use editable X and Z coordinates.", "<green>Selected");
    public static final LinesKey RTP_CENTER_CUSTOM_AVAILABLE = lines("rtp.destination.center_custom_available", "<aqua><bold>Custom Center</bold>", "<gray>Use editable X and Z coordinates.", "<yellow>Left click to select");
    public static final LinesKey RTP_NUMERIC_LINK = lines("rtp.numeric.link", "<yellow><bold>{label} <white>{value}</bold>", "<gray>Open clear decrease/increase controls.", "<yellow>Left click");
    public static final LinesKey RTP_RESET_CENTER = lines("rtp.destination.reset_center", "<yellow><bold>Reset Center / Target</bold>", "<gray>Use the source world and portal center.", "<yellow>Left click");
    public static final LinesKey RTP_LANDING_HEADER = lines("rtp.landing.header", "<aqua><bold>Landing Rules</bold>", "<gray>Surface mode uses one dry, non-tree terrain surface.");
    public static final LinesKey RTP_SURFACE_SELECTED = lines("rtp.landing.surface_selected", "<green><bold>Surface</bold>", "<gray>No water, tree tops, or underground fallback.", "<green>Selected");
    public static final LinesKey RTP_SURFACE_AVAILABLE = lines("rtp.landing.surface_available", "<aqua><bold>Surface</bold>", "<gray>No water, tree tops, or underground fallback.", "<yellow>Left click to select");
    public static final LinesKey RTP_PREFERRED_SELECTED = lines("rtp.landing.preferred_selected", "<green><bold>Preferred Height</bold>", "<gray>Search outward from a preferred Y value.", "<green>Selected");
    public static final LinesKey RTP_PREFERRED_AVAILABLE = lines("rtp.landing.preferred_available", "<aqua><bold>Preferred Height</bold>", "<gray>Search outward from a preferred Y value.", "<yellow>Left click to select");
    public static final LinesKey RTP_SAFE_LANDING = lines("rtp.landing.safe_policy", "<aqua><bold>Safe Landing Policy</bold>", "<gray>Water, waterlogged blocks, hazards, trees, collisions, and unsupported landings are rejected.");
    public static final LinesKey RTP_ROUTING_HEADER = lines("rtp.routing.header", "<aqua><bold>Rotation & Pool</bold>", "<gray>Choose modes directly, then apply the batch once.");
    public static final LinesKey RTP_SHARED_SELECTED = lines("rtp.routing.shared_selected", "<green><bold>Shared Destination</bold>", "<gray>Everyone sees and uses the same route.", "<green>Selected");
    public static final LinesKey RTP_SHARED_AVAILABLE = lines("rtp.routing.shared_available", "<aqua><bold>Shared Destination</bold>", "<gray>Everyone sees and uses the same route.", "<yellow>Left click to select");
    public static final LinesKey RTP_PRIVATE_SELECTED = lines("rtp.routing.private_selected", "<green><bold>Per-player Destinations</bold>", "<gray>Each player receives a private reservation.", "<green>Selected");
    public static final LinesKey RTP_PRIVATE_AVAILABLE = lines("rtp.routing.private_available", "<aqua><bold>Per-player Destinations</bold>", "<gray>Each player receives a private reservation.", "<yellow>Left click to select");
    public static final LinesKey RTP_STATIC_SELECTED = lines("rtp.routing.static_selected", "<green><bold>Static</bold>", "<gray>Keep the same destination.", "<green>Selected");
    public static final LinesKey RTP_STATIC_AVAILABLE = lines("rtp.routing.static_available", "<aqua><bold>Static</bold>", "<gray>Keep the same destination.", "<yellow>Left click to select");
    public static final LinesKey RTP_TIMED_SELECTED = lines("rtp.routing.timed_selected", "<green><bold>Timed</bold>", "<gray>Rotate after the configured duration.", "<green>Selected");
    public static final LinesKey RTP_TIMED_AVAILABLE = lines("rtp.routing.timed_available", "<aqua><bold>Timed</bold>", "<gray>Rotate after the configured duration.", "<yellow>Left click to select");
    public static final LinesKey RTP_TRIP_SELECTED = lines("rtp.routing.trip_selected", "<green><bold>After Every Trip</bold>", "<gray>Promote a prepared replacement after use.", "<green>Selected");
    public static final LinesKey RTP_TRIP_AVAILABLE = lines("rtp.routing.trip_available", "<aqua><bold>After Every Trip</bold>", "<gray>Promote a prepared replacement after use.", "<yellow>Left click to select");
    public static final LinesKey RTP_MANUAL_REROLL = lines("rtp.routing.manual_reroll", "<yellow><bold>Manual Reroll</bold>", "<gray>{description}", "<yellow>Left click");
    public static final LinesKey RTP_REBUILD_POOL = lines("rtp.routing.rebuild_pool", "<yellow><bold>Rebuild Pool</bold>", "<gray>{description}", "<yellow>Left click");
    public static final TextKey RTP_ACTION_CONFIRM = text("rtp.routing.action_confirm", "Open a separate confirmation screen.");
    public static final LinesKey RTP_EFFECTS_HEADER = lines("rtp.effects.header", "<aqua><bold>Effects</bold>", "<gray>Presentation changes do not regenerate destinations.");
    public static final LinesKey RTP_RIM_ON_SELECTED = lines("rtp.effects.rim_on_selected", "<green><bold>Readiness Rim On</bold>", "<gray>Show private readiness around the portal rim.", "<green>Selected");
    public static final LinesKey RTP_RIM_ON_AVAILABLE = lines("rtp.effects.rim_on_available", "<aqua><bold>Readiness Rim On</bold>", "<gray>Show private readiness around the portal rim.", "<yellow>Left click to select");
    public static final LinesKey RTP_RIM_OFF_SELECTED = lines("rtp.effects.rim_off_selected", "<green><bold>Readiness Rim Off</bold>", "<gray>Hide the readiness rim.", "<green>Selected");
    public static final LinesKey RTP_RIM_OFF_AVAILABLE = lines("rtp.effects.rim_off_available", "<aqua><bold>Readiness Rim Off</bold>", "<gray>Hide the readiness rim.", "<yellow>Left click to select");
    public static final LinesKey RTP_SOUND_ON_SELECTED = lines("rtp.effects.sound_on_selected", "<green><bold>Portal Sounds On</bold>", "<gray>Play this RTP portal's effects and travel sounds.", "<green>Selected");
    public static final LinesKey RTP_SOUND_ON_AVAILABLE = lines("rtp.effects.sound_on_available", "<aqua><bold>Portal Sounds On</bold>", "<gray>Play this RTP portal's effects and travel sounds.", "<yellow>Left click to select");
    public static final LinesKey RTP_SOUND_OFF_SELECTED = lines("rtp.effects.sound_off_selected", "<green><bold>Portal Sounds Off</bold>", "<gray>Mute this RTP portal; particles remain enabled.", "<green>Selected");
    public static final LinesKey RTP_SOUND_OFF_AVAILABLE = lines("rtp.effects.sound_off_available", "<aqua><bold>Portal Sounds Off</bold>", "<gray>Mute this RTP portal; particles remain enabled.", "<yellow>Left click to select");
    public static final LinesKey RTP_TARGET_UNAVAILABLE = lines("rtp.numeric.target_unavailable", "<aqua><bold>Target World Unavailable</bold>", "<gray>Load the target world before editing this value.");
    public static final LinesKey RTP_NUMERIC_VALUE = lines("rtp.numeric.value", "<aqua><bold>{value}</bold>", "<gray>Current draft value.");
    public static final LinesKey RTP_NUMERIC_ADJUST = lines("rtp.numeric.adjust", "<yellow><bold>{direction}{step}</bold>", "<gray>Adjust the draft by {direction}{step}.", "<yellow>Left click");
    public static final LinesKey RTP_NUMERIC_HEADER = lines("rtp.numeric.header", "<aqua><bold>{label}</bold>", "<gray>{description}");
    public static final LinesKey RTP_CONFIRM_REROLL = lines("rtp.confirm.reroll", "<aqua><bold>Reroll Shared Route?</bold>", "<gray>The current projection stays online until the replacement is ready.");
    public static final LinesKey RTP_CONFIRM_REBUILD = lines("rtp.confirm.rebuild", "<aqua><bold>Rebuild Private Pool?</bold>", "<gray>Existing reservations stay intact while free candidates rebuild.");
    public static final LinesKey RTP_CONFIRM = lines("rtp.confirm.confirm", "<yellow><bold>Confirm</bold>", "<gray>Run the action now.", "<yellow>Left click");
    public static final LinesKey RTP_CANCEL = lines("rtp.confirm.cancel", "<yellow><bold>Cancel</bold>", "<gray>Return without changing runtime state.", "<yellow>Left click");
    public static final TextKey RTP_STATUS_READY = text("rtp.status.ready", "Ready");
    public static final TextKey RTP_STATUS_WARMING = text("rtp.status.warming", "Warming");
    public static final TextKey RTP_STATUS_REROLLING = text("rtp.status.rerolling", "Rerolling");
    public static final TextKey RTP_STATUS_BACKOFF = text("rtp.status.backoff", "Retry Backoff");
    public static final TextKey RTP_STATUS_WORLD_UNAVAILABLE = text("rtp.status.world_unavailable", "Target World Unavailable");
    public static final TextKey RTP_STATUS_INTEGRATION_FAILED = text("rtp.status.integration_failed", "Access Integration Failed");
    public static final TextKey RTP_STATUS_FAILED = text("rtp.status.failed", "Failed");
    public static final TextKey RTP_STATUS_IDLE = text("rtp.status.idle", "Idle");
    public static final TextKey RTP_ROTATION_STATIC = text("rtp.rotation.static", "Static");
    public static final TextKey RTP_ROTATION_TIMED = text("rtp.rotation.timed", "Timed");
    public static final TextKey RTP_ROTATION_TRIP = text("rtp.rotation.trip", "After Every Trip");
    public static final LinesKey RTP_STATUS_HEADER = lines("rtp.status.header", "<gold><bold>Random Destination Status</bold>", "<gray>State: {state}");
    public static final TextKey RTP_STATUS_RETRY = text("rtp.status.retry", "<gray>Retry in: <red>{duration}");
    public static final TextKey RTP_STATUS_ACTIVE = text("rtp.status.active", "<gray>Active: {readiness}");
    public static final TextKey RTP_STATUS_STANDBY = text("rtp.status.standby", "<gray>Standby: {readiness}");
    public static final TextKey RTP_STATUS_ROTATION = text("rtp.status.rotation", "<gray>Draft rotation: <aqua>{rotation}");
    public static final TextKey RTP_STATUS_POOL = text("rtp.status.pool", "<gray>Free: <aqua>{free}<gray>  Reserved: <aqua>{reserved}");
    public static final TextKey RTP_STATUS_TARGET_MISSING = text("rtp.status.target_missing", "<red>Target world is not loaded.");
    public static final TextKey RTP_STATUS_ACCESS_FAILED = text("rtp.status.access_failed", "<red>Destination access checks failed closed.");
    public static final TextKey RTP_LABEL_CENTER_X = text("rtp.numeric.center_x", "Center X");
    public static final TextKey RTP_LABEL_CENTER_Z = text("rtp.numeric.center_z", "Center Z");
    public static final TextKey RTP_LABEL_MIN_RADIUS = text("rtp.numeric.minimum_radius", "Minimum Radius");
    public static final TextKey RTP_LABEL_MAX_RADIUS = text("rtp.numeric.maximum_radius", "Maximum Radius");
    public static final TextKey RTP_LABEL_LOWER_Y = text("rtp.numeric.lower_y", "Lower Y");
    public static final TextKey RTP_LABEL_UPPER_Y = text("rtp.numeric.upper_y", "Upper Y");
    public static final TextKey RTP_LABEL_PREFERRED_Y = text("rtp.numeric.preferred_y", "Preferred Y");
    public static final TextKey RTP_LABEL_CYCLE = text("rtp.numeric.cycle", "Cycle Duration");
    public static final TextKey RTP_LABEL_PRIVATE_ROTATION = text("rtp.numeric.private_rotation", "Private Rotation Time");
    public static final TextKey RTP_LABEL_LEASE = text("rtp.numeric.lease", "Idle Lease Grace");
    public static final TextKey RTP_LABEL_RELEASE = text("rtp.numeric.release", "Private Release");
    public static final TextKey RTP_DESCRIPTION_CENTER = text("rtp.numeric.description.center", "Move the custom center on this axis.");
    public static final TextKey RTP_DESCRIPTION_MIN_RADIUS = text("rtp.numeric.description.minimum_radius", "Inner edge of the destination annulus.");
    public static final TextKey RTP_DESCRIPTION_MAX_RADIUS = text("rtp.numeric.description.maximum_radius", "Outer edge of the destination annulus.");
    public static final TextKey RTP_DESCRIPTION_Y = text("rtp.numeric.description.y", "Legal feet-height search bound.");
    public static final TextKey RTP_DESCRIPTION_CYCLE = text("rtp.numeric.description.cycle", "Time between shared timed rotations.");
    public static final TextKey RTP_DESCRIPTION_PRIVATE_ROTATION = text("rtp.numeric.description.private_rotation", "Maximum age of each player's prepared destination.");
    public static final TextKey RTP_DESCRIPTION_LEASE = text("rtp.numeric.description.lease", "Keep prepared destinations warm briefly after everyone leaves.");
    public static final TextKey RTP_DESCRIPTION_RELEASE = text("rtp.numeric.description.release", "Delay before an unused private reservation releases.");
    public static final TextKey RTP_DURATION_HOURS = text("rtp.duration.hours", "{value}h");
    public static final TextKey RTP_DURATION_MINUTES = text("rtp.duration.minutes", "{value}m");
    public static final TextKey RTP_DURATION_SECONDS = text("rtp.duration.seconds", "{value}s");
    public static final TextKey RTP_DURATION_DECIMAL_SECONDS = text("rtp.duration.decimal_seconds", "{value}s");

    private WormholesMessages() {
    }

    public static MessageCatalog catalog() {
        return MessageCatalog.builder(ENGLISH_LOCALE)
                .addAll(DirectorMessages.keys())
                .addAll(KEYS)
                .build();
    }

    public static List<MessageKey> keys() {
        return List.copyOf(KEYS);
    }

    private static TextKey text(String id, String english) {
        TextKey key = TextKey.of(id, english);
        KEYS.add(key);
        return key;
    }

    private static LinesKey lines(String id, String... english) {
        LinesKey key = LinesKey.of(id, english);
        KEYS.add(key);
        return key;
    }

    private static PluralKey plural(String id, String selectorArgument, Map<String, String> english) {
        PluralKey key = PluralKey.of(id, selectorArgument, english);
        KEYS.add(key);
        return key;
    }
}
