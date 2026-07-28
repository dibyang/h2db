/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.OnlineBackupValidationContext;
import org.h2.api.ParticipantArtifactSource;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.api.StorageEngineProvider;
import org.h2.api.SystemCatalogProvider;
import org.h2.api.TableEngineProvider;
import org.h2.engine.Database;
import org.h2.engine.PluginRegistry.RegisteredProvider;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.engine.StorageEngineResolver;
import org.h2.engine.backup.DatabaseIdentityMetadata.Snapshot;
import org.h2.engine.backup.OnlineBackupManifest;
import org.h2.engine.backup.OnlineBackupManifest.Participant;
import org.h2.engine.backup.OnlineBackupManifest.RequiredProvider;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;

/**
 * 解析 validation allowlist、只读试打开 H2 catalog，并调用 participant 校验。
 */
final class ShadowValidationOpener {

    private ShadowValidationOpener() {
    }

    static ValidationSummary validate(Database activeDatabase, Path staging,
            OnlineBackupManifest manifest, ShadowRestoreOptions options,
            long deadlineNanos) throws Exception {
        Snapshot activeIdentity = activeDatabase.getOnlineBackupMetadata()
                .requireSnapshot();
        if (!activeIdentity.getDatabaseId().equals(manifest.getDatabaseId())) {
            throw new IllegalArgumentException(
                    "Backup databaseId does not match active database");
        }
        UUID shadowGenerationId = options.getShadowGenerationId();
        if (shadowGenerationId.equals(manifest.getSourceGenerationId())
                || shadowGenerationId.equals(
                        activeIdentity.getGenerationId())) {
            throw new IllegalArgumentException(
                    "Shadow generationId must be new");
        }
        Selection selection = resolveProviders(activeDatabase, manifest,
                options);
        requireRemaining(deadlineNanos, "H2 validation open");
        ensureStorageMetadata(staging, manifest.getStorageEngineId());
        validateH2Database(staging, manifest, options,
                selection.coreRegistrations, deadlineNanos);
        validateParticipants(staging, manifest, options,
                selection.participants, deadlineNanos);
        return new ValidationSummary(selection.coreProviderKeys,
                selection.participants.size());
    }

    private static Selection resolveProviders(Database database,
            OnlineBackupManifest manifest, ShadowRestoreOptions options) {
        TreeMap<String, ShadowRestoreOptions.ProviderSelection> allowed =
                new TreeMap<>();
        for (ShadowRestoreOptions.ProviderSelection provider
                : options.getAdditionalRequiredProviders()) {
            String key = providerKey(provider.getType(), provider.getId());
            if (allowed.put(key, provider) != null) {
                throw new IllegalArgumentException(
                        "Duplicate validation provider selection: " + key);
            }
            if (!isCoreValidationType(provider.getType())) {
                throw unvalidatable(key);
            }
        }
        TreeMap<String, ValidationProviderRegistry.Registration> core =
                new TreeMap<>();
        List<RequiredProvider> required = manifest.getRequiredProviders();
        if (required.isEmpty()) {
            if (!StorageEngineResolver.DEFAULT_STORAGE_ENGINE_ID.equals(
                    manifest.getStorageEngineId())) {
                throw unvalidatable("Missing provider provenance");
            }
            addLegacyBuiltin(database, core, StorageEngineProvider.TYPE,
                    manifest.getStorageEngineId());
            addLegacyBuiltin(database, core, SystemCatalogProvider.TYPE,
                    manifest.getStorageEngineId());
        } else {
            for (RequiredProvider requiredProvider : required) {
                String key = providerKey(requiredProvider.getType(),
                        requiredProvider.getId());
                if (!isCoreValidationType(requiredProvider.getType())) {
                    throw unvalidatable(key);
                }
                RegisteredProvider registered = requireRegistered(database,
                        requiredProvider.getType(), requiredProvider.getId());
                if (!requiredProvider.getPluginId().equals(
                        registered.getPluginId())
                        || !requiredProvider.getPluginVersion().equals(
                                registered.getPluginVersion())) {
                    throw unvalidatable(key);
                }
                if (registered.getSource() != PluginSource.BUILTIN
                        && !allowed.containsKey(key)) {
                    throw unvalidatable(key);
                }
                addCore(core, key, requiredProvider.getType(),
                        requiredProvider.getId(), registered);
            }
        }
        for (ShadowRestoreOptions.ProviderSelection provider
                : allowed.values()) {
            String key = providerKey(provider.getType(), provider.getId());
            if (!core.containsKey(key)) {
                RegisteredProvider registered = requireRegistered(database,
                        provider.getType(), provider.getId());
                addCore(core, key, provider.getType(), provider.getId(),
                        registered);
            }
        }
        requireCoreProvider(core, StorageEngineProvider.TYPE,
                manifest.getStorageEngineId());
        ArrayList<ParticipantBinding> participants = resolveParticipants(
                database, manifest, options);
        return new Selection(new ArrayList<>(core.values()),
                new ArrayList<>(core.keySet()), participants);
    }

