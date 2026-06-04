/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Parent-process crash harness for long-running stress tests.
 */
public final class CrashHarness {

    private final LongRunConfig config;
    private final ExternalH2JarMetadata h2JarMetadata;
    private volatile Process currentWorker;

    CrashHarness(LongRunConfig config, ExternalH2JarMetadata h2JarMetadata) {
        this.config = config;
        this.h2JarMetadata = h2JarMetadata;
    }

    public int run() throws LongRunFailure {
        Thread shutdownHook = new Thread(this::destroyCurrentWorker, "h2-longrun-crash-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            if (!config.isResume()) {
                deleteRecursively(new File(config.getWorkDir(), "worker-logs"));
            }
            for (int i = 0; i < config.getCrashCycles(); i++) {
                int cycle = i + 1;
                WorkerProcess worker = startWorker(shouldResumeWorkloadWorker(cycle),
                        Math.max(config.getCrashIntervalMillis() * 2L, config.getDurationMillis()), cycle, false);
                Thread.sleep(config.getCrashIntervalMillis());
                destroyWorker(worker.process);
                worker.process.waitFor();
                worker.joinOutput();
                LongRunLog.parent("Crash harness killed worker cycle=" + cycle + processLabel(worker.process)
                        + " workerLog=" + worker.logFile.getPath());
                WorkerProcess recoveryWorker = startWorker(true, Math.max(1_000L, config.getCrashIntervalMillis()),
                        cycle, true);
                int recovery = recoveryWorker.process.waitFor();
                recoveryWorker.joinOutput();
                LongRunLog.parent("Crash harness recovery worker exited cycle=" + cycle
                        + processLabel(recoveryWorker.process) + " exitCode=" + recovery
                        + " workerLog=" + recoveryWorker.logFile.getPath());
                currentWorker = null;
                if (recovery != 0) {
                    return recovery;
                }
            }
            return 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LongRunFailure("Crash harness failed", e);
        } finally {
            destroyCurrentWorker();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignore) {
                // JVM is already shutting down.
            }
        }
    }

    private WorkerProcess startWorker(boolean resume, long durationMillis, int cycle, boolean recoveryPhase)
            throws IOException {
        File workerLog = workerLogFile(config.getWorkDir(), cycle, recoveryPhase);
        prepareWorkerLog(workerLog);
        ArrayList<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(classPath());
        command.add("org.h2.test.longrun.LongRunTestApp");
        if (config.getConfigFile() != null) {
            command.add("--config");
            command.add(config.getConfigFile().getPath());
        }
        command.add("--work-dir");
        command.add(config.getWorkDir().getPath());
        command.add("--duration");
        command.add(Long.toString(durationMillis));
        command.add("--seed");
        command.add(Long.toString(config.getSeed()));
        command.add("--mode");
        command.add(config.getMode().name().toLowerCase());
        command.add("--resume");
        command.add(Boolean.toString(resume));
        command.add("--worker");
        command.add("true");
        command.add("--log-file");
        command.add(workerLog.getPath());
        if (h2JarMetadata != null) {
            command.add("--h2-jar");
            command.add(h2JarMetadata.getFile().getPath());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(true);
        Process worker = builder.start();
        currentWorker = worker;
        Thread outputThread = startWorkerOutputForwarder(worker, workerLog, cycle);
        LongRunLog.parent("Crash harness started worker cycle=" + cycle + processLabel(worker)
                + " resume=" + resume + " durationMillis=" + durationMillis
                + " workerLog=" + workerLog.getPath());
        return new WorkerProcess(worker, outputThread, workerLog);
    }

    static boolean shouldResumeWorkloadWorker(int cycle) {
        return cycle > 1;
    }

    static boolean isWorkerProgressLine(String line) {
        return line != null && (line.startsWith("PROGRESS ") || line.indexOf("] PROGRESS ") >= 0);
    }

    static File workerLogFile(File workDir, int cycle, boolean recovery) {
        File logDir = new File(workDir, "worker-logs");
        String phase = recovery ? "recovery" : "run";
        return new File(logDir, String.format(java.util.Locale.ROOT, "cycle-%03d-%s.log",
                Integer.valueOf(cycle), phase));
    }

    private static Thread startWorkerOutputForwarder(Process worker, File workerLog, int cycle) {
        Thread thread = new Thread(() -> {
            try {
                forwardWorkerOutput(worker.getInputStream(), workerLog);
            } catch (IOException e) {
                LongRunLog.parent("Crash harness worker log forwarder failed cycle=" + cycle
                        + " workerLog=" + workerLog.getPath() + " message=" + e.getMessage());
            }
        }, "h2-longrun-worker-log-" + cycle);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void forwardWorkerOutput(InputStream input, File workerLog) throws IOException {
        prepareWorkerLog(workerLog);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new FileOutputStream(workerLog, true))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.println(line);
                writer.flush();
                if (isWorkerProgressLine(line)) {
                    System.out.println(line);
                    System.out.flush();
                }
            }
        }
    }

    private static void prepareWorkerLog(File workerLog) throws IOException {
        File parent = workerLog.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create worker log dir: " + parent);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Could not delete old worker log: " + file);
        }
    }

    private void destroyCurrentWorker() {
        Process worker = currentWorker;
        if (worker != null) {
            destroyWorker(worker);
        }
    }

    private static void destroyWorker(Process worker) {
        try {
            worker.destroyForcibly();
            worker.waitFor(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignore) {
            // The worker may already be gone.
        }
    }

    private static String processId(Process process) {
        try {
            Object value = Process.class.getMethod("pid").invoke(process);
            return value == null ? "unknown" : value.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String processLabel(Process process) {
        String pid = processId(process);
        return "unknown".equals(pid) ? "" : " workerPid=" + pid;
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), name).getPath();
    }

    private String classPath() {
        String classPath = System.getProperty("java.class.path");
        if (h2JarMetadata == null) {
            return classPath;
        }
        return h2JarMetadata.getFile().getPath() + File.pathSeparator + classPath;
    }

    private static final class WorkerProcess {
        final Process process;
        final Thread outputThread;
        final File logFile;

        WorkerProcess(Process process, Thread outputThread, File logFile) {
            this.process = process;
            this.outputThread = outputThread;
            this.logFile = logFile;
        }

        void joinOutput() throws InterruptedException {
            outputThread.join(5_000L);
        }
    }
}
