/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for longrun configuration defaults and overrides.
 */
public final class LongRunConfigTest {

    @TempDir
    public File tempDir;

    @Test
    public void defaultReclamationIntervalIsTenSeconds() throws Exception {
        LongRunConfig config = LongRunConfig.load(CommandLineOptions.parse());

        assertEquals(10_000L, config.getReclamationIntervalMillis());
        assertEquals("i1", config.getInstanceName());
        assertEquals(30L * 60L * 1000L, config.getReopenIntervalMillis());
        assertEquals(64, config.getReclamationMaxCandidateChunks());
        assertEquals(64 * 1024 * 1024, config.getReclamationMaxLiveBytesToRewrite());
        assertEquals(30_000L, config.getProgressIntervalMillis());
        assertEquals(500, config.getReclamationMaxRunMillis());
        assertEquals(500, config.getReclamationMaxTailCompactionMillis());
        assertEquals(0, config.getReclamationMinSchedulerIntervalMillis());
        assertEquals(false, config.isFaultEnabled());
        assertEquals(30L * 60L * 1000L, config.getFaultIntervalMillis());
        assertEquals(4096, config.getFaultMaxBytes());
        assertEquals(8, config.getFaultRetainedCopies());
        assertEquals(5, config.getFaultKinds().length);
    }

    @Test
    public void configFileOverridesReclamationBudget() throws Exception {
        File configFile = new File(tempDir, "longrun.properties");
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("check.reopenInterval=7s\n");
            writer.write("reclamation.interval=7s\n");
            writer.write("reclamation.maxCandidateChunks=9\n");
            writer.write("reclamation.maxLiveBytesToRewriteMb=12\n");
            writer.write("reclamation.maxRun=250\n");
            writer.write("reclamation.maxTailCompaction=300\n");
            writer.write("reclamation.minSchedulerInterval=2s\n");
            writer.write("progress.interval=12s\n");
        }

        LongRunConfig config = LongRunConfig.load(CommandLineOptions.parse("--config", configFile.getPath()));

