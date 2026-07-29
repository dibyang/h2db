/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.api.StorageEngineProvider;
import org.h2.api.SystemCatalogProvider;
import org.h2.engine.Database;
import org.h2.engine.PluginRegistry.RegisteredProvider;
import org.h2.engine.backup.DatabaseIdentityMetadata.Snapshot;
import org.h2.message.DbException;
import org.h2.mvstore.MVStorePreparedSnapshot;

/**
 * Prepared H2 and external participant snapshots for one coordinated cut.
 */
public final class OnlineBackupSession implements AutoCloseable {

    /**
     * Session lifecycle state.
     */
    public enum State {
        PREPARING,
        PREPARED,
        MATERIALIZING,
        ABORTING,
        ABORTED,
        CLOSED
    }

    private final OnlineBackupContext context;
    private final Database database;
    private final OnlineBackupParticipantCoordinator participantCoordinator;

    private MVStorePreparedSnapshot h2Snapshot;
    private State state = State.PREPARING;
    private long preparePauseMillis;
    private Path publishedTarget;
    private OnlineBackupPublishResult publishedResult;
    private boolean preparationActive = true;
    private boolean materializationActive;
    private boolean cancellationRequested;
    private boolean cancellationSignalActive;
    private boolean cleanupActive;
    private Throwable cancellationFailure;
    private Throwable terminalFailure;

    private OnlineBackupSession(Database database,
            OnlineBackupContext context,
            OnlineBackupParticipantCoordinator participantCoordinator) {
        this.database = database;
        this.context = context;
        this.participantCoordinator = participantCoordinator;
    }

