package smog;

import java.util.IdentityHashMap;
import java.util.Map;

/** Server-thread, weighted deficit budget. Charges ALL SMOG runtime work to the shared pool. */
public final class GeometryWorkBudget {
    private static final long FLOOR = 50000L;
    private static final int FAIR_TICKS = 20;
    private static final class Account {
        int weight, missed;
        long credit;
        boolean offered, served;
    }
    private final long configured = RestartOptions.millis("smog.serverGeometryBudgetMs",6,1,40);
    private final Map<Object,Account> accounts = new IdentityHashMap<>();
    private long started, previous, previousSmog, used, remaining, pool;
    private boolean allocated;
    private final long[] tickDurations = new long[1200];
    private int tickCount, tickCursor;
    private boolean tickStatsDirty;
    private double p95, p99, maximum;

    public void begin(long now) {
        started=now; used=0; allocated=false;
        // Leave measured solver/source/adapter cost in the headroom estimate.
        pool=Math.max(1000000L,Math.min(configured,49000000L-previous));
        pool=Math.max(1000000L,pool-Math.min(pool/2,Math.max(0,previousSmog-configured)));
        remaining=pool;
        for (Account a:accounts.values()) { a.offered=false; a.served=false; a.weight=0; }
    }
    public void offer(Object owner,int weight) {
        Account a=accounts.computeIfAbsent(owner,k -> new Account());
        a.offered=true;
        a.weight=Math.max(0,weight);
        if (a.weight==0) { a.credit=0; a.missed=0; }
    }
    private void allocate() {
        long weights=0;
        for (Account a:accounts.values()) if (a.offered) weights+=a.weight;
        if (weights>0) for (Account a:accounts.values()) if (a.offered && a.weight>0)
            a.credit=Math.min(configured*2,a.credit+pool*a.weight/weights);
        allocated=true;
    }
    public long grant(Object owner,int currentWeight) {
        Account a=accounts.get(owner);
        if (a==null || !a.offered) {
            offer(owner,currentWeight); a=accounts.get(owner);
            // New work arriving after the tick inventory gets a small first unit.
            if (allocated && a.weight>0) a.credit+=Math.min(1000000L,remaining);
        } else if (a.weight==0 && currentWeight>0) {
            a.weight=currentWeight;
            if (allocated) a.credit+=Math.min(1000000L,remaining);
        }
        if (!allocated) allocate();
        if (currentWeight<=0 || a.served) return 0;
        a.served=true;
        long grant=Math.min(remaining,Math.max(0,a.credit));
        if (grant<FLOOR) {
            if (++a.missed>=FAIR_TICKS) grant=FLOOR;
            else grant=0;
        }
        if (grant>0) a.missed=0;
        return grant;
    }
    public void used(Object owner,long totalSmogNanos,long geometryNanos) {
        long total=Math.max(0,totalSmogNanos);
        used+=total; remaining=Math.max(0,remaining-total);
        Account a=accounts.get(owner);
        if (a!=null) a.credit=Math.max(-configured*2,a.credit-Math.max(0,geometryNanos));
    }
    public void forget(Object owner) { accounts.remove(owner); }
    public void end(long now) {
        previous=Math.max(0,now-started); previousSmog=used;
        tickDurations[tickCursor] = previous;
        tickCursor = (tickCursor + 1) % tickDurations.length;
        tickCount = Math.min(tickDurations.length, tickCount + 1);
        tickStatsDirty = true;
        maximum = Math.max(maximum, previous / 1e6);
    }
    /** Sort only at diagnostic logging time; shared across dimensions in this server tick. */
    public void updateMsptMetrics(RestartMetrics metrics) {
        if (tickStatsDirty && tickCount != 0) {
            long[] sorted = java.util.Arrays.copyOf(tickDurations, tickCount);
            java.util.Arrays.sort(sorted);
            p95 = sorted[(int)Math.ceil(tickCount * .95) - 1] / 1e6;
            p99 = sorted[(int)Math.ceil(tickCount * .99) - 1] / 1e6;
            tickStatsDirty = false;
        }
        metrics.set("server_mspt_p95", p95); metrics.set("server_mspt_p99", p99);
        metrics.set("server_mspt_max", maximum);
    }
    public long previousNanos() { return previous; }
    public double previousMspt() { return previous/1e6; }
    public double previousSmogMs() { return previousSmog/1e6; }
}
