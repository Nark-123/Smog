package smog;

import java.util.Arrays;

/** No Minecraft needed: verifies the exclusive safe-call timing buckets and reset. */
public final class SafeWindowProfileTests {

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    public static void main(String[] args) {
        GeometryMetrics m = new GeometryMetrics();
        m.beginSafeCall(false);
        m.phase(GeometryMetrics.PARTITION, 11);
        m.publish(4);
        m.endSafeCall(25, 200, false, 2, 3);
        check(m.safeSnapshot()[GeometryMetrics.SAFE_CALLS] == 0, "Ordinary geometry call attributed to safe window");
        m.beginSafeCall(true);
        m.phase(GeometryMetrics.INTERNAL_CONTACTS, 100);
        m.phase(GeometryMetrics.EXTERNAL_CONTACTS, 20);
        m.worldValidation(4, true);
        m.contactValidation(6, false);
        m.refreshed(3);
        m.refreshedAdvance(9);
        m.publish(40);
        m.publicationEvents(8);
        m.front(10);
        m.endSafeCall(220, 200, true, 4, 2);
        long[] v = m.safeSnapshot();
        check(
            v[GeometryMetrics.SAFE_CALLS] == 1 && v[GeometryMetrics.SAFE_ELAPSED_NANOS] == 220
                && v[GeometryMetrics.SAFE_BUDGET_NANOS] == 200
                && v[GeometryMetrics.SAFE_BUDGET_ENDS] == 1
                && v[GeometryMetrics.SAFE_IDLE_ENDS] == 0,
            "Call profiling");
        check(
            v[GeometryMetrics.SAFE_BUILD_NANOS] == 120 && v[GeometryMetrics.SAFE_REFRESH_REBUILD_NANOS] == 9
                && v[GeometryMetrics.SAFE_PUBLISH_NANOS] == 40
                && v[GeometryMetrics.SAFE_APPLY_EVENTS_NANOS] == 8,
            "Operation accounting");
        check(
            v[GeometryMetrics.SAFE_WORLD_CHECK_NANOS] == 4 && v[GeometryMetrics.SAFE_CONTACT_CHECK_NANOS] == 6
                && v[GeometryMetrics.SAFE_REFRESH_REQUEST_NANOS] == 3
                && v[GeometryMetrics.SAFE_FRONT_NANOS] == 10
                && v[GeometryMetrics.SAFE_PUBLISHED] == 1,
            "Per-operation counters");
        check(
            v[GeometryMetrics.SAFE_PREPARED_START_SUM] == 4 && v[GeometryMetrics.SAFE_PREPARED_END_SUM] == 2,
            "Prepared occupancy");
        check(m.safeCallMaxNanos == 220, "Max geometry call");
        long other = v[GeometryMetrics.SAFE_ELAPSED_NANOS] - v[GeometryMetrics.SAFE_WORLD_CHECK_NANOS]
            - v[GeometryMetrics.SAFE_CONTACT_CHECK_NANOS]
            - v[GeometryMetrics.SAFE_REFRESH_REQUEST_NANOS]
            - v[GeometryMetrics.SAFE_BUILD_NANOS]
            - v[GeometryMetrics.SAFE_PUBLISH_NANOS]
            - v[GeometryMetrics.SAFE_APPLY_EVENTS_NANOS]
            - v[GeometryMetrics.SAFE_FRONT_NANOS];
        check(other == 29, "Timer categories double-counted");
        m.reset();
        check(
            Arrays.equals(m.safeSnapshot(), new long[GeometryMetrics.SAFE_COUNTER_COUNT]) && m.safeCallMaxNanos == 0,
            "Reset of safe counters");
        System.out.println("SMOG_SAFE_PROFILE_PASS");
    }
}
