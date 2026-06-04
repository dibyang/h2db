/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Immutable configuration for one long-running stress test process.
 */
public final class LongRunConfig {

    public static final long DEFAULT_SMOKE_DURATION_MILLIS = 5L * 60L * 1000L;
    public static final long DEFAULT_RECLAMATION_INTERVAL_MILLIS = 10L * 1000L;
    public static final long DEFAULT_PROGRESS_INTERVAL_MILLIS = 30L * 1000L;

    private final File configFile;
    private final String instanceName;
    private final String runName;
    private final File workDir;
    private final long durationMillis;
    private final long seed;
    private final LongRunMode mode;
    private final boolean resume;
    private final boolean worker;
    private final File h2Jar;
    private final int keySpace;
    private final int valueSizeMin;
    private final int valueSizeMax;
    private final String ledgerMode;
    private final long ledgerMaxEntries;
    private final int retentionTimeMillis;
    private final int versionsToKeep;
    private final long metricsIntervalMillis;
    private final long progressIntervalMillis;
    private final long stateIntervalMillis;
    private final boolean reclamationEnabled;
    private final long reclamationIntervalMillis;
    private final int reclamationMaxCandidateChunks;
    private final int reclamationMaxLiveBytesToRewrite;
    private final int reclamationMaxRunMillis;
    private final int reclamationMaxTailCompactionMillis;
    private final int reclamationMinSchedulerIntervalMillis;
    private final long reopenIntervalMillis;
    private final boolean faultEnabled;
    private final long faultIntervalMillis;
    private final FaultInjectionKind[] faultKinds;
    private final int faultMaxBytes;
    private final int faultRetainedCopies;
    private final boolean crashEnabled;
    private final long crashIntervalMillis;
    private final int crashCycles;
    private final long maxDbSizeBytes;

    private LongRunConfig(Builder builder) {
        configFile = builder.configFile;
        instanceName = builder.instanceName;
        runName = builder.runName;
        workDir = builder.workDir;
        durationMillis = builder.durationMillis;
        seed = builder.seed;
        mode = builder.mode;
        resume = builder.resume;
        worker = builder.worker;
        h2Jar = builder.h2Jar;
        keySpace = builder.keySpace;
        valueSizeMin = builder.valueSizeMin;
        valueSizeMax = builder.valueSizeMax;
        ledgerMode = builder.ledgerMode;
        ledgerMaxEntries = builder.ledgerMaxEntries;
        retentionTimeMillis = builder.retentionTimeMillis;
        versionsToKeep = builder.versionsToKeep;
        metricsIntervalMillis = builder.metricsIntervalMillis;
        progressIntervalMillis = builder.progressIntervalMillis;
        stateIntervalMillis = builder.stateIntervalMillis;
        reclamationEnabled = builder.reclamationEnabled;
        reclamationIntervalMillis = builder.reclamationIntervalMillis;
        reclamationMaxCandidateChunks = builder.reclamationMaxCandidateChunks;
        reclamationMaxLiveBytesToRewrite = builder.reclamationMaxLiveBytesToRewrite;
        reclamationMaxRunMillis = builder.reclamationMaxRunMillis;
        reclamationMaxTailCompactionMillis = builder.reclamationMaxTailCompactionMillis;
        reclamationMinSchedulerIntervalMillis = builder.reclamationMinSchedulerIntervalMillis;
        reopenIntervalMillis = builder.reopenIntervalMillis;
        faultEnabled = builder.faultEnabled;
        faultIntervalMillis = builder.faultIntervalMillis;
        faultKinds = builder.faultKinds.clone();
        faultMaxBytes = builder.faultMaxBytes;
        faultRetainedCopies = builder.faultRetainedCopies;
        crashEnabled = builder.crashEnabled;
        crashIntervalMillis = builder.crashIntervalMillis;
        crashCycles = builder.crashCycles;
        maxDbSizeBytes = builder.maxDbSizeBytes;
    }

