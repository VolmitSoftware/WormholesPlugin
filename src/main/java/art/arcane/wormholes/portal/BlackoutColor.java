package art.arcane.wormholes.portal;

import java.util.Locale;

public enum BlackoutColor
{
	WHITE,
	ORANGE,
	MAGENTA,
	LIGHT_BLUE,
	YELLOW,
	LIME,
	PINK,
	GRAY,
	LIGHT_GRAY,
	CYAN,
	PURPLE,
	BLUE,
	BROWN,
	GREEN,
	RED,
	BLACK;

	private static final BlackoutColor[] VALUES = values();

	public String materialName()
	{
		return name() + "_CONCRETE";
	}

	public String blockState()
	{
		return "minecraft:" + name().toLowerCase(Locale.ROOT) + "_concrete";
	}

	public String displayName()
	{
		String[] parts = name().split("_");
		StringBuilder builder = new StringBuilder();

		for(int i = 0; i < parts.length; i++)
		{
			if(i > 0)
			{
				builder.append(' ');
			}

			String part = parts[i];
			builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
			builder.append(part.substring(1).toLowerCase(Locale.ROOT));
		}

		builder.append(" Concrete");
		return builder.toString();
	}

	public BlackoutColor next()
	{
		return VALUES[(ordinal() + 1) % VALUES.length];
	}

	public static BlackoutColor fromName(String name, BlackoutColor fallback)
	{
		if(name == null)
		{
			return fallback;
		}

		String key = name.trim().toUpperCase(Locale.ROOT);

		for(BlackoutColor color : VALUES)
		{
			if(color.name().equals(key))
			{
				return color;
			}
		}

		return fallback;
	}
}
