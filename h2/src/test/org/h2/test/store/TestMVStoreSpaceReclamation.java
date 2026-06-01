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

import org.h2.mvstore.ChunkLivenessSnapshot;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.MVStoreOnlineReclamationResult;
import org.h2.mvstore.MVStoreReclamationAnalysis;
import org.h2.mvstore.MVStoreReclamationAnalyzer;
import org.h2.mvstore.MVStoreReclamationCode;
import org.h2.mvstore.MVStoreReclamationCoordinator;
import org.h2.mvstore.MVStoreReclamationRecovery;
import org.h2.mvstore.MVStoreReclamationRelocationMap;
import org.h2.mvstore.MVStoreReclamationRequest;
import org.h2.mvstore.MVStoreReclamationScheduler;
import org.h2.mvstore.MVStoreReclamationStatus;
import org.h2.mvstore.MVStoreSpaceReclamation;
import org.h2.mvstore.MVStoreSpaceReclamationAnalysis;
import org.h2.mvstore.MVStoreSpaceReclamationMaintenance;
import org.h2.mvstore.MVStoreSpaceReclamationOperationGate;
import org.h2.mvstore.MVStoreSpaceReclamationOptions;
import org.h2.mvstore.MVStoreSpaceReclamationEvent;
import org.h2.mvstore.MVStoreSpaceReclamationListener;
import org.h2.mvstore.MVStoreSpaceReclamationPhase;
import org.h2.mvstore.MVStoreSpaceReclamationRequestDecision;
import org.h2.mvstore.MVStoreSpaceReclamationResult;
import org.h2.mvstore.MVStoreTool;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.util.IOUtils;

/**
 * Tests the dedicated MVStore space reclamation scaffolding.
 */
public class TestMVStoreSpaceReclamation extends TestBase {