    public static LongRunConfig load(CommandLineOptions options) throws IOException {
        Properties properties = new Properties();
        if (options.getConfigFile() != null) {
            File config = options.getConfigFile();
            if (!config.isFile()) {
                throw new IllegalArgumentException("Config file does not exist: " + config.getAbsolutePath());
            }
            try (FileInputStream in = new FileInputStream(config)) {
                properties.load(in);
            }
        }
        Builder builder = new Builder();
        builder.configFile(options.getConfigFile());
        builder.instanceName(property(properties, "run.instance", "i1"));
        builder.runName(property(properties, "run.name", "h2-longrun"));
        builder.workDir(new File(property(properties, "run.workDir", "work/smoke")));
        builder.durationMillis(parseDurationMillis(property(properties, "run.duration", "5m")));
        builder.seed(Long.parseLong(property(properties, "run.seed", "20260601")));
        builder.mode(LongRunMode.parse(property(properties, "workload.mode", "mvstore")));
        builder.resume(Boolean.parseBoolean(property(properties, "run.resume", "false")));
        builder.worker(Boolean.parseBoolean(property(properties, "run.worker", "false")));
        builder.keySpace(Integer.parseInt(property(properties, "workload.keySpace", "10000")));
        builder.valueSizeMin(Integer.parseInt(property(properties, "workload.valueSizeMin", "64")));
        builder.valueSizeMax(Integer.parseInt(property(properties, "workload.valueSizeMax", "4096")));
        builder.ledgerMode(property(properties, "workload.ledgerMode", "bounded"));
        builder.ledgerMaxEntries(Long.parseLong(property(properties, "workload.ledgerMaxEntries", "100000")));
        builder.retentionTimeMillis(Integer.parseInt(property(properties, "workload.retentionTimeMillis", "0")));
        builder.versionsToKeep(Integer.parseInt(property(properties, "workload.versionsToKeep", "0")));
        builder.metricsIntervalMillis(parseDurationMillis(property(properties, "metrics.interval", "5s")));
        builder.progressIntervalMillis(parseDurationMillis(property(properties, "progress.interval", "30s")));
        builder.stateIntervalMillis(parseDurationMillis(property(properties, "state.interval", "5s")));
        builder.reclamationEnabled(Boolean.parseBoolean(property(properties, "reclamation.enabled", "true")));
        builder.reclamationIntervalMillis(parseDurationMillis(property(properties, "reclamation.interval", "10s")));
        builder.reclamationMaxCandidateChunks(Integer.parseInt(property(properties,
                "reclamation.maxCandidateChunks", "64")));
        builder.reclamationMaxLiveBytesToRewrite((int) parseSizeBytesMb(property(properties,
                "reclamation.maxLiveBytesToRewriteMb", "64")));
        builder.reclamationMaxRunMillis((int) parseDurationMillis(property(properties,
                "reclamation.maxRun", "500")));
        builder.reclamationMaxTailCompactionMillis((int) parseDurationMillis(property(properties,
                "reclamation.maxTailCompaction", "500")));
        builder.reclamationMinSchedulerIntervalMillis((int) parseDurationMillis(property(properties,
                "reclamation.minSchedulerInterval", "0")));
        builder.reopenIntervalMillis(parseDurationMillis(property(properties, "check.reopenInterval", "30m")));
        builder.faultEnabled(Boolean.parseBoolean(property(properties, "fault.enabled", "false")));
        builder.faultIntervalMillis(parseDurationMillis(property(properties, "fault.interval", "30m")));
        builder.faultKinds(parseFaultKinds(property(properties, "fault.kinds",
                "truncate,bit-flip,zero-range,random-range,partial-page")));
        builder.faultMaxBytes(Integer.parseInt(property(properties, "fault.maxBytes", "4096")));
        builder.faultRetainedCopies(Integer.parseInt(property(properties, "fault.retainedCopies", "8")));
        builder.crashEnabled(Boolean.parseBoolean(property(properties, "crash.enabled", "false")));
        builder.crashIntervalMillis(parseDurationMillis(property(properties, "crash.interval", "120s")));
        builder.crashCycles(Integer.parseInt(property(properties, "crash.cycles", "1")));
        builder.maxDbSizeBytes(parseSizeBytes(property(properties, "limits.maxDbSizeGb", "0")));
        String h2Jar = property(properties, "run.h2Jar", "");
        if (!h2Jar.trim().isEmpty()) {
            builder.h2Jar(new File(h2Jar));
        }
        if (options.getWorkDir() != null) {
            builder.workDir(options.getWorkDir());
        }
        if (options.getDuration() != null) {
            builder.durationMillis(parseDurationMillis(options.getDuration()));
        }
        if (options.getSeed() != null) {
            builder.seed(options.getSeed().longValue());
        }
        if (options.getMode() != null) {
            builder.mode(options.getMode());
        }
        if (options.getResume() != null) {
            builder.resume(options.getResume().booleanValue());
        }
        if (options.getWorker() != null) {
            builder.worker(options.getWorker().booleanValue());
        }
        if (options.getH2Jar() != null) {
            builder.h2Jar(options.getH2Jar());
        }
        String reclamationInterval = System.getProperty("h2.longrun.reclamation.interval");
        if (reclamationInterval != null && !reclamationInterval.trim().isEmpty()) {
            builder.reclamationIntervalMillis(parseDurationMillis(reclamationInterval));
        }
        String progressInterval = System.getProperty("h2.longrun.progress.interval");
        if (progressInterval != null && !progressInterval.trim().isEmpty()) {
            builder.progressIntervalMillis(parseDurationMillis(progressInterval));
        }
        String reopenInterval = System.getProperty("h2.longrun.reopen.interval");
        if (reopenInterval != null && !reopenInterval.trim().isEmpty()) {
            builder.reopenIntervalMillis(parseDurationMillis(reopenInterval));
        }
        String faultEnabled = System.getProperty("h2.longrun.fault.enabled");
        if (faultEnabled != null && !faultEnabled.trim().isEmpty()) {
            builder.faultEnabled(Boolean.parseBoolean(faultEnabled));
        }
        String faultInterval = System.getProperty("h2.longrun.fault.interval");
        if (faultInterval != null && !faultInterval.trim().isEmpty()) {
            builder.faultIntervalMillis(parseDurationMillis(faultInterval));
        }
        String faultKinds = System.getProperty("h2.longrun.fault.kinds");
        if (faultKinds != null && !faultKinds.trim().isEmpty()) {
            builder.faultKinds(parseFaultKinds(faultKinds));
        }
        String faultMaxBytes = System.getProperty("h2.longrun.fault.maxBytes");
        if (faultMaxBytes != null && !faultMaxBytes.trim().isEmpty()) {
            builder.faultMaxBytes(Integer.parseInt(faultMaxBytes));
        }
        String faultRetainedCopies = System.getProperty("h2.longrun.fault.retainedCopies");
        if (faultRetainedCopies != null && !faultRetainedCopies.trim().isEmpty()) {
            builder.faultRetainedCopies(Integer.parseInt(faultRetainedCopies));
        }
        String crashEnabled = System.getProperty("h2.longrun.crash.enabled");
        if (crashEnabled != null && !crashEnabled.trim().isEmpty()) {
            builder.crashEnabled(Boolean.parseBoolean(crashEnabled));
        }
        String crashInterval = System.getProperty("h2.longrun.crash.interval");
        if (crashInterval != null && !crashInterval.trim().isEmpty()) {
            builder.crashIntervalMillis(parseDurationMillis(crashInterval));
        }
        String crashCycles = System.getProperty("h2.longrun.crash.cycles");
        if (crashCycles != null && !crashCycles.trim().isEmpty()) {
            builder.crashCycles(Integer.parseInt(crashCycles));
        }
        return builder.build();
    }

