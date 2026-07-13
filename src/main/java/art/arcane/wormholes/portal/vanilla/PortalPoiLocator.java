package art.arcane.wormholes.portal.vanilla;

import org.bukkit.Location;
import org.bukkit.World;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class PortalPoiLocator {
    private static final Lookup LOOKUP = resolve();

    private PortalPoiLocator() {
    }

    static Location locateNearestNetherPortal(World world, Location origin, int radius) {
        if (LOOKUP == null) {
            return null;
        }
        try {
            return (Location) LOOKUP.method().invoke(world, origin, LOOKUP.netherPortalType(), radius);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("Paper portal POI lookup failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Paper portal POI lookup is unavailable", exception);
        }
    }

    private static Lookup resolve() {
        try {
            Class<?> poiType = Class.forName("io.papermc.paper.entity.poi.PoiType");
            Class<?> poiTypes = Class.forName("io.papermc.paper.entity.poi.PoiTypes");
            Field netherPortal = poiTypes.getField("NETHER_PORTAL");
            Method method = World.class.getMethod("locateNearestPoi", Location.class, poiType, int.class);
            return new Lookup(method, netherPortal.get(null));
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private record Lookup(Method method, Object netherPortalType) {
    }
}
