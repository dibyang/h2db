/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.h2.mvstore.MVStorePreparedSnapshot.MaterializationReader;
import org.h2.mvstore.MVStorePreparedSnapshot.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic prepared-snapshot reader and cleanup state tests.
 */
public class MVStorePreparedSnapshotLeaseTest {

    @TempDir
    Path directory;

    /**
     * Expiry with an active reader keeps the pin until that reader exits.
     */
    @Test
    public void expiredReaderKeepsPinUntilExit() throws Exception {
        try (MVStore store = openStore("stuck-reader")) {
            MVStorePreparedSnapshot snapshot = store.prepareSnapshot(25L);
            try (MaterializationReader reader =
                    snapshot.beginMaterialization()) {
                awaitState(snapshot, State.CANCEL_REQUESTED);
                assertEquals(1, snapshot.getActiveReaders());
                assertTrue(snapshot.isStuckReaderObserved());
                assertEquals(1, snapshot.getReaderOwners().size());
                assertTrue(snapshot.getPinAgeMillis() >= 0L);
                assertTrue(snapshot.isControlledRestartRecommended());
                assertFalse(store.isSpaceReused());
            }
            assertEquals(State.CLOSED, snapshot.getState());
            assertEquals(0, snapshot.getActiveReaders());
            assertTrue(snapshot.getReaderOwners().isEmpty());
            assertFalse(snapshot.isControlledRestartRecommended());
            assertTrue(store.isSpaceReused());
        }
    }

    /**
     * Abort, close, expiry, and duplicate reader close share one idempotent
     * cleanup path.
     */
    @Test
    public void competingCleanupWaitsForAllReaders() throws Exception {
        try (MVStore store = openStore("cleanup-race")) {
            MVStorePreparedSnapshot snapshot = store.prepareSnapshot(30_000L);
            MaterializationReader first = snapshot.beginMaterialization();
            MaterializationReader second = snapshot.beginMaterialization();
            snapshot.abort();
            snapshot.close();
            first.close();
            first.close();
            assertEquals(State.CANCEL_REQUESTED, snapshot.getState());
            assertEquals(1, snapshot.getActiveReaders());
            assertFalse(store.isSpaceReused());
            second.close();
            assertEquals(State.CLOSED, snapshot.getState());
            assertTrue(store.isSpaceReused());
        }
    }

    /**
     * Store shutdown aborts an idle snapshot before closing its file channel.
     */
    @Test
    public void storeCloseAbortsIdleSnapshot() {
        MVStore store = openStore("store-close");
        MVStorePreparedSnapshot snapshot = store.prepareSnapshot(30_000L);
        store.close();
        assertEquals(State.CLOSED, snapshot.getState());
        assertTrue(store.isClosed());
    }

    /**
     * A zero-length lease may expire immediately, but prepare must not invert
     * the store lock and snapshot monitor lock order.
     */
    @Test
    public void zeroLeasePrepareAndExpiryDoNotDeadlock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "zero-lease-regression");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        try {
            assertTrue(executor.submit(() -> {
                for (int i = 0; i < 64; i++) {
                    try (MVStore store = openStore("zero-lease-" + i)) {
                        MVStorePreparedSnapshot snapshot =
                                store.prepareSnapshot(0L);
                        awaitState(snapshot, State.CLOSED);
                        assertTrue(store.isSpaceReused());
                    }
                }
                return true;
            }).get(10L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private MVStore openStore(String name) {
        MVStore store = new MVStore.Builder()
                .fileName(directory.resolve(name + ".mv.db").toString())
                .open();
        MVMap<Integer, String> map = store.openMap("data");
        map.put(1, "value");
        store.commit();
        return store;
    }

    private static void awaitState(MVStorePreparedSnapshot snapshot,
            State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (snapshot.getState() != expected
                && System.nanoTime() - deadline < 0L) {
            Thread.yield();
        }
        assertEquals(expected, snapshot.getState());
    }
}
