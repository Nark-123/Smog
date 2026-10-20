package smog;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Immutable, mass-free local geometry. Wire formats 1/2; semantic world-rule version 1. */
final class GeometryPack {

    static final int VERSION = 2;
    private static final ThreadLocal<byte[]> DISTANCE_SCRATCH = ThreadLocal.withInitial(() -> new byte[512]);
    final CellKey key;
    final VoxelLabels labels;
    final int[] volumes, seeds, contacts;
    final VoxelDistance distance;
    final double[] boundary;
    final CubeKey[] dependencies;
    final byte[][] fingerprints;

    GeometryPack(CellKey key, DimensionState.Geometry g, int[] seeds, double[] boundary, int[] contacts,
        ImmutableCubeSnapshot[] snapshots) {
        this.key = key;
        labels = g.labels;
        distance = g.distance;
        volumes = g.volumes.clone();
        this.seeds = Arrays.copyOf(seeds, volumes.length);
        this.boundary = Arrays.copyOf(boundary, volumes.length);
        this.contacts = contacts.clone();
        dependencies = g.dependencies.clone();
        fingerprints = new byte[dependencies.length][];
        for (int i = 0; i < dependencies.length; i++) fingerprints[i] = snapshots[i] == null ? null
            : snapshots[i].fingerprint()
                .clone();
    }

    private GeometryPack(CellKey key, VoxelLabels labels, VoxelDistance distance, int[] volumes, int[] seeds,
        double[] boundary, int[] contacts, CubeKey[] dependencies, byte[][] fingerprints) {
        this.key = key;
        this.labels = labels;
        this.distance = distance;
        this.volumes = volumes;
        this.seeds = seeds;
        this.boundary = boundary;
        this.contacts = contacts;
        this.dependencies = dependencies;
        this.fingerprints = fingerprints;
    }

    static byte[] configFingerprint(SmogConfig c) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); // Semantic algorithm version is independent of wire encoding.
            out.writeUTF("smog-semantic-air-rules-1");
            Checkpoint.config(out, c);
            out.flush();
            return RecordCodec.digest()
                .digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    void write(DataOutput out) throws IOException {
        write(out, 1);
    }

    void write(DataOutput out, int version) throws IOException {
        if (version != 1 && version != 2) throw new IOException("Unsupported pack version");
        out.writeInt(key.x);
        out.writeInt(key.y);
        out.writeInt(key.z);
        out.writeShort(volumes.length);
        if (version == 1) {
            for (int p = 0; p < 512; p++) out.writeShort(labels.get(p));
            for (int p = 0; p < 512; p++) out.writeByte(distance.get(p));
        } else {
            labels.writeAdaptive(out, volumes.length);
            distance.writeAdaptive(out);
        }
        for (int i = 0; i < volumes.length; i++) {
            out.writeShort(volumes[i]);
            out.writeShort(seeds[i]);
            out.writeDouble(boundary[i]);
        }
        out.writeInt(contacts.length / 4);
        for (int c : contacts) out.writeInt(c);
        out.writeByte(dependencies.length);
        for (int i = 0; i < dependencies.length; i++) {
            CubeKey k = dependencies[i];
            out.writeInt(k.x);
            out.writeInt(k.y);
            out.writeInt(k.z);
            out.writeBoolean(fingerprints[i] != null);
            if (fingerprints[i] != null) out.write(fingerprints[i]);
        }
    }

    static GeometryPack read(DataInput in, int dimension) throws IOException {
        return read(in, dimension, 1);
    }

    static GeometryPack read(DataInput in, int dimension, int version) throws IOException {
        if (version != 1 && version != 2) throw new IOException("Unsupported pack version");
        CellKey key = new CellKey(dimension, in.readInt(), in.readInt(), in.readInt());
        key.block(511);
        int n = in.readUnsignedShort();
        if (n > 512) throw new IOException("Invalid cache nodes");
        int[] counts = new int[n], volumes = new int[n], seeds = new int[n];
        byte[] distance = DISTANCE_SCRATCH.get();
        double[] boundary = new double[n];
        VoxelLabels labels = version == 1 ? VoxelLabels.read(in, n, counts) : VoxelLabels.readAdaptive(in, n, counts);
        VoxelDistance distances;
        if (version == 1) {
            in.readFully(distance);
            distances = VoxelDistance.of(distance);
        } else distances = VoxelDistance.readAdaptive(in, distance);
        for (int i = 0; i < n; i++) {
            volumes[i] = in.readUnsignedShort();
            seeds[i] = in.readUnsignedShort();
            boundary[i] = in.readDouble();
            if (volumes[i] < 1 || volumes[i] != counts[i]
                || seeds[i] >= 512
                || labels.get(seeds[i]) != i
                || !Double.isFinite(boundary[i])
                || boundary[i] < 0
                || boundary[i] > 6144) throw new IOException("Invalid cache node geometry");
        }
        int edges = in.readInt();
        if (edges < 0 || edges > 1344) throw new IOException("Cache contact count");
        int[] contacts = new int[edges * 4];
        Set<Long> seen = edges == 0 ? java.util.Collections.emptySet() : new HashSet<>();
        int totalArea = 0;
        for (int i = 0; i < contacts.length; i += 4) {
            for (int j = 0; j < 4; j++) contacts[i + j] = in.readInt();
            int a = contacts[i], b = contacts[i + 1], area = contacts[i + 2], dist = contacts[i + 3];
            if (a < 0 || a >= b
                || b >= n
                || area < 1
                || area > 1344
                || dist < 1
                || dist > 511
                || !seen.add(((long) a << 32) | b)) throw new IOException("Invalid cache contact");
            totalArea += area;
            if (totalArea > 1344) throw new IOException("Cache contact area exceeds Cell8 faces");
        }
        int deps = in.readUnsignedByte();
        if (deps < 1 || deps > 8) throw new IOException("Cache dependency count");
        CubeKey[] keys = new CubeKey[deps];
        byte[][] hashes = new byte[deps][];
        int occupied = 0;
        BlockPos origin = key.block(0);
        CubeKey self = key.cube();
        CubeKey min = origin.offset(-2, -2, -2)
            .cube(),
            max = origin.offset(9, 9, 9)
                .cube();
        int expected = (max.x - min.x + 1) * (max.y - min.y + 1) * (max.z - min.z + 1);
        if (expected != deps) throw new IOException("Incomplete halo");
        for (int i = 0; i < deps; i++) {
            keys[i] = new CubeKey(dimension, in.readInt(), in.readInt(), in.readInt());
            CubeKey k = keys[i];
            if (k.x < min.x || k.x > max.x || k.y < min.y || k.y > max.y || k.z < min.z || k.z > max.z)
                throw new IOException("Cache halo coordinates");
            int slot = k.x - min.x + (max.x - min.x + 1) * (k.y - min.y + (max.y - min.y + 1) * (k.z - min.z));
            if ((occupied & (1 << slot)) != 0) throw new IOException("Cache duplicate halo");
            occupied |= 1 << slot;
            if (in.readBoolean()) {
                hashes[i] = new byte[32];
                in.readFully(hashes[i]);
            }
            if (k.equals(self) && hashes[i] == null) throw new IOException("Missing self fingerprint");
        }
        return new GeometryPack(key, labels, distances, volumes, seeds, boundary, contacts, keys, hashes);
    }

    long bytes() {
        return 256L + labels.bytes()
            + distance.bytes()
            + 16L * volumes.length
            + 4L * contacts.length
            + 96L * dependencies.length;
    }
}
