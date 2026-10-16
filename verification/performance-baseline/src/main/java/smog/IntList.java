package smog;

import java.util.Arrays;

/**
 * Reused primitive work buffer. Package-private, never exposed by the API.
 */
final class IntList {

    int[] data = new int[16];

    int size;

    void add(int v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, capacity(data.length, size + 1));
        }
        data[size++] = v;
    }

    int pop() {
        return data[--size];
    }

    void clear() {
        size = 0;
    }

    void sort() {
        Arrays.sort(data, 0, size);
    }

    static int capacity(int old, int required) {
        long result = Math.max(required, old + (long) old / 2 + 16);
        if (result > Integer.MAX_VALUE - 8) {
            if (required > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("Array too large");
            }
            return Integer.MAX_VALUE - 8;
        }
        return (int) result;
    }
}
