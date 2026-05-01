package art.arcane.wormholes.util;

import art.arcane.volmlib.util.collection.KList;

import java.util.Collection;

public abstract class GListAdapter<F, T> {
    public KList<T> adapt(Collection<F> source) {
        KList<T> out = new KList<>();
        if (source == null) {
            return out;
        }
        for (F item : source) {
            T mapped = onAdapt(item);
            if (mapped != null) {
                out.add(mapped);
            }
        }
        return out;
    }

    public abstract T onAdapt(F from);
}
