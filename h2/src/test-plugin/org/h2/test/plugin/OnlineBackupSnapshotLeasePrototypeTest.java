/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OQ-09 prepared snapshot lease 并发回收的可行性原型。
 *
 * <p>该原型只验证测试态 lease controller 与真实 MVStore reuse-space
 * 开关的释放顺序，不代表正式状态机、API 或 snapshot 实现已经确定。</p>
 */
public class OnlineBackupSnapshotLeasePrototypeTest {

    @TempDir
    Path directory;

    /**
     * T-H2BR-LEASE-SPIKE-IDLE-EXPIRY-01。
     */
    @Test
    public void idleExpiryAutomaticallyReleasesPin() {
        try (MVStore store = openStore("idle-expiry");
                SnapshotLeasePrototype lease = new SnapshotLeasePrototype(store)) {
            assertFalse(store.isSpaceReused());

            lease.expire();

            assertEquals(LeaseState.CLOSED, lease.getState());
            assertEquals(1, lease.getCleanupCount());
            assertTrue(store.isSpaceReused());
        }
    }

    /**
     * T-H2BR-LEASE-SPIKE-MATERIALIZE-CANCEL-01。
     */
    @Test
    public void materializingExpiryWaitsForReaderExit() throws Exception {
        CountDownLatch readerEntered = new CountDownLatch(1);
        CountDownLatch allowReaderExit = new CountDownLatch(1);
        AtomicBoolean readerWasInterrupted = new AtomicBoolean();
        AtomicBoolean readerObservedCancellation = new AtomicBoolean();

        try (MVStore store = openStore("materialize-cancel");
                SnapshotLeasePrototype lease = new SnapshotLeasePrototype(store)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Void> materializer = executor.submit(() -> {
                    try (SnapshotReader ignored = lease.beginMaterialize()) {
                        readerEntered.countDown();
                        try {
                            allowReaderExit.await();
                        } catch (InterruptedException e) {
                            readerWasInterrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                        readerObservedCancellation.set(lease.isCancellationRequested());
                    }
                    return null;
                });

                assertTrue(readerEntered.await(5, TimeUnit.SECONDS));
                lease.expire();

                assertEquals(LeaseState.CANCEL_REQUESTED, lease.getState());
                assertEquals(1, lease.getActiveReaders());
                assertEquals(0, lease.getCleanupCount());
                assertFalse(store.isSpaceReused());

                allowReaderExit.countDown();
                materializer.get(5, TimeUnit.SECONDS);

                assertFalse(readerWasInterrupted.get());
                assertTrue(readerObservedCancellation.get());
                assertEquals(LeaseState.CLOSED, lease.getState());
                assertEquals(0, lease.getActiveReaders());
                assertEquals(1, lease.getCleanupCount());
                assertTrue(store.isSpaceReused());
            } finally {
                allowReaderExit.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * T-H2BR-LEASE-SPIKE-CLEANUP-RACE-01。
     */
    @Test
    public void competingCleanupRequestsHaveOneOwner() throws Exception {
        try (MVStore store = openStore("cleanup-race");
                SnapshotLeasePrototype lease = new SnapshotLeasePrototype(store);
                SnapshotReader reader = lease.beginMaterialize()) {
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Void>> requests = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    final int action = i % 3;
                    requests.add(executor.submit(() -> {
                        start.await();
                        if (action == 0) {
                            lease.expire();
                        } else if (action == 1) {
                            lease.abort();
                        } else {
                            lease.close();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<Void> request : requests) {
                    request.get(5, TimeUnit.SECONDS);
                }

                assertEquals(LeaseState.CANCEL_REQUESTED, lease.getState());
                assertEquals(0, lease.getCleanupCount());
                assertFalse(store.isSpaceReused());

                reader.close();

                assertEquals(LeaseState.CLOSED, lease.getState());
                assertEquals(1, lease.getCleanupCount());
                assertTrue(store.isSpaceReused());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * T-H2BR-LEASE-SPIKE-LATE-READER-01。
     */
    @Test
    public void expiryRejectsLateMaterializer() {
        try (MVStore store = openStore("late-reader");
                SnapshotLeasePrototype lease = new SnapshotLeasePrototype(store)) {
            lease.expire();

            assertThrows(IllegalStateException.class, lease::beginMaterialize);
            assertEquals(LeaseState.CLOSED, lease.getState());
            assertEquals(1, lease.getCleanupCount());
            assertTrue(store.isSpaceReused());
        }
    }

    /**
     * T-H2BR-LEASE-SPIKE-STUCK-READER-01。
     */
    @Test
    public void stuckReaderKeepsPinAndBlocksNextSnapshot() {
        try (MVStore store = openStore("stuck-reader");
                SnapshotLeasePrototype lease = new SnapshotLeasePrototype(store);
                SnapshotReader reader = lease.beginMaterialize()) {
            lease.expire();
            lease.observeExpiredLease();

            assertTrue(lease.isStuckReaderObserved());
            assertFalse(lease.canPrepareAnotherSnapshot());
            assertFalse(lease.canEnterMaintenance());
            assertFalse(store.isSpaceReused());
            assertEquals(0, lease.getCleanupCount());

            reader.close();

            assertEquals(LeaseState.CLOSED, lease.getState());
            assertTrue(lease.canPrepareAnotherSnapshot());
            assertTrue(store.isSpaceReused());
        }
    }

    /**
     * T-H2BR-LEASE-SPIKE-MAINTENANCE-GUARD-01。
     */
    @Test
    public void maintenanceGuardIsReleasedOnlyAfterCleanup() {
        try (MVStore store = openStore("maintenance-guard");
                SnapshotLeasePrototype lease = new SnapshotLeasePrototype(store);
                SnapshotReader first = lease.beginMaterialize();
                SnapshotReader second = lease.beginMaterialize()) {
            assertFalse(lease.canEnterMaintenance());

            lease.abort();
            first.close();

            assertEquals(1, lease.getActiveReaders());
            assertFalse(lease.canEnterMaintenance());
            assertFalse(store.isSpaceReused());

            second.close();

            assertEquals(0, lease.getActiveReaders());
            assertTrue(lease.canEnterMaintenance());
            assertTrue(store.isSpaceReused());
        }
    }

    private MVStore openStore(String name) {
        MVStore store = new MVStore.Builder()
                .fileName(directory.resolve(name + ".mv.db").toString())
                .open();
        MVMap<Integer, String> map = store.openMap("data");
        for (int i = 0; i < 128; i++) {
            map.put(i, "value-" + i);
        }
        store.commit();
        assertTrue(store.isSpaceReused());
        return store;
    }

    private enum LeaseState {
        PREPARED,
        MATERIALIZING,
        CANCEL_REQUESTED,
        CLOSED
    }

    /**
     * 测试态 lease controller。所有状态转换都在同一 monitor 内线性化。
     */
    private static final class SnapshotLeasePrototype implements AutoCloseable {

        private final MVStore store;
        private final boolean previousReuseSpace;
        private LeaseState state = LeaseState.PREPARED;
        private int activeReaders;
        private int cleanupCount;
        private boolean stuckReaderObserved;

        SnapshotLeasePrototype(MVStore store) {
            this.store = store;
            previousReuseSpace = store.isSpaceReused();
            store.setReuseSpace(false);
        }

        synchronized SnapshotReader beginMaterialize() {
            if (state != LeaseState.PREPARED && state != LeaseState.MATERIALIZING) {
                throw new IllegalStateException("Snapshot lease does not accept a new reader: " + state);
            }
            state = LeaseState.MATERIALIZING;
            activeReaders++;
            return new SnapshotReader(this);
        }

        synchronized void expire() {
            requestCancellation();
        }

        synchronized void abort() {
            requestCancellation();
        }

        @Override
        public synchronized void close() {
            requestCancellation();
        }

        synchronized boolean isCancellationRequested() {
            return state == LeaseState.CANCEL_REQUESTED || state == LeaseState.CLOSED;
        }

        synchronized void observeExpiredLease() {
            if (state == LeaseState.CANCEL_REQUESTED && activeReaders > 0) {
                stuckReaderObserved = true;
            }
        }

        synchronized LeaseState getState() {
            return state;
        }

        synchronized int getActiveReaders() {
            return activeReaders;
        }

        synchronized int getCleanupCount() {
            return cleanupCount;
        }

        synchronized boolean isStuckReaderObserved() {
            return stuckReaderObserved;
        }

        synchronized boolean canPrepareAnotherSnapshot() {
            return state == LeaseState.CLOSED;
        }

        synchronized boolean canEnterMaintenance() {
            return state == LeaseState.CLOSED;
        }

        private void requestCancellation() {
            if (state == LeaseState.CLOSED) {
                return;
            }
            state = LeaseState.CANCEL_REQUESTED;
            cleanupIfDrained();
        }

        private synchronized void readerClosed() {
            if (activeReaders <= 0) {
                throw new IllegalStateException("Snapshot reader count underflow");
            }
            activeReaders--;
            cleanupIfDrained();
        }

        private void cleanupIfDrained() {
            if (state == LeaseState.CANCEL_REQUESTED && activeReaders == 0) {
                store.setReuseSpace(previousReuseSpace);
                cleanupCount++;
                state = LeaseState.CLOSED;
            }
        }
    }

    private static final class SnapshotReader implements AutoCloseable {

        private final SnapshotLeasePrototype lease;
        private boolean closed;

        SnapshotReader(SnapshotLeasePrototype lease) {
            this.lease = lease;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                lease.readerClosed();
            }
        }
    }
}
