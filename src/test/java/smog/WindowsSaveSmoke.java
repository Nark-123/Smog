package smog;

import java.nio.file.FileSystems;

/** Exercises the Windows directory-sync branch with the full checkpoint smoke. */
public final class WindowsSaveSmoke {

    public static void main(String[] args) throws Exception {
        // Initialize the native filesystem provider before simulating os.name.
        FileSystems.getDefault();
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            CoreIntegrationSmoke.main(args);
            System.out.println("SMOG_WINDOWS_SAVE_BRANCH_PASS");
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }
}
