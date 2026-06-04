/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.IOException;
import org.h2.engine.Constants;

/**
 * Standalone CLI entrypoint for H2 long-running stress tests.
 */
public final class LongRunTestApp {

    private LongRunTestApp() {
    }

    public static void main(String... args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String... args) {
        try {
            CommandLineOptions options = CommandLineOptions.parse(args);
            if (options.isHelp()) {
                printHelp();
                return 0;
            }
            if (options.isReport()) {
                return new ReportAnalyzer(options).run();
            }
            LongRunConfig config = LongRunConfig.load(options);
            ExternalH2JarMetadata h2JarMetadata = ExternalH2JarMetadata.inspect(config.getH2Jar());
            LongRunLog.info(config, testedComponentSummary(h2JarMetadata));
            LongRunLog.info(config, "H2 LongRun Test App role="
                    + (config.isWorker() ? "worker" : "parent"));
            LongRunLog.info(config, config.summary());
            if (h2JarMetadata != null) {
                LongRunLog.info(config, h2JarMetadata.summary());
            }
            if (config.isCrashEnabled() && !config.isWorker()) {
                int exitCode = new CrashHarness(config, h2JarMetadata).run();
                return exitCode == 0 ? writeCompletionReport(config, options) : exitCode;
            }
            int exitCode = new WorkloadRunner(config, h2JarMetadata).run();
            return exitCode == 0 ? writeCompletionReport(config, options) : exitCode;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid longrun arguments: " + e.getMessage());
            return 2;
        } catch (LongRunFailure e) {
            System.err.println("Longrun failed: " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace(System.err);
            }
            return 10;
        } catch (IOException e) {
            System.err.println("Unable to read longrun configuration: " + e.getMessage());
            return 2;
        }
    }

    private static int writeCompletionReport(LongRunConfig config, CommandLineOptions options) throws IOException {
        LongRunLog.info(config, "Generating longrun report...");
        return new ReportAnalyzer(config.getWorkDir(), options.getLogFile()).run();
    }

    private static String testedComponentSummary(ExternalH2JarMetadata h2JarMetadata) {
        if (h2JarMetadata != null) {
            return "testedComponent=h2db, testedComponentVersion="
                    + valueOrDefault(h2JarMetadata.getImplementationVersion(), "unknown")
                    + ", testedComponentSource=" + h2JarMetadata.getFile().getPath();
        }
        return "testedComponent=h2db, testedComponentVersion=" + Constants.FULL_VERSION
                + ", testedComponentSource=classpath";
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.length() == 0 ? defaultValue : value;
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar h2-longrun.jar -c <file> [options]");
        System.out.println("       java -jar h2-longrun.jar report -w <dir> [-l <file>]");
        System.out.println("Options:");
        System.out.println("  -h, --help");
        System.out.println("  -c, --config <file>    Longrun properties file.");
        System.out.println("  -w, --work-dir <dir>   Override run.workDir.");
        System.out.println("  -l, --log-file <file>  Log file used by automatic and manual report analysis.");
        System.out.println("  -n, --min-ops-per-second <n>  Warn if minimum sampled throughput is lower.");
        System.out.println("  -t, --max-throughput-drop-ratio <n>  Warn if (avg-min)/avg exceeds this ratio.");
        System.out.println("  -f, --max-final-size-gb <n>  Warn if finalSizeBytes is larger.");
        System.out.println("  -p, --max-size-per-million-ops-gb <n>  Warn if size per million ops is larger.");
        System.out.println("  -a, --max-size-amplification <n>  Warn if MVStore size amplification is larger.");
        System.out.println("  -e, --min-reclamation-events <n>  Warn if long runs have fewer reclamation events.");
        System.out.println("  -x, --max-error-lines <n>  Fail report if suspicious log lines exceed this value.");
        System.out.println("  -d, --duration <5m|12h>    Override run.duration. Default smoke is 5m.");
        System.out.println("  -s, --seed <number>        Override run.seed.");
        System.out.println("  -m, --mode <mvstore|sql|mixed>");
        System.out.println("  -R, --resume <true|false>  Resume from an existing state file.");
        System.out.println("  -k, --worker <true|false>  Internal child-process mode for crash harness.");
        System.out.println("  -j, --h2-jar <file>        Candidate H2 jar for future external mode.");
    }
}
