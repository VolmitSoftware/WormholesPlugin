package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

public final class WorldPairingTest
{
	@Test
	public void overworldToNetherDividesByEight()
	{
		assertEquals(0, WorldPairing.overworldToNether(0));
		assertEquals(1, WorldPairing.overworldToNether(8));
		assertEquals(125, WorldPairing.overworldToNether(1000));
		assertEquals(12, WorldPairing.overworldToNether(100));
	}

	@Test
	public void overworldToNetherFloorsNegativeCoordinates()
	{
		assertEquals(-1, WorldPairing.overworldToNether(-1));
		assertEquals(-1, WorldPairing.overworldToNether(-8));
		assertEquals(-2, WorldPairing.overworldToNether(-9));
		assertEquals(-13, WorldPairing.overworldToNether(-100));
	}

	@Test
	public void netherToOverworldMultipliesByEight()
	{
		assertEquals(0, WorldPairing.netherToOverworld(0));
		assertEquals(64, WorldPairing.netherToOverworld(8));
		assertEquals(-800, WorldPairing.netherToOverworld(-100));
	}

	@Test
	public void roundTripFromNetherStaysWithinChunkScale()
	{
		int nether = WorldPairing.overworldToNether(1000);
		int back = WorldPairing.netherToOverworld(nether);
		assertEquals(1000, back);
	}

	@Test
	public void builtInDimensionsPairByCanonicalKeys()
	{
		assertEquals(NamespacedKey.minecraft("the_nether"),
			WorldPairing.pairedNetherKey(NamespacedKey.minecraft("overworld")));
		assertEquals(NamespacedKey.minecraft("the_end"),
			WorldPairing.pairedEndKey(NamespacedKey.minecraft("overworld")));
		assertEquals(NamespacedKey.minecraft("overworld"),
			WorldPairing.pairedOverworldKey(NamespacedKey.minecraft("the_nether")));
	}

	@Test
	public void customDimensionsKeepTheirNamespaceWhenPaired()
	{
		NamespacedKey overworld = new NamespacedKey("iris", "floating_islands");
		assertEquals(new NamespacedKey("iris", "floating_islands_nether"),
			WorldPairing.pairedNetherKey(overworld));
		assertEquals(overworld,
			WorldPairing.pairedOverworldKey(new NamespacedKey("iris", "floating_islands_the_end")));
	}

	@Test
	public void customOverworldFallsBackToTheCanonicalNether()
	{
		NamespacedKey overworld = new NamespacedKey("iris", "generated_world");
		assertEquals(List.of(new NamespacedKey("iris", "generated_world_nether"), NamespacedKey.minecraft("the_nether")),
			WorldPairing.pairedNetherKeys(overworld));
		assertEquals(List.of(NamespacedKey.minecraft("the_nether")),
			WorldPairing.pairedNetherKeys(NamespacedKey.minecraft("overworld")));
	}
}
