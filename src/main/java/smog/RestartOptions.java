package smog;

/** Experimental, independently switchable restart paths. Read once per dimension. */
final class RestartOptions {

    final boolean cache = Boolean.parseBoolean(System.getProperty("smog.geometryCacheEnabled", "true"));
    final boolean fast = Boolean.parseBoolean(System.getProperty("smog.fastRestoreEnabled", "true"));
    final boolean optimistic = Boolean.parseBoolean(System.getProperty("smog.optimisticRestore", "true"));
    final boolean epochAppend = Boolean.parseBoolean(System.getProperty("smog.epochAppendRestoreEnabled", "false"));
    final boolean epochRegions = Boolean.parseBoolean(System.getProperty("smog.epochRegionalGroupsEnabled", "true"));
    final int regionCells = Math.max(8, Math.min(4096, Integer.getInteger("smog.epochRegionCells", 512)));
    final int regionNodes = Math.max(512, Math.min(65536, Integer.getInteger("smog.epochRegionNodes", 32768)));
    final int regionEdges = Math.max(16384, Math.min(1048576, Integer.getInteger("smog.epochRegionEdges", 131072)));
    final int microCells = Math.max(1, Math.min(256, Integer.getInteger("smog.epochMicroCells", 64)));
    final int frontQuota = Math.max(1, Math.min(16, Integer.getInteger("smog.epochFrontQuota", 1)));
    final boolean epochFrontLimit = Boolean.parseBoolean(System.getProperty("smog.epochFrontLimitEnabled", "true"));
    final boolean epochCompactContacts = Boolean
        .parseBoolean(System.getProperty("smog.epochCompactContactsEnabled", "true"));
    final long restoreBudget = millis("smog.restoreBudgetMs", 4, 1, 20);
    final long validationBudget = millis("smog.validationBudgetMs", 1, 0, 10);
    final int preparedCubes = Math.max(1, Math.min(16, Integer.getInteger("smog.restorePreparedCubes", 8)));
    final int batchNodes = Math.max(512, Math.min(4096, Integer.getInteger("smog.fastRestoreMaxNodes", 2048)));
    final int batchEdges = Math.max(1728, Math.min(16384, Integer.getInteger("smog.fastRestoreMaxEdges", 16384)));
    final long cacheBytes = Math.max(1, Math.min(256, Integer.getInteger("smog.geometryCacheMiB", 32))) * 1048576L;

    static long millis(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, Integer.getInteger(key, fallback))) * 1000000L;
    }
}
