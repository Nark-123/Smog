package smog;

/** Private preparation on the simulation owner thread; -1 wait, 0 done, 1 bounded progress. */
abstract class RestorePlan {
    abstract boolean runnable();
    abstract int advance();
}
