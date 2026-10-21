package smog;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic iteration with no backing tree for the usual empty Cell8 maps. */
final class LazyIntMap<V> extends AbstractMap<Integer, V> {

    private TreeMap<Integer, V> values;

    @Override
    public V put(Integer key, V value) {
        if (values == null) values = new TreeMap<>();
        return values.put(key, value);
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends V> source) {
        if (!source.isEmpty()) {
            if (values == null) values = new TreeMap<>();
            values.putAll(source);
        }
    }

    @Override
    public V get(Object key) {
        return values == null ? null : values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return values != null && values.containsKey(key);
    }

    @Override
    public int size() {
        return values == null ? 0 : values.size();
    }

    @Override
    public void clear() {
        values = null;
    }

    @Override
    public Set<Entry<Integer, V>> entrySet() {
        return values == null ? Collections.<Entry<Integer, V>>emptySet() : values.entrySet();
    }
}
