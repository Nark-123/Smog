package smog;

/**
 * Geometry-only access. A target may be a dense reference field or one local Cell8.
 */
interface PartitionTarget {

    SmogConfig settings();

    boolean airAt(int x, int y, int z);

    int position(int x, int y, int z);

    int newNode(int seed);

    void setVolume(int id, int volume);

    void label(int p, int id, int distance);
}
