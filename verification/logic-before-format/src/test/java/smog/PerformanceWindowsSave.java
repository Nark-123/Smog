package smog;

/** Full save/batch regression with the Windows directory-fsync branch selected on Linux. */
public final class PerformanceWindowsSave {
    public static void main(String[] args) throws Exception {
        java.nio.file.FileSystems.getDefault();
        java.lang.management.ManagementFactory.getThreadMXBean();
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            PerformanceFixTests.main(args);
            System.out.println("PERFORMANCE_WINDOWS_SAVE_SIMULATION_PASS");
        } finally {
            System.setProperty("os.name", original);
        }
    }
}
