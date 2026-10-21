package smog;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Single-threaded scalar diffusion graph. Coordinates: x fastest, then y, then z.
 * Owns its input arrays. Use one owning simulation thread; queries also require
 * external synchronization if another thread is advancing or editing the graph.
 *
 * Hot state is interleaved [mass, inverseVolume] in one double[]. Edges and
 * adjacency use primitive arrays. Steady steps reuse pooled interval buffers;
 * samples and rate changes allocate nothing.
 */
public final class SmogGraph implements PartitionTarget {

    public final SmogConfig config;

    public final int nx, ny, nz;

    final byte[] air, distance;

    final int[] label;

    private final int plane, containersX, containersY, containerCount;

    private final Partitioner partitioner;

    private final TrappedMass trapped = new TrappedMass();

    private final IntList freeNodes = new IntList(), freeEdges = new IntList();

    private final IntList sinkNodes = new IntList();

    int[] volume = new int[16];

    private int[] seed = new int[16], adjacency = filled(16, -1), sinkSlot = filled(16, -1);

    private double[] state = new double[32], boundary = new double[16], sinkFactor = new double[16];

    private int nodeSlots, liveNodes;

    // Half-edge 2*e belongs to endpoint A, 2*e+1 to endpoint B.
    // These intrusive lists make removing an edge O(1), without per-node objects.
    private int[] endpoints = new int[32], next = new int[32], previous = new int[32];

    private double[] conductance = new double[16], coefficient = new double[16];

    private int edgeSlots, liveEdges;

    private final IntList activeEdges = new IntList();

    private int[] edgeActiveSlot = new int[16];

    private int[] sourcePositions = new int[0], sourceNodes = new int[0], sourcePhases = new int[0];

    private double[] sourceRates = new double[0], sourceHalfMass = new double[0];

    private int law;

    private double diffusion, timeStep, escaped, injected;

    private long iteration;

    private final double buildMillis;

    private PatchWorkspace patchWork;

    private int partIndex, partCount;

    // Immutable solver view. The solver walks activeEdges, never mutable adjacency.
    private int stepNodeLimit, stepActiveEdgeLimit, stepEdgeSlotLimit, stepSinkLimit;
    private long solverEpoch;
    private boolean appending;
    private int appendNodeStart, appendNodeEnd, appendEdgeEnd;

    private boolean partCycling;

    // A partial step is a transaction. state is the private candidate; workState
    // holds the last committed state until the candidate is published or discarded.
    private double[] workState = new double[0], nodeLoad = new double[0], delta = new double[0],
        mobility = new double[0];

    private double savedInjected, savedEscaped, savedBlocked, blocked;

    private final PendingChanges queuedChanges = new PendingChanges(), patchInput = new PendingChanges();

    private int[] editPositions = new int[16], queuePositions = new int[16];

    private byte[] editValues = new byte[16], queueValues = new byte[16];

    private int[] sourceHead, sourceNext = new int[0];

    private long lastReboundSources;

    // Wall/game time belongs to API calls, not to an abortable transport candidate.
    private double gameTime, modelTime, emissionBaseTime;

    private long gameTicks, catchUpSteps, cancelledCandidates;

    private int scheduleParts;

    private boolean scheduleCycling, budgetDirty, budgetMode;

    private double[] pendingAccepted = new double[0], pendingRejected = new double[0], sourceStamp = new double[0];

    private double candidateRejected;

    // Completed emission intervals are immutable until transport publishes them.
    private static final class EmissionFrame {

        final double[] accepted;

        double rejected, total;

        EmissionFrame(int count) {
            accepted = new double[count];
        }
    }

    private final ArrayDeque<EmissionFrame> emissionFrames = new ArrayDeque<>();

    private final ArrayDeque<EmissionFrame> spareFrames = new ArrayDeque<>();

    private int windowTick;

    private long emissionWindows;

    private double queuedEmissionMass;

    private boolean[] frozen = new boolean[16], touchedBoxes, sourceBoxPending;

    private int[] queuedPerBox;

    private final IntList touched = new IntList(), frozenNodes = new IntList();

    private double[] firstSinkLoss = new double[16];

    private int fluxDone;

    private boolean[] sourceBlocked = new boolean[0], deferredSource = new boolean[0];

    private double[] eventAdds = new double[16], eventRemoves = new double[16];

    private boolean[] eventMarked = new boolean[16];

    private final IntList eventNodes = new IntList();

    private double pendingAdded, actuallyRemoved;

    private boolean sourceTrapsPending;

    private long rebuildCount;

    public long rebuildCount() {
        return rebuildCount;
    }

    public double pendingAddedMass() {
        return pendingAdded;
    }

    public double removedMass() {
        return actuallyRemoved;
    }

    public int pendingIntervals() {
        return emissionFrames.size();
    }

    private int lastAppliedChanges;

    public double gameTime() {
        return gameTime;
    }

    public double modelTime() {
        return modelTime;
    }

    public double modelLag() {
        return Math.max(0, gameTime - modelTime);
    }

    public long gameTicks() {
        return gameTicks;
    }

    public long catchUpSteps() {
        return catchUpSteps;
    }

    public long cancelledCandidates() {
        return cancelledCandidates;
    }

    public long oldestPatchAgeTicks() {
        return queuedChanges.oldestAge(gameTicks);
    }

    public int overdueChanges(long ageTicks) {
        if (ageTicks < 0) {
            throw new IllegalArgumentException("Negative age");
        }
        return queuedChanges.overdue(gameTicks, ageTicks);
    }

    public int lastAppliedChanges() {
        return lastAppliedChanges;
    }

    public String progressReason() {
        return queuedChanges.size != 0 ? "LOCAL_GEOMETRY_QUARANTINE"
            : partCount != 0 ? "TRANSPORT_IN_PROGRESS"
                : !emissionFrames.isEmpty() ? "COMPLETED_INTERVAL_PENDING"
                    : windowTick != 0 ? "COLLECTING_INTERVAL" : "IDLE";
    }

    public double pendingEmissionMass() {
        double total = queuedEmissionMass;
        for (int i = 0; i < sourceRates.length; i++) {
            total += pendingAccepted[i] + pendingRejected[i] + unaccounted(i);
        }
        return total;
    }

    public double generatedEmissionMass() {
        return injectedMass() + blockedEmissionMass() + pendingEmissionMass() + pendingAdded;
    }

    public static final int SOURCE_ACTIVE = 0, SOURCE_DISABLED = 1, SOURCE_BLOCKED = 2;

    public SmogGraph(byte[] air, int nx, int ny, int nz) {
        this(air, nx, ny, nz, SmogConfig.throat8());
    }