    /**
     * Resolve and prepare all explicitly selected participants.
     *
     * @param database database
     * @param options prepare options
     * @return prepared session
     * @throws Exception if prepare or cleanup fails
     */
    public static OnlineBackupSession prepare(Database database,
            OnlineBackupOptions options) throws Exception {
        if (database == null || options == null) {
            throw new IllegalArgumentException(
                    "database and options must not be null");
        }
        DatabaseIdentityMetadata identityMetadata =
                database.getOnlineBackupMetadata();
        if (identityMetadata == null) {
            throw DbException.get(ErrorCode.UNSUPPORTED_SETTING_COMBINATION,
                    "ONLINE_BACKUP_COORDINATION is disabled");
        }
        Snapshot identity = identityMetadata.requireSnapshot();
        long deadlineNanos = deadline(options.getPrepareTimeoutMillis());
        OnlineBackupContext context = new OnlineBackupContext(
                options.getBackupId() != null ? options.getBackupId()
                        : UUID.randomUUID(),
                UUID.randomUUID(),
                identity.getDatabaseId(), identity.getGenerationId(),
                identity.getSchemaEpoch(), deadlineNanos);
        OnlineBackupParticipantCoordinator participantCoordinator =
                OnlineBackupParticipantCoordinator.resolve(database,
                        options.getParticipantIds());
        OnlineBackupSession session =
                new OnlineBackupSession(database, context,
                        participantCoordinator);
        database.claimOnlineBackupSession(session);
        Throwable failure = null;
        try {
            participantCoordinator.arm(context, session);
            long barrierStartedNanos = System.nanoTime();
            long barrierTimeout = requireRemaining(context,
                    "online backup barrier");
            try (DatabaseOperationGate.BackupBarrier ignored =
                    database.getOperationGate()
                            .beginBackupBarrier(barrierTimeout)) {
                session.checkPreparationAllowed("H2 snapshot prepare");
                database.getStore().flush();
                MVStorePreparedSnapshot snapshot =
                        database.getStore().getMvStore().prepareSnapshot(
                                options.getSnapshotLeaseMillis());
                synchronized (session) {
                    session.h2Snapshot = snapshot;
                }
                session.checkPreparationAllowed("H2 snapshot prepare");
                session.requireH2SnapshotActive();
                participantCoordinator.capture(context, session);
                session.requireH2SnapshotActive();
            } finally {
                session.preparePauseMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - barrierStartedNanos);
            }
            synchronized (session) {
                session.checkPreparationAllowed("online backup prepare");
                session.preparationActive = false;
                session.state = State.PREPARED;
                session.notifyAll();
            }
            return session;
        } catch (Throwable e) {
            failure = e;
            synchronized (session) {
                session.preparationActive = false;
                session.awaitCancellationSignalUninterruptibly();
                failure = addFailure(failure, session.cancellationFailure);
                session.cancellationFailure = null;
                session.cleanupActive = true;
                session.state = State.ABORTING;
                session.notifyAll();
            }
            failure = session.finishCleanup(State.ABORTED, failure);
            rethrow(failure);
            throw new AssertionError();
        }
    }

    /**
     * Get immutable backup context.
     *
     * @return backup context
     */
    public OnlineBackupContext getContext() {
        return context;
    }

    /**
     * Get H2 prepared snapshot.
     *
     * @return H2 snapshot
     */
    public synchronized MVStorePreparedSnapshot getH2Snapshot() {
        requirePrepared();
        return h2Snapshot;
    }

    /**
     * Get prepared participant metadata in stable participant order.
     *
     * @return metadata list
     */
    public synchronized List<PreparedParticipantMetadata>
            getParticipantMetadata() {
        requirePrepared();
        return participantCoordinator.getMetadata();
    }

    /**
     * Get participant descriptors in stable participant order.
     *
     * @return participant descriptors
     */
    public synchronized List<ParticipantSnapshot> getParticipants() {
        requirePrepared();
        return participantCoordinator.getSnapshots();
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
     * Materialize and atomically publish this prepared session.
     *
     * @param finalDirectory final bundle directory
     * @return publish result
     * @throws Exception if materialization or publication fails
     */
    public OnlineBackupPublishResult publish(
            Path finalDirectory) throws Exception {
        if (finalDirectory == null) {
            throw new IllegalArgumentException(
                    "finalDirectory must not be null");
        }
        Path target = finalDirectory.toAbsolutePath().normalize();
        synchronized (this) {
            if (publishedResult != null) {
                if (!publishedTarget.equals(target)) {
                    throw new IllegalStateException(
                            "Session was already published to another target");
                }
                return publishedResult;
            }
            if (state != State.PREPARED || materializationActive
                    || cleanupActive) {
                throw new IllegalStateException(
                        "Online backup session cannot publish: " + state);
            }
            cancellationRequested = false;
            cancellationFailure = null;
            materializationActive = true;
            state = State.MATERIALIZING;
        }
        OnlineBackupPublishResult result = null;
        Throwable failure = null;
        try {
            result = OnlineBackupBundlePublisher.publish(this, target);
        } catch (Throwable e) {
            failure = e;
        }
        boolean canceled;
        synchronized (this) {
            materializationActive = false;
            canceled = cancellationRequested;
            if (canceled) {
                awaitCancellationSignalUninterruptibly();
                failure = addFailure(failure, cancellationFailure);
                cancellationFailure = null;
                cleanupActive = true;
                state = State.ABORTING;
            } else {
                if (failure == null) {
                    publishedResult = result;
                    publishedTarget = target;
                }
                state = State.PREPARED;
            }
            notifyAll();
        }
        if (canceled) {
            if (failure == null && result == null) {
                failure = new IOException(
                        "Online backup materialization was canceled");
            }
            failure = finishCleanup(State.ABORTED, failure);
        }
        rethrow(failure);
        return result;
    }

    /**
     * Abort all prepared resources in reverse order.
     *
     * @throws Exception if cleanup fails
     */
    public void abort() throws Exception {
        rethrow(terminate(false));
    }

    @Override
    public void close() throws Exception {
        rethrow(terminate(true));
    }

    private Throwable cleanup(Throwable failure) {
        failure = participantCoordinator.cleanup(failure);
        if (h2Snapshot != null) {
            try {
                h2Snapshot.close();
            } catch (Throwable cleanupFailure) {
                failure = addFailure(failure, cleanupFailure);
            } finally {
                h2Snapshot = null;
            }
        }
        return failure;
    }

    private Throwable terminate(boolean closing) throws Exception {
        boolean cleanupOwner = false;
        boolean cancellationOwner = false;
        synchronized (this) {
            if (state == State.CLOSED
                    || !closing && state == State.ABORTED) {
                return null;
            }
            if (materializationActive || preparationActive) {
                cancellationRequested = true;
                state = State.ABORTING;
                if (!cancellationSignalActive) {
                    cancellationSignalActive = true;
                    cancellationOwner = true;
                }
            } else if (cleanupActive || state == State.ABORTING) {
                // Another thread owns cleanup.
            } else if (state == State.ABORTED) {
                state = State.CLOSED;
                database.releaseOnlineBackupSession(this);
                return null;
            } else {
                state = State.ABORTING;
                cleanupActive = true;
                cleanupOwner = true;
            }
        }
        if (cancellationOwner) {
            Throwable signalFailure = requestH2MaterializationCancellation();
            synchronized (this) {
                cancellationFailure = addFailure(cancellationFailure,
                        signalFailure);
                cancellationSignalActive = false;
                notifyAll();
            }
        }
        Throwable failure;
        if (cleanupOwner) {
            failure = finishCleanup(closing ? State.CLOSED : State.ABORTED,
                    null);
        } else {
            synchronized (this) {
                while (preparationActive || materializationActive
                        || cleanupActive
                        || cancellationSignalActive) {
                    wait();
                }
                failure = terminalFailure;
                if (closing && state != State.CLOSED) {
                    state = State.CLOSED;
                }
            }
            database.releaseOnlineBackupSession(this);
        }
        return failure;
    }

    private Throwable requestH2MaterializationCancellation() {
        MVStorePreparedSnapshot snapshot;
        synchronized (this) {
            snapshot = h2Snapshot;
        }
        if (snapshot == null) {
            return null;
        }
        try {
            snapshot.abort();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Throwable finishCleanup(State terminalState, Throwable failure) {
        Throwable cleanupFailure;
        if (failure == null) {
            cleanupFailure = cleanup(null);
            failure = cleanupFailure;
        } else {
            int previousSuppressed = failure.getSuppressed().length;
            failure = cleanup(failure);
            cleanupFailure = null;
            Throwable[] suppressed = failure.getSuppressed();
            for (int i = previousSuppressed; i < suppressed.length; i++) {
                cleanupFailure = addFailure(cleanupFailure, suppressed[i]);
            }
        }
        synchronized (this) {
            terminalFailure = cleanupFailure;
            cleanupActive = false;
            state = terminalState;
            notifyAll();
        }
        database.releaseOnlineBackupSession(this);
        return failure;
    }

    private void awaitCancellationSignalUninterruptibly() {
        boolean interrupted = false;
        while (cancellationSignalActive) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isMaterializationCancellationRequested() {
        synchronized (this) {
            return cancellationRequested;
        }
    }

    void checkMaterializationAllowed() throws IOException {
        if (isMaterializationCancellationRequested()) {
            throw new IOException(
                    "Online backup materialization was canceled");
        }
    }

    void checkPreparationAllowed(String operation)
            throws IOException {
        synchronized (this) {
            if (cancellationRequested) {
                throw new IOException("Online backup prepare was canceled");
            }
        }
        requireRemaining(context, operation);
    }

    private void requirePrepared() {
        if (state != State.PREPARED && state != State.MATERIALIZING) {
            throw new IllegalStateException(
                    "Online backup session is not prepared: " + state);
        }
    }

    String getDatabaseName() {
        return database.getShortName();
    }

    long getPreparePauseMillis() {
        return preparePauseMillis;
    }

    String getStorageEngineId() {
        return database.getStorageEngineId();
    }

    List<OnlineBackupManifest.RequiredProvider>
            getRequiredValidationProviders() {
        ArrayList<OnlineBackupManifest.RequiredProvider> result =
                new ArrayList<>();
        addRequiredProvider(result, StorageEngineProvider.TYPE,
                database.getStorageEngineId());
        addRequiredProvider(result, SystemCatalogProvider.TYPE,
                database.getSystemCatalogProvider().getId());
        result.sort(Comparator
                .comparing(OnlineBackupManifest.RequiredProvider::getType)
                .thenComparing(
                        OnlineBackupManifest.RequiredProvider::getId));
        return result;
    }

    private void addRequiredProvider(
            List<OnlineBackupManifest.RequiredProvider> result, String type,
            String id) {
        RegisteredProvider registered = database.getPluginRegistry()
                .getProviders(type).get(id);
        if (registered == null) {
            throw new IllegalStateException(
                    "Required provider is not registered: " + type + '/'
                            + id);
        }
        result.add(new OnlineBackupManifest.RequiredProvider(type, id,
                registered.getPluginId(), registered.getPluginVersion()));
    }

    List<ParticipantMaterializer> getParticipantMaterializers() {
        return participantCoordinator.getMaterializers();
    }

    private void requireH2SnapshotActive() {
        if (h2Snapshot.getState()
                != MVStorePreparedSnapshot.State.PREPARED) {
            throw new IllegalStateException(
                    "H2 prepared snapshot lease expired during prepare");
        }
    }

    private static long deadline(long timeoutMillis) {
        long maxTimeoutMillis = Long.MAX_VALUE / 2L / 1_000_000L;
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.min(timeoutMillis, maxTimeoutMillis));
        return System.nanoTime() + timeoutNanos;
    }

    private static long requireRemaining(OnlineBackupContext context,
            String operation) {
        long remainingMillis = context.getRemainingMillis();
        if (remainingMillis <= 0L) {
            throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, operation);
        }
        return remainingMillis;
    }

    private static Throwable addFailure(Throwable failure,
            Throwable cleanupFailure) {
        if (cleanupFailure == null) {
            return failure;
        }
        if (failure == null) {
            return cleanupFailure;
        }
        if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw (Error) failure;
    }

    /**
     * Immutable participant provenance and prepared metadata.
     */
    public static final class ParticipantSnapshot {

        private final String participantId;
        private final String pluginId;
        private final String pluginVersion;
        private final PreparedParticipantMetadata metadata;

        ParticipantSnapshot(String participantId, String pluginId,
                String pluginVersion,
                PreparedParticipantMetadata metadata) {
            this.participantId = participantId;
            this.pluginId = pluginId;
            this.pluginVersion = pluginVersion;
            this.metadata = metadata;
        }

        /**
         * @return participant ID
         */
        public String getParticipantId() {
            return participantId;
        }

        /**
         * @return owning plugin ID
         */
        public String getPluginId() {
            return pluginId;
        }

        /**
         * @return owning plugin version
         */
        public String getPluginVersion() {
            return pluginVersion;
        }

        /**
         * @return prepared metadata
         */
        public PreparedParticipantMetadata getMetadata() {
            return metadata;
        }
    }

    static final class ParticipantMaterializer {

        final ParticipantSnapshot snapshot;
        final PreparedBackupParticipant prepared;

        ParticipantMaterializer(ParticipantSnapshot snapshot,
                PreparedBackupParticipant prepared) {
            this.snapshot = snapshot;
            this.prepared = prepared;
        }
    }
}
