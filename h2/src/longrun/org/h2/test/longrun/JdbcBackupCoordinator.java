/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group.
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Runs periodic backups for longrun while workload is progressing.
 */
public final class JdbcBackupCoordinator implements AutoCloseable {

    private static final long MIN_POLL_INTERVAL = 1_000L;
    private static final Comparator<File> LAST_MODIFIED_ASC = new Comparator<File>() {
        @Override
        public int compare(File first, File second) {
            return Long.compare(first.lastModified(), second.lastModified());
        }
    };
    private static final String BACKUP_EXTENSION = ".sql";
    private static final String CHECKSUM_SUFFIX = ".md5";
    private static final String BACKUP_TIME_PATTERN = "yyyyMMdd-HHmmss";

    private final LongRunConfig config;
    private final LongRunWorkload workload;
    private final File backupDir;
    private final long backupIntervalMillis;
    private final int maxBackupsToKeep;
    private final boolean failOnError;
    private volatile boolean running;
    private volatile LongRunFailure failure;
    private Thread thread;

    JdbcBackupCoordinator(LongRunConfig config, LongRunWorkload workload) {
        this.config = config;
        this.workload = workload;
        backupIntervalMillis = config.getBackupIntervalMillis();
        maxBackupsToKeep = config.getBackupMaxRetained();
        failOnError = config.isBackupFailOnError();
        backupDir = resolveBackupDir(config);
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void start(long deadline) {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(() -> run(deadline), "h2-longrun-backup");
        thread.start();
    }

    boolean hasFailure() {
        return failure != null;
    }

    LongRunFailure getAndClearFailure() {
        LongRunFailure value = failure;
        failure = null;
        return value;
    }

    private void run(long deadline) {
        long nextRun = System.currentTimeMillis() + backupIntervalMillis;
        while (running && System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();
            long waitMillis = nextRun - now;
            if (waitMillis > 0L) {
                sleep(waitMillis);
                continue;
            }
            if (System.currentTimeMillis() >= deadline || !running) {
                break;
            }
            nextRun = System.currentTimeMillis() + backupIntervalMillis;
            try {
                backupOnce();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new LongRunFailure("JDBC backup failed", e);
                }
                LongRunLog.info(config, "Backup failed: " + e.getMessage());
                if (failOnError) {
                    running = false;
                }
            }
            try {
                pruneBackups();
            } catch (IOException e) {
                if (failure == null) {
                    failure = new LongRunFailure("Backup retention cleanup failed", e);
                }
                LongRunLog.info(config, "Backup retention failed: " + e.getMessage());
                if (failOnError) {
                    running = false;
                }
            }
        }
    }

    private void backupOnce() throws Exception {
        if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
            throw new IOException("Could not create backup dir: " + backupDir);
        }
        String timestamp = timestamp();
        File backupFile = new File(backupDir, timestamp + BACKUP_EXTENSION);
        executeBackup(backupFile);
        writeMd5(backupFile);
    }

    private void executeBackup(File backupFile) throws Exception {
        Class.forName("org.h2.Driver");
        String sql = "SCRIPT TO '" + escapeSqlLiteral(backupFile.getPath()) + "'";
        try (Connection connection = DriverManager.getConnection(workload.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private void writeMd5(File backupFile) throws Exception {
        if (!backupFile.isFile()) {
            throw new IOException("Backup file not found after backup: " + backupFile);
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = new FileInputStream(backupFile)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte b : digest.digest()) {
            builder.append(toHex(b));
        }
        File checksumFile = new File(backupFile.getPath() + CHECKSUM_SUFFIX);
        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(checksumFile),
                StandardCharsets.UTF_8)) {
            out.write(builder.toString());
            out.write("  ");
            out.write(backupFile.getName());
            out.write('\n');
        }
        LongRunLog.info(config, "Backup completed: " + backupFile.getName());
    }

    private void pruneBackups() throws IOException {
        File[] existing = backupDir.listFiles((dir, name) -> name.endsWith(BACKUP_EXTENSION));
        if (existing == null || existing.length <= maxBackupsToKeep) {
            return;
        }
        Arrays.sort(existing, LAST_MODIFIED_ASC);
        int remove = existing.length - maxBackupsToKeep;
        for (int i = 0; i < remove; i++) {
            deleteBackup(existing[i]);
        }
    }

    private void deleteBackup(File backupFile) throws IOException {
        if (backupFile == null || !backupFile.exists()) {
            return;
        }
        if (!backupFile.delete()) {
            throw new IOException("Could not delete old backup: " + backupFile);
        }
        File checksum = new File(backupFile.getPath() + CHECKSUM_SUFFIX);
        if (checksum.exists() && !checksum.delete()) {
            throw new IOException("Could not delete old backup checksum: " + checksum);
        }
    }

    private void sleep(long millis) {
        long remaining = millis;
        while (remaining > 0L && running) {
            try {
                Thread.sleep(Math.min(remaining, MIN_POLL_INTERVAL));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= MIN_POLL_INTERVAL;
        }
    }

    private static File resolveBackupDir(LongRunConfig config) {
        return config.getBackupDirectory();
    }

    private String timestamp() {
        return new SimpleDateFormat(BACKUP_TIME_PATTERN, Locale.US).format(new Date());
    }

    private static String toHex(byte value) {
        return String.format(Locale.US, "%02x", value);
    }
}
