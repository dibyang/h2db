/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.MVStoreTool;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.util.IOUtils;

/**
 * Tests the dedicated MVStore space reclamation scaffolding.
 */
public class TestMVStoreSpaceReclamation extends TestBase {

    private static final int BLOAT_ENTRY_COUNT = 96;
    private static final int BLOAT_VALUE_SIZE = 128 * 1024;
    private static final long BLOAT_MIN_FILE_SIZE = 4L * 1024 * 1024;
    private static final byte MARKER = 42;
    private static final String KEEP_FILES = "h2.test.mvStoreSpaceReclamation.keepFiles";

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
        runScenario("T-SPACE-BLOAT-BASELINE-01", this::testBloatBaseline);
        runScenario("T-SHADOW-COMPACT-SHRINK-01", this::testShadowCompactShrinksBloatedStore);
        runScenario("T-ONLINE-COMPACT-BLOCKS-WRITES-01", this::testMaintenanceGateBlocksWrites);
        runScenario("T-ONLINE-COMPACT-VERIFY-FAIL-01", this::testVerifyFailureKeepsSource);
        runScenario("T-ONLINE-COMPACT-CRASH-BEFORE-SWITCH-01", this::testCrashBeforeSwitchKeepsSource);
        runScenario("T-ONLINE-COMPACT-CRASH-DURING-SWITCH-01", this::testCrashDuringSwitchRecoversSource);
        runScenario("T-ONLINE-COMPACT-FAULT-MATRIX-01", this::testFaultInjectionMatrixKeepsReadableStore);

