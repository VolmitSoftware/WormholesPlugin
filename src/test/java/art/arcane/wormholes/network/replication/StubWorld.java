package art.arcane.wormholes.network.replication;

import org.bukkit.World;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

public final class StubWorld {
    private StubWorld() {
    }

    public static World create(UUID uid) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getUID".equals(name)) {
                    return uid;
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return uid.hashCode();
                }
                if ("toString".equals(name)) {
                    return "StubWorld[" + uid + "]";
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return Boolean.FALSE;
                }
                if (returnType == int.class || returnType == long.class
                    || returnType == short.class || returnType == byte.class
                    || returnType == float.class || returnType == double.class) {
                    return 0;
                }
                return null;
            });
    }
}
