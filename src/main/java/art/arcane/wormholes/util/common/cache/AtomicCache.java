package art.arcane.wormholes.util.common.cache;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class AtomicCache<T> {
    private final AtomicReference<T> ref = new AtomicReference<>();

    public AtomicCache() {
    }

    public T aquire(Supplier<T> supplier) {
        T value = ref.get();
        if (value != null) {
            return value;
        }

        T computed = supplier.get();
        if (ref.compareAndSet(null, computed)) {
            return computed;
        }

        T existing = ref.get();
        return existing != null ? existing : computed;
    }

    public void invalidate() {
        ref.set(null);
    }

    public T peek() {
        return ref.get();
    }
}
