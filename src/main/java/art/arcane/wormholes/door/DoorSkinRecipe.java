package art.arcane.wormholes.door;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.bukkit.Material;

final class DoorSkinRecipe
{
	private DoorSkinRecipe()
	{
	}

	static Optional<Result> resolve(List<Ingredient> ingredients)
	{
		return resolve(ingredients, DoorSkin::isPlayerOperable);
	}

	static Optional<Result> resolve(List<Ingredient> ingredients, Predicate<Material> supportedSkin)
	{
		Objects.requireNonNull(ingredients, "ingredients");
		Objects.requireNonNull(supportedSkin, "supportedSkin");
		if(ingredients.size() != 2)
		{
			return Optional.empty();
		}

		Ingredient first = Objects.requireNonNull(ingredients.get(0), "ingredient");
		Ingredient second = Objects.requireNonNull(ingredients.get(1), "ingredient");
		Ingredient source;
		Ingredient target;
		if(first.identity() != null && second.identity() == null)
		{
			source = first;
			target = second;
		}
		else if(first.identity() == null && second.identity() != null)
		{
			source = second;
			target = first;
		}
		else
		{
			return Optional.empty();
		}

		if(!DoorSkin.isDoor(source.material())
			|| !supportedSkin.test(target.material())
			|| source.material() == target.material())
		{
			return Optional.empty();
		}
		return Optional.of(new Result(source.identity(), target.material()));
	}

	record Ingredient(Material material, DoorItemIdentity identity)
	{
		Ingredient
		{
			Objects.requireNonNull(material, "material");
		}
	}

	record Result(DoorItemIdentity identity, Material material)
	{
		Result
		{
			Objects.requireNonNull(identity, "identity");
			Objects.requireNonNull(material, "material");
		}
	}
}
