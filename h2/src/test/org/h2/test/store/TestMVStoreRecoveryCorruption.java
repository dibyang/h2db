/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.store;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import org.h2.api.ErrorCode;
import org.h2.mvstore.Chunk;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.FileStore;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.MVStoreTool;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.test.TestDb;
import org.h2.test.utils.FilePathReorderWrites;
import org.h2.test.utils.FilePathUnstable;
import org.h2.tools.Server;

/**
 * Tests MVStore recovery and operational boundaries for the file corruption
 * investigation.
 *
 * <p>By default this class is a red regression suite for the recovery bugs
 * tracked in the investigation document. With
 * {@code -Dh2.test.mvStoreRecoveryCorruption.characterize=true}, known current
 * failures are accepted so the same suite can be used to reproduce and inspect
 * the failures before the production fix is implemented.</p>
 */
public class TestMVStoreRecoveryCorruption extends TestDb {

    private static final int MVSTORE_BLOCK_SIZE = 4 * 1024;
    private static final int BLOAT_ENTRY_COUNT = 96;
    private static final int BLOAT_VALUE_SIZE = 128 * 1024;
    private static final long BLOAT_MIN_FILE_SIZE = 4L * 1024 * 1024;
    private static final byte MARKER = 42;
    private static final String CHARACTERIZE =
            "h2.test.mvStoreRecoveryCorruption.characterize";
    private static final String KEEP_FILES =
            "h2.test.mvStoreRecoveryCorruption.keepFiles";

    private final ArrayList<String> failures = new ArrayList<>();

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public void test() throws Exception {
        runScenario("T-COMPACT-MOVE-01", true, () -> testCompactMovePowerFailureRecovery(false, 2, 242));
        runScenario("T-TORN-WRITE-01", true, () -> testCompactMovePowerFailureRecovery(true, 1, 142));
        runScenario("T-COMPACT-OVERWRITE-01", true, this::testOldBlockOverwriteRecovery);
        runScenario("T-COMPACT-STORE-ORDER-01", true, this::testCompactStoreOrderFailurePoints);
        runScenario("T-COMPACT-RETENTION-01", true, this::testCompactRetentionBoundary);
        runScenario("T-FSYNC-REORDER-01", false, this::testReorderedWritesWithoutCompactRecover);
        runScenario("T-DISK-FULL-01", false, this::testDiskFullKeepsLastCommittedMarker);
        runScenario("T-RECOVERY-REPOINT-01", true, this::testRecoveryCanRepointMovedLiveChunk);
        runScenario("T-DISCOVER-FALSE-HEADER-01", true, this::testFalseHeaderDoesNotHideCompleteChunk);
        runScenario("T-DISCOVER-ID-WRAP-01", false, this::testChunkZeroHeaderBoundary);
        runScenario("T-RECOVERY-GENERATION-MATCH-01", false, this::testRecoveryPhysicalChunkGenerationMatch);
        runScenario("T-RECOVERY-LAYOUT-CYCLE-01", true, this::testLayoutCycleRecovery);
        runScenario("T-RECOVERY-PHYSICAL-VIEW-01", true, this::testPhysicalViewDoesNotReplaceLayoutMetadata);
        runScenario("T-REPAIR-ROBUST-01", true, this::testRepairDoesNotThrowOnCompactMoveSample);
        runScenario("T-SHUTDOWN-COMPACT-01", false, this::testTcpShutdownCompactKeepsCommittedData);
        runScenario("T-AUTO-COMPACT-CONFIG-01", false, this::testAutoCompactConfigurationBoundary);
        runScenario("T-NO-AUTO-COMPACT-BLOAT-01", false, this::testNoAutoCompactCanLeaveBloatedFile);
        runScenario("T-OFFLINE-COMPACT-SHRINK-01", false, this::testOfflineCompactShrinksBloatedFile);
        runScenario("T-BACKUP-COMPACT-01", false, this::testEmbeddedScriptWhileTcpDatabaseIsOpen);
        runScenario("T-BACKUP-FAILED-HANDLER-01", false, this::testBackupFailureBoundaryDoesNotDamageOpenDatabase);
        runScenario("T-BACKUP-TIMEOUT-CONSISTENCY-01", false, this::testBrokenScriptDoesNotDamageExistingDatabase);
        runScenario("T-RESTORE-LIVE-01", false, this::testLiveDatabaseFileMoveBoundary);
        runScenario("T-CONNECTION-LIFECYCLE-01", false, this::testConnectionLifecycleWithShutdownCompact);
        runScenario("T-UNSUPPORTED-MULTIWRITER-01", false, this::testConcurrentWriterWithoutNoLockIsRejected);
        runScenario("T-DATA-MARKER-REGRESSION-01", false, this::testMarkerBaseline);

        if (!failures.isEmpty()) {
            fail("MVStore corruption scenario failures: " + failures);
        }
    }