    private static final int BLOAT_ENTRY_COUNT = 96;
    private static final int LARGE_BLOAT_ENTRY_COUNT = 160;
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
        runScenario("T-MAINTENANCE-COMPACT-REPLACE-01", this::testMaintenanceCompactReplacesClosedStore);
        runScenario("T-SHADOW-COMPACT-SHRINK-01", this::testShadowCompactShrinksBloatedStore);
        runScenario("T-SHADOW-COMPACT-PREPARE-01", this::testShadowCompactPrepareDoesNotReplaceSource);
        runScenario("T-ONLINE-COMPACT-SWITCH-SHADOW-01", this::testSwitchToPreparedShadow);
        runScenario("T-ONLINE-COMPACT-CATCHUP-WRITES-01", this::testSwitchRejectsChangedSource);
        runScenario("T-ONLINE-COMPACT-CATCHUP-FEASIBILITY-01", this::testCatchUpFeasibilityAnalysis);
        runScenario("T-ONLINE-COMPACT-FULL-COPY-FALLBACK-01", this::testFullCopyFallbackForChangedSource);
        runScenario("T-ONLINE-COMPACT-SOURCE-FINGERPRINT-01", this::testManifestRecordsSourceFingerprint);
        runScenario("T-ONLINE-COMPACT-DIAGNOSTICS-01", this::testReclamationDiagnostics);
        runScenario("T-ONLINE-COMPACT-DIAGNOSTIC-LISTENER-01", this::testDiagnosticListener);
        runScenario("T-ONLINE-COMPACT-DIAGNOSTIC-LISTENER-FAIL-01", this::testDiagnosticListenerFailureIgnored);
        runScenario("T-ONLINE-COMPACT-API-STATUS-01", this::testApiStatus);
        runScenario("T-ONLINE-COMPACT-MANIFEST-RECOVER-01", this::testManifestRecoveryRestoresSource);
        runScenario("T-ONLINE-COMPACT-BLOCKS-WRITES-01", this::testMaintenanceGateBlocksWrites);
        runScenario("T-ONLINE-COMPACT-LONG-TRANSACTION-01", this::testLongTransactionBlocksSwitch);
        runScenario("T-ONLINE-COMPACT-TCP-BEHAVIOR-01", this::testTcpBehaviorDecisions);
        runScenario("T-ONLINE-COMPACT-BACKUP-INTERACTION-01", this::testBackupInteractionGate);
        runScenario("T-ONLINE-COMPACT-LARGE-STORE-01", this::testLargeStoreCompact);
        runScenario("T-ONLINE-COMPACT-SLOW-IO-01", this::testSlowIoCompact);
        runScenario("T-ONLINE-COMPACT-RESIDUAL-CLEANUP-01", this::testResidualCleanup);
        runScenario("T-ONLINE-COMPACT-VERIFY-FAIL-01", this::testVerifyFailureKeepsSource);
        runScenario("T-ONLINE-COMPACT-CRASH-BEFORE-SWITCH-01", this::testCrashBeforeSwitchKeepsSource);
        runScenario("T-ONLINE-COMPACT-CRASH-DURING-SWITCH-01", this::testCrashDuringSwitchRecoversSource);
        runScenario("T-ONLINE-COMPACT-FAULT-MATRIX-01", this::testFaultInjectionMatrixKeepsReadableStore);
        runScenario("T-S2-RECLAMATION-ANALYSIS-CANDIDATES-01", this::testReclamationAnalysisFindsCandidates);
        runScenario("T-S2-RECLAMATION-ANALYSIS-LOW-VALUE-01", this::testReclamationAnalysisRejectsLowValueStore);
        runScenario("T-S2-RECLAMATION-ANALYSIS-RECENT-CHUNK-01", this::testReclamationAnalysisReportsRecentChunks);
        runScenario("T-S2-RECLAMATION-ANALYSIS-VALIDATION-01", this::testReclamationAnalysisValidation);
        runScenario("T-S2-RECLAMATION-COORDINATOR-DRY-RUN-01", this::testReclamationCoordinatorDryRun);
        runScenario("T-S2-RECLAMATION-COORDINATOR-RUN-01", this::testReclamationCoordinatorRunsBoundedRewrite);
        runScenario("T-S2-RECLAMATION-COORDINATOR-SKIP-01", this::testReclamationCoordinatorSkipsWithoutCandidates);
        runScenario("T-S2-RECLAMATION-REQUEST-VALIDATION-01", this::testReclamationRequestValidation);
        runScenario("T-S2-PAGE-RELOCATION-OPEN-MAP-01", this::testPageRelocationReportsOpenMapProgress);
        runScenario("T-S2-PAGE-RELOCATION-LAZY-MAP-01", this::testPageRelocationCanUseLazyOpenedMaps);
        runScenario("T-S2-RECLAMATION-JOURNAL-CLEANUP-01", this::testReclamationJournalIsCleanedAfterRun);
        runScenario("T-S2-RECLAMATION-JOURNAL-RECOVER-EMPTY-01", this::testReclamationJournalRecoverWithoutJournal);
        runScenario("T-S2-RECLAMATION-JOURNAL-RECOVER-PUBLISHED-01",
                this::testReclamationJournalRecoversPublishedMarker);
        runScenario("T-S2-RECLAMATION-JOURNAL-RECOVER-UNPUBLISHED-01",
                this::testReclamationJournalRecoversUnpublishedMarker);
        runScenario("T-S2-RECLAMATION-JOURNAL-V1-META-01",
                this::testReclamationJournalV1Metadata);
        runScenario("T-S2-RECLAMATION-RECOVERY-ACTION-01",
                this::testReclamationRecoveryReportsCrashSafeAction);
        runScenario("T-S2-RECLAMATION-JOURNAL-RECOVER-REOPEN-01",
                this::testReclamationJournalRecoversAfterReopen);
        runScenario("T-S2-RECLAMATION-COORDINATOR-RECOVER-FIRST-01",
                this::testCoordinatorRecoversStaleJournalBeforeRun);
        runScenario("T-S2-RELOCATION-MAP-FEATURE-GATE-01", this::testRelocationMapFeatureGate);
        runScenario("T-S2-RELOCATION-MAP-RESOLVE-01", this::testRelocationMapResolvesPagePosition);
        runScenario("T-S2-RELOCATION-MAP-STRUCTURED-RESOLVE-01",
                this::testRelocationMapStructuredResolve);
        runScenario("T-S2-RELOCATION-MAP-EXPIRE-01", this::testRelocationMapRemovesExpiredMappings);
        runScenario("T-S2-RELOCATION-MAP-LIFECYCLE-01", this::testRelocationMapLifecycleClear);
        runScenario("T-S2-RELOCATION-MAP-COMPAT-GATE-01", this::testRelocationMapCompatibilityGate);
        runScenario("T-S2-TAIL-MOVER-BUDGET-01", this::testTailMoverRunsOnlyWithExplicitBudget);
        runScenario("T-S2-SCHEDULER-DISABLED-01", this::testSchedulerIsDisabledByDefault);
        runScenario("T-S2-SCHEDULER-ENABLED-01", this::testSchedulerRunsWhenEnabled);
        runScenario("T-S2-SCHEDULER-BACKOFF-01", this::testSchedulerBackoffAfterRun);
        runScenario("T-S2-DEFAULT-HOUSEKEEPING-ENABLED-01", this::testDefaultHousekeepingRunsOnlineReclamation);
        runScenario("T-S2-DEFAULT-HOUSEKEEPING-DISABLED-01", this::testHousekeepingCanDisableOnlineReclamation);
        runScenario("T-S2-DEFAULT-HOUSEKEEPING-CLOSED-01", this::testHousekeepingSkipsClosedStore);
        runScenario("T-S2-CONCURRENT-WRITE-RECLAMATION-01", this::testConcurrentWriteDuringOnlineReclamation);
        runScenario("T-S2-PERF-NO-CANDIDATE-FAST-01", this::testNoCandidateReclamationReturnsQuickly);
        runScenario("T-S2-PERF-BOUNDED-SPACE-BASELINE-01", this::testBoundedReclamationDoesNotGrowFile);

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

    private void testMaintenanceCompactReplacesClosedStore() {
        String base = mvStoreFile("maintenanceCompactReplace");
        try {
            BloatStats stats = createBloatedStore(base);
            MVStoreSpaceReclamationOptions options = MVStoreSpaceReclamationOptions.builder()
                    .keepBackup(true).build();
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(base, options);
            assertTrue(result.isReplaced());
            assertEquals(stats.afterDeleteSize, result.getSourceSize());
            assertTrue(result.getCompactedSize() < stats.afterDeleteSize / 4);
            assertTrue(FileUtils.exists(result.getBackupFileName()));
            assertOnlyMarkerReadable(base);
            assertOnlyMarkerReadable(result.getBackupFileName());
        } finally {
            deleteFilesUnlessKept(base);
            deleteFilesUnlessKept(base + ".reclaim.backup");
        }
    }

