/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.h2.api.ErrorCode;
import org.h2.message.DbException;

/**
 * Coordinates short database-wide backup barriers and transaction drains.
 *
 * <p>This class is internal. Callers must enter commit and DDL operations before
 * taking database locks. A barrier owns only this class' lock while it waits.</p>
 */
public final class DatabaseOperationGate {

    /**
     * Current gate state.
     */
    public enum State {
        OPEN,
        BACKUP_BARRIER,
        TRANSACTION_DRAIN,
        FENCED,
        CLOSED
    }

    /**
     * Last observed operation-gate failure category.
     */
    public enum FailureReason {
        NONE,
        BACKUP_BARRIER_TIMEOUT,
        TRANSACTION_DRAIN_TIMEOUT,
        ACTIVATION_TIMEOUT,
        QUIESCING,
        GENERATION_FENCED,
        INTERRUPTED,
        CLOSED,
        BUSY
    }

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final ThreadLocal<Integer> commitDepth = new ThreadLocal<>();
    private final ThreadLocal<Integer> ddlDepth = new ThreadLocal<>();

    private State state = State.OPEN;
    private long generation;
    private int activeCommits;
    private int activeDdl;
    private int activeTransactions;
    private int waitingCommits;
    private int waitingDdl;
    private String drainingGenerationId;
    private String fencedGenerationId;
    private long backupBarrierCount;
    private long backupBarrierTimeoutCount;
    private long transactionDrainCount;
    private long transactionDrainTimeoutCount;
    private long lastBackupBarrierWaitNanos;
    private long lastTransactionDrainWaitNanos;
    private long maxAdmissionWaitNanos;
    private long maxBackupBarrierWaitNanos;
    private long maxTransactionDrainWaitNanos;
    private volatile FailureReason lastFailureReason = FailureReason.NONE;

    /**
     * Enter a commit operation.
     */
    public void enterCommit() {
        Integer depth = commitDepth.get();
        if (depth != null) {
            commitDepth.set(depth + 1);
            return;
        }
        boolean nestedDdl = ddlDepth.get() != null;
        lockInterruptibly();
        long waitStartNanos = 0L;
        try {
            if (!nestedDdl) {
                while (state == State.BACKUP_BARRIER) {
                    if (waitStartNanos == 0L) {
                        waitStartNanos = System.nanoTime();
                    }
                    waitingCommits++;
                    try {
                        awaitChanged();
                    } finally {
                        waitingCommits--;
                    }
                }
            }
            checkUsable();
            activeCommits++;
            commitDepth.set(1);
        } finally {
            recordAdmissionWait(waitStartNanos);
            lock.unlock();
        }
    }

