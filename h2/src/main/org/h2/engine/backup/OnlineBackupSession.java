/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
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
        ABORTING,
        ABORTED,
        CLOSED
    }

    private final OnlineBackupContext context;
    private final Database database;
    private final ArrayList<ParticipantHandle> participants =
            new ArrayList<>();

    private MVStorePreparedSnapshot h2Snapshot;
    private State state = State.PREPARING;

    private OnlineBackupSession(Database database,
            OnlineBackupContext context) {
        this.database = database;
        this.context = context;
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
                UUID.randomUUID(), UUID.randomUUID(),
                identity.getDatabaseId(), identity.getGenerationId(),
                identity.getSchemaEpoch(), deadlineNanos);
        ArrayList<ResolvedParticipant> resolved =
                resolveParticipants(database, options.getParticipantIds());
        OnlineBackupSession session =
                new OnlineBackupSession(database, context);
        database.claimOnlineBackupSession(session);
        synchronized (session) {
            Throwable failure = null;
            try {
                long barrierTimeout = requireRemaining(context,
                        "online backup barrier");
                try (DatabaseOperationGate.BackupBarrier ignored =
                        database.getOperationGate()
                                .beginBackupBarrier(barrierTimeout)) {
                    requireRemaining(context, "H2 snapshot prepare");
                    database.getStore().flush();
                    session.h2Snapshot = database.getStore().getMvStore()
                            .prepareSnapshot(
                                    options.getSnapshotLeaseMillis());
                    requireRemaining(context, "H2 snapshot prepare");
                    session.requireH2SnapshotActive();
                    for (ResolvedParticipant participant : resolved) {
                        requireRemaining(context,
                                "participant " + participant.id);
                        PreparedBackupParticipant prepared =
                                participant.provider.prepare(context);
                        if (prepared == null) {
                            throw new IllegalStateException(
                                    "Participant returned null: "
                                            + participant.id);
                        }
                        ParticipantHandle handle = new ParticipantHandle(
                                participant, prepared);
                        session.participants.add(handle);
                        handle.validateMetadata();
                        requireRemaining(context,
                                "participant " + participant.id);
                    }
                    session.requireH2SnapshotActive();
                }
                session.state = State.PREPARED;
                return session;
            } catch (Throwable e) {
                failure = e;
                session.abortAfterPrepareFailure(e);
                throw e;
            } finally {
                if (failure != null) {
                    session.state = State.ABORTED;
                    database.releaseOnlineBackupSession(session);
                }
            }
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
        ArrayList<PreparedParticipantMetadata> metadata =
                new ArrayList<>(participants.size());
        for (ParticipantHandle participant : participants) {
            metadata.add(participant.metadata);
        }
        return Collections.unmodifiableList(metadata);
    }

    /**
     * Get participant descriptors in stable participant order.
     *
     * @return participant descriptors
     */
    public synchronized List<ParticipantSnapshot> getParticipants() {
        requirePrepared();
        ArrayList<ParticipantSnapshot> snapshots =
                new ArrayList<>(participants.size());
        for (ParticipantHandle participant : participants) {
            snapshots.add(new ParticipantSnapshot(
                    participant.resolved.id,
                    participant.resolved.pluginId,
                    participant.resolved.pluginVersion,
                    participant.metadata));
        }
        return Collections.unmodifiableList(snapshots);
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
     * Abort all prepared resources in reverse order.
     *
     * @throws Exception if cleanup fails
     */
    public synchronized void abort() throws Exception {
        if (state == State.ABORTED || state == State.CLOSED) {
            return;
        }
        state = State.ABORTING;
        Throwable failure;
        try {
            failure = cleanup(null);
            state = State.ABORTED;
        } finally {
            database.releaseOnlineBackupSession(this);
        }
        rethrow(failure);
    }

    @Override
    public synchronized void close() throws Exception {
        if (state == State.CLOSED) {
            return;
        }
        Throwable failure = null;
        try {
            if (state != State.ABORTED) {
                state = State.ABORTING;
                failure = cleanup(null);
                state = State.ABORTED;
            }
        } finally {
            state = State.CLOSED;
            database.releaseOnlineBackupSession(this);
        }
        rethrow(failure);
    }

    private void abortAfterPrepareFailure(Throwable failure) {
        state = State.ABORTING;
        cleanup(failure);
    }

    private Throwable cleanup(Throwable failure) {
        for (int i = participants.size() - 1; i >= 0; i--) {
            try {
                participants.get(i).prepared.abort();
            } catch (Throwable cleanupFailure) {
                failure = addFailure(failure, cleanupFailure);
            }
        }
        participants.clear();
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

    private void requirePrepared() {
        if (state != State.PREPARED) {
            throw new IllegalStateException(
                    "Online backup session is not prepared: " + state);
        }
    }

    private void requireH2SnapshotActive() {
        if (h2Snapshot.getState()
                != MVStorePreparedSnapshot.State.PREPARED) {
            throw new IllegalStateException(
                    "H2 prepared snapshot lease expired during prepare");
        }
    }

    private static ArrayList<ResolvedParticipant> resolveParticipants(
            Database database, List<String> selectedIds) {
        Map<String, RegisteredProvider> registered =
                database.getPluginRegistry().getProviders(
                        OnlineBackupParticipantProvider.TYPE);
        HashSet<String> unique = new HashSet<>();
        ArrayList<ResolvedParticipant> resolved = new ArrayList<>();
        for (String selectedId : selectedIds) {
            if (selectedId == null || selectedId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Participant id must not be empty");
            }
            if (!unique.add(selectedId)) {
                throw new IllegalArgumentException(
                        "Duplicate participant id: " + selectedId);
            }
            RegisteredProvider registration = registered.get(selectedId);
            if (registration == null) {
                throw new IllegalArgumentException(
                        "Unknown participant id: " + selectedId);
            }
            PluginProvider provider = registration.getProvider();
            if (!(provider instanceof OnlineBackupParticipantProvider)
                    || !provider.supports(
                            PluginCapability.ONLINE_BACKUP_PREPARE)) {
                throw new IllegalArgumentException(
                        "Participant does not support coordinated backup: "
                                + selectedId);
            }
            resolved.add(new ResolvedParticipant(selectedId,
                    registration.getPluginId(),
                    registration.getPluginVersion(),
                    (OnlineBackupParticipantProvider) provider));
        }
        resolved.sort(Comparator.comparing(participant -> participant.id));
        return resolved;
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

    private static final class ResolvedParticipant {

        final String id;
        final String pluginId;
        final String pluginVersion;
        final OnlineBackupParticipantProvider provider;

        ResolvedParticipant(String id, String pluginId, String pluginVersion,
                OnlineBackupParticipantProvider provider) {
            this.id = id;
            this.pluginId = pluginId;
            this.pluginVersion = pluginVersion;
            this.provider = provider;
        }
    }

    private static final class ParticipantHandle {

        final ResolvedParticipant resolved;
        final PreparedBackupParticipant prepared;
        PreparedParticipantMetadata metadata;

        ParticipantHandle(ResolvedParticipant resolved,
                PreparedBackupParticipant prepared) {
            this.resolved = resolved;
            this.prepared = prepared;
        }

        void validateMetadata() {
            metadata = prepared.getPreparedMetadata();
            if (metadata == null
                    || !resolved.id.equals(metadata.getParticipantId())) {
                throw new IllegalStateException(
                        "Participant metadata id mismatch: expected "
                                + resolved.id);
            }
        }
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
}
