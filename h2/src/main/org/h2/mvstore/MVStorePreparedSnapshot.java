/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.h2.store.fs.FilePath;

/**
 * A fixed-cut MVStore file snapshot with a bounded lease.
 */
public final class MVStorePreparedSnapshot implements AutoCloseable {

    /**
     * Snapshot lifecycle state.
     */
    public enum State {
        PREPARED,
        MATERIALIZING,
        CANCEL_REQUESTED,
        CLOSED
    }

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final ScheduledExecutorService LEASE_EXECUTOR =
            createLeaseExecutor();

    private static ScheduledExecutorService createLeaseExecutor() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "H2 Online Backup Snapshot Lease");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final MVStore store;
    private final SingleFileStore source;
    private final byte[] headerBlocks;
    private final long copyLength;
    private final long snapshotVersion;
    private final String sourceFingerprint;
    private final long deadlineNanos;
    private final long createdNanos;
    private final boolean previousReuseSpace;
    private final long leaseMillis;
    private ScheduledFuture<?> expiryTask;

    private State state = State.PREPARED;
    private int activeReaders;
    private boolean stuckReaderObserved;
    private long nextReaderId;
    private final HashMap<Long, String> readerOwners = new HashMap<>();

    MVStorePreparedSnapshot(MVStore store, SingleFileStore source,
            byte[] headerBlocks, long copyLength, long snapshotVersion,
            String sourceFingerprint, long leaseMillis,
            boolean previousReuseSpace) {
        this.store = store;
        this.source = source;
        this.headerBlocks = headerBlocks;
        this.copyLength = copyLength;
        this.snapshotVersion = snapshotVersion;
        this.sourceFingerprint = sourceFingerprint;
        this.previousReuseSpace = previousReuseSpace;
        long maxLeaseMillis = Long.MAX_VALUE / 2L / 1_000_000L;
        this.leaseMillis = Math.min(leaseMillis, maxLeaseMillis);
        long leaseNanos = TimeUnit.MILLISECONDS.toNanos(
                this.leaseMillis);
        long now = System.nanoTime();
        createdNanos = now;
        deadlineNanos = now + leaseNanos;
    }

    void startLease() {
        ScheduledFuture<?> task = LEASE_EXECUTOR.schedule(this::expire,
                leaseMillis, TimeUnit.MILLISECONDS);
        synchronized (this) {
            expiryTask = task;
            if (state == State.CLOSED) {
                task.cancel(false);
            }
        }
    }

    /**
     * Copy this fixed snapshot into a new file.
     *
     * @param targetFileName target file name
     * @throws IOException if copying fails
     */
    public void materialize(String targetFileName) throws IOException {
        FilePath target = FilePath.get(targetFileName);
        boolean created = false;
        Throwable failure = null;
        try (MaterializationReader ignored = beginMaterialization()) {
            if (!target.createFile()) {
                throw new IOException("Snapshot target already exists: "
                        + targetFileName);
            }
            created = true;
            try (FileChannel output = target.open("rw")) {
                writeFully(output, 0L, ByteBuffer.wrap(headerBlocks));
                long position = headerBlocks.length;
                ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
                while (position < copyLength) {
                    checkCancellation();
                    int length = (int) Math.min(buffer.capacity(),
                            copyLength - position);
                    buffer.clear();
                    buffer.limit(length);
                    source.readSnapshotBytes(position, buffer);
                    buffer.flip();
                    writeFully(output, position, buffer);
                    position += length;
                }
                output.force(true);
            }
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            if (failure != null && created) {
                try {
                    target.delete();
                } catch (Throwable deleteFailure) {
                    failure.addSuppressed(deleteFailure);
                }
            }
        }
    }

    /**
     * Get the fixed source length.
     *
     * @return source length
     */
    public long getCopyLength() {
        return copyLength;
    }

    /**
     * Get the captured MVStore version.
     *
     * @return MVStore version
     */
    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    /**
     * Get the source fingerprint.
     *
     * @return source fingerprint
     */
    public String getSourceFingerprint() {
        return sourceFingerprint;
    }

    /**
     * Get the absolute monotonic lease deadline.
     *
     * @return deadline in {@link System#nanoTime()} domain
     */
    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Get lifecycle state.
     *
     * @return lifecycle state
     */
    public synchronized State getState() {
        return state;
    }

    /**
     * Get active materialization reader count.
     *
     * @return reader count
     */
    public synchronized int getActiveReaders() {
        return activeReaders;
    }

    /**
     * Whether an expired lease still has an active reader.
     *
     * @return whether a stuck reader was observed
     */
    public synchronized boolean isStuckReaderObserved() {
        return stuckReaderObserved;
    }

    /**
     * Get the current pin age.
     *
     * @return pin age in milliseconds
     */
    public long getPinAgeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - createdNanos);
    }

    /**
     * Get physical source growth since the fixed cut.
     *
     * @return non-negative source growth in bytes
     */
    public long getSourceGrowthBytes() {
        return Math.max(0L, source.getSnapshotLength() - copyLength);
    }

    /**
     * Get a snapshot of active reader owners.
     *
     * @return reader owner descriptions
     */
    public synchronized List<String> getReaderOwners() {
        return new ArrayList<>(readerOwners.values());
    }

    /**
     * Whether operators should use controlled restart procedures instead of
     * forcibly releasing the pin.
     *
     * @return whether controlled restart is recommended
     */
    public synchronized boolean isControlledRestartRecommended() {
        return stuckReaderObserved && activeReaders > 0;
    }

    /**
     * Abort this snapshot.
     */
    public synchronized void abort() {
        requestCancellation();
    }

    @Override
    public synchronized void close() {
        requestCancellation();
    }

    synchronized MaterializationReader beginMaterialization() {
        if (state != State.PREPARED && state != State.MATERIALIZING) {
            throw new IllegalStateException(
                    "Prepared snapshot does not accept a reader: " + state);
        }
        if (System.nanoTime() - deadlineNanos >= 0L) {
            requestCancellation();
            throw new IllegalStateException("Prepared snapshot lease expired");
        }
        state = State.MATERIALIZING;
        activeReaders++;
        long readerId = ++nextReaderId;
        Thread thread = Thread.currentThread();
        readerOwners.put(readerId, thread.getName() + '#' + thread.getId());
        return new MaterializationReader(this, readerId);
    }

    private synchronized void endReader(long readerId) {
        if (activeReaders <= 0) {
            throw new IllegalStateException("Snapshot reader count underflow");
        }
        if (readerOwners.remove(readerId) == null) {
            throw new IllegalStateException("Unknown snapshot reader");
        }
        activeReaders--;
        cleanupIfDrained();
    }

    private synchronized void expire() {
        if (activeReaders > 0) {
            stuckReaderObserved = true;
        }
        requestCancellation();
    }

    private synchronized void checkCancellation() throws IOException {
        if (state == State.CANCEL_REQUESTED || state == State.CLOSED
                || System.nanoTime() - deadlineNanos >= 0L) {
            requestCancellation();
            throw new IOException("Prepared snapshot materialization canceled");
        }
    }

    private void requestCancellation() {
        if (state != State.CLOSED) {
            state = State.CANCEL_REQUESTED;
            cleanupIfDrained();
        }
    }

    private void cleanupIfDrained() {
        if (state == State.CANCEL_REQUESTED && activeReaders == 0) {
            ScheduledFuture<?> task = expiryTask;
            if (task != null) {
                task.cancel(false);
            }
            store.releasePreparedSnapshot(this, previousReuseSpace);
            state = State.CLOSED;
        }
    }

    private static void writeFully(FileChannel channel, long position,
            ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            int written = channel.write(source, position);
            if (written <= 0) {
                throw new IOException("Unable to write prepared snapshot");
            }
            position += written;
        }
    }

    static final class MaterializationReader implements AutoCloseable {

        private final MVStorePreparedSnapshot snapshot;
        private final long readerId;
        private boolean closed;

        MaterializationReader(MVStorePreparedSnapshot snapshot,
                long readerId) {
            this.snapshot = snapshot;
            this.readerId = readerId;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                snapshot.endReader(readerId);
            }
        }
    }
}