    private void testShadowCompactPrepareDoesNotReplaceSource() {
        String base = mvStoreFile("shadowCompactPrepare");
        try {
            BloatStats stats = createBloatedStore(base);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactToShadow(base,
                    MVStoreSpaceReclamationOptions.DEFAULT);
            assertFalse(result.isReplaced());
            assertEquals(stats.afterDeleteSize, result.getSourceSize());
            assertTrue(FileUtils.exists(result.getShadowFileName()));
            assertTrue(FileUtils.exists(base + ".reclaim.manifest"));
            assertTrue(result.getCompactedSize() < stats.afterDeleteSize / 4);
            assertOnlyMarkerReadable(base);
            assertOnlyMarkerReadable(result.getShadowFileName());
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testManifestRecoveryRestoresSource() {
        String base = mvStoreFile("manifestRecovery");
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactToShadow(base,
                    MVStoreSpaceReclamationOptions.DEFAULT);
            assertTrue(FileUtils.exists(base + ".reclaim.manifest"));
            copyFile(base, base + ".reclaim.backup");
            FileUtils.delete(base);
            MVStoreSpaceReclamation.recover(base);
            assertFalse(FileUtils.exists(result.getShadowFileName()));
            assertFalse(FileUtils.exists(base + ".reclaim.backup"));
            assertFalse(FileUtils.exists(base + ".reclaim.manifest"));
            assertOnlyMarkerReadable(base);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testSwitchToPreparedShadow() {
        String base = mvStoreFile("switchToPreparedShadow");
        try {
            BloatStats stats = createBloatedStore(base);
            MVStoreSpaceReclamation.compactToShadow(base, MVStoreSpaceReclamationOptions.DEFAULT);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.switchToShadow(base,
                    MVStoreSpaceReclamationOptions.builder().keepBackup(true).build());
            assertTrue(result.isReplaced());
            assertTrue(result.getCompactedSize() < stats.afterDeleteSize / 4);
            assertTrue(FileUtils.exists(result.getBackupFileName()));
            assertFalse(FileUtils.exists(result.getShadowFileName()));
            assertOnlyMarkerReadable(base);
            assertOnlyMarkerReadable(result.getBackupFileName());
        } finally {
            deleteFilesUnlessKept(base);
            deleteFilesUnlessKept(base + ".reclaim.backup");
        }
    }

    private void testSwitchRejectsChangedSource() {
        String base = mvStoreFile("switchRejectsChangedSource");
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamation.compactToShadow(base, MVStoreSpaceReclamationOptions.DEFAULT);
            appendExtraMarker(base);
            try {
                MVStoreSpaceReclamation.switchToShadow(base, MVStoreSpaceReclamationOptions.DEFAULT);
                fail("switch should reject a source file changed after shadow compact");
            } catch (MVStoreException expected) {
                assertTrue(expected.getMessage().contains("Source file changed after shadow compact"));
            }
            assertMarkerAndExtraReadable(base);
            assertFalse(FileUtils.exists(base + ".reclaim.backup"));
            assertTrue(FileUtils.exists(base + ".reclaim.shadow"));
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testManifestRecordsSourceFingerprint() {
        String base = mvStoreFile("manifestRecordsSourceFingerprint");
        try {
            BloatStats stats = createBloatedStore(base);
            MVStoreSpaceReclamation.compactToShadow(base, MVStoreSpaceReclamationOptions.DEFAULT);
            String manifest = readText(base + ".reclaim.manifest");
            assertTrue(manifest.contains("sourceSize=" + stats.afterDeleteSize));
            assertTrue(manifest.contains("sourceLastModified="));
            assertTrue(manifest.contains("sourceDigest=SHA-256:"));
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testCatchUpFeasibilityAnalysis() {
        String base = mvStoreFile("catchUpFeasibilityAnalysis");
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamation.compactToShadow(base, MVStoreSpaceReclamationOptions.DEFAULT);
            MVStoreSpaceReclamationAnalysis unchanged = MVStoreSpaceReclamation.analyzePreparedShadow(base);
            assertTrue(unchanged.isSourceUnchanged());
            assertFalse(unchanged.isVersionScanCatchUpAvailable());
            assertFalse(unchanged.isFullCopyRequired());
            appendExtraMarker(base);
            MVStoreSpaceReclamationAnalysis changed = MVStoreSpaceReclamation.analyzePreparedShadow(base);
            assertFalse(changed.isSourceUnchanged());
            assertFalse(changed.isVersionScanCatchUpAvailable());
            assertTrue(changed.isFullCopyRequired());
            assertTrue(changed.getReason().contains("version-scan catch-up is not available"));
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testFullCopyFallbackForChangedSource() {
        String base = mvStoreFile("fullCopyFallbackForChangedSource");
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamation.compactToShadow(base, MVStoreSpaceReclamationOptions.DEFAULT);
            appendExtraMarker(base);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.switchToShadow(base,
                    MVStoreSpaceReclamationOptions.builder().refreshShadowIfSourceChanged(true).build());
            assertTrue(result.isReplaced());
            assertMarkerAndExtraReadable(base);
            assertFalse(FileUtils.exists(base + ".reclaim.shadow"));
            assertFalse(FileUtils.exists(base + ".reclaim.manifest"));
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationDiagnostics() {
        String base = mvStoreFile("reclamationDiagnostics");
        try {
            BloatStats stats = createBloatedStore(base);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(base,
                    MVStoreSpaceReclamationOptions.DEFAULT);
            assertTrue(result.isReplaced());
            assertEquals(stats.afterDeleteSize, result.getSourceSize());
            assertEquals(result.getSourceSize() - result.getCompactedSize(), result.getSavedBytes());
            assertTrue(result.getSavedBytes() > 0);
            assertTrue(result.getSavedPercent() > 0);
            String summary = result.getDiagnosticSummary();
            assertTrue(summary.contains("sourceSize=" + result.getSourceSize()));
            assertTrue(summary.contains("compactedSize=" + result.getCompactedSize()));
            assertTrue(summary.contains("savedBytes=" + result.getSavedBytes()));
            assertTrue(summary.contains("savedPercent=" + result.getSavedPercent()));
            assertTrue(summary.contains("replaced=true"));
            assertEquals(summary, result.toString());
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testDiagnosticListener() {
        String base = mvStoreFile("diagnosticListener");
        final ArrayList<MVStoreSpaceReclamationPhase> phases = new ArrayList<>();
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamationOptions options = MVStoreSpaceReclamationOptions.builder()
                    .diagnosticListener(new MVStoreSpaceReclamationListener() {
                        @Override
                        public void onEvent(MVStoreSpaceReclamationEvent event) {
                            assertTrue(event.getFileName().contains("diagnosticListener"));
                            assertNotNull(event.getMessage());
                            phases.add(event.getPhase());
                        }
                    }).build();
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(base, options);
            assertTrue(result.isReplaced());
            assertTrue(phases.contains(MVStoreSpaceReclamationPhase.PREPARING));
            assertTrue(phases.contains(MVStoreSpaceReclamationPhase.VERIFYING));
            assertTrue(phases.contains(MVStoreSpaceReclamationPhase.SWITCHING));
            assertEquals(MVStoreSpaceReclamationPhase.COMPLETED, phases.get(phases.size() - 1));
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testDiagnosticListenerFailureIgnored() {
        String base = mvStoreFile("diagnosticListenerFailureIgnored");
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamationOptions options = MVStoreSpaceReclamationOptions.builder()
                    .diagnosticListener(new MVStoreSpaceReclamationListener() {
                        @Override
                        public void onEvent(MVStoreSpaceReclamationEvent event) {
                            throw new IllegalStateException("diagnostic sink failed");
                        }
                    }).build();
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(base, options);
            assertTrue(result.isReplaced());
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testApiStatus() {
        assertEquals("EXPERIMENTAL_MAINTENANCE_API", MVStoreSpaceReclamation.getApiStatus());
        assertEquals("JAVA_MAINTENANCE_API", MVStoreSpaceReclamation.getEntryPoint());
    }

    private void testMaintenanceGateBlocksWrites() {
        MVStoreSpaceReclamationMaintenance gate = new MVStoreSpaceReclamationMaintenance();
        gate.enter();
        try {
            assertTrue(gate.tryEnterRead());
            gate.exitRead();
            assertFalse(gate.tryEnterWrite());
        } finally {
            gate.exit();
        }
        assertTrue(gate.tryEnterWrite());
        gate.exitWrite();
    }

    private void testLongTransactionBlocksSwitch() {
        MVStoreSpaceReclamationMaintenance gate = new MVStoreSpaceReclamationMaintenance();
        assertTrue(gate.tryEnterRead());
        gate.enter();
        try {
            assertTrue(gate.isSwitchBlockedByActiveTransactions());
            assertFalse(gate.tryEnterWrite());
        } finally {
            gate.exitRead();
            gate.exit();
        }
        assertFalse(gate.isSwitchBlockedByActiveTransactions());
    }

    private void testTcpBehaviorDecisions() {
        MVStoreSpaceReclamationMaintenance gate = new MVStoreSpaceReclamationMaintenance();
        assertEquals(MVStoreSpaceReclamationRequestDecision.ALLOW, gate.readDecision());
        assertEquals(MVStoreSpaceReclamationRequestDecision.ALLOW, gate.writeDecision());
        assertTrue(gate.tryEnterRead());
        gate.enter();
        try {
            assertEquals(MVStoreSpaceReclamationRequestDecision.ALLOW, gate.readDecision());
            assertEquals(MVStoreSpaceReclamationRequestDecision.BUSY, gate.writeDecision());
            assertEquals(MVStoreSpaceReclamationRequestDecision.WAIT, gate.switchDecision());
        } finally {
            gate.exitRead();
            gate.exit();
        }
        assertEquals(MVStoreSpaceReclamationRequestDecision.ALLOW, gate.switchDecision());
    }

    private void testBackupInteractionGate() {
        MVStoreSpaceReclamationOperationGate gate = new MVStoreSpaceReclamationOperationGate();
        assertTrue(gate.tryEnterBackup());
        assertFalse(gate.tryEnterSpaceReclamation());
        gate.exitBackup();
        assertTrue(gate.tryEnterSpaceReclamation());
        assertFalse(gate.tryEnterBackup());
        gate.exitSpaceReclamation();
        assertTrue(gate.tryEnterBackup());
        gate.exitBackup();
    }

    private void testLargeStoreCompact() {
        String base = mvStoreFile("largeStoreCompact");
        try {
            BloatStats stats = createBloatedStore(base, LARGE_BLOAT_ENTRY_COUNT);
            assertTrue(stats.afterDeleteSize > BLOAT_MIN_FILE_SIZE * 2);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(base,
                    MVStoreSpaceReclamationOptions.DEFAULT);
            assertTrue(result.isReplaced());
            assertTrue(result.getCompactedSize() < stats.afterDeleteSize / 4);
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testSlowIoCompact() {
        String base = mvStoreFile("slowIoCompact");
        try {
            createBloatedStore(base);
            MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(base,
                    MVStoreSpaceReclamationOptions.builder().ioDelayMillis(1L).build());
            assertTrue(result.isReplaced());
            assertOnlyMarkerReadable(base);
        } finally {
            deleteFilesUnlessKept(base);
        }
    }

    private void testResidualCleanup() {
        String base = mvStoreFile("residualCleanup");
        try {
            createBloatedStore(base);
            writeText(base + ".reclaim.manifest", "phase=SHADOW_READY\n");
            copyFile(base, base + ".reclaim.backup");
            copyFile(base, base + ".reclaim.shadow");
            MVStoreSpaceReclamation.cleanUp(base);
            assertFalse(FileUtils.exists(base + ".reclaim.manifest"));
            assertFalse(FileUtils.exists(base + ".reclaim.backup"));
            assertFalse(FileUtils.exists(base + ".reclaim.shadow"));
            assertOnlyMarkerReadable(base);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            deleteFilesUnlessKept(base);
        }
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

    private void testReclamationAnalysisFindsCandidates() {
        String base = mvStoreFile("reclamationAnalysisCandidates");
        MVStore store = null;
        try {
            BloatStats stats = createBloatedStore(base);
            assertBloated(stats);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store, 100);
            assertTrue(analysis.getChunks().size() > 0);
            assertTrue(analysis.hasCandidates());
            assertTrue(analysis.getEstimatedReclaimableBytes() > 0L);
            assertEquals(100, analysis.getTargetFillRate());
            assertEquals(store.getFileStore().size(), analysis.getFileSize());
            assertTrue(analysis.getDiagnosticSummary().contains("candidates=" + analysis.getCandidates().size()));

            ChunkLivenessSnapshot best = analysis.getCandidates().get(0);
            assertTrue(best.isCandidate());
            assertEquals(ChunkLivenessSnapshot.PinnedReason.NONE, best.getPinnedReason());
            assertTrue(best.getDeadBytes() > 0L);
            assertTrue(best.getPageCount() > best.getLivePageCount());
            assertTrue(best.getScore() > 0);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationAnalysisRejectsLowValueStore() {
        String base = mvStoreFile("reclamationAnalysisLowValue");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(1, new byte[] { MARKER });
            store.commit();
            store.getFileStore().sync();

            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store, 10);
            assertTrue(analysis.getChunks().size() > 0);
            assertFalse(analysis.hasCandidates());
            assertEquals(0L, analysis.getEstimatedReclaimableBytes());
            assertEquals(ChunkLivenessSnapshot.PinnedReason.NOT_REWRITABLE,
                    analysis.getChunks().get(0).getPinnedReason());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationAnalysisReportsRecentChunks() {
        String base = mvStoreFile("reclamationAnalysisRecentChunk");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store, 100);
            assertTrue(analysis.getChunks().size() > 0);
            assertFalse(analysis.hasCandidates());
            assertTrue(hasPinnedReason(analysis, ChunkLivenessSnapshot.PinnedReason.RECENT_CHUNK));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private static boolean hasPinnedReason(MVStoreReclamationAnalysis analysis,
            ChunkLivenessSnapshot.PinnedReason reason) {
        for (ChunkLivenessSnapshot snapshot : analysis.getChunks()) {
            if (snapshot.getPinnedReason() == reason) {
                return true;
            }
        }
        return false;
    }

    private void testReclamationAnalysisValidation() {
        expectIllegalArgument(() -> MVStoreReclamationAnalyzer.analyze(null));
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store);
            assertFalse(analysis.hasCandidates());
            assertEquals(0L, analysis.getFileSize());
            assertEquals(100, analysis.getFillRate());
            final MVStore openStore = store;
            expectIllegalArgument(() -> MVStoreReclamationAnalyzer.analyze(openStore, -1));
            expectIllegalArgument(() -> MVStoreReclamationAnalyzer.analyze(openStore, 101));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testReclamationCoordinatorDryRun() {
        String base = mvStoreFile("reclamationCoordinatorDryRun");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            MVStoreReclamationRequest request = new MVStoreReclamationRequest.Builder()
                    .dryRun(true)
                    .targetFillRate(100)
                    .maxCandidateChunks(2)
                    .build();
            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store, request);
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.DRY_RUN, result.getMessage());
            assertFalse(result.isRewritten());
            assertTrue(result.getCandidateChunks().size() > 0);
            assertEquals(result.getBeforeFileSize(), result.getAfterFileSize());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationCoordinatorRunsBoundedRewrite() {
        String base = mvStoreFile("reclamationCoordinatorRun");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");
            MVStoreReclamationRequest request = new MVStoreReclamationRequest.Builder()
                    .targetFillRate(100)
                    .maxCandidateChunks(2)
                    .maxLiveBytesToRewrite(16 * 1024 * 1024)
                    .build();
            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store, request);
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isRewritten());
            assertTrue(result.getCandidateChunks().size() > 0);
            assertTrue(result.getAfterChunksFillRate() >= result.getBeforeChunksFillRate());
            assertTrue(result.getBeforeEstimatedReclaimableBytes() > 0L);
            assertTrue(result.getDiagnosticSummary().contains("RECLAMATION_ROUND_FINISHED"));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationCoordinatorSkipsWithoutCandidates() {
        String base = mvStoreFile("reclamationCoordinatorSkip");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(1, new byte[] { MARKER });
            store.commit();
            store.getFileStore().sync();

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(10).build());
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.NO_RECLAMATION_CANDIDATE, result.getMessage());
            assertFalse(result.isRewritten());
            assertEquals(0, result.getCandidateChunks().size());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationRequestValidation() {
        expectIllegalArgument(() -> new MVStoreReclamationRequest.Builder().targetFillRate(-1));
        expectIllegalArgument(() -> new MVStoreReclamationRequest.Builder().targetFillRate(101));
        expectIllegalArgument(() -> new MVStoreReclamationRequest.Builder().maxCandidateChunks(0));
        expectIllegalArgument(() -> new MVStoreReclamationRequest.Builder().maxLiveBytesToRewrite(-1));
        expectIllegalArgument(() -> new MVStoreReclamationRequest.Builder().maxRunMillis(-1));
        expectIllegalArgument(() -> new MVStoreReclamationRequest.Builder().maxTailCompactionMillis(-1));
    }

    private void testPageRelocationReportsOpenMapProgress() {
        String base = mvStoreFile("pageRelocationOpenMap");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(100).build());
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isRewritten());
            assertTrue(result.getBeforeEstimatedReclaimableBytes() >= result.getEstimatedReclaimedBytes());
            assertTrue(result.getDiagnosticSummary().contains("estimatedReclaimedBytes="));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testPageRelocationCanUseLazyOpenedMaps() {
        String base = mvStoreFile("pageRelocationLazyMap");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(100).build());
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isRewritten());
            assertTrue(result.getCandidateChunks().size() > 0);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationJournalIsCleanedAfterRun() {
        String base = mvStoreFile("reclamationJournalCleanup");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(100).journalEnabled(true).build());
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            MVStoreReclamationRecovery recovery = MVStoreReclamationCoordinator.recover(store);
            assertFalse(recovery.isRecovered());
            assertEquals("NO_RECLAMATION_JOURNAL", recovery.getMessage());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationJournalRecoverWithoutJournal() {
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            MVStoreReclamationRecovery recovery = MVStoreReclamationCoordinator.recover(store);
            assertFalse(recovery.isRecovered());
            assertEquals("NO_RECLAMATION_JOURNAL", recovery.getMessage());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testReclamationJournalRecoversPublishedMarker() {
        String base = mvStoreFile("reclamationJournalRecoverPublished");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            writeReclamationJournalMarker(store, "PUBLISHED", true);
            MVStoreReclamationRecovery recovery = MVStoreReclamationCoordinator.recover(store);
            assertTrue(recovery.isRecovered());
            assertTrue(recovery.getMessage().contains("RECOVERED_PUBLISHED_RECLAMATION_JOURNAL"));
            assertNoReclamationJournal(store);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationJournalRecoversUnpublishedMarker() {
        String base = mvStoreFile("reclamationJournalRecoverUnpublished");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            writeReclamationJournalMarker(store, "EVACUATING", false);
            MVStoreReclamationRecovery recovery = MVStoreReclamationCoordinator.recover(store);
            assertTrue(recovery.isRecovered());
            assertTrue(recovery.getMessage().contains("RECOVERED_UNPUBLISHED_RECLAMATION_JOURNAL"));
            assertNoReclamationJournal(store);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationJournalV1Metadata() {
        String base = mvStoreFile("reclamationJournalV1Metadata");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            writeReclamationJournalMarker(store, "EVACUATING", true);
            MVMap<String, String> meta = store.getMetaMap();
            assertEquals("test", meta.get("reclaim.s2.activeJob"));
            assertEquals("EVACUATING", meta.get("reclaim.s2.job.test.phase"));
            assertEquals("test", meta.get("reclaim.s2.job.test.request"));
            assertEquals("index=0", meta.get("reclaim.s2.job.test.candidate.1"));
            assertNotNull(meta.get("reclaim.s2.job.test.createdVersion"));
            assertNotNull(meta.get("reclaim.s2.job.test.createdTime"));
            assertNotNull(meta.get("reclaim.s2.job.test.publish"));
            MVStoreReclamationRecovery recovery = MVStoreReclamationCoordinator.recover(store);
            assertTrue(recovery.isRecovered());
            assertTrue(recovery.getMessage().contains("job=test"));
            assertTrue(recovery.getMessage().contains("phase=EVACUATING"));
            assertNoReclamationJournal(store);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationRecoveryReportsCrashSafeAction() {
        String base = mvStoreFile("reclamationRecoveryAction");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            writeReclamationJournalMarker(store, "PUBLISHED", true);
            MVStoreReclamationRecovery published = MVStoreReclamationCoordinator.recover(store);
            assertTrue(published.isRecovered());
            assertTrue(published.isPublished());
            assertEquals("test", published.getJobId());
            assertEquals("PUBLISHED", published.getPhase());
            assertEquals("CONTINUE_FREE_AFTER_PUBLISH", published.getAction());
            assertTrue(published.getMessage().contains("action=CONTINUE_FREE_AFTER_PUBLISH"));

            writeReclamationJournalMarker(store, "EVACUATING", false);
            MVStoreReclamationRecovery unpublished = MVStoreReclamationCoordinator.recover(store);
            assertTrue(unpublished.isRecovered());
            assertFalse(unpublished.isPublished());
            assertEquals("test", unpublished.getJobId());
            assertEquals("EVACUATING", unpublished.getPhase());
            assertEquals("ROLLBACK_UNPUBLISHED_RECLAMATION", unpublished.getAction());
            assertTrue(unpublished.getMessage().contains("action=ROLLBACK_UNPUBLISHED_RECLAMATION"));

            MVStoreReclamationRecovery empty = MVStoreReclamationCoordinator.recover(store);
            assertFalse(empty.isRecovered());
            assertEquals("NONE", empty.getAction());
            assertNoReclamationJournal(store);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testReclamationJournalRecoversAfterReopen() {
        String base = mvStoreFile("reclamationJournalRecoverReopen");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            writeReclamationJournalMarker(store, "PUBLISHED", true);
            closeStore(store);
            store = null;

            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            MVStoreReclamationRecovery recovery = MVStoreReclamationCoordinator.recover(store);
            assertTrue(recovery.isRecovered());
            assertTrue(recovery.getMessage().contains("RECOVERED_PUBLISHED_RECLAMATION_JOURNAL"));
            assertNoReclamationJournal(store);
            MVMap<Integer, byte[]> data = store.openMap("data");
            assertEquals(1L, data.sizeAsLong());
            byte[] marker = data.get(-1);
            assertNotNull(marker);
            assertEquals(MARKER, marker[0]);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testCoordinatorRecoversStaleJournalBeforeRun() {
        String base = mvStoreFile("coordinatorRecoverFirst");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");
            writeReclamationJournalMarker(store, "EVACUATING", false);

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(100).build());
            assertTrue(result.getStatus() == MVStoreReclamationStatus.SUCCESS
                    || result.getStatus() == MVStoreReclamationStatus.SKIPPED);
            assertNoReclamationJournal(store);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testRelocationMapFeatureGate() {
        String base = mvStoreFile("relocationMapFeatureGate");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder()
                            .targetFillRate(100)
                            .relocationMapAllowed(true)
                            .build());
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isRelocationMapAllowed());
            assertFalse(result.isRelocationMapUsed());
            assertTrue(result.getDiagnosticSummary().contains("relocationMapAllowed=true"));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testRelocationMapResolvesPagePosition() {
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            long oldPosition = 0x1234L;
            long newPosition = 0x5678L;
            assertFalse(MVStoreReclamationRelocationMap.hasMappings(store));
            assertEquals(oldPosition, MVStoreReclamationRelocationMap.resolve(store, oldPosition));
            MVStoreReclamationRelocationMap.put(store, oldPosition, newPosition);
            assertTrue(MVStoreReclamationRelocationMap.hasMappings(store));
            assertEquals(newPosition, MVStoreReclamationRelocationMap.resolve(store, oldPosition));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testRelocationMapStructuredResolve() {
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            long oldPosition = 0x12340L;
            long newPosition = 0x56780L;
            MVStoreReclamationRelocationMap.put(store, oldPosition, newPosition, 7, 11L, 20L);
            assertTrue(MVStoreReclamationRelocationMap.hasMappings(store));
            assertEquals(newPosition, MVStoreReclamationRelocationMap.resolve(store, oldPosition, 7, 12L));
            assertEquals(oldPosition, MVStoreReclamationRelocationMap.resolve(store, oldPosition, 8, 12L));
            assertEquals(oldPosition, MVStoreReclamationRelocationMap.resolve(store, oldPosition, 7, 21L));
            assertEquals(1, MVStoreReclamationRelocationMap.countMappings(store));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testRelocationMapRemovesExpiredMappings() {
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            MVStoreReclamationRelocationMap.put(store, 0x11110L, 0x22220L, 3, 5L, 9L);
            MVStoreReclamationRelocationMap.put(store, 0x33330L, 0x44440L, 3, 5L, 20L);
            assertEquals(2, MVStoreReclamationRelocationMap.countMappings(store));
            assertEquals(1, MVStoreReclamationRelocationMap.removeExpired(store, 10L));
            assertEquals(1, MVStoreReclamationRelocationMap.countMappings(store));
            assertEquals(0x11110L, MVStoreReclamationRelocationMap.resolve(store, 0x11110L, 3, 10L));
            assertEquals(0x44440L, MVStoreReclamationRelocationMap.resolve(store, 0x33330L, 3, 10L));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testRelocationMapLifecycleClear() {
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            long oldPosition = 0x2222L;
            long newPosition = 0x3333L;
            MVStoreReclamationRelocationMap.put(store, oldPosition, newPosition);
            assertTrue(MVStoreReclamationRelocationMap.hasMappings(store));
            MVStoreReclamationRelocationMap.clear(store);
            assertFalse(MVStoreReclamationRelocationMap.hasMappings(store));
            assertEquals(oldPosition, MVStoreReclamationRelocationMap.resolve(store, oldPosition));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testRelocationMapCompatibilityGate() {
        String base = mvStoreFile("relocationMapCompatibilityGate");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().open();
            MVStoreReclamationRelocationMap.put(store, 0x4444L, 0x5555L);
            closeStore(store);
            store = null;
            try {
                store = new MVStore.Builder().fileName(base).autoCommitDisabled()
                        .allowReclamationRelocationMap(false).open();
                fail("relocation map should be rejected when compatibility gate is disabled");
            } catch (MVStoreException expected) {
                assertTrue(expected.getMessage().contains("online reclamation relocation map"));
            }
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testTailMoverRunsOnlyWithExplicitBudget() {
        String base = mvStoreFile("tailMoverBudget");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");

            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder()
                            .targetFillRate(100)
                            .maxTailCompactionMillis(50)
                            .build());
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isTailCompactionAllowed());
            assertTrue(result.isTailCompactionAttempted());
            assertTrue(result.getAfterFileSize() <= result.getBeforeFileSize());
            assertTrue(result.getDiagnosticSummary().contains("tailCompactionAttempted=true"));
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testSchedulerIsDisabledByDefault() {
        MVStore store = null;
        try {
            store = new MVStore.Builder().open();
            MVStoreReclamationScheduler scheduler = MVStoreReclamationScheduler.builder().build();
            assertFalse(scheduler.isEnabled());
            MVStoreOnlineReclamationResult result = scheduler.runIfEnabled(store);
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.RECLAMATION_SCHEDULER_DISABLED, result.getMessage());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void testSchedulerRunsWhenEnabled() {
        String base = mvStoreFile("schedulerEnabled");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");

            MVStoreReclamationScheduler scheduler = MVStoreReclamationScheduler.builder()
                    .enabled(true)
                    .request(new MVStoreReclamationRequest.Builder().targetFillRate(100).build())
                    .build();
            MVStoreOnlineReclamationResult result = scheduler.runIfEnabled(store);
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isRewritten());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testSchedulerBackoffAfterRun() {
        String base = mvStoreFile("schedulerBackoff");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");

            MVStoreReclamationScheduler scheduler = MVStoreReclamationScheduler.builder()
                    .enabled(true)
                    .minIntervalMillis(60_000L)
                    .request(new MVStoreReclamationRequest.Builder().targetFillRate(100).build())
                    .build();
            MVStoreOnlineReclamationResult first = scheduler.runIfEnabled(store);
            assertEquals(MVStoreReclamationStatus.SUCCESS, first.getStatus());
            MVStoreOnlineReclamationResult second = scheduler.runIfEnabled(store);
            assertEquals(MVStoreReclamationStatus.SKIPPED, second.getStatus());
            assertEquals(MVStoreReclamationCode.RECLAMATION_SCHEDULER_BACKOFF, second.getMessage());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testDefaultHousekeepingRunsOnlineReclamation() {
        String base = mvStoreFile("defaultHousekeepingEnabled");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");
            MVStoreOnlineReclamationResult result = store.runOnlineReclamationHousekeeping();
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.isRewritten());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testHousekeepingCanDisableOnlineReclamation() {
        String base = mvStoreFile("defaultHousekeepingDisabled");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0)
                    .onlineReclamationEnabled(false).open();
            MVStoreOnlineReclamationResult result = store.runOnlineReclamationHousekeeping();
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.RECLAMATION_SCHEDULER_DISABLED, result.getMessage());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testHousekeepingSkipsClosedStore() {
        String base = mvStoreFile("defaultHousekeepingClosed");
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            closeStore(store);
            MVStoreOnlineReclamationResult result = store.runOnlineReclamationHousekeeping();
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.RECLAMATION_STORE_CLOSED, result.getMessage());
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testConcurrentWriteDuringOnlineReclamation() throws Exception {
        String base = mvStoreFile("concurrentWriteReclamation");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            final MVMap<Integer, byte[]> data = store.openMap("data");
            final RuntimeException[] writerFailure = new RuntimeException[1];
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 16; i++) {
                        data.put(-100 - i, new byte[] { (byte) i });
                    }
                } catch (RuntimeException e) {
                    writerFailure[0] = e;
                }
            }, "mvstore-reclamation-writer");
            writer.start();
            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(100).build());
            writer.join();
            if (writerFailure[0] != null) {
                throw writerFailure[0];
            }
            store.commit();
            assertTrue(result.getStatus() == MVStoreReclamationStatus.SUCCESS
                    || result.getStatus() == MVStoreReclamationStatus.NO_PROGRESS);
            for (int i = 0; i < 16; i++) {
                byte[] value = data.get(-100 - i);
                assertNotNull(value);
                assertEquals((byte) i, value[0]);
            }
            closeStore(store);
            store = null;
            assertConcurrentMarkersReadable(base);
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testNoCandidateReclamationReturnsQuickly() {
        String base = mvStoreFile("noCandidateFast");
        MVStore store = null;
        try {
            deleteFiles(base);
            createParentDirectories(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(1, new byte[] { MARKER });
            store.commit();
            long start = System.currentTimeMillis();
            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(10).build());
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.NO_RECLAMATION_CANDIDATE, result.getMessage());
            assertTrue("no-candidate path was unexpectedly slow: " + elapsed, elapsed < 5_000L);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
        }
    }

    private void testBoundedReclamationDoesNotGrowFile() {
        String base = mvStoreFile("boundedSpaceBaseline");
        MVStore store = null;
        try {
            createBloatedStore(base);
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.openMap("data");
            MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store,
                    new MVStoreReclamationRequest.Builder().targetFillRate(100).maxTailCompactionMillis(50).build());
            assertEquals(MVStoreReclamationStatus.SUCCESS, result.getStatus());
            assertTrue(result.getBeforeFileSize() >= result.getAfterFileSize());
            assertTrue(result.getBeforeEstimatedReclaimableBytes() >= result.getEstimatedReclaimedBytes());
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
            deleteFilesUnlessKept(base);
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

    private static void writeReclamationJournalMarker(MVStore store, String phase, boolean published) {
        MVStoreReclamationCoordinator.writeRecoveryMarkerForTest(store, phase, published);
    }

    private void assertNoReclamationJournal(MVStore store) {
        MVMap<String, String> meta = store.getMetaMap();
        assertNull(meta.get("reclaim.s2.job"));
        assertNull(meta.get("reclaim.s2.phase"));
        assertNull(meta.get("reclaim.s2.candidates"));
        assertNull(meta.get("reclaim.s2.publish"));
        assertNull(meta.get("reclaim.s2.activeJob"));
        assertNull(meta.get("reclaim.s2.job.test.phase"));
        assertNull(meta.get("reclaim.s2.job.test.request"));
        assertNull(meta.get("reclaim.s2.job.test.candidate.1"));
        assertNull(meta.get("reclaim.s2.job.test.createdVersion"));
        assertNull(meta.get("reclaim.s2.job.test.createdTime"));
        assertNull(meta.get("reclaim.s2.job.test.publish"));
    }

    private void expectIllegalArgument(Scenario scenario) {
        try {
            scenario.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private BloatStats createBloatedStore(String base) {
        return createBloatedStore(base, BLOAT_ENTRY_COUNT);
    }

    private BloatStats createBloatedStore(String base, int entryCount) {
        deleteFiles(base);
        createParentDirectories(base);
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(base).autoCommitDisabled().autoCompactFillRate(0).open();
            store.setRetentionTime(0);
            store.setVersionsToKeep(0);
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(-1, new byte[] { MARKER });
            for (int i = 0; i < entryCount; i++) {
                data.put(i, bloatBytes(i));
                if ((i & 15) == 15) {
                    store.commit();
                }
            }
            store.commit();
            store.getFileStore().sync();
            long fullSize = FileUtils.size(base);
            for (int i = 0; i < entryCount; i++) {
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

    private void assertMarkerAndExtraReadable(String fileName) {
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(fileName).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            assertEquals(2L, data.sizeAsLong());
            byte[] marker = data.get(-1);
            byte[] extra = data.get(-2);
            assertNotNull(marker);
            assertNotNull(extra);
            assertEquals(MARKER, marker[0]);
            assertEquals(MARKER + 1, extra[0]);
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void assertConcurrentMarkersReadable(String fileName) {
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(fileName).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            for (int i = 0; i < 16; i++) {
                byte[] value = data.get(-100 - i);
                assertNotNull(value);
                assertEquals((byte) i, value[0]);
            }
            closeStore(store);
            store = null;
        } finally {
            closeStoreImmediately(store);
        }
    }

    private void appendExtraMarker(String fileName) {
        MVStore store = null;
        try {
            store = new MVStore.Builder().fileName(fileName).autoCommitDisabled().open();
            MVMap<Integer, byte[]> data = store.openMap("data");
            data.put(-2, new byte[] { MARKER + 1 });
            store.commit();
            store.getFileStore().sync();
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

    private static String readText(String fileName) throws IOException {
        try (InputStream in = FileUtils.newInputStream(fileName)) {
            return new String(IOUtils.readBytesAndClose(in, -1), "UTF-8");
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
        FileUtils.delete(base + ".reclaim.shadow");
        FileUtils.delete(base + ".reclaim.backup");
        FileUtils.delete(base + ".reclaim.manifest");
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
