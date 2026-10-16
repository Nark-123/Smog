package smog;

/**
 * Immutable construction settings. No configuration lookup in the edge loop.
 */
public final class SmogConfig {

    public static final int COMPONENT = 0, CORE = 1, PATH = 2, THROAT = 3;

    public static final int LINEAR = 0, QUADRATIC_EXACT = 2, QUADRATIC_RATIONAL = 3, QUADRATIC_FAST = 4,
        QUADRATIC_LEGACY = 6;

    public static final int X_MINUS = 1, X_PLUS = 2, Y_MINUS = 4, Y_PLUS = 8, Z_MINUS = 16, Z_PLUS = 32;

    public static final int SIDES_AND_TOP = X_MINUS | X_PLUS | Y_PLUS | Z_MINUS | Z_PLUS;

    public static final int DEFAULT_CONTAINER_SIZE = 8;

    public static final int DEFAULT_RADIUS = 12;

    public static final double DEFAULT_DIFFUSION = 1e6;

    public static final double DEFAULT_TIME_STEP = 1.0;

    public static final int DEFAULT_CYCLE_PERIOD = 97;

    public static final int DEFAULT_CYCLE_ON = 53;

    public static final int DEFAULT_CYCLE_STEPS = 5;

    public final int containerSize, mode, radius, phase, openMask, law;

    public final double diffusion, timeStep, maxMixing, maxDiffusivity;

    public final int cyclePeriod, cycleOn, cycleSteps;

    public final boolean narrowPathRefinement;

    private SmogConfig(Builder b) {
        if (b.containerSize < 1 || b.containerSize > 16
            || b.radius < 1
            || b.radius > 255
            || b.phase < 0
            || b.phase >= b.containerSize
            || b.mode < COMPONENT
            || b.mode > THROAT
            || (b.openMask & ~63) != 0) {
            throw new IllegalArgumentException("Invalid partition or boundary settings");
        }
        checkLaw(b.law);
        positiveFinite(b.diffusion, "diffusion");
        positiveFinite(b.timeStep, "timeStep");
        positiveFinite(b.maxDiffusivity, "maxDiffusivity");
        if (!(b.maxMixing > 0 && b.maxMixing <= .5)) {
            throw new IllegalArgumentException("maxMixing must be in (0, 0.5]");
        }
        if (b.cyclePeriod < 1 || b.cyclePeriod > Integer.MAX_VALUE / 2
            || b.cycleOn < 0
            || b.cycleOn > b.cyclePeriod
            || b.cycleSteps < 1) {
            throw new IllegalArgumentException("Invalid source cycle");
        }
        containerSize = b.containerSize;
        mode = b.mode;
        radius = b.radius;
        phase = b.phase;
        openMask = b.openMask;
        law = b.law;
        diffusion = b.diffusion;
        timeStep = b.timeStep;
        maxMixing = b.maxMixing;
        maxDiffusivity = b.maxDiffusivity;
        narrowPathRefinement = b.narrowPathRefinement;
        cyclePeriod = b.cyclePeriod;
        cycleOn = b.cycleOn;
        cycleSteps = b.cycleSteps;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SmogConfig throat8() {
        return builder().build();
    }

    static void checkLaw(int law) {
        if (law != LINEAR && law != QUADRATIC_EXACT
            && law != QUADRATIC_RATIONAL
            && law != QUADRATIC_FAST
            && law != QUADRATIC_LEGACY) {
            throw new IllegalArgumentException("Supported laws: 0, 2, 3, 4; legacy replay: 6");
        }
    }

    static void positiveFinite(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }

    public static final class Builder {

        private boolean narrowPathRefinement = true;

        private int containerSize = DEFAULT_CONTAINER_SIZE, mode = THROAT;

        private int radius = DEFAULT_RADIUS, phase, openMask, law = QUADRATIC_FAST;

        private double diffusion = DEFAULT_DIFFUSION, timeStep = DEFAULT_TIME_STEP;

        private double maxMixing = .5;

        private double maxDiffusivity = 64;

        private int cyclePeriod = DEFAULT_CYCLE_PERIOD, cycleOn = DEFAULT_CYCLE_ON;

        private int cycleSteps = DEFAULT_CYCLE_STEPS;

        /**
         * Disable only for legacy geometry replay or controlled ablation.
         */
        public Builder narrowPathRefinement(boolean v) {
            narrowPathRefinement = v;
            return this;
        }

        public Builder containerSize(int v) {
            containerSize = v;
            return this;
        }

        public Builder mode(int v) {
            mode = v;
            return this;
        }

        public Builder radius(int v) {
            radius = v;
            return this;
        }

        public Builder phase(int v) {
            phase = v;
            return this;
        }

        public Builder openMask(int v) {
            openMask = v;
            return this;
        }

        public Builder law(int v) {
            law = v;
            return this;
        }

        public Builder diffusion(double v) {
            diffusion = v;
            return this;
        }

        public Builder timeStep(double v) {
            timeStep = v;
            return this;
        }

        /**
         * Maximum fraction of a node's mass that can leave in the balanced exchange.
         */
        public Builder maxMixing(double v) {
            maxMixing = v;
            return this;
        }

        /**
         * Saturation of beta*(ci+cj), preserving conductance differences at high concentrations.
         */
        public Builder maxDiffusivity(double v) {
            maxDiffusivity = v;
            return this;
        }

        public Builder cycle(int period, int onPhases, int stepsPerPhase) {
            cyclePeriod = period;
            cycleOn = onPhases;
            cycleSteps = stepsPerPhase;
            return this;
        }

        public SmogConfig build() {
            return new SmogConfig(this);
        }
    }
}