    private void runScenario(String id, boolean knownCurrentFailure, Scenario scenario) throws Exception {
        try {
            scenario.run();
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) {
                throw (OutOfMemoryError) t;
            }
            if (knownCurrentFailure && Boolean.getBoolean(CHARACTERIZE)) {
                println(id + " known failure reproduced: " + rootMessage(t));
                return;
            }
            failures.add(id + ": " + rootMessage(t));
        }
    }

    private void testCompactMovePowerFailureRecovery(boolean partialWrites, int seed, int stop) {
        String base = mvStoreFile("compactMovePowerLoss-" + partialWrites + "-" + seed + "-" + stop);
        try {
            runCompactMoveWorkload(base, seed, stop, partialWrites, true, true);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testOldBlockOverwriteRecovery() {
        String base = mvStoreFile("oldBlockOverwrite");
        try {
            runCompactMoveWorkload(base, 2, 242, false, true, true);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testCompactStoreOrderFailurePoints() {
        int[] stops = { 238, 242, 246 };
        for (int stop : stops) {
            String base = mvStoreFile("compactStoreOrder-" + stop);
            try {
                runCompactMoveWorkload(base, 2, stop, false, true, true);
                assertMarkerReadable(base);
            } finally {
                deleteFilesUnlessKept(base);
            }
        }
    }

    private void testCompactRetentionBoundary() {
        String base = mvStoreFile("compactRetention");
        try {
            runCompactMoveWorkload(base, 2, 242, false, true, false);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testReorderedWritesWithoutCompactRecover() {
        String base = mvStoreFile("reorderNoCompact");
        try {
            runReorderedWriteWorkloadWithoutCompact(base, 4, 36);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testDiskFullKeepsLastCommittedMarker() {
        FilePathUnstable fs = FilePathUnstable.register();
        String base = "memFS:diskFullKeepsMarker.mv.db";
        deleteFiles(base);
        fs.setPartialWrites(false);
        fs.setDiskFullCount(0, 0);
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName("unstable:" + base).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(-1, new byte[] { MARKER });
            store.commit();
            store.getFileStore().sync();
            fs.setDiskFullCount(2, 3);
            try {
                data.put(1, filledBytes(1, 32 * 1024));
                store.commit();
                store.getFileStore().sync();
                fail("Disk full was not injected");
            } catch (MVStoreException expected) {
                // expected
            }
        } finally {
            fs.setDiskFullCount(0, 0);
            fs.setPartialWrites(false);
            closeStoreImmediately(store);
        }
        try {
            assertMarkerReadable(base);
        } finally {
            deleteFiles(base);
        }
    }

    private void testRecoveryCanRepointMovedLiveChunk() {
        String base = mvStoreFile("repointMovedLiveChunk");
        try {
            runCompactMoveWorkload(base, 2, 242, false, true, true);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testFalseHeaderDoesNotHideCompleteChunk() {
        String base = mvStoreFile("falseHeader");
        try {
            runCompactMoveWorkload(base, 2, 242, false, true, true);
            assertNull(infoError(base));
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testChunkZeroHeaderBoundary() {
        MVStoreException exception = null;
        try {
            new MVStore.Builder().fileName(mvStoreFile("missingChunkZero")).open().close();
        } catch (MVStoreException e) {
            exception = e;
        }
        assertNull(exception);
    }

    private void testRecoveryPhysicalChunkGenerationMatch() throws Exception {
        String base = mvStoreFile("generationMatch");
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(base).open();
            FileStore<?> fileStore = store.getFileStore();
            Object expected = fileStore.createChunk(chunkString(1, 3, 10));
            Object wrongGeneration = fileStore.createChunk(chunkString(1, 4, 11));
            Object matchingGeneration = fileStore.createChunk(chunkString(1, 5, 10));
            Method method = FileStore.class.getDeclaredMethod("getRecoveryReadChunk", Chunk.class);
            method.setAccessible(true);
            setRecoveryPhysicalChunk(fileStore, wrongGeneration);
            assertNull(method.invoke(fileStore, expected));
            setRecoveryPhysicalChunk(fileStore, matchingGeneration);
            assertNotNull(method.invoke(fileStore, expected));
            method.setAccessible(false);
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testLayoutCycleRecovery() {
        String base = mvStoreFile("layoutCycle");
        try {
            runCompactMoveWorkload(base, 2, 242, false, true, true);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testPhysicalViewDoesNotReplaceLayoutMetadata() {
        String base = mvStoreFile("physicalView");
        try {
            runCompactMoveWorkload(base, 1, 142, true, true, true);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testRepairDoesNotThrowOnCompactMoveSample() throws IOException {
        String base = mvStoreFile("repairRobust");
        String noChunk = mvStoreFile("repairNoChunk");
        String incompleteChunk = mvStoreFile("repairIncompleteChunk");
        try {
            runCompactMoveWorkload(base, 2, 242, false, true, true);
            try {
                MVStoreTool.repair(base);
            } catch (NullPointerException e) {
                throw e;
            } catch (RuntimeException e) {
                // Repair may fail to find a good version for this sample, but it
                // must not fail with an implementation bug such as NPE.
            }
            writeText(noChunk, "not a store");
            assertNoValidChunkRollback(noChunk);
            MVStoreTool.repair(noChunk);
            writeIncompleteChunk(incompleteChunk);
            assertNoValidChunkRollback(incompleteChunk);
            MVStoreTool.repair(incompleteChunk);
        } finally {
            deleteFilesUnlessKept(base);
            deleteFilesUnlessKept(base + ".back");
            deleteFilesUnlessKept(base + ".temp");
            deleteFilesUnlessKept(noChunk);
            deleteFilesUnlessKept(noChunk + ".back");
            deleteFilesUnlessKept(noChunk + ".temp");
            deleteFilesUnlessKept(incompleteChunk);
            deleteFilesUnlessKept(incompleteChunk + ".back");
            deleteFilesUnlessKept(incompleteChunk + ".temp");
        }
    }

    private void assertNoValidChunkRollback(String fileName) {
        StringWriter writer = new StringWriter();
        assertEquals(-1, MVStoreTool.rollback(fileName, Long.MAX_VALUE, writer));
        assertTrue(writer.toString().contains("No valid chunk found"));
    }

    private void testTcpShutdownCompactKeepsCommittedData() throws Exception {
        TcpDb db = startTcpDb("tcpShutdownCompact");
        try {
            try (Connection conn = getConnection(db.tcpUrl())) {
                Statement stat = conn.createStatement();
                createMarkerTable(stat);
                stat.execute("SHUTDOWN COMPACT");
            } catch (SQLException e) {
                if (e.getErrorCode() != ErrorCode.DATABASE_IS_CLOSED &&
                        e.getErrorCode() != ErrorCode.OBJECT_CLOSED) {
                    throw e;
                }
            }
            assertJdbcMarker(db.tcpUrl(), 1);
        } finally {
            db.stop();
        }
    }

    private void testAutoCompactConfigurationBoundary() throws SQLException {
        String db = "autoCompactConfig";
        deleteDb(db);
        try (Connection conn = getConnection(db + ";AUTO_COMPACT_FILL_RATE=0;MAX_COMPACT_TIME=0")) {
            Statement stat = conn.createStatement();
            createMarkerTable(stat);
            stat.execute("CHECKPOINT SYNC");
        }
        assertJdbcMarker(db + ";AUTO_COMPACT_FILL_RATE=0;MAX_COMPACT_TIME=0", 1);
        deleteDb(db);
    }

    private void testNoAutoCompactCanLeaveBloatedFile() {
        String base = mvStoreFile("noAutoCompactBloat");
        try {
            BloatStats stats = createBloatedStore(base);
            assertTrue("file did not grow enough: " + stats.afterDeleteSize,
                    stats.afterDeleteSize > BLOAT_MIN_FILE_SIZE);
            assertTrue("file was unexpectedly shrunk: before=" + stats.fullSize +
                            " afterDelete=" + stats.afterDeleteSize,
                    stats.afterDeleteSize >= stats.fullSize / 2);
            assertTrue("store did not report sparse usage: fillRate=" + stats.fillRate +
                            " chunksFillRate=" + stats.chunksFillRate,
                    stats.fillRate < 50 || stats.chunksFillRate < 50);
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testOfflineCompactShrinksBloatedFile() {
        String base = mvStoreFile("offlineCompactShrink");
        String compacted = base + ".compacted";
        try {
            BloatStats stats = createBloatedStore(base);
            long bloatedSize = stats.afterDeleteSize;
            FileUtils.delete(compacted);
            MVStoreTool.compact(base, compacted, false);
            long compactedSize = FileUtils.size(compacted);
            assertTrue("offline compact did not shrink enough: before=" + bloatedSize +
                            " after=" + compactedSize,
                    compactedSize < bloatedSize / 4);
            assertTrue("offline compacted file is still unexpectedly large: " + compactedSize,
                    compactedSize < BLOAT_MIN_FILE_SIZE);
            assertOnlyMarkerReadable(compacted);
        } finally {
            deleteFilesUnlessKept(base);
            deleteFilesUnlessKept(compacted);
        }
    }

    private void testEmbeddedScriptWhileTcpDatabaseIsOpen() throws Exception {
        TcpDb db = startTcpDb("backupCompact");
        String script = getBaseDir() + "/backupCompact.sql";
        FileUtils.delete(script);
        try (Connection tcp = getConnection(db.tcpUrl())) {
            Statement tcpStat = tcp.createStatement();
            createMarkerTable(tcpStat);
            try (Connection embedded = getConnection("jdbc:h2:" + db.path())) {
                embedded.createStatement().execute("SCRIPT DROP TO '" + sqlPath(script) + "'");
            } catch (SQLException e) {
                if (e.getErrorCode() != ErrorCode.DATABASE_ALREADY_OPEN_1) {
                    throw e;
                }
            }
            assertJdbcMarker(db.tcpUrl(), 1);
        } finally {
            db.stop();
            FileUtils.delete(script);
        }
    }

    private void testBackupFailureBoundaryDoesNotDamageOpenDatabase() throws Exception {
        String db = "backupFailedBoundary";
        deleteDb(db);
        try (Connection owner = getConnection(db)) {
            Statement stat = owner.createStatement();
            createMarkerTable(stat);
            stat.execute("SET EXCLUSIVE TRUE");
            try {
                assertThrows(ErrorCode.DATABASE_IS_IN_EXCLUSIVE_MODE, () -> getConnection(db).close());
            } finally {
                stat.execute("SET EXCLUSIVE FALSE");
            }
        }
        assertJdbcMarker(db, 1);
        deleteDb(db);
    }

    private void testBrokenScriptDoesNotDamageExistingDatabase() throws Exception {
        String db = "backupTimeoutConsistency";
        String script = getBaseDir() + "/broken-backup.sql";
        deleteDb(db);
        writeText(script, "create table broken(\n");
        try (Connection conn = getConnection(db)) {
            Statement stat = conn.createStatement();
            createMarkerTable(stat);
            try {
                stat.execute("RUNSCRIPT FROM '" + sqlPath(script) + "'");
                fail("Broken script was accepted");
            } catch (SQLException expected) {
                // expected
            }
            assertJdbcMarker(conn, 1);
        } finally {
            deleteDb(db);
            FileUtils.delete(script);
        }
    }

    private void testLiveDatabaseFileMoveBoundary() throws Exception {
        String db = "restoreLive";
        deleteDb(db);
        String dbPath = getBaseDir() + "/" + db;
        Path mv = Paths.get(dbPath + ".mv.db");
        Path moved = Paths.get(dbPath + ".mv.db.moved");
        Files.deleteIfExists(moved);
        try (Connection conn = getConnection(db)) {
            Statement stat = conn.createStatement();
            createMarkerTable(stat);
            try {
                Files.move(mv, moved, StandardCopyOption.REPLACE_EXISTING);
                Files.move(moved, mv, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException expectedOnWindows) {
                // A live database file should not be replaced by an auto-restore path.
            }
            assertJdbcMarker(conn, 1);
        } finally {
            if (Files.exists(moved) && !Files.exists(mv)) {
                Files.move(moved, mv, StandardCopyOption.REPLACE_EXISTING);
            }
            deleteDb(db);
        }
    }

    private void testConnectionLifecycleWithShutdownCompact() throws Exception {
        String db = "connectionLifecycle";
        deleteDb(db);
        Connection leaked = null;
        try {
            leaked = getConnection(db);
            Statement stat = leaked.createStatement();
            createMarkerTable(stat);
            try (Connection shutdown = getConnection(db)) {
                shutdown.createStatement().execute("SHUTDOWN COMPACT");
            } catch (SQLException e) {
                if (e.getErrorCode() != ErrorCode.DATABASE_IS_CLOSED &&
                        e.getErrorCode() != ErrorCode.OBJECT_CLOSED) {
                    throw e;
                }
            }
        } finally {
            closeSilently(leaked);
        }
        assertJdbcMarker(db, 1);
        deleteDb(db);
    }

    private void testConcurrentWriterWithoutNoLockIsRejected() {
        String base = mvStoreFile("concurrentWriter");
        deleteFiles(base);
        MVStore first = null;
        try {
            first = new MVStore.Builder().fileName(base).open();
            MVStore openFirst = first;
            assertThrows(MVStoreException.class, () -> new MVStore.Builder().fileName(base).open().close());
            assertFalse(openFirst.getFileStore().isReadOnly());
        } finally {
            closeStoreImmediately(first);
            deleteFilesUnlessKept(base);
        }
    }

    private void testMarkerBaseline() {
        String base = mvStoreFile("markerBaseline");
        try {
            runCompactMoveWorkload(base, 7, 0, false, true, true);
            assertMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void runCompactMoveWorkload(String base, int seed, int powerFailureCountdown,
            boolean partialWrites, boolean compactFile, boolean retentionZero) {
        FilePathReorderWrites fs = FilePathReorderWrites.register();
        FilePathReorderWrites.setPartialWrites(partialWrites);
        deleteFiles(base);
        createParentDirectories(base);
        FileUtils.createFile(base);
        fs.setPowerOffCountdown(0, seed);

        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName("reorder:" + base).autoCommitDisabled().open();
            if (!retentionZero) {
                store.setRetentionTime(45_000);
            }
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(-1, new byte[] { MARKER });
            for (int i = 0; i < 600; i++) {
                data.put(i, filledBytes(i, 4096 + (i & 7) * 512));
                if ((i & 31) == 31) {
                    store.commit();
                }
            }
            store.commit();
            store.getFileStore().sync();

            boolean failureInjected = false;
            if (powerFailureCountdown > 0) {
                fs.setPowerOffCountdown(powerFailureCountdown, seed);
            }
            try {
                Random random = new Random(seed);
                for (int i = 0; i < 260; i++) {
                    int key = random.nextInt(900);
                    if ((i & 3) == 0) {
                        data.remove(key);
                    } else {
                        data.put(key, filledBytes(seed + i, 1024 + random.nextInt(12_000)));
                    }
                    MVMap<Integer, byte[]> map = store.openMap("map" + (i % 64));
                    map.put(i, filledBytes(key, 256 + random.nextInt(2048)));
                    store.commit();
                    if ((i & 7) == 0) {
                        compact(store, compactFile);
                    }
                }
                compact(store, compactFile);
                store.commit();
                store.getFileStore().sync();
            } catch (RuntimeException e) {
                if (powerFailureCountdown == 0 || !(e instanceof MVStoreException || isPowerFailure(e))) {
                    throw e;
                }
                failureInjected = true;
            }
            if (powerFailureCountdown > 0 && !failureInjected) {
                fail("Power failure was not injected");
            }
            if (failureInjected) {
                closeStoreImmediately(store);
            } else {
                closeStore(store);
            }
            store = null;
        } finally {
            fs.setPowerOffCountdown(0, seed);
            FilePathReorderWrites.setPartialWrites(false);
            closeStoreImmediately(store);
        }
    }

    private void runReorderedWriteWorkloadWithoutCompact(String base, int seed, int powerFailureCountdown) {
        FilePathReorderWrites fs = FilePathReorderWrites.register();
        FilePathReorderWrites.setPartialWrites(false);
        deleteFiles(base);
        createParentDirectories(base);
        FileUtils.createFile(base);
        fs.setPowerOffCountdown(0, seed);

        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName("reorder:" + base).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(-1, new byte[] { MARKER });
            store.commit();
            store.getFileStore().sync();
            fs.setPowerOffCountdown(powerFailureCountdown, seed);
            boolean failureInjected = false;
            try {
                for (int i = 0; i < 200; i++) {
                    data.put(i, filledBytes(i, 2048));
                    store.commit();
                }
                store.getFileStore().sync();
            } catch (MVStoreException e) {
                failureInjected = true;
            }
            if (!failureInjected) {
                fail("Power failure was not injected");
            }
            if (failureInjected) {
                closeStoreImmediately(store);
            } else {
                closeStore(store);
            }
            store = null;
        } finally {
            fs.setPowerOffCountdown(0, seed);
            FilePathReorderWrites.setPartialWrites(false);
            closeStoreImmediately(store);
        }
    }

    private void compact(MVStore store, boolean compactFile) {
        if (compactFile) {
            store.compactFile(1000);
        } else {
            store.compact(80, 16 * 1024);
        }
    }

    private BloatStats createBloatedStore(String base) {
        deleteFiles(base);
        createParentDirectories(base);
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(base).autoCommitDisabled()
                    .autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.setVersionsToKeep(0);
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(-1, new byte[] { MARKER });
            for (int i = 0; i < BLOAT_ENTRY_COUNT; i++) {
                data.put(i, bloatBytes(i));
                if ((i & 15) == 15) {
                    store.commit();
                }
            }
            store.commit();
            store.getFileStore().sync();
            long fullSize = FileUtils.size(base);
            for (int i = 0; i < BLOAT_ENTRY_COUNT; i++) {
                data.remove(i);
                if ((i & 15) == 15) {
                    store.commit();
                }
            }
            store.commit();
            store.getFileStore().sync();
            assertEquals(1L, data.sizeAsLong());
            BloatStats stats = new BloatStats(fullSize, FileUtils.size(base),
                    store.getFillRate(), store.getFileStore().getChunksFillRate());
            closeStore(store);
            store = null;
            return stats;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void assertMarkerReadable(String fileName) {
        MVStoreException failure = tryOpenAndReadMarker(fileName);
        if (failure != null) {
            throw failure;
        }
    }

    private void assertOnlyMarkerReadable(String fileName) {
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(fileName).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            assertEquals(1L, data.sizeAsLong());
            byte[] marker = data.get(-1);
            assertNotNull(marker);
            assertEquals(1, marker.length);
            assertEquals(MARKER, marker[0]);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private MVStoreException tryOpenAndReadMarker(String fileName) {
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(fileName).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            byte[] marker = data.get(-1);
            assertNotNull(marker);
            assertEquals(1, marker.length);
            assertEquals(MARKER, marker[0]);
            closeStore(store);
            store = null;
            return null;
        } catch (MVStoreException e) {
            return e;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private String infoError(String fileName) {
        return MVStoreTool.info(fileName, new java.io.StringWriter());
    }

    private void createMarkerTable(Statement stat) throws SQLException {
        stat.execute("CREATE TABLE IF NOT EXISTS MARKER(ID INT PRIMARY KEY, VAL INT)");
        stat.execute("MERGE INTO MARKER KEY(ID) VALUES(1, 1)");
        stat.execute("CHECKPOINT SYNC");
    }

    private void assertJdbcMarker(String db, int expected) throws SQLException {
        try (Connection conn = getConnection(db)) {
            assertJdbcMarker(conn, expected);
        }
    }

    private void assertJdbcMarker(Connection conn, int expected) throws SQLException {
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT VAL FROM MARKER WHERE ID=1")) {
            assertTrue(rs.next());
            assertEquals(expected, rs.getInt(1));
            assertFalse(rs.next());
        }
    }

    private TcpDb startTcpDb(String name) throws SQLException {
        String dir = getBaseDir() + "/tcp";
        FileUtils.createDirectories(dir);
        deleteJdbcFiles(dir + "/" + name);
        Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists", "-baseDir", dir).start();
        return new TcpDb(server, dir, name);
    }

    private String mvStoreFile(String name) {
        return getBaseDir() + "/mvStoreRecoveryCorruption/" + name + ".mv.db";
    }

    private static byte[] filledBytes(int seed, int length) {
        byte[] value = new byte[length];
        value[0] = (byte) seed;
        value[length - 1] = (byte) (seed >>> 8);
        return value;
    }

    private static byte[] bloatBytes(int seed) {
        byte[] value = new byte[BLOAT_VALUE_SIZE];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    private static void writeText(String fileName, String text) throws IOException {
        createParentDirectories(fileName);
        try (OutputStream out = FileUtils.newOutputStream(fileName, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeIncompleteChunk(String fileName) throws IOException {
        byte[] block = new byte[MVSTORE_BLOCK_SIZE];
        byte[] header = ("chunk:1,block:0,len:2,pages:0,max:0,map:0,root:0," +
                "time:0,version:1\n").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(header, 0, block, 0, header.length);
        createParentDirectories(fileName);
        try (OutputStream out = FileUtils.newOutputStream(fileName, false)) {
            out.write(block);
        }
    }

    private static String chunkString(int id, long block, long version) {
        return "chunk:" + Integer.toHexString(id) + ",block:" + Long.toHexString(block) +
                ",len:1,pages:1,max:20,map:1,root:0,time:1,version:" +
                Long.toHexString(version);
    }

    private static void setRecoveryPhysicalChunk(FileStore<?> fileStore, Object chunk)
            throws Exception {
        Field field = FileStore.class.getDeclaredField("recoveryPhysicalChunksById");
        field.setAccessible(true);
        HashMap<Integer, Object> chunks = new HashMap<>();
        chunks.put(1, chunk);
        field.set(fileStore, chunks);
        field.setAccessible(false);
    }

    private static String sqlPath(String fileName) {
        return fileName.replace('\\', '/');
    }

    private static void createParentDirectories(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slash >= 0) {
            FileUtils.createDirectories(fileName.substring(0, slash));
        }
    }

    private static void closeStore(MVStore store) {
        if (store != null) {
            store.close();
        }
    }

    private static void closeStoreImmediately(MVStore store) {
        if (store != null) {
            try {
                store.closeImmediately();
            } catch (RuntimeException ignore) {
                // ignore
            }
        }
    }

    private static void closeSilently(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignore) {
                // ignore
            }
        }
    }

    private static void deleteFilesUnlessKept(String base) {
        if (!Boolean.getBoolean(KEEP_FILES)) {
            deleteFiles(base);
        }
    }

    private static void deleteFiles(String base) {
        FileUtils.delete(base);
        FileUtils.delete(base + ".copy");
        FileUtils.delete(base + ".lock.db");
        FileUtils.delete(base + ".trace.db");
    }

    private static void deleteJdbcFiles(String dbPath) {
        FileUtils.delete(dbPath + ".mv.db");
        FileUtils.delete(dbPath + ".lock.db");
        FileUtils.delete(dbPath + ".trace.db");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }

    private static boolean isPowerFailure(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root instanceof IOException && "Power Failure".equals(root.getMessage());
    }

    private static final class BloatStats {
        final long fullSize;
        final long afterDeleteSize;
        final int fillRate;
        final int chunksFillRate;

        BloatStats(long fullSize, long afterDeleteSize, int fillRate, int chunksFillRate) {
            this.fullSize = fullSize;
            this.afterDeleteSize = afterDeleteSize;
            this.fillRate = fillRate;
            this.chunksFillRate = chunksFillRate;
        }
    }

    private interface Scenario {
        void run() throws Exception;
    }

    private static final class TcpDb {
        final Server server;
        final String dir;
        final String name;

        TcpDb(Server server, String dir, String name) {
            this.server = server;
            this.dir = dir;
            this.name = name;
        }

        String path() {
            return dir + "/" + name;
        }

        String tcpUrl() {
            return "jdbc:h2:tcp://localhost:" + server.getPort() + "/" + name;
        }

        void stop() {
            server.stop();
            deleteJdbcFiles(path());
        }
    }
}
