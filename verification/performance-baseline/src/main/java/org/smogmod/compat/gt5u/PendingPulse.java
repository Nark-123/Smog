package org.smogmod.compat.gt5u;

import smog.BlockPos;

/**
 * A one-shot event, never a permanent source rate.
 */
public final class PendingPulse {

    public final BlockPos position;

    public final long amount;

    public final boolean chunkOnly;

    public final boolean adjacent;

    public PendingPulse(BlockPos position, long amount, boolean chunkOnly, boolean adjacent) {
        this.position = position;
        this.amount = amount;
        this.chunkOnly = chunkOnly;
        this.adjacent = adjacent;
    }
}
