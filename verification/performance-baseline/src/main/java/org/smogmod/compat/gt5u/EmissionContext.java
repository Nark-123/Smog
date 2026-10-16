package org.smogmod.compat.gt5u;

import net.minecraft.world.World;

/**
 * Exception-safe nested context for a source whose original call only carries a Chunk.
 */
public final class EmissionContext implements AutoCloseable {

    private static final ThreadLocal<EmissionContext> CURRENT = new ThreadLocal<>();

    public final World world;

    public final int x, y, z;

    private final EmissionContext previous;

    public EmissionContext(World world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static EmissionContext current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
