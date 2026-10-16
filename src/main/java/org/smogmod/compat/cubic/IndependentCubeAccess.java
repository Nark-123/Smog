package org.smogmod.compat.cubic;

import smog.CubeKey;
import smog.WorldSnapshotProvider;

/**
 * Extension point only. No CubicChunks game API is linked or claimed as supported.
 */
public interface IndependentCubeAccess extends WorldSnapshotProvider {

    long epoch(CubeKey cube);

    long revision(CubeKey cube);
}
