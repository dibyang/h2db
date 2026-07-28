/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable manifest for a published coordinated backup bundle.
 */
public final class OnlineBackupManifest {

    /**
     * Initial bundle format version.
     */
    public static final int FORMAT_VERSION = 1;

    /**
     * The only status valid in an immutable final manifest.
     */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private final int formatVersion;
    private final String status;
    private final UUID backupId;
    private final UUID cutId;
    private final String databaseName;
    private final UUID databaseId;
    private final UUID sourceGenerationId;
    private final long schemaEpoch;
    private final String h2dbVersion;
    private final String storageEngineId;
    private final List<RequiredProvider> requiredProviders;
    private final String createdAt;
    private final long preparePauseMillis;
    private final Artifact h2dbArtifact;
    private final List<Participant> participants;

    OnlineBackupManifest(int formatVersion, String status, UUID backupId,
            UUID cutId, String databaseName, UUID databaseId,
            UUID sourceGenerationId, long schemaEpoch, String h2dbVersion,
            String storageEngineId,
            List<RequiredProvider> requiredProviders, String createdAt,
            long preparePauseMillis, Artifact h2dbArtifact,
            List<Participant> participants) {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported manifest format: " + formatVersion);
        }
        if (!STATUS_PUBLISHED.equals(status)) {
            throw new IllegalArgumentException(
                    "Final manifest status must be PUBLISHED");
        }
        this.formatVersion = formatVersion;
        this.status = status;
        this.backupId = requireNonNull(backupId, "backupId");
        this.cutId = requireNonNull(cutId, "cutId");
        this.databaseName = requireNonBlank(databaseName, "databaseName");
        this.databaseId = requireNonNull(databaseId, "databaseId");
        this.sourceGenerationId = requireNonNull(sourceGenerationId,
                "sourceGenerationId");
        if (schemaEpoch < 0L || preparePauseMillis < 0L) {
            throw new IllegalArgumentException(
                    "Manifest counters must not be negative");
        }
        this.schemaEpoch = schemaEpoch;
        this.h2dbVersion = requireNonBlank(h2dbVersion, "h2dbVersion");
        this.storageEngineId = requireNonBlank(storageEngineId,
                "storageEngineId");
        ArrayList<RequiredProvider> providerCopy = new ArrayList<>(
                requireNonNull(requiredProviders, "requiredProviders"));
        String previousProvider = null;
        for (RequiredProvider provider : providerCopy) {
            requireNonNull(provider, "requiredProvider");
            String key = provider.getType() + '\0' + provider.getId();
            if (previousProvider != null
                    && previousProvider.compareTo(key) >= 0) {
                throw new IllegalArgumentException(
                        "Required providers must be strictly sorted");
            }
            previousProvider = key;
        }
        this.requiredProviders = Collections.unmodifiableList(providerCopy);
        this.createdAt = requireNonBlank(createdAt, "createdAt");
        this.preparePauseMillis = preparePauseMillis;
        this.h2dbArtifact = requireNonNull(h2dbArtifact, "h2dbArtifact");
        ArrayList<Participant> participantCopy =
                new ArrayList<>(requireNonNull(participants, "participants"));
        String previous = null;
        for (Participant participant : participantCopy) {
            requireNonNull(participant, "participant");
            String id = participant.getParticipantId();
            if (previous != null && previous.compareTo(id) >= 0) {
                throw new IllegalArgumentException(
                        "Participants must be strictly sorted by ID");
            }
            previous = id;
        }
        this.participants = Collections.unmodifiableList(participantCopy);
    }

    /**
     * @return manifest format version
     */
    public int getFormatVersion() {
        return formatVersion;
    }

    /**
     * @return immutable manifest status
     */
    public String getStatus() {
        return status;
    }

    /**
     * @return backup idempotency key
     */
    public UUID getBackupId() {
        return backupId;
    }

    /**
     * @return coordinated cut ID
     */
    public UUID getCutId() {
        return cutId;
    }

    /**
     * @return logical database name
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * @return stable logical database ID
     */
    public UUID getDatabaseId() {
        return databaseId;
    }

    /**
     * @return source generation ID
     */
    public UUID getSourceGenerationId() {
        return sourceGenerationId;
    }

    /**
     * @return schema epoch captured at the cut
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }

    /**
     * @return H2 version that created this bundle
     */
    public String getH2dbVersion() {
        return h2dbVersion;
    }

    /**
     * @return 源数据库 storage engine ID
     */
    public String getStorageEngineId() {
        return storageEngineId;
    }

    /**
     * @return 只读打开 H2 catalog 所需的 provider 来源信息
     */
    public List<RequiredProvider> getRequiredProviders() {
        return requiredProviders;
    }

    /**
     * @return UTC creation timestamp
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * @return prepare barrier duration in milliseconds
     */
    public long getPreparePauseMillis() {
        return preparePauseMillis;
    }

    /**
     * @return H2 database artifact
     */
    public Artifact getH2dbArtifact() {
        return h2dbArtifact;
    }

    /**
     * @return participants in stable ID order
     */
    public List<Participant> getParticipants() {
        return participants;
    }

    /**
     * Immutable file checksum entry.
     */
    public static final class Artifact {

        private final String path;
        private final long length;
        private final String sha256;

        Artifact(String path, long length, String sha256) {
            this.path = requireNonBlank(path, "artifact path");
            if (length < 0L) {
                throw new IllegalArgumentException(
                        "artifact length must not be negative");
            }
            this.length = length;
            this.sha256 = requireSha256(sha256);
        }

        /**
         * @return bundle-relative path
         */
        public String getPath() {
            return path;
        }

        /**
         * @return file length
         */
        public long getLength() {
            return length;
        }

        /**
         * @return lowercase SHA-256
         */
        public String getSha256() {
            return sha256;
        }
    }

    /**
     * Validation open 所需的不可变 provider 来源信息。
     */
    public static final class RequiredProvider {

        private final String type;
        private final String id;
        private final String pluginId;
        private final String pluginVersion;

        RequiredProvider(String type, String id, String pluginId,
                String pluginVersion) {
            this.type = requireNonBlank(type, "provider type");
            this.id = requireNonBlank(id, "provider id");
            this.pluginId = requireNonBlank(pluginId, "provider pluginId");
            this.pluginVersion = requireNonBlank(pluginVersion,
                    "provider pluginVersion");
        }

        /**
         * @return provider 类型
         */
        public String getType() {
            return type;
        }

        /**
         * @return provider ID
         */
        public String getId() {
            return id;
        }

        /**
         * @return 所属插件 ID
         */
        public String getPluginId() {
            return pluginId;
        }

        /**
         * @return 所属插件版本
         */
        public String getPluginVersion() {
            return pluginVersion;
        }
    }

    /**
     * Immutable participant provenance, cut metadata, and artifacts.
     */
    public static final class Participant {

        private final String participantId;
        private final String pluginId;
        private final String pluginVersion;
        private final String snapshotId;
        private final Map<String, String> attributes;
        private final List<Artifact> artifacts;

        Participant(String participantId, String pluginId,
                String pluginVersion, String snapshotId,
                Map<String, String> attributes, List<Artifact> artifacts) {
            this.participantId = requireNonBlank(participantId,
                    "participantId");
            this.pluginId = requireNonBlank(pluginId, "pluginId");
            this.pluginVersion = requireNonBlank(pluginVersion,
                    "pluginVersion");
            this.snapshotId = requireNonBlank(snapshotId, "snapshotId");
            TreeMap<String, String> attributeCopy =
                    new TreeMap<>(requireNonNull(attributes, "attributes"));
            for (Map.Entry<String, String> entry : attributeCopy.entrySet()) {
                requireNonBlank(entry.getKey(), "attribute key");
                requireNonNull(entry.getValue(), "attribute value");
            }
            this.attributes = Collections.unmodifiableMap(attributeCopy);
            ArrayList<Artifact> artifactCopy =
                    new ArrayList<>(requireNonNull(artifacts, "artifacts"));
            String previous = null;
            for (Artifact artifact : artifactCopy) {
                requireNonNull(artifact, "artifact");
                String path = artifact.getPath();
                if (previous != null && previous.compareTo(path) >= 0) {
                    throw new IllegalArgumentException(
                            "Artifacts must be strictly sorted by path");
                }
                previous = path;
            }
            this.artifacts = Collections.unmodifiableList(artifactCopy);
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
         * @return participant snapshot ID
         */
        public String getSnapshotId() {
            return snapshotId;
        }

        /**
         * @return stable participant cut attributes
         */
        public Map<String, String> getAttributes() {
            return attributes;
        }

        /**
         * @return artifacts in stable path order
         */
        public List<Artifact> getArtifacts() {
            return artifacts;
        }
    }

    private static String requireSha256(String value) {
        value = requireNonBlank(value, "sha256");
        if (value.length() != 64) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                throw new IllegalArgumentException("Invalid SHA-256");
            }
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
