package art.arcane.wormholes.door;

import art.arcane.wormholes.util.JSONArray;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON persistence for dimensional-door identity and allocation state. */
public final class DimensionalDoorRepository {
    private static final String STATE_FILE = "state.json";
    private static final Pattern NEXT_POCKET_SLOT = Pattern.compile("\\\"nextPocketSlot\\\"\\s*:\\s*(\\d+)");
    private static final Pattern POCKET_SLOT = Pattern.compile("\\\"slot\\\"\\s*:\\s*(\\d+)");

    private final Path stateFile;
    private DoorStoreSnapshot loaded;

    /** Uses {@code <plugin data>/doors/state.json}. */
    public static DimensionalDoorRepository under(Path pluginDataDirectory) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        return new DimensionalDoorRepository(pluginDataDirectory.resolve("doors").resolve(STATE_FILE));
    }

    /** Accepts the exact state-file path, primarily for tests and migrations. */
    public DimensionalDoorRepository(Path stateFile) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile").toAbsolutePath().normalize();
    }

    public synchronized DoorStoreSnapshot load() throws IOException {
        if (loaded != null) {
            return loaded;
        }
        if (!Files.isRegularFile(stateFile)) {
            loaded = DoorStoreSnapshot.empty();
            return loaded;
        }

        try {
            loaded = fromJson(new JSONObject(VIO.readAll(stateFile.toFile())));
            return loaded;
        } catch (RuntimeException e) {
            throw new IOException("Could not parse dimensional-door state at " + stateFile, e);
        }
    }

    public synchronized void save(DoorStoreSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        VIO.writeAll(stateFile.toFile(), toJson(snapshot).toString(2));
        loaded = snapshot;
    }

    public synchronized Optional<ReturnTicket> getReturnTicket(UUID playerId) throws IOException {
        return load().returnTicket(Objects.requireNonNull(playerId, "playerId"));
    }

    public synchronized void putReturnTicket(ReturnTicket ticket) throws IOException {
        save(load().withReturnTicket(Objects.requireNonNull(ticket, "ticket")));
    }

    public synchronized Optional<ReturnTicket> removeReturnTicket(UUID playerId) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        Optional<ReturnTicket> removed = load().returnTicket(playerId);
        if (removed.isPresent()) {
            save(loaded.withoutReturnTicket(playerId));
        }
        return removed;
    }

    public Path stateFile() {
        return stateFile;
    }

    public synchronized long recoverNextPocketSlot() throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return 0L;
        }
        String encoded = VIO.readAll(stateFile.toFile());
        Matcher nextSlot = NEXT_POCKET_SLOT.matcher(encoded);
        long recoveredNextSlot = nextSlot.find() ? parseRecoveredSlot(nextSlot.group(1)) : -1L;

        long highestSlot = -1L;
        Matcher allocatedSlot = POCKET_SLOT.matcher(encoded);
        while (allocatedSlot.find()) {
            highestSlot = Math.max(highestSlot, parseRecoveredSlot(allocatedSlot.group(1)));
        }
        if (recoveredNextSlot >= 0L || highestSlot >= 0L) {
            long afterHighestSlot = highestSlot < 0L ? 0L : Math.incrementExact(highestSlot);
            return Math.max(recoveredNextSlot, afterHighestSlot);
        }
        throw new IOException("Could not recover the next pocket slot from " + stateFile);
    }

    private static JSONObject toJson(DoorStoreSnapshot snapshot) {
        JSONObject root = new JSONObject()
            .put("schema", snapshot.schema())
            .put("nextPocketSlot", snapshot.nextPocketSlot());

        JSONArray pairs = new JSONArray();
        for (DoorPairIdentity pair : snapshot.pairs()) {
            pairs.put(new JSONObject()
                .put("pairId", pair.pairId().toString())
                .put("endpointAItemId", pair.endpointAItemId().toString())
                .put("endpointBItemId", pair.endpointBItemId().toString()));
        }
        root.put("pairs", pairs);

        JSONArray endpoints = new JSONArray();
        for (PlacedDoorEndpoint endpoint : snapshot.endpoints()) {
            DoorPosition position = endpoint.position();
            DoorItemIdentity identity = endpoint.identity();
            JSONObject item = new JSONObject()
                .put("itemId", identity.itemId().toString())
                .put("kind", identity.kind().name());
            if (identity.pairId() != null) {
                item.put("pairId", identity.pairId().toString());
            }
            if (identity.pairEndpoint() != null) {
                item.put("pairEndpoint", identity.pairEndpoint().name());
            }
            if (identity.spaceId() != null) {
                item.put("spaceId", identity.spaceId().toString());
            }
            endpoints.put(new JSONObject()
                .put("worldId", position.worldId().toString())
                .put("worldName", position.worldName())
                .put("x", position.x())
                .put("y", position.y())
                .put("z", position.z())
                .put("item", item));
        }
        root.put("endpoints", endpoints);

        JSONArray spaces = new JSONArray();
        for (PocketSpace space : snapshot.spaces()) {
            spaces.put(new JSONObject()
                .put("spaceId", space.spaceId().toString())
                .put("bindingKind", space.binding().kind().name())
                .put("bindingId", space.binding().bindingId().toString())
                .put("slot", space.slot())
                .put("centerX", space.centerX())
                .put("centerY", space.centerY())
                .put("centerZ", space.centerZ()));
        }
        root.put("spaces", spaces);

        JSONArray tickets = new JSONArray();
        for (ReturnTicket ticket : snapshot.returnTickets()) {
            tickets.put(new JSONObject()
                .put("playerId", ticket.playerId().toString())
                .put("sourceEndpointId", ticket.sourceEndpointId().toString())
                .put("sourceWorldId", ticket.sourceWorldId().toString())
                .put("sourceWorldName", ticket.sourceWorldName())
                .put("x", ticket.x())
                .put("y", ticket.y())
                .put("z", ticket.z())
                .put("yaw", ticket.yaw())
                .put("pitch", ticket.pitch()));
        }
        root.put("returnTickets", tickets);
        return root;
    }

    private static DoorStoreSnapshot fromJson(JSONObject root) {
        int schema = root.getInt("schema");
        long nextPocketSlot = root.getLong("nextPocketSlot");

        JSONArray pairJson = root.getJSONArray("pairs");
        List<DoorPairIdentity> pairs = new ArrayList<>(pairJson.length());
        for (int i = 0; i < pairJson.length(); i++) {
            JSONObject pair = pairJson.getJSONObject(i);
            pairs.add(new DoorPairIdentity(
                uuid(pair, "pairId"),
                uuid(pair, "endpointAItemId"),
                uuid(pair, "endpointBItemId")
            ));
        }

        JSONArray endpointJson = root.getJSONArray("endpoints");
        List<PlacedDoorEndpoint> endpoints = new ArrayList<>(endpointJson.length());
        for (int i = 0; i < endpointJson.length(); i++) {
            JSONObject endpoint = endpointJson.getJSONObject(i);
            JSONObject item = endpoint.getJSONObject("item");
            DoorKind kind = DoorKind.valueOf(item.getString("kind"));
            DoorItemIdentity identity = new DoorItemIdentity(
                uuid(item, "itemId"),
                kind,
                optionalUuid(item, "pairId"),
                item.has("pairEndpoint") ? PairEndpoint.valueOf(item.getString("pairEndpoint")) : null,
                optionalUuid(item, "spaceId")
            );
            endpoints.add(new PlacedDoorEndpoint(
                new DoorPosition(
                    uuid(endpoint, "worldId"),
                    endpoint.getString("worldName"),
                    endpoint.getInt("x"),
                    endpoint.getInt("y"),
                    endpoint.getInt("z")
                ),
                identity
            ));
        }

        JSONArray spaceJson = root.getJSONArray("spaces");
        List<PocketSpace> spaces = new ArrayList<>(spaceJson.length());
        for (int i = 0; i < spaceJson.length(); i++) {
            JSONObject space = spaceJson.getJSONObject(i);
            spaces.add(new PocketSpace(
                uuid(space, "spaceId"),
                new PocketBinding(
                    PocketBindingKind.valueOf(space.getString("bindingKind")),
                    uuid(space, "bindingId")
                ),
                space.getLong("slot"),
                space.getInt("centerX"),
                space.getInt("centerY"),
                space.getInt("centerZ")
            ));
        }

        JSONArray ticketJson = root.getJSONArray("returnTickets");
        List<ReturnTicket> tickets = new ArrayList<>(ticketJson.length());
        for (int i = 0; i < ticketJson.length(); i++) {
            JSONObject ticket = ticketJson.getJSONObject(i);
            tickets.add(new ReturnTicket(
                uuid(ticket, "playerId"),
                uuid(ticket, "sourceEndpointId"),
                uuid(ticket, "sourceWorldId"),
                ticket.getString("sourceWorldName"),
                ticket.getDouble("x"),
                ticket.getDouble("y"),
                ticket.getDouble("z"),
                (float) ticket.getDouble("yaw"),
                (float) ticket.getDouble("pitch")
            ));
        }
        return new DoorStoreSnapshot(schema, nextPocketSlot, pairs, endpoints, spaces, tickets);
    }

    private static UUID uuid(JSONObject json, String key) {
        return UUID.fromString(json.getString(key));
    }

    private static UUID optionalUuid(JSONObject json, String key) {
        return json.has(key) ? uuid(json, key) : null;
    }

    private static long parseRecoveredSlot(String encoded) throws IOException {
        try {
            return Long.parseLong(encoded);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid dimensional-door pocket slot '" + encoded + "'", exception);
        }
    }
}