        assertEquals(7_000L, config.getReclamationIntervalMillis());
        assertEquals(7_000L, config.getReopenIntervalMillis());
        assertEquals(9, config.getReclamationMaxCandidateChunks());
        assertEquals(12 * 1024 * 1024, config.getReclamationMaxLiveBytesToRewrite());
        assertEquals(250, config.getReclamationMaxRunMillis());
        assertEquals(300, config.getReclamationMaxTailCompactionMillis());
        assertEquals(2_000, config.getReclamationMinSchedulerIntervalMillis());
        assertEquals(12_000L, config.getProgressIntervalMillis());
    }

    @Test
    public void systemPropertyOverridesReclamationInterval() throws Exception {
        String oldValue = System.getProperty("h2.longrun.reclamation.interval");
        String oldProgress = System.getProperty("h2.longrun.progress.interval");
        System.setProperty("h2.longrun.reclamation.interval", "3s");
        System.setProperty("h2.longrun.progress.interval", "0");
        try {
            LongRunConfig config = LongRunConfig.load(CommandLineOptions.parse());

            assertEquals(3_000L, config.getReclamationIntervalMillis());
            assertEquals(0L, config.getProgressIntervalMillis());
        } finally {
            restoreProperty("h2.longrun.reclamation.interval", oldValue);
            restoreProperty("h2.longrun.progress.interval", oldProgress);
        }
    }

    @Test
    public void systemPropertyOverridesFaultInjection() throws Exception {
        String oldEnabled = System.getProperty("h2.longrun.fault.enabled");
        String oldInterval = System.getProperty("h2.longrun.fault.interval");
        String oldKinds = System.getProperty("h2.longrun.fault.kinds");
        String oldMaxBytes = System.getProperty("h2.longrun.fault.maxBytes");
        String oldRetainedCopies = System.getProperty("h2.longrun.fault.retainedCopies");
        System.setProperty("h2.longrun.fault.enabled", "true");
        System.setProperty("h2.longrun.fault.interval", "3s");
        System.setProperty("h2.longrun.fault.kinds", "bit-flip,partial-page");
        System.setProperty("h2.longrun.fault.maxBytes", "128");
        System.setProperty("h2.longrun.fault.retainedCopies", "3");
        try {
            LongRunConfig config = LongRunConfig.load(CommandLineOptions.parse());

            assertTrue(config.isFaultEnabled());
            assertEquals(3_000L, config.getFaultIntervalMillis());
            assertEquals(2, config.getFaultKinds().length);
            assertEquals(FaultInjectionKind.BIT_FLIP, config.getFaultKinds()[0]);
            assertEquals(128, config.getFaultMaxBytes());
            assertEquals(3, config.getFaultRetainedCopies());
        } finally {
            restoreProperty("h2.longrun.fault.enabled", oldEnabled);
            restoreProperty("h2.longrun.fault.interval", oldInterval);
            restoreProperty("h2.longrun.fault.kinds", oldKinds);
            restoreProperty("h2.longrun.fault.maxBytes", oldMaxBytes);
            restoreProperty("h2.longrun.fault.retainedCopies", oldRetainedCopies);
        }
    }

    @Test
    public void sampleSmokeConfigUsesTenSecondReclamationInterval() throws Exception {
        File configFile = new File("src/longrun/resources/smoke.properties");

        assertTrue(configFile.isFile());
        LongRunConfig config = LongRunConfig.load(CommandLineOptions.parse("--config", configFile.getPath()));
        assertEquals(10_000L, config.getReclamationIntervalMillis());
    }

    @Test
    public void reliabilityProfilesArePackagedConfigs() throws Exception {
        File reopenConfig = new File("src/longrun/resources/reopen.properties");
        File crashConfig = new File("src/longrun/resources/crash.properties");
        File nightlyConfig = new File("src/longrun/resources/nightly.properties");
        File performanceConfig = new File("src/longrun/resources/performance.properties");
        File comprehensiveConfig = new File("src/longrun/resources/comprehensive.properties");
        File soakConfig = new File("src/longrun/resources/soak-30d.properties");
        File faultConfig = new File("src/longrun/resources/fault-injection.properties");

        assertTrue(reopenConfig.isFile());
        assertTrue(crashConfig.isFile());
        assertTrue(nightlyConfig.isFile());
        assertTrue(performanceConfig.isFile());
        assertTrue(comprehensiveConfig.isFile());
        assertTrue(soakConfig.isFile());
        assertTrue(faultConfig.isFile());
        LongRunConfig reopen = LongRunConfig.load(CommandLineOptions.parse("--config", reopenConfig.getPath()));
        LongRunConfig crash = LongRunConfig.load(CommandLineOptions.parse("--config", crashConfig.getPath()));
        LongRunConfig nightly = LongRunConfig.load(CommandLineOptions.parse("--config", nightlyConfig.getPath()));
        LongRunConfig performance = LongRunConfig.load(CommandLineOptions.parse("--config",
                performanceConfig.getPath()));
        LongRunConfig comprehensive = LongRunConfig.load(CommandLineOptions.parse("--config",
                comprehensiveConfig.getPath()));
        LongRunConfig soak = LongRunConfig.load(CommandLineOptions.parse("--config", soakConfig.getPath()));
        LongRunConfig fault = LongRunConfig.load(CommandLineOptions.parse("--config", faultConfig.getPath()));
        assertTrue(reopen.getReopenIntervalMillis() > 0L);
        assertTrue(crash.isCrashEnabled());
        assertTrue(crash.getCrashCycles() > 0);
        assertTrue(crash.getReopenIntervalMillis() > 0L);
        assertTrue(comprehensive.isCrashEnabled());
        assertTrue(comprehensive.getCrashCycles() > 0);
        assertTrue(comprehensive.getReopenIntervalMillis() > 0L);
        assertEquals(0L, performance.getReopenIntervalMillis());
        assertEquals(2_000L, performance.getMetricsIntervalMillis());
        assertEquals(10_000L, performance.getReclamationIntervalMillis());
        assertEquals(10_000L, performance.getReclamationMinSchedulerIntervalMillis());
        assertEquals(64L * 1024L * 1024L, performance.getReclamationMaxLiveBytesToRewrite());
        assertEquals(250L, performance.getReclamationMaxRunMillis());
        assertEquals(250L, performance.getReclamationMaxTailCompactionMillis());
        assertEquals("bounded", comprehensive.getLedgerMode());
        assertEquals(60L * 60L * 1000L, reopen.getDurationMillis());
        assertEquals("reopen", reopen.getInstanceName());
        assertEquals(2L * 60L * 1000L, reopen.getReopenIntervalMillis());
        assertEquals(30L * 60L * 1000L, crash.getDurationMillis());
        assertEquals("crash", crash.getInstanceName());
        assertEquals(60_000L, crash.getCrashIntervalMillis());
        assertEquals(15, crash.getCrashCycles());
        assertEquals("nightly", nightly.getInstanceName());
        assertEquals(12L * 60L * 60L * 1000L, nightly.getDurationMillis());
        assertEquals(30L * 60L * 1000L, nightly.getReopenIntervalMillis());
        assertEquals("comprehensive", comprehensive.getInstanceName());
        assertEquals(12, comprehensive.getCrashCycles());
        assertEquals("soak-30d", soak.getInstanceName());
        assertEquals("bounded", soak.getLedgerMode());
        assertEquals(10_000L, soak.getReclamationIntervalMillis());
        assertEquals(180, soak.getCrashCycles());
        assertTrue(fault.isFaultEnabled());
        assertEquals("fault-injection", fault.getInstanceName());
        assertEquals(120_000L, fault.getFaultIntervalMillis());
        assertEquals(5, fault.getFaultRetainedCopies());
        assertEquals(FaultInjectionKind.TRUNCATE, fault.getFaultKinds()[0]);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
