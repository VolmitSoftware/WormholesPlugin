package art.arcane.wormholes.perf;

import art.arcane.wormholes.network.CompressionDictionary;
import art.arcane.wormholes.network.DictionarySampleCollector;
import art.arcane.wormholes.network.WireCodec;
import art.arcane.wormholes.network.WireCompression;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkBulk;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkDiffBatch;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntitySendState;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ViewSlice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class WormholesBandwidthHarness {
    private static final String SCENARIO_BASELINE = "BASELINE";
    private static final String SCENARIO_COMPRESSION_ONLY = "COMPRESSION_ONLY";
    private static final String SCENARIO_COMPRESSION_DICT = "COMPRESSION_DICT";
    private static final String SCENARIO_ALL_OPTS = "ALL_OPTS";
    private static final String SCENARIO_STREAMING_BASELINE_REPL_OFF = "STREAMING_BASELINE_REPL_OFF";
    private static final String SCENARIO_REPLICATION_COLD = "REPLICATION_COLD";
    private static final String SCENARIO_REPLICATION_WARM = "REPLICATION_WARM";
    private static final String SCENARIO_REPLICATION_STATIC = "REPLICATION_STATIC";

    private static final int ENTITY_BATCH_SIZE = 16;
    private static final int SLICE_PALETTE_SIZE = 8;
    private static final int SLICE_BIOME_PALETTE_SIZE = 4;
    private static final String[] HARNESS_BLOCK_STATES = {
        "minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block[snowy=false]",
        "minecraft:oak_log[axis=y]", "minecraft:water[level=0]", "minecraft:sand", "minecraft:cobblestone"
    };
    private static final int SLICE_SIZE_X = 16;
    private static final int SLICE_SIZE_Y = 24;
    private static final int SLICE_SIZE_Z = 16;
    private static final int FRAMES_PER_SUBSCRIBER_PER_TICK = 1;
    private static final int TICKS_PER_SECOND = 20;
    private static final int VIEW_SLICE_EVERY_N_TICKS = 5;
    private static final int DICT_TRAIN_BUDGET_BYTES = 256 * 1024;
    private static final int DICT_TARGET_SIZE_BYTES = 16 * 1024;
    private static final int CHUNKS_PER_PEER_DEFAULT = 32;
    private static final int ACTIVITY_BLOCKS_PER_CHUNK_PER_SEC_DEFAULT = 5;

    private WormholesBandwidthHarness() {
    }

    public static void main(String[] args) throws Exception {
        int subscribers = Integer.parseInt(System.getProperty("subscribers", "32"));
        int durationSeconds = Integer.parseInt(System.getProperty("duration", "30"));
        int chunksPerPeer = Integer.parseInt(System.getProperty("chunks", String.valueOf(CHUNKS_PER_PEER_DEFAULT)));
        int activityRate = Integer.parseInt(System.getProperty("activity", String.valueOf(ACTIVITY_BLOCKS_PER_CHUNK_PER_SEC_DEFAULT)));
        String scenariosCsv = System.getProperty("scenarios",
            SCENARIO_BASELINE + "," + SCENARIO_COMPRESSION_ONLY + "," + SCENARIO_COMPRESSION_DICT + "," + SCENARIO_ALL_OPTS
                + "," + SCENARIO_STREAMING_BASELINE_REPL_OFF + "," + SCENARIO_REPLICATION_COLD
                + "," + SCENARIO_REPLICATION_WARM + "," + SCENARIO_REPLICATION_STATIC);
        List<String> scenarios = Arrays.asList(scenariosCsv.split(","));

        System.out.println("Wormholes bandwidth harness");
        System.out.println("subscribers=" + subscribers + " duration=" + durationSeconds + "s chunks=" + chunksPerPeer + " activity=" + activityRate + " scenarios=" + scenarios);
        System.out.println();

        Map<String, ScenarioResult> results = new HashMap<>();
        CompressionDictionary sharedDictionary = null;

        for (String scenario : scenarios) {
            String trimmed = scenario.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ScenarioResult result = runScenario(trimmed, subscribers, durationSeconds, chunksPerPeer, activityRate, sharedDictionary);
            results.put(trimmed, result);
            if (trimmed.equals(SCENARIO_BASELINE) && needsDictionary(scenarios)) {
                sharedDictionary = trainDictionaryFromBaseline(subscribers, Math.min(5, durationSeconds));
            }
        }

        printTable(scenarios, results);
    }

    private static boolean needsDictionary(List<String> scenarios) {
        for (String scenario : scenarios) {
            String trimmed = scenario.trim();
            if (trimmed.equals(SCENARIO_COMPRESSION_DICT) || trimmed.equals(SCENARIO_ALL_OPTS)) {
                return true;
            }
        }
        return false;
    }

    private static ScenarioResult runScenario(String name, int subscribers, int durationSeconds, int chunksPerPeer, int activityRate, CompressionDictionary sharedDictionary) throws IOException {
        switch (name) {
            case SCENARIO_BASELINE:
                return runEntityScenario(name, new ScenarioConfig(false, false, false, null), subscribers, durationSeconds);
            case SCENARIO_COMPRESSION_ONLY:
                return runEntityScenario(name, new ScenarioConfig(true, false, false, null), subscribers, durationSeconds);
            case SCENARIO_COMPRESSION_DICT:
                return runEntityScenario(name, new ScenarioConfig(true, true, false, sharedDictionary), subscribers, durationSeconds);
            case SCENARIO_ALL_OPTS:
                return runEntityScenario(name, new ScenarioConfig(true, true, true, sharedDictionary), subscribers, durationSeconds);
            case SCENARIO_STREAMING_BASELINE_REPL_OFF:
                return runStreamingBaselineReplOff(name, subscribers, durationSeconds, chunksPerPeer);
            case SCENARIO_REPLICATION_COLD:
                return runReplicationScenario(name, subscribers, durationSeconds, chunksPerPeer, activityRate, true);
            case SCENARIO_REPLICATION_WARM:
                return runReplicationScenario(name, subscribers, durationSeconds, chunksPerPeer, activityRate, false);
            case SCENARIO_REPLICATION_STATIC:
                return runReplicationScenario(name, subscribers, durationSeconds, chunksPerPeer, 0, false);
            default:
                throw new IllegalArgumentException("unknown scenario " + name);
        }
    }

    private static ScenarioResult runEntityScenario(String name, ScenarioConfig config, int subscribers, int durationSeconds) throws IOException {
        WireCompression compression = config.compressionEnabled ? new WireCompression(WireCompression.DEFAULT_LEVEL) : null;
        if (compression != null && config.dictionary != null && config.dictMode) {
            compression.installDictionary(config.dictionary);
        }
        try {
            int totalTicks = durationSeconds * TICKS_PER_SECOND;
            long totalTxBytes = 0L;
            long totalRxBytes = 0L;
            List<Integer> frameSizes = new ArrayList<>(subscribers * totalTicks);

            List<SubscriberState> subscriberStates = new ArrayList<>(subscribers);
            for (int i = 0; i < subscribers; i++) {
                subscriberStates.add(new SubscriberState(i));
            }

            for (int tick = 0; tick < totalTicks; tick++) {
                for (SubscriberState subscriber : subscriberStates) {
                    for (int f = 0; f < FRAMES_PER_SUBSCRIBER_PER_TICK; f++) {
                        WireMessage message = buildEntityFrame(subscriber, config, tick);
                        byte[] frame = WireCodec.encodeFrame(message, compression, config.dictMode);
                        frameSizes.add(frame.length);
                        totalTxBytes += frame.length;
                        totalRxBytes += frame.length;
                    }
                    if (tick % VIEW_SLICE_EVERY_N_TICKS == 0) {
                        WireMessage.ChunkBulkBatch chunkBatch = buildLegacySliceBatch(subscriber, tick);
                        byte[] frame = WireCodec.encodeFrame(chunkBatch, compression, config.dictMode);
                        frameSizes.add(frame.length);
                        totalTxBytes += frame.length;
                        totalRxBytes += frame.length;
                    }
                }
            }

            int frameCount = frameSizes.size();
            long meanFrameSize = frameCount == 0 ? 0L : totalTxBytes / frameCount;
            long p95FrameSize = computeP95(frameSizes);
            return new ScenarioResult(name, totalTxBytes, totalRxBytes, frameCount, meanFrameSize, p95FrameSize);
        } finally {
            if (compression != null) {
                compression.close();
            }
        }
    }

    private static ScenarioResult runStreamingBaselineReplOff(String name, int subscribers, int durationSeconds, int chunksPerPeer) throws IOException {
        int totalTicks = durationSeconds * TICKS_PER_SECOND;
        long totalTxBytes = 0L;
        long totalRxBytes = 0L;
        List<Integer> frameSizes = new ArrayList<>(subscribers * totalTicks);

        List<SubscriberState> subscriberStates = new ArrayList<>(subscribers);
        for (int i = 0; i < subscribers; i++) {
            subscriberStates.add(new SubscriberState(i));
        }

        for (int tick = 0; tick < totalTicks; tick++) {
            for (SubscriberState subscriber : subscriberStates) {
                for (int c = 0; c < chunksPerPeer; c++) {
                    WireMessage.ChunkBulkBatch chunkBatch = buildLegacySingleSlice(subscriber, tick, c);
                    byte[] frame = WireCodec.encodeFrame(chunkBatch);
                    frameSizes.add(frame.length);
                    totalTxBytes += frame.length;
                    totalRxBytes += frame.length;
                }
            }
        }

        int frameCount = frameSizes.size();
        long meanFrameSize = frameCount == 0 ? 0L : totalTxBytes / frameCount;
        long p95FrameSize = computeP95(frameSizes);
        return new ScenarioResult(name, totalTxBytes, totalRxBytes, frameCount, meanFrameSize, p95FrameSize);
    }

    private static ScenarioResult runReplicationScenario(String name, int subscribers, int durationSeconds, int chunksPerPeer, int activityRate, boolean includeColdStart) throws IOException {
        int totalTicks = durationSeconds * TICKS_PER_SECOND;
        long totalTxBytes = 0L;
        long totalRxBytes = 0L;
        List<Integer> frameSizes = new ArrayList<>(subscribers * totalTicks);

        List<SubscriberState> subscriberStates = new ArrayList<>(subscribers);
        for (int i = 0; i < subscribers; i++) {
            subscriberStates.add(new SubscriberState(i));
        }

        if (includeColdStart) {
            for (SubscriberState subscriber : subscriberStates) {
                for (int c = 0; c < chunksPerPeer; c++) {
                    WireMessage.ChunkBulkBatch bulk = buildLegacySingleSlice(subscriber, 0, c);
                    byte[] frame = WireCodec.encodeFrame(bulk);
                    frameSizes.add(frame.length);
                    totalTxBytes += frame.length;
                    totalRxBytes += frame.length;
                }
            }
        }

        long blockSequence = 1L;
        for (int tick = 0; tick < totalTicks; tick++) {
            if (activityRate <= 0) {
                continue;
            }
            int blocksPerTickPerChunk = Math.max(1, (activityRate + (TICKS_PER_SECOND - 1)) / TICKS_PER_SECOND);
            boolean emitThisTick = activityRate >= TICKS_PER_SECOND ? true : (tick % Math.max(1, TICKS_PER_SECOND / activityRate)) == 0;
            if (!emitThisTick) {
                continue;
            }
            for (SubscriberState subscriber : subscriberStates) {
                List<ChunkDiffBatch> batches = new ArrayList<>(chunksPerPeer);
                for (int c = 0; c < chunksPerPeer; c++) {
                    long chunkKey = ViewSlice.columnKey(c, subscriber.id);
                    List<BlockChange> blocks = new ArrayList<>(blocksPerTickPerChunk);
                    for (int b = 0; b < blocksPerTickPerChunk; b++) {
                        int lx = subscriber.random.nextInt(16);
                        int ly = 60 + subscriber.random.nextInt(SLICE_SIZE_Y);
                        int lz = subscriber.random.nextInt(16);
                        int packed = BlockChange.pack(lx, ly, lz);
                        String state = HARNESS_BLOCK_STATES[subscriber.random.nextInt(HARNESS_BLOCK_STATES.length)];
                        blocks.add(new BlockChange(packed, state, BlockChange.FLAG_NONE));
                    }
                    batches.add(new ChunkDiffBatch(chunkKey, blockSequence++, blocks, List.<LightDiff>of(), List.<BlockEntityDiff>of()));
                }
                WireMessage.ChunkDiff message = new WireMessage.ChunkDiff(batches);
                byte[] frame = WireCodec.encodeFrame(message);
                frameSizes.add(frame.length);
                totalTxBytes += frame.length;
                totalRxBytes += frame.length;
            }
        }

        int frameCount = frameSizes.size();
        long meanFrameSize = frameCount == 0 ? 0L : totalTxBytes / frameCount;
        long p95FrameSize = computeP95(frameSizes);
        return new ScenarioResult(name, totalTxBytes, totalRxBytes, frameCount, meanFrameSize, p95FrameSize);
    }

    private static long computeP95(List<Integer> samples) {
        if (samples.isEmpty()) {
            return 0L;
        }
        List<Integer> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.floor(0.95D * (sorted.size() - 1));
        return sorted.get(index).longValue();
    }

    private static WireMessage buildEntityFrame(SubscriberState subscriber, ScenarioConfig config, int tick) {
        UUID portalId = subscriber.portalId;
        List<EntityVisual> entities = new ArrayList<>(ENTITY_BATCH_SIZE);
        for (int i = 0; i < ENTITY_BATCH_SIZE; i++) {
            UUID entityId = subscriber.entityIds[i];
            EntitySendState sendState = subscriber.sendStates[i];
            EntityVisual currentFull = synthesizeEntity(entityId, subscriber.random, tick, i);
            EntityVisual outgoing;
            if (config.deltaEnabled) {
                EntityVisual previous = sendState.getLastSentSnapshot();
                EntityVisual delta = EntityDeltaCodec.buildDelta(currentFull, previous, sendState.allocateSequence(), 0.05D);
                outgoing = delta;
                sendState.recordSent(currentFull, delta.isFull());
            } else {
                outgoing = EntityVisual.full(
                    currentFull.id(), currentFull.typeKey(),
                    currentFull.x(), currentFull.y(), currentFull.z(),
                    currentFull.height(),
                    currentFull.lookX(), currentFull.lookY(), currentFull.lookZ(),
                    currentFull.yaw(), currentFull.pitch(),
                    currentFull.velocityX(), currentFull.velocityY(), currentFull.velocityZ(),
                    currentFull.onGround(),
                    currentFull.playerName(),
                    currentFull.textureValue(),
                    currentFull.textureSignature(),
                    currentFull.passengerOf(),
                    currentFull.metadata(),
                    currentFull.equipment(),
                    sendState.allocateSequence()
                );
                sendState.recordSent(currentFull, true);
            }
            entities.add(outgoing);
        }
        List<UUID> presentIds = new ArrayList<>(ENTITY_BATCH_SIZE);
        for (int i = 0; i < ENTITY_BATCH_SIZE; i++) {
            presentIds.add(subscriber.entityIds[i]);
        }
        return new WireMessage.ViewEntities(portalId, entities, presentIds);
    }

    private static EntityVisual synthesizeEntity(UUID entityId, Random random, int tick, int slot) {
        double x = 100.0D + Math.sin((tick + slot) * 0.05D) * 5.0D;
        double y = 64.0D + Math.cos((tick + slot) * 0.07D) * 0.5D;
        double z = 200.0D + Math.cos((tick + slot) * 0.05D) * 5.0D;
        float yaw = (float) ((tick * 2 + slot * 16) % 360);
        float pitch = 0.0F;
        byte[] metadata = new byte[8];
        random.nextBytes(metadata);
        byte[] equipment = new byte[16];
        random.nextBytes(equipment);
        return EntityVisual.full(
            entityId,
            "minecraft:zombie",
            x, y, z,
            1.95D,
            0.0D, 0.0D, 1.0D,
            yaw, pitch,
            0.0D, 0.0D, 0.0D,
            true,
            "",
            "",
            "",
            null,
            metadata,
            equipment,
            tick
        );
    }

    private static WireMessage.ChunkBulkBatch buildLegacySliceBatch(SubscriberState subscriber, int tick) throws IOException {
        return buildLegacySingleSlice(subscriber, tick, 0);
    }

    private static WireMessage.ChunkBulkBatch buildLegacySingleSlice(SubscriberState subscriber, int tick, int columnIndex) throws IOException {
        ViewSlice slice = synthesizeSlice(subscriber.random, tick, subscriber.id, columnIndex);
        byte[] payload = ChunkBulkBuilder.encodeSliceBytes(slice);
        long chunkKey = ViewSlice.columnKey(columnIndex, subscriber.id);
        ChunkBulk bulk = new ChunkBulk(chunkKey, tick, payload);
        return new WireMessage.ChunkBulkBatch(List.of(bulk));
    }

    private static ViewSlice synthesizeSlice(Random random, int tick, int subscriberId, int columnIndex) {
        int cells = SLICE_SIZE_X * SLICE_SIZE_Y * SLICE_SIZE_Z;
        List<String> palette = new ArrayList<>(SLICE_PALETTE_SIZE);
        palette.add("minecraft:air");
        palette.add("minecraft:stone");
        palette.add("minecraft:dirt");
        palette.add("minecraft:grass_block[snowy=false]");
        palette.add("minecraft:oak_log[axis=y]");
        palette.add("minecraft:water[level=0]");
        palette.add("minecraft:sand");
        palette.add("minecraft:cobblestone");
        List<String> biomePalette = new ArrayList<>(SLICE_BIOME_PALETTE_SIZE);
        biomePalette.add("minecraft:plains");
        biomePalette.add("minecraft:forest");
        biomePalette.add("minecraft:desert");
        biomePalette.add("minecraft:swamp");
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[cells];
        int runStart = 0;
        short currentBlock = (short) (random.nextInt(palette.size()));
        byte currentLight = (byte) random.nextInt(16);
        short currentBiome = (short) random.nextInt(biomePalette.size());
        while (runStart < cells) {
            int runLength = Math.min(cells - runStart, 4 + random.nextInt(16));
            for (int i = 0; i < runLength; i++) {
                indices[runStart + i] = currentBlock;
                light[runStart + i] = currentLight;
                biomes[runStart + i] = currentBiome;
            }
            runStart += runLength;
            currentBlock = (short) random.nextInt(palette.size());
            currentLight = (byte) random.nextInt(16);
            currentBiome = (short) random.nextInt(biomePalette.size());
        }
        int minX = columnIndex << 4;
        int minZ = subscriberId << 4;
        return new ViewSlice(minX, 60, minZ, SLICE_SIZE_X, SLICE_SIZE_Y, SLICE_SIZE_Z, palette, indices, light, biomePalette, biomes);
    }

    private static CompressionDictionary trainDictionaryFromBaseline(int subscribers, int trainSeconds) throws IOException {
        DictionarySampleCollector collector = new DictionarySampleCollector(DICT_TRAIN_BUDGET_BYTES);
        List<SubscriberState> states = new ArrayList<>(subscribers);
        for (int i = 0; i < subscribers; i++) {
            states.add(new SubscriberState(i));
        }
        int totalTicks = trainSeconds * TICKS_PER_SECOND;
        for (int tick = 0; tick < totalTicks && !collector.isFull(); tick++) {
            for (SubscriberState subscriber : states) {
                WireMessage entitiesFrame = buildEntityFrame(subscriber, new ScenarioConfig(false, false, false, null), tick);
                byte[] payload = WireCodec.encodePayload(entitiesFrame);
                if (payload.length >= 32 && payload.length <= 32 * 1024) {
                    collector.record(payload);
                }
                if (tick % VIEW_SLICE_EVERY_N_TICKS == 0) {
                    WireMessage.ChunkBulkBatch chunkBatch = buildLegacySliceBatch(subscriber, tick);
                    byte[] snapshotPayload = WireCodec.encodePayload(chunkBatch);
                    if (snapshotPayload.length >= 32 && snapshotPayload.length <= 32 * 1024) {
                        collector.record(snapshotPayload);
                    }
                }
                if (collector.isFull()) {
                    break;
                }
            }
        }
        List<byte[]> samples = collector.snapshot();
        if (samples.isEmpty()) {
            return null;
        }
        return CompressionDictionary.train(samples, DICT_TARGET_SIZE_BYTES, 1);
    }

    private static void printTable(List<String> scenarios, Map<String, ScenarioResult> results) {
        long baselineBytes = 0L;
        ScenarioResult anchor = results.get(SCENARIO_STREAMING_BASELINE_REPL_OFF);
        if (anchor == null) {
            anchor = results.get(SCENARIO_BASELINE);
        }
        if (anchor != null) {
            baselineBytes = anchor.txBytes;
        }
        String header = String.format("%-32s | %12s | %12s | %10s | %12s | %12s",
            "scenario", "tx bytes", "rx bytes", "ratio", "mean frame", "p95 frame");
        System.out.println(header);
        System.out.println(repeat('-', header.length()));
        for (String scenario : scenarios) {
            String trimmed = scenario.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ScenarioResult result = results.get(trimmed);
            if (result == null) {
                continue;
            }
            String ratio;
            if (baselineBytes <= 0L) {
                ratio = "n/a";
            } else {
                ratio = String.format("%.3f", (double) result.txBytes / (double) baselineBytes);
            }
            System.out.println(String.format("%-32s | %12d | %12d | %10s | %12d | %12d",
                trimmed,
                result.txBytes,
                result.rxBytes,
                ratio,
                result.meanFrameSize,
                result.p95FrameSize));
        }
    }

    private static String repeat(char ch, int times) {
        char[] chars = new char[times];
        Arrays.fill(chars, ch);
        return new String(chars);
    }

    private static final class SubscriberState {
        private final int id;
        private final UUID portalId;
        private final UUID[] entityIds;
        private final EntitySendState[] sendStates;
        private final Random random;

        private SubscriberState(int id) {
            this.id = id;
            this.portalId = new UUID(0xFEEDL, (long) id);
            this.entityIds = new UUID[ENTITY_BATCH_SIZE];
            this.sendStates = new EntitySendState[ENTITY_BATCH_SIZE];
            for (int i = 0; i < ENTITY_BATCH_SIZE; i++) {
                UUID entityId = new UUID(0xBEEFL, ((long) id << 32) | (long) i);
                this.entityIds[i] = entityId;
                this.sendStates[i] = new EntitySendState(entityId);
            }
            this.random = new Random(((long) id) * 0x9E3779B97F4A7C15L);
        }
    }

    private static final class ScenarioConfig {
        private final boolean compressionEnabled;
        private final boolean dictMode;
        private final boolean deltaEnabled;
        private final CompressionDictionary dictionary;

        private ScenarioConfig(boolean compressionEnabled, boolean dictMode, boolean deltaEnabled, CompressionDictionary dictionary) {
            this.compressionEnabled = compressionEnabled;
            this.dictMode = dictMode;
            this.deltaEnabled = deltaEnabled;
            this.dictionary = dictionary;
        }
    }

    private static final class ScenarioResult {
        private final String name;
        private final long txBytes;
        private final long rxBytes;
        private final int frameCount;
        private final long meanFrameSize;
        private final long p95FrameSize;

        private ScenarioResult(String name, long txBytes, long rxBytes, int frameCount, long meanFrameSize, long p95FrameSize) {
            this.name = name;
            this.txBytes = txBytes;
            this.rxBytes = rxBytes;
            this.frameCount = frameCount;
            this.meanFrameSize = meanFrameSize;
            this.p95FrameSize = p95FrameSize;
        }
    }
}