    /**
     * Leave a commit operation.
     */
    public void exitCommit() {
        Integer depth = commitDepth.get();
        if (depth == null) {
            throw new IllegalStateException("Commit operation was not entered");
        }
        if (depth > 1) {
            commitDepth.set(depth - 1);
            return;
        }
        commitDepth.remove();
        lock.lock();
        try {
            if (--activeCommits < 0) {
                activeCommits = 0;
                throw new IllegalStateException("Active commit count underflow");
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the current thread already owns commit admission.
     *
     * @return whether commit admission is held
     */
    public boolean isCommitEnteredByCurrentThread() {
        return commitDepth.get() != null;
    }

    /**
     * Enter a DDL operation.
     */
    public void enterDdl() {
        Integer depth = ddlDepth.get();
        if (depth != null) {
            ddlDepth.set(depth + 1);
            return;
        }
        boolean nestedCommit = commitDepth.get() != null;
        lockInterruptibly();
        long waitStartNanos = 0L;
        try {
            if (!nestedCommit) {
                while (state == State.BACKUP_BARRIER) {
                    if (waitStartNanos == 0L) {
                        waitStartNanos = System.nanoTime();
                    }
                    waitingDdl++;
                    try {
                        awaitChanged();
                    } finally {
                        waitingDdl--;
                    }
                }
            }
            if (state == State.TRANSACTION_DRAIN) {
                throw quiescing();
            }
            checkUsable();
            activeDdl++;
            ddlDepth.set(1);
        } finally {
            recordAdmissionWait(waitStartNanos);
            lock.unlock();
        }
    }

    /**
     * Leave a DDL operation.
     */
    public void exitDdl() {
        Integer depth = ddlDepth.get();
        if (depth == null) {
            throw new IllegalStateException("DDL operation was not entered");
        }
        if (depth > 1) {
            ddlDepth.set(depth - 1);
            return;
        }
        ddlDepth.remove();
        lock.lock();
        try {
            if (--activeDdl < 0) {
                activeDdl = 0;
                throw new IllegalStateException("Active DDL count underflow");
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the current thread already owns DDL admission.
     *
     * @return whether DDL admission is held
     */
    public boolean isDdlEnteredByCurrentThread() {
        return ddlDepth.get() != null;
    }

    /**
     * Register a newly-created transaction.
     */
    public void enterTransaction() {
        lockInterruptibly();
        try {
            if (state == State.TRANSACTION_DRAIN && ddlDepth.get() == null) {
                throw quiescing();
            }
            checkUsable();
            activeTransactions++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Unregister a completed transaction.
     */
    public void exitTransaction() {
        lock.lock();
        try {
            if (--activeTransactions < 0) {
                activeTransactions = 0;
                throw new IllegalStateException("Active transaction count underflow");
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Start a backup barrier and wait for admitted commit and DDL operations.
     *
     * @param timeoutMillis total timeout in milliseconds
     * @return barrier handle
     */
    public BackupBarrier beginBackupBarrier(long timeoutMillis) {
        validateTimeout(timeoutMillis);
        lockInterruptibly();
        long startNanos = System.nanoTime();
        try {
            requireOpen();
            state = State.BACKUP_BARRIER;
            long barrierGeneration = ++generation;
            backupBarrierCount++;
            long remainingNanos = toNanos(timeoutMillis);
            while (activeCommits != 0 || activeDdl != 0) {
                if (remainingNanos <= 0L) {
                    backupBarrierTimeoutCount++;
                    lastFailureReason = FailureReason.BACKUP_BARRIER_TIMEOUT;
                    state = State.OPEN;
                    changed.signalAll();
                    throw timeout("online backup barrier");
                }
                remainingNanos = awaitChanged(remainingNanos);
                requireOwned(State.BACKUP_BARRIER, barrierGeneration);
            }
            requireOwned(State.BACKUP_BARRIER, barrierGeneration);
            recordBackupBarrierWait(startNanos);
            return new BackupBarrier(this, barrierGeneration);
        } catch (RuntimeException e) {
            recordBackupBarrierWait(startNanos);
            if (state == State.BACKUP_BARRIER) {
                state = State.OPEN;
                changed.signalAll();
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Start transaction draining and wait for admitted work to finish.
     *
     * @param timeoutMillis total timeout in milliseconds
     * @return drain handle
     */
    public TransactionDrain beginTransactionDrain(long timeoutMillis) {
        return beginTransactionDrain(timeoutMillis, "unknown");
    }

    /**
     * 启动 activation transaction drain，并等待已准入工作结束。
     *
     * @param timeoutMillis 总超时毫秒数
     * @param generationId 正在排空的 generation ID
     * @return drain handle
     */
    public TransactionDrain beginTransactionDrain(long timeoutMillis,
            String generationId) {
        validateTimeout(timeoutMillis);
        if (generationId == null || generationId.trim().isEmpty()) {
            throw DbException.getInvalidValueException("generationId",
                    generationId);
        }
        lockInterruptibly();
        long startNanos = System.nanoTime();
        try {
            requireOpen();
            state = State.TRANSACTION_DRAIN;
            drainingGenerationId = generationId;
            long drainGeneration = ++generation;
            transactionDrainCount++;
            long remainingNanos = toNanos(timeoutMillis);
            while (activeTransactions != 0 || activeCommits != 0 || activeDdl != 0) {
                if (remainingNanos <= 0L) {
                    transactionDrainTimeoutCount++;
                    lastFailureReason = FailureReason.ACTIVATION_TIMEOUT;
                    state = State.OPEN;
                    drainingGenerationId = null;
                    changed.signalAll();
                    throw activationTimeout(generationId);
                }
                remainingNanos = awaitChanged(remainingNanos);
                requireOwned(State.TRANSACTION_DRAIN, drainGeneration);
            }
            requireOwned(State.TRANSACTION_DRAIN, drainGeneration);
            recordTransactionDrainWait(startNanos);
            return new TransactionDrain(this, drainGeneration);
        } catch (RuntimeException e) {
            recordTransactionDrainWait(startNanos);
            if (state == State.TRANSACTION_DRAIN) {
                state = State.OPEN;
                drainingGenerationId = null;
                changed.signalAll();
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Obtain a consistent metrics snapshot.
     *
     * @return metrics snapshot
     */
    public Metrics getMetrics() {
        lock.lock();
        try {
            return new Metrics(state, activeCommits, activeDdl, activeTransactions,
                    waitingCommits, waitingDdl, backupBarrierCount,
                    backupBarrierTimeoutCount, transactionDrainCount,
                    transactionDrainTimeoutCount, lastBackupBarrierWaitNanos,
                    lastTransactionDrainWaitNanos, maxAdmissionWaitNanos,
                    maxBackupBarrierWaitNanos, maxTransactionDrainWaitNanos,
                    lastFailureReason);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stop this gate and wake all waiters.
     */
    public void shutdown() {
        lock.lock();
        try {
            state = State.CLOSED;
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 若 generation 已被永久 fence，则抛出稳定的连接错误。
     */
    public void checkNotFenced() {
        lock.lock();
        try {
            if (state == State.FENCED) {
                throw fenced();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return generation 是否已被永久 fence
     */
    public boolean isFenced() {
        lock.lock();
        try {
            return state == State.FENCED;
        } finally {
            lock.unlock();
        }
    }

    private void release(State expectedState, long expectedGeneration) {
        lock.lock();
        try {
            if (state == expectedState && generation == expectedGeneration) {
                state = State.OPEN;
                if (expectedState == State.TRANSACTION_DRAIN) {
                    drainingGenerationId = null;
                }
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private void fence(long expectedGeneration, String generationId) {
        lock.lock();
        try {
            requireOwned(State.TRANSACTION_DRAIN, expectedGeneration);
            if (activeTransactions != 0 || activeCommits != 0
                    || activeDdl != 0) {
                throw new IllegalStateException(
                        "Cannot fence generation with active operations");
            }
            state = State.FENCED;
            drainingGenerationId = null;
            fencedGenerationId = generationId;
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void requireOwned(State expectedState, long expectedGeneration) {
        if (state == expectedState && generation == expectedGeneration) {
            return;
        }
        checkUsable();
        lastFailureReason = FailureReason.BUSY;
        throw DbException.get(ErrorCode.DATABASE_IS_IN_EXCLUSIVE_MODE);
    }

    private void requireOpen() {
        checkUsable();
        if (state != State.OPEN) {
            lastFailureReason = FailureReason.BUSY;
            throw DbException.get(ErrorCode.DATABASE_IS_IN_EXCLUSIVE_MODE);
        }
    }

    private void checkUsable() {
        if (state == State.FENCED) {
            throw fenced();
        }
        if (state == State.CLOSED) {
            lastFailureReason = FailureReason.CLOSED;
            throw DbException.get(ErrorCode.DATABASE_IS_CLOSED);
        }
    }

    private DbException quiescing() {
        lastFailureReason = FailureReason.QUIESCING;
        return DbException.get(ErrorCode.ONLINE_BACKUP_QUIESCING_1,
                drainingGenerationId == null ? "unknown"
                        : drainingGenerationId);
    }

    private DbException fenced() {
        lastFailureReason = FailureReason.GENERATION_FENCED;
        return DbException.get(ErrorCode.GENERATION_FENCED_1,
                fencedGenerationId == null ? "unknown" : fencedGenerationId);
    }

    private void recordAdmissionWait(long startNanos) {
        if (startNanos != 0L) {
            maxAdmissionWaitNanos = Math.max(maxAdmissionWaitNanos,
                    System.nanoTime() - startNanos);
        }
    }

    private void recordBackupBarrierWait(long startNanos) {
        lastBackupBarrierWaitNanos = System.nanoTime() - startNanos;
        maxBackupBarrierWaitNanos = Math.max(maxBackupBarrierWaitNanos,
                lastBackupBarrierWaitNanos);
    }

    private void recordTransactionDrainWait(long startNanos) {
        lastTransactionDrainWaitNanos = System.nanoTime() - startNanos;
        maxTransactionDrainWaitNanos = Math.max(maxTransactionDrainWaitNanos,
                lastTransactionDrainWaitNanos);
    }

    private void lockInterruptibly() {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailureReason = FailureReason.INTERRUPTED;
            throw DbException.get(ErrorCode.STATEMENT_WAS_CANCELED, e);
        }
    }

    private void awaitChanged() {
        try {
            changed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailureReason = FailureReason.INTERRUPTED;
            throw DbException.get(ErrorCode.STATEMENT_WAS_CANCELED, e);
        }
    }

    private long awaitChanged(long remainingNanos) {
        try {
            return changed.awaitNanos(remainingNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailureReason = FailureReason.INTERRUPTED;
            throw DbException.get(ErrorCode.STATEMENT_WAS_CANCELED, e);
        }
    }

    private static void validateTimeout(long timeoutMillis) {
        if (timeoutMillis < 0L) {
            throw DbException.getInvalidValueException("timeoutMillis", timeoutMillis);
        }
    }

    private static long toNanos(long timeoutMillis) {
        long maxMillis = Long.MAX_VALUE / 1_000_000L;
        return TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, maxMillis));
    }

    private static DbException timeout(String operation) {
        return DbException.get(ErrorCode.LOCK_TIMEOUT_1, operation);
    }

    private static DbException activationTimeout(String generationId) {
        return DbException.get(
                ErrorCode.ONLINE_BACKUP_ACTIVATION_TIMEOUT_1, generationId);
    }

    /**
     * Acquired backup barrier.
     */
    public static final class BackupBarrier implements AutoCloseable {

        private final DatabaseOperationGate gate;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BackupBarrier(DatabaseOperationGate gate, long generation) {
            this.gate = gate;
            this.generation = generation;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                gate.release(State.BACKUP_BARRIER, generation);
            }
        }
    }

    /**
     * Acquired transaction drain.
     */
    public static final class TransactionDrain implements AutoCloseable {

        private final DatabaseOperationGate gate;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TransactionDrain(DatabaseOperationGate gate, long generation) {
            this.gate = gate;
            this.generation = generation;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                gate.release(State.TRANSACTION_DRAIN, generation);
            }
        }

        void fence(String generationId) {
            if (closed.compareAndSet(false, true)) {
                gate.fence(generation, generationId);
            } else {
                throw new IllegalStateException(
                        "Transaction drain was already consumed");
            }
        }
    }

    /**
     * Immutable operation-gate metrics.
     */
    public static final class Metrics {

        private final State state;
        private final int activeCommits;
        private final int activeDdl;
        private final int activeTransactions;
        private final int waitingCommits;
        private final int waitingDdl;
        private final long backupBarrierCount;
        private final long backupBarrierTimeoutCount;
        private final long transactionDrainCount;
        private final long transactionDrainTimeoutCount;
        private final long lastBackupBarrierWaitNanos;
        private final long lastTransactionDrainWaitNanos;
        private final long maxAdmissionWaitNanos;
        private final long maxBackupBarrierWaitNanos;
        private final long maxTransactionDrainWaitNanos;
        private final FailureReason lastFailureReason;

        private Metrics(State state, int activeCommits, int activeDdl,
                int activeTransactions, int waitingCommits, int waitingDdl,
                long backupBarrierCount, long backupBarrierTimeoutCount,
                long transactionDrainCount, long transactionDrainTimeoutCount,
                long lastBackupBarrierWaitNanos,
                long lastTransactionDrainWaitNanos, long maxAdmissionWaitNanos,
                long maxBackupBarrierWaitNanos,
                long maxTransactionDrainWaitNanos,
                FailureReason lastFailureReason) {
            this.state = state;
            this.activeCommits = activeCommits;
            this.activeDdl = activeDdl;
            this.activeTransactions = activeTransactions;
            this.waitingCommits = waitingCommits;
            this.waitingDdl = waitingDdl;
            this.backupBarrierCount = backupBarrierCount;
            this.backupBarrierTimeoutCount = backupBarrierTimeoutCount;
            this.transactionDrainCount = transactionDrainCount;
            this.transactionDrainTimeoutCount = transactionDrainTimeoutCount;
            this.lastBackupBarrierWaitNanos = lastBackupBarrierWaitNanos;
            this.lastTransactionDrainWaitNanos = lastTransactionDrainWaitNanos;
            this.maxAdmissionWaitNanos = maxAdmissionWaitNanos;
            this.maxBackupBarrierWaitNanos = maxBackupBarrierWaitNanos;
            this.maxTransactionDrainWaitNanos = maxTransactionDrainWaitNanos;
            this.lastFailureReason = lastFailureReason;
        }

        public State getState() {
            return state;
        }

        public int getActiveCommits() {
            return activeCommits;
        }

        public int getActiveDdl() {
            return activeDdl;
        }

        public int getActiveTransactions() {
            return activeTransactions;
        }

        public int getWaitingCommits() {
            return waitingCommits;
        }

        public int getWaitingDdl() {
            return waitingDdl;
        }

        public long getBackupBarrierCount() {
            return backupBarrierCount;
        }

        public long getBackupBarrierTimeoutCount() {
            return backupBarrierTimeoutCount;
        }

        public long getTransactionDrainCount() {
            return transactionDrainCount;
        }

        public long getTransactionDrainTimeoutCount() {
            return transactionDrainTimeoutCount;
        }

        public long getLastBackupBarrierWaitNanos() {
            return lastBackupBarrierWaitNanos;
        }

        public long getLastTransactionDrainWaitNanos() {
            return lastTransactionDrainWaitNanos;
        }

        public long getMaxAdmissionWaitNanos() {
            return maxAdmissionWaitNanos;
        }

        public long getMaxBackupBarrierWaitNanos() {
            return maxBackupBarrierWaitNanos;
        }

        public long getMaxTransactionDrainWaitNanos() {
            return maxTransactionDrainWaitNanos;
        }

        public FailureReason getLastFailureReason() {
            return lastFailureReason;
        }
    }
}
