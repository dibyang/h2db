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

import org.h2.api.ArmedBackupParticipant;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.engine.Database;
import org.h2.engine.PluginRegistry.RegisteredProvider;

/**
 * Owns participant selection, phased preparation, immutable views, and cleanup.
 */
final class OnlineBackupParticipantCoordinator {

    private final ArrayList<ParticipantHandle> participants;

    private OnlineBackupParticipantCoordinator(
            ArrayList<ParticipantHandle> participants) {
        this.participants = participants;
    }

    /**
     * Resolve explicitly selected participants in stable ID order.
     */
    static OnlineBackupParticipantCoordinator resolve(Database database,
            List<String> selectedIds) {
        Map<String, RegisteredProvider> registered =
                database.getPluginRegistry().getProviders(
                        OnlineBackupParticipantProvider.TYPE);
        HashSet<String> unique = new HashSet<>();
        ArrayList<ParticipantHandle> participants = new ArrayList<>();
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
                            PluginCapability.ONLINE_BACKUP_PREPARE)
                    && !provider.supports(
                            PluginCapability.ONLINE_BACKUP_PHASED_PREPARE)) {
                throw new IllegalArgumentException(
                        "Participant does not support coordinated backup: "
                                + selectedId);
            }
            participants.add(new ParticipantHandle(selectedId,
                    registration.getPluginId(),
                    registration.getPluginVersion(),
                    (OnlineBackupParticipantProvider) provider));
        }
        participants.sort(Comparator.comparing(
                participant -> participant.id));
        return new OnlineBackupParticipantCoordinator(participants);
    }

    void arm(OnlineBackupContext context, OnlineBackupSession session)
            throws Exception {
        for (ParticipantHandle participant : participants) {
            session.checkPreparationAllowed(
                    "participant arm " + participant.id);
            participant.arm(context);
            session.checkPreparationAllowed(
                    "participant arm " + participant.id);
        }
    }

    void capture(OnlineBackupContext context, OnlineBackupSession session)
            throws Exception {
        for (ParticipantHandle participant : participants) {
            session.checkPreparationAllowed(
                    "participant capture " + participant.id);
            participant.capture(context);
            session.checkPreparationAllowed(
                    "participant capture " + participant.id);
        }
    }

    List<PreparedParticipantMetadata> getMetadata() {
        ArrayList<PreparedParticipantMetadata> result =
                new ArrayList<>(participants.size());
        for (ParticipantHandle participant : participants) {
            result.add(participant.metadata);
        }
        return Collections.unmodifiableList(result);
    }

    List<OnlineBackupSession.ParticipantSnapshot> getSnapshots() {
        ArrayList<OnlineBackupSession.ParticipantSnapshot> result =
                new ArrayList<>(participants.size());
        for (ParticipantHandle participant : participants) {
            result.add(participant.snapshot());
        }
        return Collections.unmodifiableList(result);
    }

    List<OnlineBackupSession.ParticipantMaterializer> getMaterializers() {
        ArrayList<OnlineBackupSession.ParticipantMaterializer> result =
                new ArrayList<>(participants.size());
        for (ParticipantHandle participant : participants) {
            result.add(new OnlineBackupSession.ParticipantMaterializer(
                    participant.snapshot(), participant.prepared));
        }
        return result;
    }

    Throwable cleanup(Throwable failure) {
        for (int i = participants.size() - 1; i >= 0; i--) {
            ParticipantHandle participant = participants.get(i);
            try {
                if (participant.prepared != null) {
                    participant.prepared.abort();
                } else if (participant.armed != null) {
                    participant.armed.abort();
                }
            } catch (Throwable cleanupFailure) {
                failure = addFailure(failure, cleanupFailure);
            }
        }
        participants.clear();
        return failure;
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

    private static final class ParticipantHandle {

        final String id;
        final String pluginId;
        final String pluginVersion;
        final OnlineBackupParticipantProvider provider;
        ArmedBackupParticipant armed;
        PreparedBackupParticipant prepared;
        PreparedParticipantMetadata metadata;

        ParticipantHandle(String id, String pluginId, String pluginVersion,
                OnlineBackupParticipantProvider provider) {
            this.id = id;
            this.pluginId = pluginId;
            this.pluginVersion = pluginVersion;
            this.provider = provider;
        }

        void arm(OnlineBackupContext context) throws Exception {
            if (provider.supports(
                    PluginCapability.ONLINE_BACKUP_PHASED_PREPARE)) {
                armed = provider.arm(context);
                if (armed == null) {
                    throw new IllegalStateException(
                            "Phased participant returned null arm handle: "
                                    + id);
                }
            }
        }

        void capture(OnlineBackupContext context) throws Exception {
            PreparedBackupParticipant captured = armed != null
                    ? armed.capture(context) : provider.prepare(context);
            if (captured == null) {
                throw new IllegalStateException(
                        "Participant returned null: " + id);
            }
            prepared = captured;
            armed = null;
            metadata = prepared.getPreparedMetadata();
            if (metadata == null
                    || !id.equals(metadata.getParticipantId())) {
                throw new IllegalStateException(
                        "Participant metadata id mismatch: expected " + id);
            }
        }

        OnlineBackupSession.ParticipantSnapshot snapshot() {
            return new OnlineBackupSession.ParticipantSnapshot(id, pluginId,
                    pluginVersion, metadata);
        }
    }
}