    public File getConfigFile() {
        return configFile;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public String getRunName() {
        return runName;
    }

    public File getWorkDir() {
        return workDir;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getSeed() {
        return seed;
    }

    public LongRunMode getMode() {
        return mode;
    }

    public boolean isResume() {
        return resume;
    }

    public boolean isWorker() {
        return worker;
    }

    public File getH2Jar() {
        return h2Jar;
    }

    public int getKeySpace() {
        return keySpace;
    }

    public int getValueSizeMin() {
        return valueSizeMin;
    }

    public int getValueSizeMax() {
        return valueSizeMax;
    }

    public String getLedgerMode() {
        return ledgerMode;
    }

    public long getLedgerMaxEntries() {
        return ledgerMaxEntries;
    }

    public int getRetentionTimeMillis() {
        return retentionTimeMillis;
    }

    public int getVersionsToKeep() {
        return versionsToKeep;
    }

    public long getMetricsIntervalMillis() {
        return metricsIntervalMillis;
    }

    public long getProgressIntervalMillis() {
        return progressIntervalMillis;
    }

    public long getStateIntervalMillis() {
        return stateIntervalMillis;
    }

    public boolean isReclamationEnabled() {
        return reclamationEnabled;
    }

    public long getReclamationIntervalMillis() {
        return reclamationIntervalMillis;
    }

    public int getReclamationMaxCandidateChunks() {
        return reclamationMaxCandidateChunks;
    }

    public int getReclamationMaxLiveBytesToRewrite() {
        return reclamationMaxLiveBytesToRewrite;
    }

    public int getReclamationMaxRunMillis() {
        return reclamationMaxRunMillis;
    }

    public int getReclamationMaxTailCompactionMillis() {
        return reclamationMaxTailCompactionMillis;
    }

    public int getReclamationMinSchedulerIntervalMillis() {
        return reclamationMinSchedulerIntervalMillis;
    }

    public long getReopenIntervalMillis() {
        return reopenIntervalMillis;
    }

    public boolean isFaultEnabled() {
        return faultEnabled;
    }

    public long getFaultIntervalMillis() {
        return faultIntervalMillis;
    }

    public FaultInjectionKind[] getFaultKinds() {
        return faultKinds.clone();
    }

    public int getFaultMaxBytes() {
        return faultMaxBytes;
    }

    public int getFaultRetainedCopies() {
        return faultRetainedCopies;
    }

    public boolean isCrashEnabled() {
        return crashEnabled;
    }

    public long getCrashIntervalMillis() {
        return crashIntervalMillis;
    }

    public int getCrashCycles() {
        return crashCycles;
    }

    public long getMaxDbSizeBytes() {
        return maxDbSizeBytes;
    }

    public String summary() {
        return "instance=" + instanceName +
                ", runName=" + runName +
                ", mode=" + mode +
                ", durationMillis=" + durationMillis +
                ", seed=" + seed +
                ", workDir=" + workDir.getPath() +
                ", resume=" + resume +
                ", worker=" + worker +
                ", keySpace=" + keySpace +
                ", valueSizeMin=" + valueSizeMin +
                ", valueSizeMax=" + valueSizeMax +
                ", ledgerMode=" + ledgerMode +
                ", ledgerMaxEntries=" + ledgerMaxEntries +
                ", retentionTimeMillis=" + retentionTimeMillis +
                ", versionsToKeep=" + versionsToKeep +
                ", progressIntervalMillis=" + progressIntervalMillis +
                ", reclamationEnabled=" + reclamationEnabled +
                ", reclamationIntervalMillis=" + reclamationIntervalMillis +
                ", reclamationMaxCandidateChunks=" + reclamationMaxCandidateChunks +
                ", reclamationMaxLiveBytesToRewrite=" + reclamationMaxLiveBytesToRewrite +
                ", reclamationMaxRunMillis=" + reclamationMaxRunMillis +
                ", reclamationMaxTailCompactionMillis=" + reclamationMaxTailCompactionMillis +
                ", reclamationMinSchedulerIntervalMillis=" + reclamationMinSchedulerIntervalMillis +
                ", reopenIntervalMillis=" + reopenIntervalMillis +
                ", faultEnabled=" + faultEnabled +
                ", faultIntervalMillis=" + faultIntervalMillis +
                ", faultKinds=" + faultKindsSummary() +
                ", faultMaxBytes=" + faultMaxBytes +
                ", faultRetainedCopies=" + faultRetainedCopies +
                ", crashEnabled=" + crashEnabled +
                ", crashIntervalMillis=" + crashIntervalMillis +
                ", crashCycles=" + crashCycles +
                ", maxDbSizeBytes=" + maxDbSizeBytes +
                ", h2Jar=" + (h2Jar == null ? "" : h2Jar.getPath()) +
                ", config=" + (configFile == null ? "" : configFile.getPath());
    }

    static long parseDurationMillis(String value) {
        if (value == null) {
            return DEFAULT_SMOKE_DURATION_MILLIS;
        }
        String text = value.trim().toLowerCase();
        if (text.isEmpty()) {
            return DEFAULT_SMOKE_DURATION_MILLIS;
        }
        long multiplier = 1L;
        char last = text.charAt(text.length() - 1);
        if (last == 's' || last == 'm' || last == 'h' || last == 'd') {
            text = text.substring(0, text.length() - 1);
            if (last == 's') {
                multiplier = 1000L;
            } else if (last == 'm') {
                multiplier = 60L * 1000L;
            } else if (last == 'h') {
                multiplier = 60L * 60L * 1000L;
            } else {
                multiplier = 24L * 60L * 60L * 1000L;
            }
        }
        long amount = Long.parseLong(text);
        if (amount < 0L) {
            throw new IllegalArgumentException("duration");
        }
        return amount * multiplier;
    }

    private static String property(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : value;
    }

    private static long parseSizeBytes(String value) {
        long gb = Long.parseLong(value.trim());
        if (gb < 0L) {
            throw new IllegalArgumentException("limits.maxDbSizeGb");
        }
        return gb * 1024L * 1024L * 1024L;
    }

    private static long parseSizeBytesMb(String value) {
        long mb = Long.parseLong(value.trim());
        if (mb < 0L) {
            throw new IllegalArgumentException("sizeMb");
        }
        return mb * 1024L * 1024L;
    }

    private static FaultInjectionKind[] parseFaultKinds(String value) {
        String[] parts = value.split(",");
        java.util.ArrayList<FaultInjectionKind> kinds = new java.util.ArrayList<>();
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                kinds.add(FaultInjectionKind.parse(part));
            }
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("fault.kinds");
        }
        return kinds.toArray(new FaultInjectionKind[0]);
    }

