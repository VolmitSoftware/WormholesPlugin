package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public final class RtpSafetyValidatorTest
{
	private static final RtpSafetyValidator VALIDATOR = new RtpSafetyValidator();
	private static final RtpDestination DESTINATION = new RtpDestination("minecraft:overworld", 0, 64, 0, 3L, 4);
	private static final RtpValidationRequest.EntityEnvelope BASELINE = RtpValidationRequest.EntityEnvelope.baseline();
	private static final RtpValidationRequest.WorldBorder WIDE_BORDER = new RtpValidationRequest.WorldBorder(-1_000.0D, -1_000.0D, 1_000.0D, 1_000.0D);

	@Test
	public void completeSupportAndHeadroomAreSafe()
	{
		RtpValidationRequest request = request(DESTINATION, BASELINE, safeSnapshots(DESTINATION, BASELINE));

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertTrue(result.safe());
		assertEquals(RtpSafetyResult.Code.SAFE, result.code());
		assertEquals(DESTINATION, result.destination());
	}

	@Test
	public void translatedEntityEnvelopeUsesHorizontalCenterAndFeetAnchoring()
	{
		RtpValidationRequest.EntityEnvelope envelope = new RtpValidationRequest.EntityEnvelope(-0.2D, 0.8D,
				-0.4D, 1.6D, -0.9D, 0.1D);
		RtpValidationRequest request = request(DESTINATION, envelope, safeSnapshots(DESTINATION, envelope));

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.SAFE, result.code());
	}

	@Test
	public void mismatchedSnapshotWorldIsRejected()
	{
		List<RtpValidationRequest.RegionSnapshot> snapshots = safeSnapshots(DESTINATION, BASELINE);
		RtpValidationRequest request = requestBuilder(DESTINATION, BASELINE, snapshots)
				.snapshotWorldKey("minecraft:the_end")
				.build();

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.WORLD_MISMATCH, result.code());
	}

	@Test
	public void partialSupportAndPositiveAreaHolesAreRejected()
	{
		List<RtpValidationRequest.CollisionBox> partial = List.of(
				new RtpValidationRequest.CollisionBox(0.0D, 0.0D, 0.0D, 0.45D, 1.0D, 1.0D),
				new RtpValidationRequest.CollisionBox(0.55D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D));
		RtpValidationRequest.BlockSnapshot support = RtpValidationRequest.BlockSnapshot.of(0, 63, 0,
				"minecraft:stone", false, false, partial);
		List<RtpValidationRequest.RegionSnapshot> snapshots = replaceBlock(safeSnapshots(DESTINATION, BASELINE), support);

		RtpSafetyResult result = VALIDATOR.validate(request(DESTINATION, BASELINE, snapshots)).join();

		assertFalse(result.safe());
		assertEquals(RtpSafetyResult.Code.UNSUPPORTED, result.code());
	}

	@Test
	public void disjointSupportShapesCoverTheWholeFootprintIndependentOfOrder()
	{
		RtpValidationRequest.CollisionBox left = new RtpValidationRequest.CollisionBox(0.0D, 0.0D, 0.0D, 0.5D, 1.0D, 1.0D);
		RtpValidationRequest.CollisionBox right = new RtpValidationRequest.CollisionBox(0.5D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
		RtpValidationRequest.BlockSnapshot ordered = RtpValidationRequest.BlockSnapshot.of(0, 63, 0,
				"minecraft:stone", false, false, List.of(left, right));
		RtpValidationRequest.BlockSnapshot reversed = RtpValidationRequest.BlockSnapshot.of(0, 63, 0,
				"minecraft:stone", false, false, List.of(right, left));

		RtpSafetyResult orderedResult = VALIDATOR.validate(request(DESTINATION, BASELINE,
				replaceBlock(safeSnapshots(DESTINATION, BASELINE), ordered))).join();
		RtpSafetyResult reversedResult = VALIDATOR.validate(request(DESTINATION, BASELINE,
				replaceBlock(safeSnapshots(DESTINATION, BASELINE), reversed))).join();

		assertEquals(RtpSafetyResult.Code.SAFE, orderedResult.code());
		assertEquals(orderedResult.code(), reversedResult.code());
	}

	@Test
	public void bodyCollisionAndHeadroomCollisionAreRejected()
	{
		RtpValidationRequest.BlockSnapshot body = RtpValidationRequest.BlockSnapshot.solid(0, 64, 0, "minecraft:stone");
		RtpValidationRequest.BlockSnapshot head = RtpValidationRequest.BlockSnapshot.solid(0, 65, 0, "minecraft:stone");

		RtpSafetyResult bodyResult = VALIDATOR.validate(request(DESTINATION, BASELINE,
				replaceBlock(safeSnapshots(DESTINATION, BASELINE), body))).join();
		RtpSafetyResult headResult = VALIDATOR.validate(request(DESTINATION, BASELINE,
				replaceBlock(safeSnapshots(DESTINATION, BASELINE), head))).join();

		assertEquals(RtpSafetyResult.Code.BODY_COLLISION, bodyResult.code());
		assertEquals(RtpSafetyResult.Code.BODY_COLLISION, headResult.code());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"minecraft:water",
			"minecraft:lava",
			"minecraft:bubble_column",
			"minecraft:fire",
			"minecraft:soul_fire",
			"minecraft:powder_snow",
			"minecraft:cactus",
			"minecraft:magma_block",
			"minecraft:campfire",
			"minecraft:soul_campfire",
			"minecraft:sweet_berry_bush",
			"minecraft:wither_rose",
			"minecraft:pointed_dripstone",
			"minecraft:cobweb",
			"minecraft:nether_portal",
			"minecraft:end_portal",
			"minecraft:end_gateway"
	})
	public void builtInLiquidsAndHazardsAreRejected(String materialKey)
	{
		boolean liquid = materialKey.equals("minecraft:water")
				|| materialKey.equals("minecraft:lava")
				|| materialKey.equals("minecraft:bubble_column");
		boolean active = materialKey.endsWith("campfire");
		RtpValidationRequest.BlockSnapshot hazard = RtpValidationRequest.BlockSnapshot.of(0, 64, 0,
				materialKey, liquid, active, List.of());
		List<RtpValidationRequest.RegionSnapshot> snapshots = replaceBlock(safeSnapshots(DESTINATION, BASELINE), hazard);

		RtpSafetyResult result = VALIDATOR.validate(request(DESTINATION, BASELINE, snapshots)).join();

		assertEquals(liquid ? RtpSafetyResult.Code.LIQUID : RtpSafetyResult.Code.HAZARD, result.code());
	}

	@Test
	public void liquidFlagRejectsWaterloggedBodySpace()
	{
		RtpValidationRequest.BlockSnapshot waterlogged = RtpValidationRequest.BlockSnapshot.of(0, 64, 0,
				"minecraft:oak_stairs", true, false, List.of());
		List<RtpValidationRequest.RegionSnapshot> snapshots = replaceBlock(safeSnapshots(DESTINATION, BASELINE), waterlogged);

		RtpSafetyResult result = VALIDATOR.validate(request(DESTINATION, BASELINE, snapshots)).join();

		assertEquals(RtpSafetyResult.Code.LIQUID, result.code());
	}

	@Test
	public void surfaceModeRejectsTreeSupportButAllowsClearGroundBeneathACanopy()
	{
		RtpValidationRequest.BlockSnapshot treeSupport = RtpValidationRequest.BlockSnapshot.of(
				0,
				63,
				0,
				"minecraft:oak_log",
				false,
				false,
				true,
				List.of(RtpValidationRequest.CollisionBox.fullBlock()));
		List<RtpValidationRequest.RegionSnapshot> treeSnapshots = replaceBlock(
				safeSnapshots(DESTINATION, BASELINE),
				treeSupport);
		RtpValidationRequest.BlockSnapshot highCanopy = RtpValidationRequest.BlockSnapshot.of(
				0,
				67,
				0,
				"minecraft:oak_leaves",
				false,
				false,
				true,
				List.of(RtpValidationRequest.CollisionBox.fullBlock()));
		RtpSafetyResult treeResult = VALIDATOR.validate(requestBuilder(DESTINATION, BASELINE, treeSnapshots)
				.surfaceMode(true)
				.build()).join();
		RtpSafetyResult groundResult = VALIDATOR.validate(requestBuilder(
				DESTINATION,
				BASELINE,
				replaceBlock(safeSnapshots(DESTINATION, BASELINE), highCanopy))
				.surfaceMode(true)
				.build()).join();

		assertEquals(RtpSafetyResult.Code.HAZARD, treeResult.code());
		assertEquals(RtpSafetyResult.Code.SAFE, groundResult.code());
	}

	@Test
	public void configuredHazardsAreRejectedWithoutBukkitMaterialAccess()
	{
		RtpValidationRequest.BlockSnapshot hazard = RtpValidationRequest.BlockSnapshot.air(0, 64, 0, "custom:unstable_crystal");
		List<RtpValidationRequest.RegionSnapshot> snapshots = replaceBlock(safeSnapshots(DESTINATION, BASELINE), hazard);
		RtpValidationRequest request = requestBuilder(DESTINATION, BASELINE, snapshots)
				.configuredHazards(Set.of("custom:unstable_crystal"))
				.build();

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.HAZARD, result.code());
	}

	@Test
	public void exactEpsilonControlsFullWorldBorderContainment()
	{
		double exactMaximum = 0.8D + RtpSafetyValidator.EPSILON;
		RtpValidationRequest.WorldBorder exact = new RtpValidationRequest.WorldBorder(-1.0D, -1.0D, exactMaximum, exactMaximum);
		RtpValidationRequest.WorldBorder narrow = new RtpValidationRequest.WorldBorder(-1.0D, -1.0D,
				exactMaximum - RtpSafetyValidator.EPSILON / 2.0D, exactMaximum);
		List<RtpValidationRequest.RegionSnapshot> snapshots = safeSnapshots(DESTINATION, BASELINE);

		RtpSafetyResult exactResult = VALIDATOR.validate(requestBuilder(DESTINATION, BASELINE, snapshots)
				.worldBorder(exact)
				.build()).join();
		RtpSafetyResult narrowResult = VALIDATOR.validate(requestBuilder(DESTINATION, BASELINE, snapshots)
				.worldBorder(narrow)
				.build()).join();

		assertEquals(RtpSafetyResult.Code.SAFE, exactResult.code());
		assertEquals(RtpSafetyResult.Code.WORLD_BORDER, narrowResult.code());
	}

	@Test
	public void fullEnvelopeMustFitInsideWorldHeight()
	{
		List<RtpValidationRequest.RegionSnapshot> snapshots = safeSnapshots(DESTINATION, BASELINE);
		RtpValidationRequest request = requestBuilder(DESTINATION, BASELINE, snapshots)
				.worldBounds(0, 65)
				.build();

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.WORLD_HEIGHT, result.code());
	}

	@Test
	public void netherCaveFloorPassesButRoofAndBedrockBandAreRejected()
	{
		RtpDestination caveDestination = new RtpDestination("minecraft:the_nether", 0, 120, 0, 1L, 0);
		RtpDestination bandDestination = new RtpDestination("minecraft:the_nether", 0, 123, 0, 1L, 1);
		RtpDestination roofDestination = new RtpDestination("minecraft:the_nether", 0, 127, 0, 1L, 2);
		List<RtpValidationRequest.RegionSnapshot> caveSnapshots = safeSnapshots(caveDestination, BASELINE);
		List<RtpValidationRequest.RegionSnapshot> bandSnapshots = safeSnapshots(bandDestination, BASELINE);
		List<RtpValidationRequest.RegionSnapshot> roofSnapshots = safeSnapshots(roofDestination, BASELINE);

		RtpSafetyResult caveResult = VALIDATOR.validate(requestBuilder(caveDestination, BASELINE, caveSnapshots)
				.dimension(RtpValidationRequest.Dimension.NETHER)
				.worldBounds(0, 256)
				.netherLogicalCeiling(128)
				.build()).join();
		RtpSafetyResult bandResult = VALIDATOR.validate(requestBuilder(bandDestination, BASELINE, bandSnapshots)
				.dimension(RtpValidationRequest.Dimension.NETHER)
				.worldBounds(0, 256)
				.netherLogicalCeiling(128)
				.build()).join();
		RtpSafetyResult roofResult = VALIDATOR.validate(requestBuilder(roofDestination, BASELINE, roofSnapshots)
				.dimension(RtpValidationRequest.Dimension.NETHER)
				.worldBounds(0, 256)
				.netherLogicalCeiling(128)
				.build()).join();

		assertEquals(RtpSafetyResult.Code.SAFE, caveResult.code());
		assertEquals(RtpSafetyResult.Code.NETHER_ROOF, bandResult.code());
		assertEquals(RtpSafetyResult.Code.NETHER_ROOF, roofResult.code());
	}

	@Test
	public void endVoidWithoutExistingSupportIsRejected()
	{
		RtpDestination destination = new RtpDestination("minecraft:the_end", 0, 64, 0, 1L, 0);
		RtpValidationRequest.BlockSnapshot voidSupport = RtpValidationRequest.BlockSnapshot.air(0, 63, 0);
		List<RtpValidationRequest.RegionSnapshot> snapshots = replaceBlock(safeSnapshots(destination, BASELINE), voidSupport);
		RtpValidationRequest request = requestBuilder(destination, BASELINE, snapshots)
				.dimension(RtpValidationRequest.Dimension.END)
				.build();

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.END_VOID, result.code());
	}

	@Test
	public void eightBlockEnvelopeIsAcceptedAtTheHardLimit()
	{
		RtpValidationRequest.EntityEnvelope maximum = new RtpValidationRequest.EntityEnvelope(-4.0D, 4.0D,
				-2.0D, 6.0D, -4.0D, 4.0D);
		RtpValidationRequest request = request(DESTINATION, maximum, safeSnapshots(DESTINATION, maximum));

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.SAFE, result.code());
	}

	@Test
	public void envelopesLargerThanEightBlocksAreRejected()
	{
		RtpValidationRequest.EntityEnvelope oversized = new RtpValidationRequest.EntityEnvelope(-4.000001D, 4.000001D,
				-0.25D, 1.75D, -0.3D, 0.3D);
		RtpValidationRequest request = request(DESTINATION, oversized, safeSnapshots(DESTINATION, oversized));

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.ENVELOPE_TOO_LARGE, result.code());
	}

	@Test
	public void fourChunkFourRegionEnvelopeIsAccepted()
	{
		RtpDestination destination = new RtpDestination("minecraft:overworld", 15, 64, 15, 1L, 0);
		RtpValidationRequest.EntityEnvelope envelope = new RtpValidationRequest.EntityEnvelope(-1.0D, 1.0D,
				0.0D, 1.8D, -1.0D, 1.0D);
		List<RtpValidationRequest.RegionSnapshot> snapshots = safeSnapshots(destination, envelope);

		RtpSafetyResult result = VALIDATOR.validate(request(destination, envelope, snapshots)).join();

		assertEquals(4, snapshots.size());
		assertEquals(RtpSafetyResult.Code.SAFE, result.code());
	}

	@Test
	public void moreThanFourSnapshotChunksAreRejected()
	{
		List<RtpValidationRequest.RegionSnapshot> snapshots = new ArrayList<RtpValidationRequest.RegionSnapshot>(safeSnapshots(DESTINATION, BASELINE));
		for(int chunkX = 1; chunkX <= 4; chunkX++)
		{
			snapshots.add(new RtpValidationRequest.RegionSnapshot("region:" + chunkX, chunkX, 0, List.of()));
		}

		RtpSafetyResult result = VALIDATOR.validate(request(DESTINATION, BASELINE, snapshots)).join();

		assertEquals(RtpSafetyResult.Code.TOO_MANY_CHUNKS, result.code());
	}

	@Test
	public void moreThanFourRegionOwnersAreRejected()
	{
		List<RtpValidationRequest.RegionSnapshot> snapshots = new ArrayList<RtpValidationRequest.RegionSnapshot>(safeSnapshots(DESTINATION, BASELINE));
		snapshots.add(new RtpValidationRequest.RegionSnapshot("region:1", 1, 0, List.of()));
		snapshots.add(new RtpValidationRequest.RegionSnapshot("region:2", 2, 0, List.of()));
		snapshots.add(new RtpValidationRequest.RegionSnapshot("region:3", 3, 0, List.of()));
		snapshots.add(new RtpValidationRequest.RegionSnapshot("region:4", 3, 0, List.of()));

		RtpSafetyResult result = VALIDATOR.validate(request(DESTINATION, BASELINE, snapshots)).join();

		assertEquals(RtpSafetyResult.Code.TOO_MANY_REGIONS, result.code());
	}

	@Test
	public void missingRegionOwnedSnapshotFailsClosed()
	{
		RtpSafetyResult result = VALIDATOR.validate(request(DESTINATION, BASELINE, List.of())).join();

		assertEquals(RtpSafetyResult.Code.MISSING_SNAPSHOT, result.code());
	}

	@Test
	public void validationDoesNotMutateSnapshotInputs()
	{
		List<RtpValidationRequest.RegionSnapshot> mutableSnapshots = new ArrayList<RtpValidationRequest.RegionSnapshot>(safeSnapshots(DESTINATION, BASELINE));
		RtpValidationRequest request = request(DESTINATION, BASELINE, mutableSnapshots);
		List<RtpValidationRequest.RegionSnapshot> expectedSnapshots = request.regionSnapshots();
		mutableSnapshots.clear();

		RtpSafetyResult result = VALIDATOR.validate(request).join();

		assertEquals(RtpSafetyResult.Code.SAFE, result.code());
		assertEquals(expectedSnapshots, request.regionSnapshots());
	}

	private static RtpValidationRequest request(RtpDestination destination, RtpValidationRequest.EntityEnvelope envelope,
			List<RtpValidationRequest.RegionSnapshot> snapshots)
	{
		return requestBuilder(destination, envelope, snapshots).build();
	}

	private static RtpValidationRequest.Builder requestBuilder(RtpDestination destination, RtpValidationRequest.EntityEnvelope envelope,
			List<RtpValidationRequest.RegionSnapshot> snapshots)
	{
		return RtpValidationRequest.builder(destination)
				.worldBounds(-64, 320)
				.worldBorder(WIDE_BORDER)
				.dimension(RtpValidationRequest.Dimension.OVERWORLD)
				.netherLogicalCeiling(128)
				.entityEnvelope(envelope)
				.regionSnapshots(snapshots);
	}

	private static List<RtpValidationRequest.RegionSnapshot> safeSnapshots(RtpDestination destination,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		double centerX = destination.blockX() + 0.5D;
		double centerZ = destination.blockZ() + 0.5D;
		double width = envelope.maximumXOffset() - envelope.minimumXOffset();
		double depth = envelope.maximumZOffset() - envelope.minimumZOffset();
		double height = envelope.maximumYOffset() - envelope.minimumYOffset();
		int minimumX = floor(centerX - width / 2.0D - RtpSafetyValidator.EPSILON);
		int maximumX = floor(centerX + width / 2.0D + RtpSafetyValidator.EPSILON);
		int minimumZ = floor(centerZ - depth / 2.0D - RtpSafetyValidator.EPSILON);
		int maximumZ = floor(centerZ + depth / 2.0D + RtpSafetyValidator.EPSILON);
		int maximumBodyY = floor(destination.feetY() + height + RtpSafetyValidator.EPSILON);
		Map<Chunk, List<RtpValidationRequest.BlockSnapshot>> blocksByChunk = new LinkedHashMap<Chunk, List<RtpValidationRequest.BlockSnapshot>>();

		for(int x = minimumX; x <= maximumX; x++)
		{
			for(int z = minimumZ; z <= maximumZ; z++)
			{
				add(blocksByChunk, RtpValidationRequest.BlockSnapshot.solid(x, destination.feetY() - 1, z, "minecraft:stone"));
				for(int y = destination.feetY(); y <= maximumBodyY; y++)
				{
					add(blocksByChunk, RtpValidationRequest.BlockSnapshot.air(x, y, z));
				}
			}
		}

		List<RtpValidationRequest.RegionSnapshot> snapshots = new ArrayList<RtpValidationRequest.RegionSnapshot>(blocksByChunk.size());
		for(Map.Entry<Chunk, List<RtpValidationRequest.BlockSnapshot>> entry : blocksByChunk.entrySet())
		{
			Chunk chunk = entry.getKey();
			snapshots.add(new RtpValidationRequest.RegionSnapshot("region:" + chunk.x() + ":" + chunk.z(),
					chunk.x(), chunk.z(), entry.getValue()));
		}
		return List.copyOf(snapshots);
	}

	private static void add(Map<Chunk, List<RtpValidationRequest.BlockSnapshot>> blocksByChunk,
			RtpValidationRequest.BlockSnapshot block)
	{
		Chunk chunk = new Chunk(Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
		blocksByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<RtpValidationRequest.BlockSnapshot>()).add(block);
	}

	private static List<RtpValidationRequest.RegionSnapshot> replaceBlock(List<RtpValidationRequest.RegionSnapshot> snapshots,
			RtpValidationRequest.BlockSnapshot replacement)
	{
		List<RtpValidationRequest.RegionSnapshot> replaced = new ArrayList<RtpValidationRequest.RegionSnapshot>(snapshots.size());
		for(RtpValidationRequest.RegionSnapshot snapshot : snapshots)
		{
			List<RtpValidationRequest.BlockSnapshot> blocks = new ArrayList<RtpValidationRequest.BlockSnapshot>(snapshot.blocks().size());
			for(RtpValidationRequest.BlockSnapshot block : snapshot.blocks())
			{
				if(block.x() == replacement.x() && block.y() == replacement.y() && block.z() == replacement.z())
				{
					blocks.add(replacement);
				}
				else
				{
					blocks.add(block);
				}
			}
			replaced.add(new RtpValidationRequest.RegionSnapshot(snapshot.regionKey(), snapshot.chunkX(), snapshot.chunkZ(), blocks));
		}
		return List.copyOf(replaced);
	}

	private static int floor(double value)
	{
		return (int) Math.floor(value);
	}

	private record Chunk(int x, int z)
	{
	}
}