    private static void addLegacyBuiltin(Database database,
            TreeMap<String, ValidationProviderRegistry.Registration> core,
            String type, String id) {
        RegisteredProvider registered = requireRegistered(database, type, id);
        if (registered.getSource() != PluginSource.BUILTIN) {
            throw unvalidatable(providerKey(type, id));
        }
        addCore(core, providerKey(type, id), type, id, registered);
    }

    private static void addCore(
            TreeMap<String, ValidationProviderRegistry.Registration> core,
            String key, String type, String id,
            RegisteredProvider registered) {
        PluginProvider provider = registered.getProvider();
        if (!provider.supports(PluginCapability.VALIDATION_OPEN)) {
            throw unvalidatable(key);
        }
        if (core.put(key, new ValidationProviderRegistry.Registration(type,
                id, registered.getPluginId(),
                registered.getPluginVersion(), provider)) != null) {
            throw new IllegalArgumentException(
                    "Duplicate required provider: " + key);
        }
    }

    private static void requireCoreProvider(
            TreeMap<String, ValidationProviderRegistry.Registration> core,
            String type, String id) {
        if (!core.containsKey(providerKey(type, id))) {
            throw unvalidatable(providerKey(type, id));
        }
    }

    private static ArrayList<ParticipantBinding> resolveParticipants(
            Database database, OnlineBackupManifest manifest,
            ShadowRestoreOptions options) {
        HashSet<String> allowlist = new HashSet<>();
        for (String id : options.getParticipantAllowlist()) {
            if (id == null || id.trim().isEmpty() || !allowlist.add(id)) {
                throw new IllegalArgumentException(
                        "Invalid participant validation allowlist");
            }
        }
        ArrayList<ParticipantBinding> result = new ArrayList<>();
        for (Participant participant : manifest.getParticipants()) {
            String id = participant.getParticipantId();
            if (!allowlist.remove(id)) {
                throw unvalidatable("participant/" + id);
            }
            RegisteredProvider registered = requireRegistered(database,
                    OnlineBackupParticipantProvider.TYPE, id);
            PluginProvider provider = registered.getProvider();
            if (!(provider instanceof OnlineBackupParticipantProvider)
                    || !provider.supports(
                            PluginCapability.ONLINE_BACKUP_VALIDATE)
                    || !participant.getPluginId().equals(
                            registered.getPluginId())
                    || !participant.getPluginVersion().equals(
                            registered.getPluginVersion())) {
                throw unvalidatable("participant/" + id);
            }
            result.add(new ParticipantBinding(participant,
                    (OnlineBackupParticipantProvider) provider));
        }
        if (!allowlist.isEmpty()) {
            throw new IllegalArgumentException(
                    "Participant allowlist contains unrequested provider");
        }
        return result;
    }

