/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.test.longrun.mvstore.MVStoreWorkload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for deterministic MVStore longrun workload behavior.
 */
public final class MVStoreWorkloadTest {

    @TempDir
    public File tempDir;

    @Test
    public void boundedLedgerKeepsConfiguredEntryLimit() throws Exception {
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("bounded-ledger-test")
                .workDir(new File(tempDir, "bounded"))
                .durationMillis(10L)
                .seed(1L)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("bounded")
                .ledgerMaxEntries(10L)
                .build();
        assertTrue(config.getWorkDir().mkdirs());
        LongRunState state = new LongRunState();
        Properties properties = new Properties();

        try (MVStoreWorkload workload = new MVStoreWorkload(config, state)) {
            for (int i = 0; i < 200; i++) {
                workload.step();
            }
            workload.commit();
            workload.verify();
            workload.collectReportProperties(properties);
        }

        assertEquals("bounded", properties.getProperty("mvstore.ledgerMode"));
        assertEquals("10", properties.getProperty("mvstore.ledgerMaxEntries"));
        assertEquals("0", properties.getProperty("mvstore.retentionTimeMillis"));
        assertEquals("0", properties.getProperty("mvstore.versionsToKeep"));
        assertEquals("true", properties.getProperty("mvstore.onlineReclamationBuilderOptionsApplied"));
        assertTrue(Long.parseLong(properties.getProperty("mvstore.ledgerEntries")) <= 10L);
    }

    @Test
    public void missingOnlineReclamationBuilderOptionIsSkipped() {
        assertTrue(!MVStoreWorkload.applyBuilderIntOption(new Object(), "onlineReclamationMaxCandidateChunks", 64));
    }

    @Test
    public void appendOnlyLedgerKeepsEveryWrittenEvent() throws Exception {
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("append-ledger-test")
                .workDir(new File(tempDir, "append"))
                .durationMillis(10L)
                .seed(1L)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("append-only")
                .ledgerMaxEntries(10L)
                .build();
        assertTrue(config.getWorkDir().mkdirs());
        LongRunState state = new LongRunState();
        Properties properties = new Properties();

        try (MVStoreWorkload workload = new MVStoreWorkload(config, state)) {
            for (int i = 0; i < 200; i++) {
                workload.step();
            }
            workload.commit();
            workload.verify();
            workload.collectReportProperties(properties);
        }

        assertEquals("append-only", properties.getProperty("mvstore.ledgerMode"));
        assertTrue(Long.parseLong(properties.getProperty("mvstore.ledgerEntries")) > 10L);
    }

