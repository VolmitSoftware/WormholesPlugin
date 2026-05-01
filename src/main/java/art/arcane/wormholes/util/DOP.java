package art.arcane.wormholes.util;

import org.bukkit.util.Vector;

public abstract class DOP {
    private final String name;

    public DOP(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public abstract Vector op(Vector v);
}
