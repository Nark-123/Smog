package smog;

import java.util.Set;

/**
 * Owner-thread view shared by contact preparation, final validation and front updates.
 * CubeRecord/Cell objects are never removed from DimensionState; their mutable fields
 * must still be read at commit. A missing record is looked up again, never cached as ready.
 */
final class NeighborView {

    final CellKey[] keys = new CellKey[6];
    final CubeKey[] cubes = new CubeKey[6];
    final DimensionState.Geometry[] geometry = new DimensionState.Geometry[6];
    private final DimensionState.CubeRecord[] records = new DimensionState.CubeRecord[6];
    private final long[] tickets = new long[6];
    private final DimensionState state;
    long lookups;

    NeighborView(DimensionState state, CellKey key) {
        this.state = state;
        for (int d = 0; d < 6; d++) {
            keys[d] = CellBuild.neighbor(key, d);
            cubes[d] = keys[d].cube();
        }
    }

    DimensionState.Cell current(int d) {
        DimensionState.CubeRecord record = records[d];
        if (record == null) {
            records[d] = record = state.cubes.get(cubes[d]);
            lookups++;
        }
        return record == null ? null : record.cells[keys[d].slot()];
    }

    void bind(int d, DimensionState.Cell cell) {
        records[d] = cell.cube;
    }

    void capture(int d) {
        DimensionState.Cell c = current(d);
        geometry[d] = c == null ? null : c.geometry;
        tickets[d] = c == null ? -1 : c.ticket;
    }

    void capture() {
        for (int d = 0; d < 6; d++) capture(d);
    }

    boolean changed(int d) {
        DimensionState.Cell c = current(d);
        return (c == null ? null : c.geometry) != geometry[d] || (c == null ? -1 : c.ticket) != tickets[d];
    }

    void addFronts(Set<DimensionState.Cell> fronts) {
        for (int d = 0; d < 6; d++) {
            DimensionState.Cell c = current(d);
            if (c != null && c.geometry != null) fronts.add(c);
        }
    }
}
