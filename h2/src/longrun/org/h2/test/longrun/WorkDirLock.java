/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * Exclusive process-local lock for one longrun work directory.
 */
public final class WorkDirLock implements AutoCloseable {

    private static final String LOCK_FILE_NAME = ".longrun.lock";

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final FileLock lock;

    private WorkDirLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    public static WorkDirLock acquire(File workDir) throws IOException {
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("Could not create work dir: " + workDir);
        }
        File lockFile = new File(workDir, LOCK_FILE_NAME);
        RandomAccessFile file = new RandomAccessFile(lockFile, "rw");
        FileChannel channel = file.getChannel();
        FileLock lock = null;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // Same JVM already owns the lock.
        }
        if (lock == null) {
            closeQuietly(channel);
            closeQuietly(file);
            throw new IOException("Longrun work dir is already locked: " + workDir.getPath());
        }
        file.setLength(0L);
        file.writeBytes("pid=" + processId() + "\n");
        file.writeBytes("timeMillis=" + System.currentTimeMillis() + "\n");
        return new WorkDirLock(file, channel, lock);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            lock.release();
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }
        }
        try {
            file.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String processId() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignore) {
            // ignore
        }
    }
}
