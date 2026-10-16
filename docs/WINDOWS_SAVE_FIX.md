# SMOG GTNH 2.8.4 - Windows directory fsync crash fix

## Observed error

SMOG checkpoint write failed in a GTNH 2.8.4 integrated server on Windows 11,
Java 25: `AccessDeniedException` from `Checkpoint.forceDirectory(core)`.
The Java NIO `FileChannel.open(directory, READ)` operation is not portable to Windows.

## Changes

- `smog.Checkpoint.forceDirectory`: on Windows, skip attempts to open a
  directory as `FileChannel`; for other platforms, treat `AccessDeniedException`
  and `UnsupportedOperationException` for DIRECTORY fsync as unsupported.
- `org.smogmod.persistence.WorldStore.force`: same Windows handling for
  `bundle/` and the parent `dimension_*` directory. Regular files still require
  `FileChannel.force(true)` and any regular-file error is propagated.
- No changes to geometry, physics, GT5U, Mixins, loading/unloading, or JAR
  manifest. Atomic replacements, hashes, and previous-checkpoint fallback are kept.

## Durability nuance

On Windows, checkpoint file contents are flushed before atomic rename, and
atomic renames remain mandatory. However, Java cannot portably force directory
metadata to stable storage; an abrupt power loss can have weaker guarantees for
recently renamed directory entries. Existing previous-generation fallback remains.

## Verification

- Compiled changed classes with `javac --release 8` (Java 8 bytecode, major 52).
- Ran `smog.CoreIntegrationSmoke` with the repacked JAR: PASS.
- Ran `smog.WindowsSaveSmoke`, which initializes the native Linux filesystem,
  then simulates `os.name=Windows 11` for the code branches: PASS.
- Compared ZIP members: only `Checkpoint` and its nested compiled classes, plus
  `WorldStore.class`, differ from the GTNH 2.8.4 compatibility JAR.
- Not tested on a real Windows system or a live full GTNH client in this environment.

## Installation

1. Back up the test world, particularly `saves/<world>/data/smog/`.
2. Keep exactly ONE SMOG JAR in `mods`; replace
   `SMOG_GTNH_0.1.0_GTNH_2.8.4_compat.jar` with the WindowsSaveFix JAR.
3. Start GTNH 2.8.4 and verify world auto-save and `/smog save` if provided.
4. Test restore after restart. Existing valid save files must be preserved.

An orphan `bundle-*` directory from a previously failed initial save can remain;
SMOG loads using `CURRENT` and `PREVIOUS` pointers, so do not delete any save
files until they have been backed up and inspected.
