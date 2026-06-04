/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;

/**
 * Command-line options for the standalone long-running stress test application.
 */
public final class CommandLineOptions {

    private boolean help;
    private boolean report;
    private File configFile;
    private File workDir;
    private File logFile;
    private Double minOpsPerSecond;
    private Double maxThroughputDropRatio;
    private Double maxFinalSizeGb;
    private Double maxSizePerMillionOpsGb;
    private Double maxSizeAmplification;
    private Integer minReclamationEvents;
    private Integer maxErrorLines;
    private String duration;
    private Long seed;
    private LongRunMode mode;
    private Boolean resume;
    private Boolean worker;
    private File h2Jar;

    public static CommandLineOptions parse(String... args) {
        CommandLineOptions options = new CommandLineOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (i == 0 && "report".equals(arg)) {
                options.report = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                options.help = true;
            } else if ("--config".equals(arg) || "-c".equals(arg)) {
                options.configFile = new File(requireValue(args, ++i, arg));
            } else if ("--work-dir".equals(arg) || "-w".equals(arg)) {
                options.workDir = new File(requireValue(args, ++i, arg));
            } else if ("--log-file".equals(arg) || "-l".equals(arg)) {
                options.logFile = new File(requireValue(args, ++i, arg));
            } else if ("--min-ops-per-second".equals(arg) || "-n".equals(arg)) {
                options.minOpsPerSecond = Double.valueOf(requireValue(args, ++i, arg));
            } else if ("--max-throughput-drop-ratio".equals(arg) || "-t".equals(arg)) {
                options.maxThroughputDropRatio = Double.valueOf(requireValue(args, ++i, arg));
            } else if ("--max-final-size-gb".equals(arg) || "-f".equals(arg)) {
                options.maxFinalSizeGb = Double.valueOf(requireValue(args, ++i, arg));
            } else if ("--max-size-per-million-ops-gb".equals(arg) || "-p".equals(arg)) {
                options.maxSizePerMillionOpsGb = Double.valueOf(requireValue(args, ++i, arg));
            } else if ("--max-size-amplification".equals(arg) || "-a".equals(arg)) {
                options.maxSizeAmplification = Double.valueOf(requireValue(args, ++i, arg));
            } else if ("--min-reclamation-events".equals(arg) || "-e".equals(arg)) {
                options.minReclamationEvents = Integer.valueOf(requireValue(args, ++i, arg));
            } else if ("--max-error-lines".equals(arg) || "-x".equals(arg)) {
                options.maxErrorLines = Integer.valueOf(requireValue(args, ++i, arg));
            } else if ("--duration".equals(arg) || "-d".equals(arg)) {
                options.duration = requireValue(args, ++i, arg);
            } else if ("--seed".equals(arg) || "-s".equals(arg)) {
                options.seed = Long.valueOf(requireValue(args, ++i, arg));
            } else if ("--mode".equals(arg) || "-m".equals(arg)) {
                options.mode = LongRunMode.parse(requireValue(args, ++i, arg));
            } else if ("--resume".equals(arg) || "-R".equals(arg)) {
                options.resume = Boolean.valueOf(requireValue(args, ++i, arg));
            } else if ("--worker".equals(arg) || "-k".equals(arg)) {
                options.worker = Boolean.valueOf(requireValue(args, ++i, arg));
            } else if ("--h2-jar".equals(arg) || "-j".equals(arg)) {
                options.h2Jar = new File(requireValue(args, ++i, arg));
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return options;
    }

    public boolean isHelp() {
        return help;
    }

    public boolean isReport() {
        return report;
    }

    public File getConfigFile() {
        return configFile;
    }

    public File getWorkDir() {
        return workDir;
    }

    public File getLogFile() {
        return logFile;
    }

    public Double getMinOpsPerSecond() {
        return minOpsPerSecond;
    }

    public Double getMaxThroughputDropRatio() {
        return maxThroughputDropRatio;
    }

    public Double getMaxFinalSizeGb() {
        return maxFinalSizeGb;
    }

    public Double getMaxSizePerMillionOpsGb() {
        return maxSizePerMillionOpsGb;
    }

    public Double getMaxSizeAmplification() {
        return maxSizeAmplification;
    }

    public Integer getMinReclamationEvents() {
        return minReclamationEvents;
    }

    public Integer getMaxErrorLines() {
        return maxErrorLines;
    }

    public String getDuration() {
        return duration;
    }

    public Long getSeed() {
        return seed;
    }

    public LongRunMode getMode() {
        return mode;
    }

    public Boolean getResume() {
        return resume;
    }

    public Boolean getWorker() {
        return worker;
    }

    public File getH2Jar() {
        return h2Jar;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