        if (!failures.isEmpty()) {
            fail("MVStore space reclamation scenario failures: " + failures);
        }
    }

    private void runScenario(String id, Scenario scenario) throws Exception {
        try {
            scenario.run();
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) {
                throw (OutOfMemoryError) t;
            }
            failures.add(id + ": " + rootMessage(t));
        }
    }

    private void testBloatBaseline() {
        String base = mvStoreFile("bloatBaseline");
        try {
            BloatStats stats = createBloatedStore(base);
            assertBloated(stats);
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testShadowCompactShrinksBloatedStore() {
        String base = mvStoreFile("shadowCompactShrink");
        String shadow = shadowFile(base);
        try {
            BloatStats stats = createBloatedStore(base);
            MVStoreTool.compact(base, shadow, false);
            assertShadowShrunk(stats.afterDeleteSize, shadow);
            assertOnlyMarkerReadable(shadow);
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
            deleteFilesUnlessKept(shadow);
        }
    }

    private void testMaintenanceGateBlocksWrites() {
        MaintenanceGate gate = new MaintenanceGate();
        gate.enter();
        try {
            assertFalse(gate.tryEnterWrite());
        } finally {
            gate.exit();
        }
        assertTrue(gate.tryEnterWrite());
    }

    private void testVerifyFailureKeepsSource() {
        String base = mvStoreFile("verifyFailure");
        try {
            createBloatedStore(base);
            SpaceReclamationFaultHarness harness = new SpaceReclamationFaultHarness(base);
            expectFault(FaultPoint.VERIFY, () -> harness.run(FaultPoint.VERIFY));
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testCrashBeforeSwitchKeepsSource() {
        String base = mvStoreFile("crashBeforeSwitch");
        try {
            createBloatedStore(base);
            SpaceReclamationFaultHarness harness = new SpaceReclamationFaultHarness(base);
            expectFault(FaultPoint.BEFORE_SWITCH, () -> harness.run(FaultPoint.BEFORE_SWITCH));
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testCrashDuringSwitchRecoversSource() {
        String base = mvStoreFile("crashDuringSwitch");
        try {
            createBloatedStore(base);
            SpaceReclamationFaultHarness harness = new SpaceReclamationFaultHarness(base);
            expectFault(FaultPoint.DURING_SWITCH, () -> harness.run(FaultPoint.DURING_SWITCH));
            harness.recover();
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testFaultInjectionMatrixKeepsReadableStore() {
        FaultPoint[] points = {
                FaultPoint.COPY,
                FaultPoint.MANIFEST_WRITE,
                FaultPoint.VERIFY,
                FaultPoint.BEFORE_SWITCH,
                FaultPoint.DURING_SWITCH,
                FaultPoint.CLEANUP
        };
        for (FaultPoint point : points) {
            String base = mvStoreFile("faultMatrix-" + point.name());
            try {
                createBloatedStore(base);
                SpaceReclamationFaultHarness harness = new SpaceReclamationFaultHarness(base);
                expectFault(point, () -> harness.run(point));
                harness.recover();
                assertOnlyMarkerReadable(base);
            } finally {
                deleteFilesUnlessKept(base);
            }
        }
    }

    private void expectFault(FaultPoint point, Scenario scenario) {
        try {
            scenario.run();
            fail("Fault was not injected: " + point);
        } catch (FaultInjectedException expected) {
            assertTrue(expected.getMessage().contains(point.name()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private BloatStats createBloatedStore(String base) {
        deleteFiles(base);
        createParentDirectories(base);
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
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
            BloatStats stats = new BloatStats(fullSize, FileUtils.size(base), store.getFillRate(),
                    store.getFileStore().getChunksFillRate());
            closeStore(store);
            store = null;
            return stats;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void assertBloated(BloatStats stats) {
        assertTrue("file did not grow enough: " + stats.afterDeleteSize, stats.afterDeleteSize > BLOAT_MIN_FILE_SIZE);
        assertTrue("file was unexpectedly shrunk: before=" + stats.fullSize + " afterDelete=" + stats.afterDeleteSize,
                stats.afterDeleteSize >= stats.fullSize / 2);
        assertTrue("store did not report sparse usage: fillRate=" + stats.fillRate + " chunksFillRate=" +
                        stats.chunksFillRate, stats.fillRate < 50 || stats.chunksFillRate < 50);
    }

    private void assertShadowShrunk(long bloatedSize, String shadow) {
        long compactedSize = FileUtils.size(shadow);
        assertTrue("shadow compact did not shrink enough: before=" + bloatedSize + " after=" + compactedSize,
                compactedSize < bloatedSize / 4);
        assertTrue("shadow file is still unexpectedly large: " + compactedSize, compactedSize < BLOAT_MIN_FILE_SIZE);
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

    private String mvStoreFile(String name) {
        return getBaseDir() + "/mvStoreSpaceReclamation/" + name + ".mv.db";
    }

    private static byte[] bloatBytes(int seed) {
        byte[] value = new byte[BLOAT_VALUE_SIZE];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    private static String shadowFile(String base) {
        return base + ".shadow";
    }

    private static String manifestFile(String base) {
        return base + ".compact.manifest";
    }

    private static String backupFile(String base) {
        return base + ".before-compact";
    }

    private static void copyFile(String source, String target) throws IOException {
        createParentDirectories(target);
        try (InputStream in = FileUtils.newInputStream(source); OutputStream out = FileUtils.newOutputStream(target,
                false)) {
            IOUtils.copy(in, out);
        }
    }

    private static void writeText(String fileName, String text) throws IOException {
        createParentDirectories(fileName);
        try (OutputStream out = FileUtils.newOutputStream(fileName, false)) {
            out.write(text.getBytes("UTF-8"));
        }
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
        FileUtils.delete(shadowFile(base));
        FileUtils.delete(shadowFile(base) + ".copy");
        FileUtils.delete(manifestFile(base));
        FileUtils.delete(backupFile(base));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }

    private enum FaultPoint {
        COPY,
        MANIFEST_WRITE,
        VERIFY,
        BEFORE_SWITCH,
        DURING_SWITCH,
        CLEANUP
    }

    private interface Scenario {
        void run() throws Exception;
    }

    private static final class MaintenanceGate {
        private boolean maintenance;

        void enter() {
            maintenance = true;
        }

        void exit() {
            maintenance = false;
        }

        boolean tryEnterWrite() {
            return !maintenance;
        }
    }

    private static final class SpaceReclamationFaultHarness {
        private final String source;
        private final String shadow;
        private final String manifest;
        private final String backup;

        SpaceReclamationFaultHarness(String source) {
            this.source = source;
            this.shadow = shadowFile(source);
            this.manifest = manifestFile(source);
            this.backup = backupFile(source);
        }

        void run(FaultPoint faultPoint) throws Exception {
            if (faultPoint == FaultPoint.MANIFEST_WRITE) {
                throw fault(faultPoint);
            }
            writeText(manifest, "phase=PREPARING\nsource=" + source + "\nshadow=" + shadow + "\n");
            if (faultPoint == FaultPoint.COPY) {
                throw fault(faultPoint);
            }
            MVStoreTool.compact(source, shadow, false);
            if (faultPoint == FaultPoint.VERIFY) {
                writeText(shadow, "not a valid MVStore");
            }
            verifyShadow(faultPoint);
            if (faultPoint == FaultPoint.BEFORE_SWITCH) {
                throw fault(faultPoint);
            }
            copyFile(source, backup);
            FileUtils.delete(source);
            if (faultPoint == FaultPoint.DURING_SWITCH) {
                throw fault(faultPoint);
            }
            FileUtils.moveAtomicReplace(shadow, source);
            if (faultPoint == FaultPoint.CLEANUP) {
                throw fault(faultPoint);
            }
            cleanup();
        }

        void recover() {
            if (!FileUtils.exists(source) && FileUtils.exists(backup)) {
                FileUtils.moveAtomicReplace(backup, source);
            }
            if (!FileUtils.exists(source) && FileUtils.exists(shadow)) {
                FileUtils.moveAtomicReplace(shadow, source);
            }
            cleanup();
        }

        private void verifyShadow(FaultPoint faultPoint) {
            MVStore store = null;
            try {
                store = new MVStore.Builder().fileName(shadow).autoCommitDisabled().open();
                MVMap<Integer, byte[]> data = store.openMap("data");
                byte[] marker = data.get(-1);
                if (marker == null || marker.length != 1 || marker[0] != MARKER) {
                    throw fault(FaultPoint.VERIFY);
                }
                store.close();
                store = null;
            } catch (MVStoreException e) {
                if (faultPoint == FaultPoint.VERIFY) {
                    throw fault(FaultPoint.VERIFY);
                }
                throw e;
            } finally {
                closeStoreImmediately(store);
            }
        }

        private void cleanup() {
            FileUtils.delete(manifest);
            FileUtils.delete(shadow);
            FileUtils.delete(backup);
        }

        private static FaultInjectedException fault(FaultPoint point) {
            return new FaultInjectedException("Injected fault: " + point.name());
        }
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

    private static final class FaultInjectedException extends RuntimeException {
        FaultInjectedException(String message) {
            super(message);
        }
    }
}