    private static void validateH2Database(Path staging,
            OnlineBackupManifest manifest, ShadowRestoreOptions options,
            List<ValidationProviderRegistry.Registration> registrations,
            long deadlineNanos) throws Exception {
        Path databaseFile = BackupBundleVerifier.resolveInside(staging,
                manifest.getH2dbArtifact().getPath());
        String fileName = databaseFile.toAbsolutePath().toString();
        String suffix = ".mv.db";
        if (!fileName.endsWith(suffix)) {
            throw new IOException("Unsupported H2 database artifact");
        }
        String databaseName = fileName.substring(0,
                fileName.length() - suffix.length())
                .replace(java.io.File.separatorChar, '/');
        Properties properties = new Properties();
        properties.setProperty("user", options.getUser());
        char[] password = options.copyPassword();
        properties.setProperty("password", new String(password));
        Arrays.fill(password, '\0');
        properties.setProperty("ACCESS_MODE_DATA", "r");
        properties.setProperty("IFEXISTS", "TRUE");
        properties.setProperty("DB_CLOSE_ON_EXIT", "FALSE");
        properties.setProperty("ONLINE_BACKUP_COORDINATION", "TRUE");
        properties.setProperty("ONLINE_BACKUP_VALIDATION", "TRUE");
        properties.setProperty("ONLINE_BACKUP_GENERATION_ID",
                options.getShadowGenerationId().toString());
        properties.setProperty("STORAGE_ENGINE",
                manifest.getStorageEngineId());
        if (options.getCipher() != null) {
            properties.setProperty("CIPHER", options.getCipher());
        }
        try (ValidationProviderRegistry.Scope ignored =
                ValidationProviderRegistry.open(registrations);
                Connection connection = DriverManager.getConnection(
                        "jdbc:h2:" + databaseName, properties)) {
            if (!connection.isReadOnly()) {
                throw new IllegalStateException(
                        "Validation database is not read-only");
            }
            Database database = ((SessionLocal) ((JdbcConnection) connection)
                    .getSession()).getDatabase();
            if (!database.isOnlineBackupValidation()) {
                throw new IllegalStateException(
                        "Database did not enter validation mode");
            }
            Snapshot identity = database.getOnlineBackupMetadata()
                    .requireSnapshot();
            if (!identity.getDatabaseId().equals(manifest.getDatabaseId())
                    || identity.getSchemaEpoch()
                            != manifest.getSchemaEpoch()
                    || !identity.getGenerationId().equals(
                            options.getShadowGenerationId())
                    || !database.getStorageEngineId().equals(
                            manifest.getStorageEngineId())) {
                throw new IllegalStateException(
                        "Shadow catalog identity validation failed");
            }
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT COUNT(*) FROM "
                                    + "INFORMATION_SCHEMA.TABLES")) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Shadow catalog query returned no row");
                }
                result.getLong(1);
            }
            requireRemaining(deadlineNanos, "H2 validation close");
        }
    }

    private static void validateParticipants(Path staging,
            OnlineBackupManifest manifest, ShadowRestoreOptions options,
            List<ParticipantBinding> participants, long deadlineNanos)
            throws Exception {
        OnlineBackupValidationContext context =
                new OnlineBackupValidationContext(manifest.getBackupId(),
                        manifest.getCutId(), manifest.getDatabaseId(),
                        manifest.getSourceGenerationId(),
                        options.getShadowGenerationId(),
                        manifest.getSchemaEpoch());
        for (ParticipantBinding binding : participants) {
            requireRemaining(deadlineNanos,
                    "participant validation " + binding.manifest
                            .getParticipantId());
            PreparedParticipantMetadata metadata =
                    new PreparedParticipantMetadata(
                            binding.manifest.getParticipantId(),
                            binding.manifest.getSnapshotId(),
                            binding.manifest.getAttributes());
            binding.provider.validateRestore(context, metadata,
                    new RestrictedArtifactSource(staging,
                            binding.manifest));
            requireRemaining(deadlineNanos,
                    "participant validation " + binding.manifest
                            .getParticipantId());
        }
    }

    private static void ensureStorageMetadata(Path staging,
            String storageEngineId) throws IOException {
        Path h2File = staging.resolve("h2/database.mv.db");
        String databaseName = h2File.toString();
        String base = databaseName.substring(0,
                databaseName.length() - ".mv.db".length());
        Path metadata = java.nio.file.Paths.get(
                StorageEngineResolver.storageMetadataFileName(base));
        byte[] expected =
                (storageEngineId + '\n').getBytes(StandardCharsets.UTF_8);
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(metadata)
                    || !Arrays.equals(expected, Files.readAllBytes(metadata))) {
                throw new IOException(
                        "Shadow storage metadata does not match manifest");
            }
            return;
        }
        BackupBundleVerifier.writeForced(metadata, expected);
    }

    private static RegisteredProvider requireRegistered(Database database,
            String type, String id) {
        RegisteredProvider registered = database.getPluginRegistry()
                .getProviders(type).get(id);
        if (registered == null) {
            throw unvalidatable(providerKey(type, id));
        }
        return registered;
    }

    private static boolean isCoreValidationType(String type) {
        return StorageEngineProvider.TYPE.equals(type)
                || SystemCatalogProvider.TYPE.equals(type)
                || TableEngineProvider.TYPE.equals(type);
    }

    private static String providerKey(String type, String id) {
        return type + '/' + id;
    }

    private static DbException unvalidatable(String provider) {
        return DbException.get(ErrorCode.UNVALIDATABLE_PROVIDER_1, provider);
    }

    private static void requireRemaining(long deadlineNanos,
            String operation) {
        if (System.nanoTime() - deadlineNanos >= 0L) {
            throw new IllegalStateException(
                    "Shadow validation deadline exceeded: " + operation);
        }
    }

    static long deadline(long timeoutMillis) {
        long maxMillis = Long.MAX_VALUE / 2L / 1_000_000L;
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Math.min(timeoutMillis, maxMillis));
    }

    private static final class RestrictedArtifactSource
            implements ParticipantArtifactSource {

        private final TreeMap<String, Path> paths = new TreeMap<>();

        RestrictedArtifactSource(Path staging, Participant participant)
                throws IOException {
            String prefix = "participants/p-"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            participant.getParticipantId().getBytes(
                                    StandardCharsets.UTF_8))
                    + '/';
            for (OnlineBackupManifest.Artifact artifact
                    : participant.getArtifacts()) {
                String path = BackupBundleVerifier.normalizeRelativePath(
                        artifact.getPath());
                if (!path.startsWith(prefix)) {
                    throw new IOException(
                            "Participant artifact root mismatch");
                }
                String relative = path.substring(prefix.length());
                if (relative.isEmpty()
                        || paths.put(relative,
                                BackupBundleVerifier.resolveInside(staging,
                                        path)) != null) {
                    throw new IOException(
                            "Duplicate participant artifact");
                }
            }
        }

        @Override
        public List<String> getRelativePaths() {
            return Collections.unmodifiableList(
                    new ArrayList<>(paths.keySet()));
        }

        @Override
        public InputStream open(String relativePath) throws IOException {
            String normalized = BackupBundleVerifier.normalizeRelativePath(
                    relativePath);
            Path file = paths.get(normalized);
            if (file == null || !Files.isRegularFile(file,
                    LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                throw new IOException(
                        "Participant artifact is not declared: "
                                + relativePath);
            }
            FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            return Channels.newInputStream(channel);
        }
    }

    private static final class ParticipantBinding {

        final Participant manifest;
        final OnlineBackupParticipantProvider provider;

        ParticipantBinding(Participant manifest,
                OnlineBackupParticipantProvider provider) {
            this.manifest = manifest;
            this.provider = provider;
        }
    }

    private static final class Selection {

        final List<ValidationProviderRegistry.Registration> coreRegistrations;
        final List<String> coreProviderKeys;
        final List<ParticipantBinding> participants;

        Selection(
                List<ValidationProviderRegistry.Registration> coreRegistrations,
                List<String> coreProviderKeys,
                List<ParticipantBinding> participants) {
            this.coreRegistrations = coreRegistrations;
            this.coreProviderKeys = coreProviderKeys;
            this.participants = participants;
        }
    }

    static final class ValidationSummary {

        final List<String> coreProviderKeys;
        final int participantCount;

        ValidationSummary(List<String> coreProviderKeys,
                int participantCount) {
            this.coreProviderKeys = coreProviderKeys;
            this.participantCount = participantCount;
        }
    }
}