    private String faultKindsSummary() {
        StringBuilder builder = new StringBuilder();
        for (FaultInjectionKind kind : faultKinds) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(kind.name());
        }
        return builder.toString();
    }

    /**
     * Mutable builder used while merging file and command-line configuration.
     */
    public static final class Builder {
        private File configFile;
        private String instanceName = "i1";
        private String runName = "h2-longrun";
        private File workDir = new File("work/smoke");
        private long durationMillis = DEFAULT_SMOKE_DURATION_MILLIS;
        private long seed = 20260601L;
        private LongRunMode mode = LongRunMode.MVSTORE;
        private boolean resume;
        private boolean worker;
        private File h2Jar;
        private int keySpace = 10_000;
        private int valueSizeMin = 64;
        private int valueSizeMax = 4096;
        private String ledgerMode = "bounded";
        private long ledgerMaxEntries = 100_000L;
        private int retentionTimeMillis;
        private int versionsToKeep;
        private long metricsIntervalMillis = 5_000L;
        private long progressIntervalMillis = DEFAULT_PROGRESS_INTERVAL_MILLIS;
        private long stateIntervalMillis = 5_000L;
        private boolean reclamationEnabled = true;
        private long reclamationIntervalMillis = DEFAULT_RECLAMATION_INTERVAL_MILLIS;
        private int reclamationMaxCandidateChunks = 64;
        private int reclamationMaxLiveBytesToRewrite = 64 * 1024 * 1024;
        private int reclamationMaxRunMillis = 500;
        private int reclamationMaxTailCompactionMillis = 500;
        private int reclamationMinSchedulerIntervalMillis;
        private long reopenIntervalMillis = 30L * 60L * 1000L;
        private boolean faultEnabled;
        private long faultIntervalMillis = 30L * 60L * 1000L;
        private FaultInjectionKind[] faultKinds = new FaultInjectionKind[] {
                FaultInjectionKind.TRUNCATE,
                FaultInjectionKind.BIT_FLIP,
                FaultInjectionKind.ZERO_RANGE,
                FaultInjectionKind.RANDOM_RANGE,
                FaultInjectionKind.PARTIAL_PAGE
        };
        private int faultMaxBytes = 4096;
        private int faultRetainedCopies = 8;
        private boolean crashEnabled;
        private long crashIntervalMillis = 120_000L;
        private int crashCycles = 1;
        private long maxDbSizeBytes;

        Builder configFile(File configFile) {
            this.configFile = configFile;
            return this;
        }

        Builder instanceName(String instanceName) {
            if (instanceName == null || instanceName.trim().isEmpty()) {
                throw new IllegalArgumentException("run.instance");
            }
            this.instanceName = instanceName.trim();
            return this;
        }

        Builder runName(String runName) {
            if (runName == null || runName.trim().isEmpty()) {
                throw new IllegalArgumentException("run.name");
            }
            this.runName = runName.trim();
            return this;
        }

        Builder workDir(File workDir) {
            if (workDir == null) {
                throw new IllegalArgumentException("workDir");
            }
            this.workDir = workDir;
            return this;
        }

        Builder durationMillis(long durationMillis) {
            if (durationMillis < 0L) {
                throw new IllegalArgumentException("duration");
            }
            this.durationMillis = durationMillis;
            return this;
        }

        Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        Builder mode(LongRunMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("mode");
            }
            this.mode = mode;
            return this;
        }

        Builder resume(boolean resume) {
            this.resume = resume;
            return this;
        }

        Builder worker(boolean worker) {
            this.worker = worker;
            return this;
        }

        Builder h2Jar(File h2Jar) {
            this.h2Jar = h2Jar;
            return this;
        }

        Builder keySpace(int keySpace) {
            if (keySpace < 1) {
                throw new IllegalArgumentException("keySpace");
            }
            this.keySpace = keySpace;
            return this;
        }

        Builder valueSizeMin(int valueSizeMin) {
            if (valueSizeMin < 1) {
                throw new IllegalArgumentException("valueSizeMin");
            }
            this.valueSizeMin = valueSizeMin;
            return this;
        }

        Builder valueSizeMax(int valueSizeMax) {
            if (valueSizeMax < 1) {
                throw new IllegalArgumentException("valueSizeMax");
            }
            this.valueSizeMax = valueSizeMax;
            return this;
        }

        Builder ledgerMode(String ledgerMode) {
            if (ledgerMode == null) {
                throw new IllegalArgumentException("workload.ledgerMode");
            }
            String normalized = ledgerMode.trim().toLowerCase();
            if (!"bounded".equals(normalized) && !"append-only".equals(normalized)) {
                throw new IllegalArgumentException("workload.ledgerMode");
            }
            this.ledgerMode = normalized;
            return this;
        }

        Builder ledgerMaxEntries(long ledgerMaxEntries) {
            if (ledgerMaxEntries < 1L) {
                throw new IllegalArgumentException("workload.ledgerMaxEntries");
            }
            this.ledgerMaxEntries = ledgerMaxEntries;
            return this;
        }

        Builder retentionTimeMillis(int retentionTimeMillis) {
            if (retentionTimeMillis < 0) {
                throw new IllegalArgumentException("workload.retentionTimeMillis");
            }
            this.retentionTimeMillis = retentionTimeMillis;
            return this;
        }

        Builder versionsToKeep(int versionsToKeep) {
            if (versionsToKeep < 0) {
                throw new IllegalArgumentException("workload.versionsToKeep");
            }
            this.versionsToKeep = versionsToKeep;
            return this;
        }

        Builder metricsIntervalMillis(long metricsIntervalMillis) {
            if (metricsIntervalMillis < 1L) {
                throw new IllegalArgumentException("metrics.interval");
            }
            this.metricsIntervalMillis = metricsIntervalMillis;
            return this;
        }

        Builder progressIntervalMillis(long progressIntervalMillis) {
            if (progressIntervalMillis < 0L) {
                throw new IllegalArgumentException("progress.interval");
            }
            this.progressIntervalMillis = progressIntervalMillis;
            return this;
        }

        Builder stateIntervalMillis(long stateIntervalMillis) {
            if (stateIntervalMillis < 1L) {
                throw new IllegalArgumentException("state.interval");
            }
            this.stateIntervalMillis = stateIntervalMillis;
            return this;
        }

        Builder reclamationEnabled(boolean reclamationEnabled) {
            this.reclamationEnabled = reclamationEnabled;
            return this;
        }

        Builder reclamationIntervalMillis(long reclamationIntervalMillis) {
            if (reclamationIntervalMillis < 1L) {
                throw new IllegalArgumentException("reclamation.interval");
            }
            this.reclamationIntervalMillis = reclamationIntervalMillis;
            return this;
        }

        Builder reclamationMaxCandidateChunks(int reclamationMaxCandidateChunks) {
            if (reclamationMaxCandidateChunks < 1) {
                throw new IllegalArgumentException("reclamation.maxCandidateChunks");
            }
            this.reclamationMaxCandidateChunks = reclamationMaxCandidateChunks;
            return this;
        }

        Builder reclamationMaxLiveBytesToRewrite(int reclamationMaxLiveBytesToRewrite) {
            if (reclamationMaxLiveBytesToRewrite < 0) {
                throw new IllegalArgumentException("reclamation.maxLiveBytesToRewriteMb");
            }
            this.reclamationMaxLiveBytesToRewrite = reclamationMaxLiveBytesToRewrite;
            return this;
        }

        Builder reclamationMaxRunMillis(int reclamationMaxRunMillis) {
            if (reclamationMaxRunMillis < 0) {
                throw new IllegalArgumentException("reclamation.maxRun");
            }
            this.reclamationMaxRunMillis = reclamationMaxRunMillis;
            return this;
        }

        Builder reclamationMaxTailCompactionMillis(int reclamationMaxTailCompactionMillis) {
            if (reclamationMaxTailCompactionMillis < 0) {
                throw new IllegalArgumentException("reclamation.maxTailCompaction");
            }
            this.reclamationMaxTailCompactionMillis = reclamationMaxTailCompactionMillis;
            return this;
        }

        Builder reclamationMinSchedulerIntervalMillis(int reclamationMinSchedulerIntervalMillis) {
            if (reclamationMinSchedulerIntervalMillis < 0) {
                throw new IllegalArgumentException("reclamation.minSchedulerInterval");
            }
            this.reclamationMinSchedulerIntervalMillis = reclamationMinSchedulerIntervalMillis;
            return this;
        }

        Builder reopenIntervalMillis(long reopenIntervalMillis) {
            if (reopenIntervalMillis < 0L) {
                throw new IllegalArgumentException("check.reopenInterval");
            }
            this.reopenIntervalMillis = reopenIntervalMillis;
            return this;
        }

        Builder faultEnabled(boolean faultEnabled) {
            this.faultEnabled = faultEnabled;
            return this;
        }

        Builder faultIntervalMillis(long faultIntervalMillis) {
            if (faultIntervalMillis < 1L) {
                throw new IllegalArgumentException("fault.interval");
            }
            this.faultIntervalMillis = faultIntervalMillis;
            return this;
        }

        Builder faultKinds(FaultInjectionKind[] faultKinds) {
            if (faultKinds == null || faultKinds.length == 0) {
                throw new IllegalArgumentException("fault.kinds");
            }
            this.faultKinds = faultKinds.clone();
            return this;
        }

        Builder faultMaxBytes(int faultMaxBytes) {
            if (faultMaxBytes < 1) {
                throw new IllegalArgumentException("fault.maxBytes");
            }
            this.faultMaxBytes = faultMaxBytes;
            return this;
        }

        Builder faultRetainedCopies(int faultRetainedCopies) {
            if (faultRetainedCopies < 0) {
                throw new IllegalArgumentException("fault.retainedCopies");
            }
            this.faultRetainedCopies = faultRetainedCopies;
            return this;
        }

        Builder crashEnabled(boolean crashEnabled) {
            this.crashEnabled = crashEnabled;
            return this;
        }

        Builder crashIntervalMillis(long crashIntervalMillis) {
            if (crashIntervalMillis < 1L) {
                throw new IllegalArgumentException("crash.interval");
            }
            this.crashIntervalMillis = crashIntervalMillis;
            return this;
        }

        Builder crashCycles(int crashCycles) {
            if (crashCycles < 0) {
                throw new IllegalArgumentException("crash.cycles");
            }
            this.crashCycles = crashCycles;
            return this;
        }

        Builder maxDbSizeBytes(long maxDbSizeBytes) {
            if (maxDbSizeBytes < 0L) {
                throw new IllegalArgumentException("limits.maxDbSizeGb");
            }
            this.maxDbSizeBytes = maxDbSizeBytes;
            return this;
        }

        LongRunConfig build() {
            if (valueSizeMin > valueSizeMax) {
                throw new IllegalArgumentException("valueSizeMin > valueSizeMax");
            }
            return new LongRunConfig(this);
        }
    }
}