    public SmogGraph(byte[] input, int nx, int ny, int nz, SmogConfig config) {
        long start = System.nanoTime();
        Objects.requireNonNull(input, "air");
        this.config = Objects.requireNonNull(config, "config");
        if (nx < 1 || ny < 1
            || nz < 1
            || nx > Integer.MAX_VALUE - 32
            || ny > Integer.MAX_VALUE - 32
            || nz > Integer.MAX_VALUE - 32
            || (long) nx * ny > Integer.MAX_VALUE - 8
            || (long) nx * ny * nz > Integer.MAX_VALUE - 8
            || (long) nx * ny * nz != input.length) {
            throw new IllegalArgumentException("Positive dimensions must match air.length and fit an int index");
        }
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        plane = nx * ny;
        this.air = input.clone();
        distance = new byte[input.length];
        label = filled(input.length, -1);
        int h = config.containerSize, phase = config.phase;
        containersX = (nx + phase + h - 1) / h;
        containersY = (ny + phase + h - 1) / h;
        long containers = (long) containersX * containersY * ((nz + phase + h - 1) / h);
        if (containers > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Too many containers");
        }
        containerCount = (int) containers;
        sourceHead = filled(containerCount, -1);
        queuedPerBox = new int[containerCount];
        touchedBoxes = new boolean[containerCount];
        sourceBoxPending = new boolean[containerCount];
        law = config.law;
        diffusion = config.diffusion;
        timeStep = config.timeStep;
        partitioner = new Partitioner(this);
        for (int z = -phase; z < nz; z += h) {
            for (int y = -phase; y < ny; y += h) {
                for (int x = -phase; x < nx; x += h) {
                    partitioner.partition(x, y, z);
                }
            }
        }
        EdgeAccumulator aggregate = new EdgeAccumulator();
        for (int z = 0, p = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++, p++) {
                    if (label[p] < 0) {
                        continue;
                    }
                    if (x + 1 < nx) {
                        contact(p, p + 1, aggregate);
                    }
                    if (y + 1 < ny) {
                        contact(p, p + nx, aggregate);
                    }
                    if (z + 1 < nz) {
                        contact(p, p + plane, aggregate);
                    }
                    if (config.openMask != 0) {
                        boundary[label[p]] += boundaryAt(p, x, y, z);
                    }
                }
            }
        }
        aggregate.appendTo(this);
        for (int id = 0; id < nodeSlots; id++) {
            refreshSink(id);
        }
        ensureStepBuffers();
        patchWork = new PatchWorkspace(volume.length, containerCount);
        buildMillis = (System.nanoTime() - start) * 1e-6;
    }

    /**
     * Empty physical graph used by DimensionState; no dense world allocation.
     */
    SmogGraph(SmogConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        nx = ny = nz = plane = containersX = containersY = containerCount = 0;
        air = distance = new byte[0];
        label = new int[0];
        sourceHead = queuedPerBox = new int[0];
        touchedBoxes = sourceBoxPending = new boolean[0];
        law = config.law;
        diffusion = config.diffusion;
        timeStep = config.timeStep;
        partitioner = null;
        buildMillis = 0;
    }

    public SmogConfig settings() {
        return config;
    }

    public int position(int x, int y, int z) {
        return uncheckedIndex(x, y, z);
    }

    public void label(int p, int id, int d) {
        label[p] = id;
        distance[p] = (byte) d;
    }

    // DimensionState owns spatial events; these hooks retain the exact solver.
    void sparseSources(int[] nodes, double[] amounts) {
        requireIdle();
        sourceNodes = nodes;
        if (sourceHalfMass.length != amounts.length) {
            sourceHalfMass = new double[amounts.length];
            deferredSource = new boolean[amounts.length];
        }
        for (int i = 0; i < nodes.length; i++) {
            sourceHalfMass[i] = amounts[i] * .5;
            deferredSource[i] = nodes[i] < 0;
        }
        budgetMode = true;
    }

    boolean sparsePart(int parts) {
        return computePart(parts, false);
    }

    void sparseChanges(IntList result, boolean[] watch) {
        for (int id = 0; id < nodeSlots; id++) {
            if (watch[id] && volume[id] != 0 && state[2 * id] > 0 && state[2 * id] != workState[2 * id]) {
                result.add(id);
            }
        }
    }

    double nodeMass(int id) {
        return visibleState()[2 * id];
    }

    void addNodeMass(int id, double amount) {
        requireMutableNode(id);
        state[2 * id] += amount;
        if (partCount != 0) workState[2 * id] += amount;
    }

    void sparseBoundary(int id, double value) {
        requireMutableNode(id);
        boundary[id] = value;
        refreshSink(id);
    }

    void sparseClocks(long step, double loss) {
        iteration = step;
        escaped = loss;
    }

    public int index(int x, int y, int z) {
        if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) {
            throw new IndexOutOfBoundsException("Coordinates outside the world");
        }
        return uncheckedIndex(x, y, z);
    }

    int uncheckedIndex(int x, int y, int z) {
        return (z * ny + y) * nx + x;
    }

    public boolean airAt(int x, int y, int z) {
        return x >= 0 && x < nx && y >= 0 && y < ny && z >= 0 && z < nz && air[uncheckedIndex(x, y, z)] != 0;
    }

    public int law() {
        return law;
    }

    public double diffusion() {
        return diffusion;
    }

    public double timeStep() {
        return timeStep;
    }

    public long iteration() {
        return iteration;
    }

    public int nodeCount() {
        return liveNodes;
    }

    public int edgeCount() {
        return liveEdges;
    }

    public int allocatedNodeSlots() {
        return nodeSlots;
    }

    public int allocatedEdgeSlots() {
        return edgeSlots;
    }

    public boolean isStepInProgress() {
        return partCount != 0;
    }

    /**
     * Changing the law always recomputes coefficients; no follow-up configure call is needed.
     */
    public SmogGraph setLaw(int newLaw) {
        SmogConfig.checkLaw(newLaw);
        if (law == newLaw) {
            return this;
        }
        requireSettledWindow();
        cancelStep();
        law = newLaw;
        refreshCoefficients();
        return this;
    }

    public SmogGraph configure(double newDiffusion, double newTimeStep) {
        SmogConfig.positiveFinite(newDiffusion, "diffusion");
        SmogConfig.positiveFinite(newTimeStep, "timeStep");
        if (diffusion == newDiffusion && timeStep == newTimeStep) {
            return this;
        }
        requireSettledWindow();
        cancelStep();
        diffusion = newDiffusion;
        timeStep = newTimeStep;
        refreshCoefficients();
        return this;
    }

    private void refreshCoefficients() {
        for (int e = 0; e < edgeSlots; e++) {
            if (endpoints[2 * e] >= 0) {
                setEdgeCoefficient(e);
            }
        }
        for (int i = 0; i < sinkNodes.size; i++) {
            int id = sinkNodes.data[i];
            sinkFactor[id] = Math.exp(-diffusion * boundary[id] * state[2 * id + 1] * timeStep * .5);
        }
        for (int i = 0; i < sourceRates.length; i++) {
            sourceHalfMass[i] = sourceRates[i] * timeStep * .5;
        }
    }

    /**
     * Positions are flattened voxel indices; duplicate positions are independent sources.
     */
    public SmogGraph setSources(int[] positions, double[] rates) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(rates, "rates");
        if (positions.length != rates.length) {
            throw new IllegalArgumentException("Source lengths differ");
        }
        for (int i = 0; i < positions.length; i++) {
            int p = positions[i];
            if (p < 0 || p >= label.length || rates[i] < 0 || !Double.isFinite(rates[i])) {
                throw new IllegalArgumentException("Source must have a valid position and a finite nonnegative rate");
            }
        }
        if (windowTick != 0 || !emissionFrames.isEmpty()) {
            throw new IllegalStateException("Finish the emission window before replacing source handles");
        }
        cancelStep();
        sourcePositions = positions.clone();
        sourceRates = rates.clone();
        Arrays.fill(sourceHead, -1);
        sourceNext = new int[positions.length];
        sourceBlocked = new boolean[positions.length];
        deferredSource = new boolean[positions.length];
        spareFrames.clear();
        sourceNodes = new int[positions.length];
        sourcePhases = new int[positions.length];
        sourceHalfMass = new double[positions.length];
        pendingAccepted = new double[positions.length];
        pendingRejected = new double[positions.length];
        sourceStamp = new double[positions.length];
        Arrays.fill(sourceStamp, gameTime);
        for (int i = 0; i < positions.length; i++) {
            sourceNodes[i] = label[positions[i]];
            sourceBlocked[i] = sourceNodes[i] < 0;
            int box = containerOf(positions[i]);
            sourceNext[i] = sourceHead[box];
            sourceHead[box] = i;
            sourcePhases[i] = (int) ((i * 2654435761L) % config.cyclePeriod);
            sourceHalfMass[i] = rates[i] * timeStep * .5;
        }
        return this;
    }

    /**
     * Clears mass, time, pending edits/emissions and counters; keeps applied geometry/settings/sources.
     */
    public void reset() {
        cancelStep();
        for (int i = 0; i < nodeSlots; i++) {
            state[2 * i] = 0;
        }
        trapped.clear();
        escaped = 0;
        injected = 0;
        blocked = 0;
        iteration = 0;
        gameTime = modelTime = emissionBaseTime = 0;
        gameTicks = catchUpSteps = cancelledCandidates = 0;
        scheduleParts = 0;
        budgetDirty = budgetMode = false;
        queuedChanges.clear();
        Arrays.fill(pendingAccepted, 0);
        Arrays.fill(pendingRejected, 0);
        Arrays.fill(sourceStamp, 0);
        emissionFrames.clear();
        spareFrames.clear();
        queuedEmissionMass = 0;
        windowTick = 0;
        emissionWindows = 0;
        Arrays.fill(sourceBoxPending, false);
        Arrays.fill(queuedPerBox, 0);
        Arrays.fill(touchedBoxes, false);
        touched.clear();
        clearFrozen();
        for (int i = 0; i < sourceNodes.length; i++) {
            sourceBlocked[i] = sourceNodes[i] < 0;
        }
        for (int k = 0; k < eventNodes.size; k++) {
            int id = eventNodes.data[k];
            eventAdds[id] = eventRemoves[id] = 0;
            eventMarked[id] = false;
        }
        eventNodes.clear();
        pendingAdded = actuallyRemoved = 0;
        rebuildCount = 0;
        sourceTrapsPending = false;
    }

    /**
     * Deterministic xorshift state initialization matching smog_seed_random. Prefer reset first.
     */
    public void seedRandom(long randomState) {
        cancelStep();
        for (int i = 0; i < nodeSlots; i++) {
            if (volume[i] != 0) {
                randomState ^= randomState << 13;
                randomState ^= randomState >>> 7;
                randomState ^= randomState << 17;
                state[2 * i] = volume[i] * (.01 + Long.remainderUnsigned(randomState, 100000) / 100000.0);
            }
        }
        // Unlike the research helper, preserve a valid ledger even after prior execution.
        injected = mass() + escaped + trapped.total() + actuallyRemoved;
    }

    public void step() {
        step(false);
    }

    public void step(boolean cycling) {
        requireIdle();
        stepPart(1, cycling);
    }

    public void runSteps(int steps, boolean cycling) {
        requireIdle();
        if (steps < 0) {
            throw new IllegalArgumentException("Negative step count");
        }
        for (int i = 0; i < steps; i++) {
            step(cycling);
        }
    }

    public void runSteps(int steps) {
        runSteps(steps, false);
    }

    /**
     * Accounts dt/parts of game time. Transport consumes completed intervals only.
     * For parts>1 the result lags by one interval; finishPendingPart drains without
     * advancing game time. Rate changes never invalidate an in-flight candidate.
     */
    public boolean stepPart(int parts, boolean cycling) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be positive");
        }
        if ((scheduleParts != 0 && (scheduleParts != parts || scheduleCycling != cycling))
            || (partCount != 0 && partCount != parts)) {
            throw new IllegalStateException("Cannot change an unfinished window's schedule");
        }
        synchronizeGeometry();
        boolean hadFrame = !emissionFrames.isEmpty();
        if (scheduleParts == 0) {
            scheduleParts = parts;
            scheduleCycling = cycling;
        }
        windowTick++;
        gameTicks++;
        gameTime = emissionBaseTime + timeStep * windowTick / parts;
        if (windowTick == parts) {
            closeEmissionWindow();
            windowTick = 0;
            scheduleParts = 0;
        }
        return (hadFrame || parts == 1) && finishPendingPart(parts);
    }

    /**
     * Performs one bounded solver portion without generating time or emissions.
     * Use after the final game tick (or explicit cancellation) to drain history.
     */
    public boolean finishPendingPart(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be positive");
        }
        synchronizeGeometry();
        if (partCount == 0) {
            releaseSourceTraps();
        }
        if (emissionFrames.isEmpty()) {
            applyMassEvents();
            return false;
        }
        if (partCount == 0) {
            clearFrozen();
            freezeQueuedBoxes();
            EmissionFrame frame = emissionFrames.peekFirst();
            for (int i = 0; i < sourceRates.length; i++) {
                sourceHalfMass[i] = frame.accepted[i] * .5;
                deferredSource[i] = sourceNodes[i] < 0 || frozen[sourceNodes[i]];
            }
            candidateRejected = frame.rejected;
            budgetMode = true;
        }
        if (!computePart(parts, false)) {
            return false;
        }
        publishBudget();
        releaseSourceTraps();
        applyMassEvents();
        return true;
    }

    private void closeEmissionWindow() {
        EmissionFrame frame = spareFrames.pollFirst();
        if (frame == null) {
            frame = new EmissionFrame(sourceRates.length);
        }
        frame.rejected = frame.total = 0;
        for (int i = 0; i < sourceRates.length; i++) {
            if (sourceStamp[i] <= emissionBaseTime && pendingAccepted[i] == 0 && pendingRejected[i] == 0) {
                double amount = sourceRates[i] * timeStep;
                if (scheduleCycling) {
                    int phase = (int) (Long.divideUnsigned(emissionWindows, config.cycleSteps) % config.cyclePeriod);
                    if ((phase + sourcePhases[i]) % config.cyclePeriod >= config.cycleOn) {
                        amount = 0;
                    }
                }
                if (sourceBlocked[i]) {
                    pendingRejected[i] = amount;
                } else {
                    pendingAccepted[i] = amount;
                }
                sourceStamp[i] = gameTime;
            } else {
                settleSource(i);
            }
            frame.accepted[i] = pendingAccepted[i];
            frame.rejected += pendingRejected[i];
            frame.total += pendingAccepted[i] + pendingRejected[i];
            pendingAccepted[i] = pendingRejected[i] = 0;
        }
        emissionFrames.addLast(frame);
        queuedEmissionMass += frame.total;
        emissionBaseTime = gameTime;
        emissionWindows++;
    }

    private double unaccounted(int i) {
        if (scheduleCycling) {
            int phase = (int) (Long.divideUnsigned(emissionWindows, config.cycleSteps) % config.cyclePeriod);
            if ((phase + sourcePhases[i]) % config.cyclePeriod >= config.cycleOn) {
                return 0;
            }
        }
        return sourceRates[i] * Math.max(0, gameTime - Math.max(emissionBaseTime, sourceStamp[i]));
    }

    private void settleSource(int i) {
        double amount = unaccounted(i);
        if (sourceBlocked[i]) {
            pendingRejected[i] += amount;
        } else {
            pendingAccepted[i] += amount;
        }
        sourceStamp[i] = gameTime;
    }

    private void publishBudget() {
        for (int i = 0; i < sourceRates.length; i++) {
            if (deferredSource[i] && sourceHalfMass[i] != 0) {
                double amount = 2 * sourceHalfMass[i];
                int node = sourceNodes[i], position = sourcePositions[i];
                if (node >= 0 && queuedPerBox[containerOf(position)] == 0) {
                    state[2 * node] += amount;
                } else {
                    trapped.add(position, amount);
                    sourceTrapsPending = true;
                }
            }
        }
        blocked += candidateRejected;
        modelTime += timeStep;
        EmissionFrame frame = emissionFrames.removeFirst();
        queuedEmissionMass -= frame.total;
        if (emissionFrames.isEmpty()) {
            queuedEmissionMass = 0;
        }
        if (spareFrames.size() < 2) {
            spareFrames.addLast(frame);
        }
        budgetMode = budgetDirty = false;
    }

    private boolean computePart(int parts, boolean cycling) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be positive");
        }
        if (partCount == 0) {
            ensureStepBuffers();
            stepNodeLimit = nodeSlots;
            stepActiveEdgeLimit = activeEdges.size;
            stepEdgeSlotLimit = edgeSlots;
            stepSinkLimit = sinkNodes.size;
            solverEpoch++;
            savedInjected = injected;
            savedEscaped = escaped;
            savedBlocked = blocked;
            System.arraycopy(state, 0, workState, 0, nodeSlots * 2);
            double[] old = state;
            state = workState;
            workState = old;
            partCount = parts;
            partIndex = 0;
            fluxDone = 0;
            partCycling = cycling;
            sourceHalf(cycling);
            sinkHalf();
            if (law == SmogConfig.QUADRATIC_FAST) {
                Arrays.fill(nodeLoad, 0, nodeSlots, 0);
                Arrays.fill(delta, 0, nodeSlots, 0);
            }
        } else if (partCount != parts || partCycling != cycling) {
            throw new IllegalStateException("Cannot change an unfinished step's schedule");
        }
        if (law == SmogConfig.QUADRATIC_FAST) {
            long total = 2L * stepActiveEdgeLimit;
            long begin = total * partIndex / parts, end = total * (partIndex + 1) / parts;
            balancedLoads((int) Math.min(begin, stepActiveEdgeLimit), (int) Math.min(end, stepActiveEdgeLimit));
            balancedFluxes((int) Math.max(0, begin - stepActiveEdgeLimit),
                (int) Math.max(0, end - stepActiveEdgeLimit));
        } else {
            exchangeRange(
                (int) ((long) stepEdgeSlotLimit * partIndex / parts),
                (int) ((long) stepEdgeSlotLimit * (partIndex + 1) / parts));
        }
        if (++partIndex != parts) {
            return false;
        }
        if (law == SmogConfig.QUADRATIC_FAST) {
            for (int i = 0; i < stepNodeLimit; i++) {
                state[2 * i] += delta[i];
            }
        }
        sinkHalf();
        sourceHalf(cycling);
        iteration++;
        partCount = 0;
        partIndex = 0;
        return true;
    }

    /**
     * Discards provisional transport, sources and sinks. Elapsed game time and
     * emission debt survive; finish using the same schedule or explicitly reset.
     */
    public void cancelStep() {
        if (partCount == 0) {
            return;
        }
        double[] old = state;
        state = workState;
        workState = old;
        injected = savedInjected;
        escaped = savedEscaped;
        blocked = savedBlocked;
        partCount = partIndex = 0;
        cancelledCandidates++;
    }

    private double[] visibleState() {
        return partCount == 0 ? state : workState;
    }

    private void ensureStepBuffers() {
        if (workState.length < state.length) {
            workState = Arrays.copyOf(workState, state.length);
        }
        if (nodeLoad.length < volume.length) {
            nodeLoad = Arrays.copyOf(nodeLoad, volume.length);
            delta = Arrays.copyOf(delta, volume.length);
        }
        if (mobility.length < coefficient.length) {
            mobility = Arrays.copyOf(mobility, coefficient.length);
        }
    }

    private void balancedLoads(int begin, int end) {
        if (frozenNodes.size != 0) {
            quarantinedLoads(begin, end);
            return;
        }
        for (int k = begin; k < end; k++) {
            int e = activeEdges.data[k], a = endpoints[2 * e];
            int b = endpoints[2 * e + 1];
            double ia = state[2 * a + 1], ib = state[2 * b + 1];
            double raw = diffusion * (state[2 * a] * ia + state[2 * b] * ib);
            double effective = config.maxDiffusivity / (1 + config.maxDiffusivity / raw);
            double w = coefficient[e] * effective;
            mobility[e] = w;
            nodeLoad[a] += w * ia;
            nodeLoad[b] += w * ib;
        }
    }

    private void quarantinedLoads(int begin, int end) {
        for (int k = begin; k < end; k++) {
            int e = activeEdges.data[k], a = endpoints[2 * e];
            int b = endpoints[2 * e + 1];
            if (frozen[a] || frozen[b]) {
                mobility[e] = 0;
                continue;
            }
            double ia = state[2 * a + 1], ib = state[2 * b + 1];
            double raw = diffusion * (state[2 * a] * ia + state[2 * b] * ib);
            double effective = config.maxDiffusivity / (1 + config.maxDiffusivity / raw);
            double w = coefficient[e] * effective;
            mobility[e] = w;
            nodeLoad[a] += w * ia;
            nodeLoad[b] += w * ib;
        }
    }

    private void balancedFluxes(int begin, int end) {
        final double inverseMixing = 1 / config.maxMixing;
        for (int k = begin; k < end; k++) {
            int e = activeEdges.data[k], a = endpoints[2 * e];
            int b = endpoints[2 * e + 1];
            double ca = state[2 * a] * state[2 * a + 1], cb = state[2 * b] * state[2 * b + 1];
            double flow = mobility[e] * (ca - cb) / (1 + inverseMixing * Math.max(nodeLoad[a], nodeLoad[b]));
            delta[a] -= flow;
            delta[b] += flow;
        }
        fluxDone = end;
    }

    private void sourceHalf(boolean cycling) {
        final double[] data = state, amounts = sourceHalfMass;
        final int[] nodes = sourceNodes;
        double total = injected;
        if (budgetMode) {
            for (int i = 0; i < nodes.length; i++) {
                double amount = amounts[i];
                if (!deferredSource[i]) {
                    data[2 * nodes[i]] += amount;
                }
                total += amount;
            }
        } else if (cycling) {
            int phase = (int) (Long.divideUnsigned(iteration, config.cycleSteps) % config.cyclePeriod);
            for (int i = 0; i < nodes.length; i++) {
                int node = nodes[i], sourcePhase = phase + sourcePhases[i];
                if (sourcePhase >= config.cyclePeriod) {
                    sourcePhase -= config.cyclePeriod;
                }
                if (sourcePhase >= config.cycleOn) {
                    continue;
                }
                if (node < 0) {
                    blocked += amounts[i];
                    continue;
                }
                double amount = amounts[i];
                data[2 * node] += amount;
                total += amount;
            }
        } else {
            for (int i = 0; i < nodes.length; i++) {
                double amount = amounts[i];
                if (nodes[i] < 0) {
                    blocked += amount;
                    continue;
                }
                data[2 * nodes[i]] += amount;
                total += amount;
            }
        }
        injected = total;
    }

    private void sinkHalf() {
        double total = escaped;
        if (law == SmogConfig.LINEAR) {
            for (int i = 0; i < stepSinkLimit; i++) {
                int id = sinkNodes.data[i], offset = 2 * id;
                if (partIndex == 0) {
                    firstSinkLoss[id] = 0;
                }
                if (frozen[id]) {
                    continue;
                }
                double mass = state[offset], remaining = mass * sinkFactor[id];
                if (partIndex == 0) {
                    firstSinkLoss[id] = mass - remaining;
                }
                total += mass - remaining;
                state[offset] = remaining;
            }
        } else {
            for (int i = 0; i < stepSinkLimit; i++) {
                int id = sinkNodes.data[i], offset = 2 * id;
                if (partIndex == 0) {
                    firstSinkLoss[id] = 0;
                }
                if (frozen[id]) {
                    continue;
                }
                double mass = state[offset], iv = state[offset + 1];
                double remaining = mass / (1 + diffusion * boundary[id] * timeStep * .5 * mass * iv * iv);
                if (partIndex == 0) {
                    firstSinkLoss[id] = mass - remaining;
                }
                total += mass - remaining;
                state[offset] = remaining;
            }
        }
        escaped = total;
    }

    private void exchangeRange(int begin, int end) {
        boolean forward = (iteration & 1) == 0;
        int first = forward ? begin : edgeSlots - 1 - begin;
        int fence = forward ? end : edgeSlots - 1 - end;
        int stride = forward ? 1 : -1;
        switch (law) {
            case SmogConfig.LINEAR:
                exchangeLinear(first, fence, stride);
                break;
            case SmogConfig.QUADRATIC_LEGACY:
                exchangeFast(first, fence, stride);
                break;
            case SmogConfig.QUADRATIC_EXACT:
                exchangeQuadratic(first, fence, stride, true);
                break;
            case SmogConfig.QUADRATIC_RATIONAL:
                exchangeQuadratic(first, fence, stride, false);
                break;
            default:
                throw new AssertionError("Invalid law");
        }
    }

    private void exchangeLinear(int first, int fence, int stride) {
        final int[] ends = endpoints;
        final double[] data = state, weights = coefficient;
        for (int e = first; e != fence; e += stride) {
            int a = ends[2 * e];
            if (a < 0) {
                continue;
            }
            if (frozen[a] || frozen[ends[2 * e + 1]]) {
                continue;
            }
            int b = ends[2 * e + 1] * 2;
            a *= 2;
            double ma = data[a], mb = data[b];
            double difference = ma * data[a + 1] - mb * data[b + 1];
            if (difference == 0) {
                continue;
            }
            double flow = weights[e] * difference;
            data[a] = ma - flow;
            data[b] = mb + flow;
        }
    }

    private void exchangeFast(int first, int fence, int stride) {
        final int[] ends = endpoints;
        final double[] data = state, weights = coefficient;
        for (int e = first; e != fence; e += stride) {
            int a = ends[2 * e];
            if (a < 0) {
                continue;
            }
            if (frozen[a] || frozen[ends[2 * e + 1]]) {
                continue;
            }
            int b = ends[2 * e + 1] * 2;
            a *= 2;
            double ma = data[a], mb = data[b], ia = data[a + 1], ib = data[b + 1];
            double ca = ma * ia, cb = mb * ib, difference = ca - cb;
            if (difference == 0) {
                continue;
            }
            double strength = weights[e] * (ca + cb);
            double flow = strength * difference / (1 + strength * (ia + ib));
            data[a] = ma - flow;
            data[b] = mb + flow;
        }
    }

    private void exchangeQuadratic(int first, int fence, int stride, boolean exact) {
        final int[] ends = endpoints;
        final double[] data = state, g = conductance;
        for (int e = first; e != fence; e += stride) {
            int a = ends[2 * e];
            if (a < 0) {
                continue;
            }
            if (frozen[a] || frozen[ends[2 * e + 1]]) {
                continue;
            }
            int b = ends[2 * e + 1] * 2;
            a *= 2;
            double ma = data[a], mb = data[b], ia = data[a + 1], ib = data[b + 1];
            double difference = ma * ia - mb * ib;
            if (difference == 0) {
                continue;
            }
            double invsum = ia + ib, mean = (ma + mb) * (ia * ib / invsum);
            if (mean == 0) {
                continue;
            }
            double x = 2 * diffusion * g[e] * timeStep * mean * invsum;
            double p;
            if (exact) {
                p = -Math.expm1(-x);
            } else {
                double t = x * (1 + .5 * x);
                p = x < 1 ? t / (1 + t) : 1 - 1 / (1 + t);
            }
            double r = ((ia - ib) / invsum) * (difference / (2 * mean));
            double fraction = p * (1 + r) / (1 + p * r);
            double flow = (difference / invsum) * fraction;
            data[a] = ma - flow;
            data[b] = mb + flow;
        }
    }

    public double sample(int voxel) {
        if (voxel < 0 || voxel >= label.length || label[voxel] < 0) {
            return 0;
        }
        int offset = 2 * label[voxel];
        double[] data = visibleState();
        return data[offset] * data[offset + 1];
    }

    public double sample(int x, int y, int z) {
        return sample(index(x, y, z));
    }

    public void sample(int[] positions, double[] output) {
        if (output.length < positions.length) {
            throw new IllegalArgumentException("Output too short");
        }
        for (int i = 0; i < positions.length; i++) {
            output[i] = sample(positions[i]);
        }
    }

    public double mass() {
        double[] data = visibleState();
        double sum = 0;
        for (int i = 0; i < nodeSlots; i++) {
            sum += data[2 * i];
        }
        return sum;
    }

    public double trappedMass() {
        return trapped.total();
    }

    public double escapedMass() {
        return partCount == 0 ? escaped : savedEscaped;
    }

    public double injectedMass() {
        return partCount == 0 ? injected : savedInjected;
    }

    public double blockedEmissionMass() {
        return partCount == 0 ? blocked : savedBlocked;
    }

    public double balanceError() {
        return mass() + escapedMass() + trapped.total() + actuallyRemoved - injectedMass();
    }

    public byte[] copyAir() {
        return air.clone();
    }

    public int[] copyLabels() {
        return label.clone();
    }

    public double[] copyField() {
        double[] result = new double[label.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = sample(i);
        }
        return result;
    }

    /**
     * Diagnostic triples [volume, mass, seed] for all allocated slots.
     */
    public double[] copyNodeData() {
        double[] result = new double[nodeSlots * 3];
        for (int i = 0; i < nodeSlots; i++) {
            result[3 * i] = volume[i];
            result[3 * i + 1] = visibleState()[2 * i];
            result[3 * i + 2] = volume[i] == 0 ? -1 : seed[i];
        }
        return result;
    }

    /**
     * Diagnostic triples [nodeA, nodeB, conductance]; deleted slots have endpoints -1.
     */
    public double[] copyEdges() {
        double[] result = new double[edgeSlots * 3];
        for (int i = 0; i < edgeSlots; i++) {
            result[3 * i] = endpoints[2 * i];
            result[3 * i + 1] = endpoints[2 * i + 1];
            result[3 * i + 2] = conductance[i];
        }
        return result;
    }

    public int newNode(int seedPosition) {
        if (partCount != 0 && !appending) requireIdle();
        if (appending && nodeSlots >= appendNodeEnd) throw new IllegalStateException("Append node reservation exhausted");
        int id;
        if (freeNodes.size != 0 && !appending) {
            id = freeNodes.pop();
        } else {
            id = nodeSlots;
            ensureNodes(nodeSlots + 1);
            nodeSlots++;
        }
        volume[id] = 0;
        seed[id] = seedPosition;
        adjacency[id] = -1;
        sinkSlot[id] = -1;
        state[2 * id] = 0;
        state[2 * id + 1] = 0;
        if (partCount != 0) {
            workState[2 * id] = workState[2 * id + 1] = 0;
            nodeLoad[id] = delta[id] = 0;
        }
        boundary[id] = 0;
        sinkFactor[id] = 1;
        firstSinkLoss[id] = 0;
        liveNodes++;
        return id;
    }

    public void setVolume(int id, int value) {
        requireMutableNode(id);
        volume[id] = value;
        state[2 * id + 1] = 1.0 / value;
        if (partCount != 0) workState[2 * id + 1] = 1.0 / value;
    }

    void addEdge(int a, int b, double g) {
        if (partCount != 0 && !appending) requireIdle();
        if (appending && (edgeSlots >= appendEdgeEnd || (a < appendNodeStart && b < appendNodeStart)))
            throw new IllegalStateException("Append must connect a new node within its reservation");
        int id;
        if (freeEdges.size != 0 && !appending) {
            id = freeEdges.pop();
        } else {
            id = edgeSlots;
            ensureEdges(edgeSlots + 1);
            edgeSlots++;
        }
        endpoints[2 * id] = a;
        endpoints[2 * id + 1] = b;
        link(2 * id, a);
        link(2 * id + 1, b);
        conductance[id] = g;
        setEdgeCoefficient(id);
        edgeActiveSlot[id] = activeEdges.size;
        activeEdges.add(id);
        liveEdges++;
    }

    private void link(int half, int node) {
        int head = adjacency[node];
        next[half] = head;
        previous[half] = -1;
        if (head >= 0) {
            previous[head] = half;
        }
        adjacency[node] = half;
    }

    private void unlink(int half) {
        int p = previous[half], n = next[half];
        if (p >= 0) {
            next[p] = n;
        } else {
            adjacency[endpoints[half]] = n;
        }
        if (n >= 0) {
            previous[n] = p;
        }
    }

    private void removeEdge(int id) {
        int slot = edgeActiveSlot[id], last = activeEdges.pop();
        if (slot < activeEdges.size) {
            activeEdges.data[slot] = last;
            edgeActiveSlot[last] = slot;
        }
        unlink(2 * id);
        unlink(2 * id + 1);
        endpoints[2 * id] = -1;
        endpoints[2 * id + 1] = -1;
        freeEdges.add(id);
        liveEdges--;
    }

    void removeNode(int id) {
        requireIdle();
        while (adjacency[id] >= 0) {
            removeEdge(adjacency[id] / 2);
        }
        removeSink(id);
        volume[id] = 0;
        seed[id] = -1;
        state[2 * id] = 0;
        state[2 * id + 1] = 0;
        boundary[id] = 0;
        freeNodes.add(id);
        liveNodes--;
    }

    private void setEdgeCoefficient(int e) {
        double invsum = state[2 * endpoints[2 * e] + 1] + state[2 * endpoints[2 * e + 1] + 1];
        coefficient[e] = law == SmogConfig.QUADRATIC_FAST ? conductance[e] * timeStep
            : law == SmogConfig.QUADRATIC_LEGACY ? diffusion * conductance[e] * timeStep
                : -Math.expm1(-diffusion * conductance[e] * timeStep * invsum) / invsum;
    }

    private void contact(int p, int q, EdgeAccumulator aggregate) {
        int a = label[p], b = label[q];
        if (a >= 0 && b >= 0 && a != b) {
            aggregate.add(a, b, (distance[p] & 255) + (distance[q] & 255) + 1);
        }
    }

    private double boundaryAt(int p, int x, int y, int z) {
        int mask = config.openMask, faces = 0;
        if ((mask & 1) != 0 && x == 0) {
            faces++;
        }
        if ((mask & 2) != 0 && x == nx - 1) {
            faces++;
        }
        if ((mask & 4) != 0 && y == 0) {
            faces++;
        }
        if ((mask & 8) != 0 && y == ny - 1) {
            faces++;
        }
        if ((mask & 16) != 0 && z == 0) {
            faces++;
        }
        if ((mask & 32) != 0 && z == nz - 1) {
            faces++;
        }
        return faces / (config.mode == SmogConfig.COMPONENT ? config.containerSize * .5 : (distance[p] & 255) + .5);
    }

    void refreshSink(int id) {
        requireMutableNode(id);
        if (boundary[id] <= 0) {
            removeSink(id);
            return;
        }
        if (sinkSlot[id] < 0) {
            sinkSlot[id] = sinkNodes.size;
            sinkNodes.add(id);
        }
        sinkFactor[id] = Math.exp(-diffusion * boundary[id] * state[2 * id + 1] * timeStep * .5);
    }

    private void removeSink(int id) {
        int slot = sinkSlot[id];
        if (slot < 0) {
            return;
        }
        int last = sinkNodes.pop();
        if (slot < sinkNodes.size) {
            sinkNodes.data[slot] = last;
            sinkSlot[last] = slot;
        }
        sinkSlot[id] = -1;
    }

    private void ensureNodes(int required) {
        if (required <= volume.length) {
            return;
        }
        int old = volume.length, capacity = IntList.capacity(old, required);
        if (capacity > (Integer.MAX_VALUE - 8) / 3) {
            throw new IllegalStateException("Too many node slots");
        }
        volume = Arrays.copyOf(volume, capacity);
        seed = Arrays.copyOf(seed, capacity);
        adjacency = Arrays.copyOf(adjacency, capacity);
        Arrays.fill(adjacency, old, capacity, -1);
        sinkSlot = Arrays.copyOf(sinkSlot, capacity);
        Arrays.fill(sinkSlot, old, capacity, -1);
        state = Arrays.copyOf(state, capacity * 2);
        boundary = Arrays.copyOf(boundary, capacity);
        sinkFactor = Arrays.copyOf(sinkFactor, capacity);
        frozen = Arrays.copyOf(frozen, capacity);
        firstSinkLoss = Arrays.copyOf(firstSinkLoss, capacity);
        eventAdds = Arrays.copyOf(eventAdds, capacity);
        eventRemoves = Arrays.copyOf(eventRemoves, capacity);
        eventMarked = Arrays.copyOf(eventMarked, capacity);
        if (patchWork != null) {
            patchWork.ensureNodes(capacity);
        }
    }

    /** Reserve sparse storage before an atomic restart commit; never a physics operation. */
    int reserveSparse(int nodes, int edges) {
        if (!appending) requireIdle();
        int nodeRequired = Math.addExact(nodeSlots, appending ? nodes : Math.max(0, nodes - freeNodes.size));
        int edgeRequired = Math.addExact(edgeSlots, appending ? edges : Math.max(0, edges - freeEdges.size));
        ensureNodes(nodeRequired);
        ensureEdges(edgeRequired);
        int activeRequired = Math.addExact(activeEdges.size, edges);
        if (activeEdges.data.length < activeRequired) activeEdges.data = Arrays
            .copyOf(activeEdges.data, IntList.capacity(activeEdges.data.length, activeRequired));
        int sinks = Math.addExact(sinkNodes.size, nodes);
        if (sinkNodes.data.length < sinks)
            sinkNodes.data = Arrays.copyOf(sinkNodes.data, IntList.capacity(sinkNodes.data.length, sinks));
        ensureStepBuffers();
        return nodeRequired;
    }

    /** One array relocation per preparation unit, before commit. Existing values are copied
     * and the live reference is swapped immediately on the owner thread. Other arrays may
     * temporarily have more capacity; nodeSlots/edgeSlots and the solver view never grow here.
     * This avoids allocating/copying every graph array in one atomic publication. */
    long appendCapacityBytes() {
        return 8L * (workState.length + (long)state.length + nodeLoad.length + delta.length
            + boundary.length + sinkFactor.length + firstSinkLoss.length + eventAdds.length + eventRemoves.length
            + mobility.length + coefficient.length + conductance.length)
            + 4L * (seed.length + (long)adjacency.length + sinkSlot.length + volume.length + endpoints.length
            + next.length + previous.length + edgeActiveSlot.length + activeEdges.data.length + sinkNodes.data.length)
            + frozen.length + (long)eventMarked.length;
    }

    boolean prepareAppendCapacity(int nodes, int edges) {
        if (nx != 0 || appending) throw new IllegalStateException("Sparse preparation only");
        int nr = Math.addExact(nodeSlots, partCount != 0 ? nodes : Math.max(0, nodes - freeNodes.size));
        int er = Math.addExact(edgeSlots, partCount != 0 ? edges : Math.max(0, edges - freeEdges.size));
        int nc = IntList.capacity(volume.length, nr), ec = IntList.capacity(conductance.length, er);
        if (nc > (Integer.MAX_VALUE - 8) / 3 || ec > (Integer.MAX_VALUE - 8) / 3)
            throw new IllegalStateException("Too many append slots");
        // Work first: beginning another step may swap the two mass buffers meanwhile.
        if (workState.length < nr * 2) { workState = Arrays.copyOf(workState, nc * 2); return false; }
        if (state.length < nr * 2) { state = Arrays.copyOf(state, nc * 2); return false; }
        if (nodeLoad.length < nr) { nodeLoad = Arrays.copyOf(nodeLoad, nc); return false; }
        if (delta.length < nr) { delta = Arrays.copyOf(delta, nc); return false; }
        if (seed.length < nr) { seed = Arrays.copyOf(seed, nc); return false; }
        if (adjacency.length < nr) {
            int old = adjacency.length; adjacency = Arrays.copyOf(adjacency, nc);
            Arrays.fill(adjacency, old, nc, -1); return false;
        }
        if (sinkSlot.length < nr) {
            int old = sinkSlot.length; sinkSlot = Arrays.copyOf(sinkSlot, nc);
            Arrays.fill(sinkSlot, old, nc, -1); return false;
        }
        if (boundary.length < nr) { boundary = Arrays.copyOf(boundary, nc); return false; }
        if (sinkFactor.length < nr) { sinkFactor = Arrays.copyOf(sinkFactor, nc); return false; }
        if (frozen.length < nr) { frozen = Arrays.copyOf(frozen, nc); return false; }
        if (firstSinkLoss.length < nr) { firstSinkLoss = Arrays.copyOf(firstSinkLoss, nc); return false; }
        if (eventAdds.length < nr) { eventAdds = Arrays.copyOf(eventAdds, nc); return false; }
        if (eventRemoves.length < nr) { eventRemoves = Arrays.copyOf(eventRemoves, nc); return false; }
        if (eventMarked.length < nr) { eventMarked = Arrays.copyOf(eventMarked, nc); return false; }
        if (volume.length < nr) { volume = Arrays.copyOf(volume, nc); return false; }
        if (mobility.length < er) { mobility = Arrays.copyOf(mobility, ec); return false; }
        if (endpoints.length < er * 2) { endpoints = Arrays.copyOf(endpoints, ec * 2); return false; }
        if (next.length < er * 2) { next = Arrays.copyOf(next, ec * 2); return false; }
        if (previous.length < er * 2) { previous = Arrays.copyOf(previous, ec * 2); return false; }
        if (edgeActiveSlot.length < er) { edgeActiveSlot = Arrays.copyOf(edgeActiveSlot, ec); return false; }
        if (coefficient.length < er) { coefficient = Arrays.copyOf(coefficient, ec); return false; }
        if (conductance.length < er) { conductance = Arrays.copyOf(conductance, ec); return false; }
        int ar = Math.addExact(activeEdges.size, edges), sr = Math.addExact(sinkNodes.size, nodes);
        if (activeEdges.data.length < ar) {
            activeEdges.data = Arrays.copyOf(activeEdges.data, IntList.capacity(activeEdges.data.length, ar)); return false;
        }
        if (sinkNodes.data.length < sr) {
            sinkNodes.data = Arrays.copyOf(sinkNodes.data, IntList.capacity(sinkNodes.data.length, sr)); return false;
        }
        return true;
    }

    /** Narrow, owner-thread append scope; reserve everything before publishing any node.
     * Existing nodes, sources, coefficients and the active/sink prefixes stay immutable.
     * No free IDs are reused, even on an idle boundary, within this scope. */
    int beginAppendBatch(int nodes, int edges) {
        if (appending || nx != 0 || law != SmogConfig.QUADRATIC_FAST || nodes < 0 || edges < 0)
            throw new IllegalStateException("Unsupported append scope");
        appendNodeStart = nodeSlots;
        appendNodeEnd = Math.addExact(nodeSlots, nodes);
        appendEdgeEnd = Math.addExact(edgeSlots, edges);
        appending = true;
        try {
            return reserveSparse(nodes, edges);
        } catch (RuntimeException | Error failure) {
            appending = false;
            throw failure;
        }
    }

    void endAppendBatch() { appending = false; }

    long solverEpoch() { return solverEpoch; }
    int pendingSolverNodes() { return partCount == 0 ? 0 : nodeSlots - stepNodeLimit; }

    private void requireMutableNode(int id) {
        if (appending && (id < appendNodeStart || id >= nodeSlots))
            throw new IllegalStateException("Append cannot modify an existing node");
        if (partCount != 0 && !appending) requireIdle();
    }

    long sparseBytes() {
        return 4L
            * (volume.length + seed.length
                + adjacency.length
                + sinkSlot.length
                + endpoints.length
                + next.length
                + previous.length
                + edgeActiveSlot.length
                + activeEdges.data.length
                + sinkNodes.data.length + freeNodes.data.length + freeEdges.data.length)
            + 8L * (state.length + boundary.length
                + sinkFactor.length
                + conductance.length
                + coefficient.length
                + workState.length
                + nodeLoad.length
                + delta.length
                + mobility.length + firstSinkLoss.length + eventAdds.length + eventRemoves.length)
            + frozen.length + eventMarked.length;
    }

    private void ensureEdges(int required) {
        if (required <= conductance.length) {
            return;
        }
        int capacity = IntList.capacity(conductance.length, required);
        if (capacity > (Integer.MAX_VALUE - 8) / 3) {
            throw new IllegalStateException("Too many edge slots");
        }
        endpoints = Arrays.copyOf(endpoints, capacity * 2);
        next = Arrays.copyOf(next, capacity * 2);
        previous = Arrays.copyOf(previous, capacity * 2);
        conductance = Arrays.copyOf(conductance, capacity);
        coefficient = Arrays.copyOf(coefficient, capacity);
        edgeActiveSlot = Arrays.copyOf(edgeActiveSlot, capacity);
    }

    private static int[] filled(int length, int value) {
        int[] a = new int[length];
        Arrays.fill(a, value);
        return a;
    }

    private void requireSettledWindow() {
        if (scheduleParts != 0 || !emissionFrames.isEmpty()) {
            throw new IllegalStateException(
                "Finish the game-time window and drain completed intervals before reconfiguring");
        }
    }

    private void requireIdle() {
        if (partCount != 0) {
            throw new IllegalStateException("Finish all stepPart calls before mutating or advancing the graph");
        }
    }

    /**
     * Primitive array payload only, including retained workspaces; excludes JVM object/array headers.
     */
    public long estimatedArrayBytes() {
        return 6L * air.length + 74L * volume.length
            + 6L * containerCount
            + 4L * (touched.data.length + frozenNodes.data.length + eventNodes.data.length)
            + 8L * sourceRates.length * (emissionFrames.size() + spareFrames.size())
            + sourceBlocked.length
            + deferredSource.length
            + 44L * conductance.length
            + 4L * activeEdges.data.length
            + 56L * sourcePositions.length
            + 4L * sourceHead.length
            + 8L * (workState.length + nodeLoad.length + delta.length + mobility.length)
            + 5L * (editPositions.length + queuePositions.length)
            + patchInput.bytes()
            + queuedChanges.bytes()
            + 4L * (freeNodes.data.length + freeEdges.data.length + sinkNodes.data.length)
            + (partitioner == null ? 0 : partitioner.bytes())
            + trapped.bytes()
            + (patchWork == null ? 0 : patchWork.bytes());
    }

    public double buildMillis() {
        return buildMillis;
    }

    // Local edits are kept here so they can update the primitive topology without a public mutable view.
    // All patch loops below are bounded by the dirty region, its incident edges, or source count.
    public double patch(int[] positions, byte[] values) {
        validatePatch(positions, values);
        if (queuedChanges.size != 0) {
            queuePatch(positions, values);
            return 0;
        }
        return applyPatch(positions, values, positions.length);
    }

    private void validatePatch(int[] positions, byte[] values) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(values, "values");
        if (positions.length != values.length) {
            throw new IllegalArgumentException("Patch lengths differ");
        }
        for (int p : positions) {
            if (p < 0 || p >= air.length) {
                throw new IndexOutOfBoundsException("Invalid patch index");
            }
        }
    }

    private double applyPatch(int[] positions, byte[] values, int count) {
        long start = System.nanoTime();
        lastReboundSources = 0;
        lastAppliedChanges = 0;
        patchInput.clear();
        for (int i = 0; i < count; i++) {
            patchInput.put(positions[i], values[i]);
        }
        if (editPositions.length < count) {
            editPositions = new int[count];
            editValues = new byte[count];
        }
        int unique = patchInput.take(count, editPositions, editValues), changed = 0;
        for (int i = 0; i < unique; i++) {
            if ((air[editPositions[i]] != 0) != (editValues[i] != 0)) {
                editPositions[changed] = editPositions[i];
                editValues[changed++] = editValues[i];
            }
        }
        // Includes a duplicate sequence ending at the original value.
        if (changed == 0) {
            return 0;
        }
        cancelStep();
        applyMassEvents();
        clearFrozen();
        budgetDirty = true;
        lastAppliedChanges = changed;
        rebuildCount++;
        PatchWorkspace w = patchWork;
        w.begin();
        collectDirtyContainers(editPositions, changed, w);
        captureOldRegion(w);
        for (int i = 0; i < changed; i++) {
            air[editPositions[i]] = editValues[i];
        }
        removeOldRegion(w);
        repartitionRegion(w);
        remapMass(w);
        rebuildLocalContacts(w);
        for (int i = 0; i < w.freshNodes.size; i++) {
            refreshSink(w.freshNodes.data[i]);
        }
        rebindSources(w);
        for (int i = 0; i < w.boxes.size; i++) {
            w.dirty[w.boxes.data[i]] = false;
        }
        ensureStepBuffers();
        return (System.nanoTime() - start) * 1e-6;
    }

    /**
     * Last write wins. Call tick/stepPart only after all edits for this tick.
     * No candidate invalidation or geometry work occurs on this path.
     */
    public void queuePatch(int[] positions, byte[] values) {
        validatePatch(positions, values);
        for (int i = 0; i < positions.length; i++) {
            int p = positions[i], box = containerOf(p);
            boolean was = queuedChanges.contains(p);
            boolean differs = (air[p] != 0) != (values[i] != 0);
            if (differs) {
                queuedChanges.put(p, values[i], gameTicks);
            } else {
                queuedChanges.remove(p);
            }
            if (was != differs) {
                queuedPerBox[box] += differs ? 1 : -1;
                if (!touchedBoxes[box]) {
                    touchedBoxes[box] = true;
                    touched.add(box);
                }
            }
        }
    }

    public int pendingChanges() {
        return queuedChanges.size;
    }

    /**
     * Bounds changed positions per call, not wall-clock time. A batch is applied atomically.
     */
    public int drainPatches(int maxChanges) {
        if (maxChanges < 1) {
            throw new IllegalArgumentException("maxChanges must be positive");
        }
        lastAppliedChanges = 0;
        int count = Math.min(maxChanges, queuedChanges.size);
        if (queuePositions.length < count) {
            queuePositions = new int[count];
            queueValues = new byte[count];
        }
        count = queuedChanges.take(count, queuePositions, queueValues);
        for (int i = 0; i < count; i++) {
            int box = containerOf(queuePositions[i]);
            queuedPerBox[box]--;
            if (!touchedBoxes[box]) {
                touchedBoxes[box] = true;
                touched.add(box);
            }
        }
        if (count != 0) {
            applyPatch(queuePositions, queueValues, count);
        }
        return count;
    }

    /**
     * At a safe window boundary apply at most one edit batch, then a solver part.
     * Pending regions are quarantined while the remote graph keeps progressing.
     */
    public boolean tick(int maxChanges, int parts, boolean cycling) {
        if (maxChanges < 1 || parts < 1) {
            throw new IllegalArgumentException("Positive work budgets required");
        }
        if (scheduleParts != 0 && (scheduleParts != parts || scheduleCycling != cycling)) {
            throw new IllegalStateException("Cannot change an unfinished window's schedule");
        }
        lastAppliedChanges = 0;
        if (queuedChanges.size != 0 && partCount == 0 && windowTick == 0) {
            drainPatches(maxChanges);
        }
        return stepPart(parts, cycling);
    }

    public int sourceStatus(int source) {
        if (source < 0 || source >= sourceRates.length) {
            throw new IndexOutOfBoundsException("source");
        }
        return sourceRates[source] == 0 ? SOURCE_DISABLED : sourceBlocked[source] ? SOURCE_BLOCKED : SOURCE_ACTIVE;
    }

    /**
     * Incremental stop/start/rate change; does not recreate or scan the source list.
     */
    public void setSourceRate(int source, double rate) {
        if (source < 0 || source >= sourceRates.length) {
            throw new IndexOutOfBoundsException("source");
        }
        if (rate < 0 || !Double.isFinite(rate)) {
            throw new IllegalArgumentException("Invalid source rate");
        }
        if (sourceRates[source] == rate) {
            return;
        }
        settleSource(source);
        sourceRates[source] = rate;
    }

    public long lastReboundSourceCount() {
        return lastReboundSources;
    }

    /**
     * O(1) position-based event; false means solid or locally dirty. No stale node handles.
     */
    public boolean queueAddMass(int position, double amount) {
        int id = eventNode(position, amount);
        if (id < 0) {
            return false;
        }
        if (!Double.isFinite(eventAdds[id] + amount) || !Double.isFinite(pendingAdded + amount)) {
            throw new IllegalArgumentException("Mass overflow");
        }
        markEvent(id);
        eventAdds[id] += amount;
        pendingAdded += amount;
        return true;
    }

    /**
     * Requests removal at the next safe boundary. Actual removal is capped by available mass.
     */
    public boolean queueRemoveMass(int position, double requested) {
        int id = eventNode(position, requested);
        if (id < 0) {
            return false;
        }
        if (!Double.isFinite(eventRemoves[id] + requested)) {
            throw new IllegalArgumentException("Mass overflow");
        }
        markEvent(id);
        eventRemoves[id] += requested;
        return true;
    }

    private int eventNode(int p, double amount) {
        if (p < 0 || p >= air.length) {
            throw new IndexOutOfBoundsException("position");
        }
        if (amount < 0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("Invalid mass");
        }
        return queuedPerBox[containerOf(p)] != 0 || queuedChanges.value(p, air[p]) == 0 ? -1 : label[p];
    }

    private void markEvent(int id) {
        if (!eventMarked[id]) {
            eventMarked[id] = true;
            eventNodes.add(id);
        }
    }

    private void releaseSourceTraps() {
        if (!sourceTrapsPending) {
            return;
        }
        sourceTrapsPending = false;
        for (int i = 0; i < sourceNodes.length; i++) {
            int node = sourceNodes[i], position = sourcePositions[i];
            if (node >= 0 && queuedPerBox[containerOf(position)] == 0) {
                state[2 * node] += trapped.take(position);
            } else {
                sourceTrapsPending = true;
            }
        }
    }

    private void applyMassEvents() {
        if (partCount != 0) {
            return;
        }
        for (int k = 0; k < eventNodes.size; k++) {
            int id = eventNodes.data[k];
            double add = eventAdds[id];
            state[2 * id] += add;
            injected += add;
            double remove = Math.min(state[2 * id], eventRemoves[id]);
            state[2 * id] -= remove;
            actuallyRemoved += remove;
            eventAdds[id] = eventRemoves[id] = 0;
            eventMarked[id] = false;
        }
        eventNodes.clear();
        pendingAdded = 0;
    }

    private void synchronizeGeometry() {
        if (touched.size == 0) {
            return;
        }
        // Legacy sequential laws cannot locally undo an already-applied flux.
        if (law != SmogConfig.QUADRATIC_FAST && queuedChanges.size != 0) {
            cancelStep();
        }
        for (int k = 0; k < touched.size; k++) {
            int box = touched.data[k];
            touchedBoxes[box] = false;
            if (queuedPerBox[box] == 0 && !sourceBoxPending[box]) {
                continue;
            }
            sourceBoxPending[box] = queuedPerBox[box] != 0;
            for (int i = sourceHead[box]; i >= 0; i = sourceNext[i]) {
                settleSource(i);
                sourceBlocked[i] = sourceNodes[i] < 0
                    || queuedChanges.value(sourcePositions[i], air[sourcePositions[i]]) == 0;
            }
            if (queuedPerBox[box] != 0) {
                freezeBox(box);
                if (partCount != 0) {
                    for (int i = sourceHead[box]; i >= 0; i = sourceNext[i]) {
                        if (!deferredSource[i] && sourceNodes[i] >= 0) {
                            deferredSource[i] = true;
                        }
                    }
                }
            }
        }
        touched.clear();
    }

    private void clearFrozen() {
        for (int i = 0; i < frozenNodes.size; i++) {
            frozen[frozenNodes.data[i]] = false;
        }
        frozenNodes.clear();
    }

    private void freezeQueuedBoxes() {
        // Snapshot only queued keys, never scan the whole world or all sources.
        int count = queuedChanges.size;
        if (queuePositions.length < count) {
            queuePositions = new int[count];
            queueValues = new byte[count];
        }
        queuedChanges.copy(queuePositions, queueValues);
        for (int i = 0; i < count; i++) {
            int box = containerOf(queuePositions[i]);
            if (!touchedBoxes[box]) {
                touchedBoxes[box] = true;
                touched.add(box);
            }
        }
        synchronizeGeometry();
    }

    private void freezeBox(int box) {
        int h = config.containerSize, phase = config.phase, x = box % containersX, t = box / containersX;
        int bx = x * h - phase, by = (t % containersY) * h - phase, bz = (t / containersY) * h - phase;
        for (int z = Math.max(0, bz); z < Math.min(nz, bz + h); z++) {
            for (int y = Math.max(0, by); y < Math.min(ny, by + h); y++) {
                for (int xx = Math.max(0, bx); xx < Math.min(nx, bx + h); xx++) {
                    int id = label[uncheckedIndex(xx, y, z)];
                    if (id < 0 || frozen[id]) {
                        continue;
                    }
                    frozen[id] = true;
                    frozenNodes.add(id);
                    if (partCount == 0) {
                        continue;
                    }
                    if (law != SmogConfig.QUADRATIC_FAST) {
                        continue;
                    }
                    for (int half = adjacency[id]; half >= 0; half = next[half]) {
                        int e = half / 2, a = endpoints[2 * e], b = endpoints[2 * e + 1];
                        boolean computed = edgeActiveSlot[e] < fluxDone;
                        if (computed) {
                            double ca = state[2 * a] * state[2 * a + 1], cb = state[2 * b] * state[2 * b + 1];
                            double flow = mobility[e] * (ca - cb)
                                / (1 + (1 / config.maxMixing) * Math.max(nodeLoad[a], nodeLoad[b]));
                            delta[a] += flow;
                            delta[b] -= flow;
                        }
                        mobility[e] = 0;
                        if (computed) {
                            recomputeEmptyDelta(a);
                            recomputeEmptyDelta(b);
                        }
                    }
                    // All incident fluxes are disabled; restore the exact committed mass.
                    // Subtracting many source halves one by one leaves rounding residues.
                    state[2 * id] = workState[2 * id];
                    delta[id] = 0;
                    escaped -= firstSinkLoss[id];
                    firstSinkLoss[id] = 0;
                }
            }
        }
    }

    // Empty receivers have only incoming flux. Re-sum surviving contributions,
    // rather than subtracting nearly equal sums and creating negative roundoff.
    // Only neighbours of newly quarantined nodes pay this local adjacency cost.
    private void recomputeEmptyDelta(int id) {
        if (frozen[id] || state[2 * id] != 0) {
            return;
        }
        double sum = 0, inverseMixing = 1 / config.maxMixing;
        for (int half = adjacency[id]; half >= 0; half = next[half]) {
            int e = half / 2;
            if (edgeActiveSlot[e] >= fluxDone || mobility[e] == 0) {
                continue;
            }
            int other = endpoints[half ^ 1];
            sum += mobility[e] * (state[2 * other] * state[2 * other + 1])
                / (1 + inverseMixing * Math.max(nodeLoad[id], nodeLoad[other]));
        }
        delta[id] = sum;
    }

    // Small phase methods keep the edit path readable and give HotSpot separate
    // compilation profiles for geometry discovery, state remapping and edge surgery.
    private void collectDirtyContainers(int[] positions, int countPositions, PatchWorkspace w) {
        for (int k = 0; k < countPositions; k++) {
            int p = positions[k];
            markContainer(p, w);
            int count = neighbors(p, w.firstNeighbors);
            for (int i = 0; i < count; i++) {
                int v = w.firstNeighbors[i];
                markContainer(v, w);
                if (config.mode == SmogConfig.THROAT) {
                    int secondCount = neighbors(v, w.secondNeighbors);
                    for (int j = 0; j < secondCount; j++) {
                        markContainer(w.secondNeighbors[j], w);
                    }
                }
            }
        }
        w.boxes.sort();
    }

    private void captureOldRegion(PatchWorkspace w) {
        int h = config.containerSize, phase = config.phase;
        for (int i = 0; i < w.boxes.size; i++) {
            int box = w.boxes.data[i], x = box % containersX, t = box / containersX;
            int bx = x * h - phase, by = (t % containersY) * h - phase, bz = (t / containersY) * h - phase;
            for (int z = Math.max(0, bz); z < Math.min(nz, bz + h); z++) {
                for (int y = Math.max(0, by); y < Math.min(ny, by + h); y++) {
                    for (int xx = Math.max(0, bx); xx < Math.min(nx, bx + h); xx++) {
                        int p = uncheckedIndex(xx, y, z), id = label[p];
                        w.region.add(p);
                        w.oldLabels.add(id);
                        if (id >= 0) {
                            if (w.oldSeen[id] != w.epoch) {
                                w.oldSeen[id] = w.epoch;
                                w.oldNodes.add(id);
                                w.oldMass[id] = state[2 * id];
                                w.survivors[id] = 0;
                            }
                            w.representative[id] = p;
                        }
                    }
                }
            }
        }
    }

    private void removeOldRegion(PatchWorkspace w) {
        for (int i = 0; i < w.region.size; i++) {
            int old = w.oldLabels.data[i];
            if (old >= 0 && air[w.region.data[i]] != 0) {
                w.survivors[old]++;
            }
        }
        // deterministic reuse; C++ unordered_map iteration was unspecified
        w.oldNodes.sort();
        for (int i = 0; i < w.oldNodes.size; i++) {
            removeNode(w.oldNodes.data[i]);
        }
        for (int i = 0; i < w.region.size; i++) {
            int p = w.region.data[i];
            label[p] = -1;
            distance[p] = 0;
        }
    }

    private void repartitionRegion(PatchWorkspace w) {
        int h = config.containerSize, phase = config.phase;
        for (int i = 0; i < w.boxes.size; i++) {
            int box = w.boxes.data[i], x = box % containersX, t = box / containersX;
            partitioner.partition(x * h - phase, (t % containersY) * h - phase, (t / containersY) * h - phase);
        }
    }

    private void remapMass(PatchWorkspace w) {
        for (int i = 0; i < w.region.size; i++) {
            int p = w.region.data[i], id = label[p], old = w.oldLabels.data[i];
            if (id < 0) {
                continue;
            }
            if (w.freshSeen[id] != w.epoch) {
                w.freshSeen[id] = w.epoch;
                w.freshNodes.add(id);
            }
            if (old >= 0 && w.survivors[old] > 0) {
                state[2 * id] += w.oldMass[old] / w.survivors[old];
            }
            state[2 * id] += trapped.take(p);
        }
        for (int i = 0; i < w.oldNodes.size; i++) {
            int old = w.oldNodes.data[i];
            if (w.survivors[old] == 0 && w.oldMass[old] != 0) {
                trapped.add(w.representative[old], w.oldMass[old]);
            }
        }
    }

    private void rebuildLocalContacts(PatchWorkspace w) {
        for (int i = 0; i < w.region.size; i++) {
            int p = w.region.data[i];
            if (label[p] < 0) {
                continue;
            }
            int count = neighbors(p, w.firstNeighbors);
            for (int j = 0; j < count; j++) {
                int q = w.firstNeighbors[j], id = label[q];
                if (id < 0 || (w.freshSeen[id] == w.epoch && q < p)) {
                    continue;
                }
                contact(p, q, w.aggregate);
            }
            if (config.openMask != 0) {
                int x = p % nx, yz = p / nx;
                boundary[label[p]] += boundaryAt(p, x, yz % ny, yz / ny);
            }
        }
        w.aggregate.appendTo(this);
    }

    private void rebindSources(PatchWorkspace w) {
        for (int k = 0; k < w.boxes.size; k++) {
            for (int i = sourceHead[w.boxes.data[k]]; i >= 0; i = sourceNext[i]) {
                settleSource(i);
                sourceNodes[i] = label[sourcePositions[i]];
                sourceBlocked[i] = sourceNodes[i] < 0
                    || queuedChanges.value(sourcePositions[i], air[sourcePositions[i]]) == 0;
                lastReboundSources++;
            }
        }
    }

    private int containerOf(int p) {
        int x = p % nx, t = p / nx, phase = config.phase, h = config.containerSize;
        return (((t / ny + phase) / h) * containersY + (t % ny + phase) / h) * containersX + (x + phase) / h;
    }

    private void markContainer(int p, PatchWorkspace w) {
        int box = containerOf(p);
        if (!w.dirty[box]) {
            w.dirty[box] = true;
            w.boxes.add(box);
        }
    }

    private int neighbors(int p, int[] result) {
        int x = p % nx, t = p / nx, y = t % ny, z = t / ny, count = 0;
        if (x > 0) {
            result[count++] = p - 1;
        }
        if (x + 1 < nx) {
            result[count++] = p + 1;
        }
        if (y > 0) {
            result[count++] = p - nx;
        }
        if (y + 1 < ny) {
            result[count++] = p + nx;
        }
        if (z > 0) {
            result[count++] = p - plane;
        }
        if (z + 1 < nz) {
            result[count++] = p + plane;
        }
        return count;
    }

    private static final class PatchWorkspace {

        final IntList boxes = new IntList(), region = new IntList(), oldLabels = new IntList();

        final IntList oldNodes = new IntList(), freshNodes = new IntList();

        final EdgeAccumulator aggregate = new EdgeAccumulator();

        final boolean[] dirty;

        final int[] firstNeighbors = new int[6], secondNeighbors = new int[6];

        int[] oldSeen, freshSeen, survivors, representative;

        double[] oldMass;

        int epoch;

        PatchWorkspace(int nodes, int boxes) {
            dirty = new boolean[boxes];
            oldSeen = new int[nodes];
            freshSeen = new int[nodes];
            survivors = new int[nodes];
            representative = new int[nodes];
            oldMass = new double[nodes];
        }

        void ensureNodes(int capacity) {
            oldSeen = Arrays.copyOf(oldSeen, capacity);
            freshSeen = Arrays.copyOf(freshSeen, capacity);
            survivors = Arrays.copyOf(survivors, capacity);
            representative = Arrays.copyOf(representative, capacity);
            oldMass = Arrays.copyOf(oldMass, capacity);
        }

        void begin() {
            boxes.clear();
            region.clear();
            oldLabels.clear();
            oldNodes.clear();
            freshNodes.clear();
            aggregate.clear();
            if (++epoch == 0) {
                Arrays.fill(oldSeen, 0);
                Arrays.fill(freshSeen, 0);
                epoch = 1;
            }
        }

        long bytes() {
            return dirty.length + 48L
                + 24L * oldSeen.length
                + aggregate.bytes()
                + 4L * (boxes.data.length + region.data.length
                    + oldLabels.data.length
                    + oldNodes.data.length
                    + freshNodes.data.length);
        }
    }
}