    @Test
    public void resumeKeepsSequenceAheadOfCommittedStoreState() throws Exception {
        File workDir = new File(tempDir, "resume");
        LongRunConfig initialConfig = new LongRunConfig.Builder()
                .runName("resume-initial-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(1L)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("bounded")
                .ledgerMaxEntries(10L)
                .build();
        assertTrue(workDir.mkdirs());
        try (MVStoreWorkload workload = new MVStoreWorkload(initialConfig, new LongRunState())) {
            for (int i = 0; i < 20; i++) {
                workload.step();
            }
            workload.commit();
        }
        LongRunConfig resumeConfig = new LongRunConfig.Builder()
                .runName("resume-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(1L)
                .resume(true)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("bounded")
                .ledgerMaxEntries(10L)
                .build();
        LongRunState recovered = new LongRunState();

        try (MVStoreWorkload workload = new MVStoreWorkload(resumeConfig, recovered)) {
            workload.verify();
        }

        assertEquals(20L, recovered.getOperationSequence());
    }

    @Test
    public void resumeAllowsCheckpointStateAheadOfCommittedStoreState() throws Exception {
        File workDir = new File(tempDir, "resume-behind");
        LongRunConfig initialConfig = new LongRunConfig.Builder()
                .runName("resume-behind-initial-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(1L)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("bounded")
                .ledgerMaxEntries(10L)
                .build();
        assertTrue(workDir.mkdirs());
        try (MVStoreWorkload workload = new MVStoreWorkload(initialConfig, new LongRunState())) {
            for (int i = 0; i < 20; i++) {
                workload.step();
            }
            workload.commit();
        }
        LongRunConfig resumeConfig = new LongRunConfig.Builder()
                .runName("resume-behind-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(1L)
                .resume(true)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("bounded")
                .ledgerMaxEntries(10L)
                .build();
        LongRunState checkpoint = new LongRunState();
        checkpoint.ensureOperationSequenceAtLeast(21L);

        try (MVStoreWorkload workload = new MVStoreWorkload(resumeConfig, checkpoint)) {
            workload.verify();
        }

        assertEquals(21L, checkpoint.getOperationSequence());
    }

    @Test
    public void faultInjectionUsesCopyAndKeepsPrimaryStoreValid() throws Exception {
        File workDir = new File(tempDir, "fault-copy");
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("fault-copy-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(1L)
                .keySpace(50)
                .valueSizeMin(16)
                .valueSizeMax(16)
                .ledgerMode("bounded")
                .ledgerMaxEntries(10L)
                .faultEnabled(true)
                .faultIntervalMillis(1_000L)
                .faultKinds(new FaultInjectionKind[] { FaultInjectionKind.TRUNCATE })
                .build();
        assertTrue(workDir.mkdirs());
        LongRunState state = new LongRunState();

        FaultInjectionResult result;
        try (MVStoreWorkload workload = new MVStoreWorkload(config, state)) {
            for (int i = 0; i < 200; i++) {
                workload.step();
            }
            result = workload.runFaultInjection(1L);
            workload.verify();
        }

        assertEquals(FaultInjectionKind.TRUNCATE, result.getKind());
        assertTrue(new File(workDir, "mvstore-longrun.mv.db").isFile());
        assertTrue(new File(workDir, "fault/fault-1.mv.db").isFile());
    }

    @Test
    public void faultInjectionCyclesThroughAllConfiguredKinds() throws Exception {
        File workDir = new File(tempDir, "fault-kinds");
        FaultInjectionKind[] kinds = FaultInjectionKind.values();
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("fault-kinds-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(2L)
                .keySpace(50)
                .valueSizeMin(64)
                .valueSizeMax(128)
                .ledgerMode("bounded")
                .ledgerMaxEntries(100L)
                .faultEnabled(true)
                .faultIntervalMillis(1_000L)
                .faultKinds(kinds)
                .faultMaxBytes(4 * 1024)
                .build();
        assertTrue(workDir.mkdirs());
        LongRunState state = new LongRunState();

        try (MVStoreWorkload workload = new MVStoreWorkload(config, state)) {
            for (int i = 0; i < 500; i++) {
                workload.step();
            }
            for (int i = 0; i < kinds.length; i++) {
                FaultInjectionResult result = workload.runFaultInjection(i + 1L);
                assertEquals(kinds[i], result.getKind());
                assertTrue(new File(workDir, "fault/fault-" + (i + 1) + ".mv.db").isFile());
                workload.verify();
            }
        }

        assertTrue(new File(workDir, "mvstore-longrun.mv.db").isFile());
    }

    @Test
    public void faultInjectionRetainsOnlyConfiguredRecentCopies() throws Exception {
        File workDir = new File(tempDir, "fault-retention");
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("fault-retention-test")
                .workDir(workDir)
                .durationMillis(10L)
                .seed(3L)
                .keySpace(50)
                .valueSizeMin(64)
                .valueSizeMax(128)
                .ledgerMode("bounded")
                .ledgerMaxEntries(100L)
                .faultEnabled(true)
                .faultIntervalMillis(1_000L)
                .faultKinds(new FaultInjectionKind[] { FaultInjectionKind.TRUNCATE })
                .faultRetainedCopies(2)
                .build();
        assertTrue(workDir.mkdirs());
        LongRunState state = new LongRunState();

        try (MVStoreWorkload workload = new MVStoreWorkload(config, state)) {
            for (int i = 0; i < 500; i++) {
                workload.step();
            }
            for (int i = 1; i <= 5; i++) {
                workload.runFaultInjection(i);
            }
        }

        File faultDir = new File(workDir, "fault");
        assertEquals(2, faultDir.listFiles((dir, name) -> name.endsWith(".mv.db")).length);
        assertTrue(!new File(faultDir, "fault-3.mv.db").exists());
        assertTrue(new File(faultDir, "fault-4.mv.db").isFile());
        assertTrue(new File(faultDir, "fault-5.mv.db").isFile());
    }

    @Test
    public void corruptedMvStoreCopyIsDetectedByVerification() throws Exception {
        File original = new File(tempDir, "real-corruption.mv.db");
        try (MVStore store = new MVStore.Builder().fileName(original.getPath()).open()) {
            MVMap<Integer, String> map = store.openMap("data");
            for (int i = 0; i < 100; i++) {
                map.put(Integer.valueOf(i), "value-" + i);
            }
            store.commit();
        }
        File corrupted = new File(tempDir, "real-corruption-copy.mv.db");
        Files.copy(original.toPath(), corrupted.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile file = new RandomAccessFile(corrupted, "rw")) {
            file.setLength(Math.min(4096L, file.length()));
        }

        assertThrows(RuntimeException.class, () -> verifyCorruptedCopy(corrupted));
    }

    private static void verifyCorruptedCopy(File corrupted) {
        try (MVStore store = new MVStore.Builder().fileName(corrupted.getPath()).readOnly().open()) {
            MVMap<Integer, String> map = store.openMap("data");
            for (int i = 0; i < 100; i++) {
                String expected = "value-" + i;
                String actual = map.get(Integer.valueOf(i));
                if (!expected.equals(actual)) {
                    throw new IllegalStateException("Corrupted MVStore copy returned " + actual
                            + " for key " + i);
                }
            }
        }
    }
}
