package smog;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;

/** Optional Java 8 facilities, discovered once. Reads occur only during a rare snapshot. */
final class ProfileJvmStats {

    private static final ThreadMXBean THREADS;
    private static volatile boolean cpu;
    private static volatile Method allocated;
    static {
        ThreadMXBean threads = null;
        try {
            threads = ManagementFactory.getThreadMXBean();
            cpu = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        } catch (RuntimeException unavailable) {
            cpu = false;
        }
        THREADS = threads;
        try {
            Class<?> extension = Class.forName("com.sun.management.ThreadMXBean");
            if (extension.isInstance(THREADS)
                && Boolean.TRUE.equals(
                    extension.getMethod("isThreadAllocatedMemorySupported")
                        .invoke(THREADS))
                && Boolean.TRUE.equals(
                    extension.getMethod("isThreadAllocatedMemoryEnabled")
                        .invoke(THREADS)))
                allocated = extension.getMethod("getThreadAllocatedBytes", long.class);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            allocated = null;
        }
    }

    static long cpu() {
        if (!cpu) return -1;
        try {
            return THREADS.getCurrentThreadCpuTime();
        } catch (RuntimeException unavailable) {
            cpu = false;
            return -1;
        }
    }

    static long allocated() {
        Method method = allocated;
        if (method == null) return -1;
        try {
            return (Long) method.invoke(
                THREADS,
                Thread.currentThread()
                    .getId());
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            allocated = null;
            return -1;
        }
    }

    private ProfileJvmStats() {}
}
