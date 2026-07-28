/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.h2.engine.Database;
import org.h2.engine.backup.OnlineBackupManifest;
import org.h2.engine.backup.OnlineBackupManifestCodec;
import org.h2.util.json.JSONBoolean;
import org.h2.util.json.JSONByteArrayTarget;
import org.h2.util.json.JSONBytesSource;
import org.h2.util.json.JSONNumber;
import org.h2.util.json.JSONObject;
import org.h2.util.json.JSONString;
import org.h2.util.json.JSONValue;
import org.h2.util.json.JSONValueTarget;

/**
 * 将已发布 bundle 恢复到全新 staging，完成 fail-closed validation 后再
 * 原子发布 shadow generation。
 */
public final class ShadowRestoreCoordinator {

    private static final long MAX_REPORT_BYTES = 4L * 1024L * 1024L;
    private static final RestoreFaultInjector NO_FAULTS =
            new RestoreFaultInjector() {
                @Override
                public void before(RestoreStep step, Path path)
                        throws IOException {
                    // Production restore does not inject failures.
                }
            };

    /**
     * 包内测试使用的 shadow 发布故障点，不属于公开 API。
     */
    enum RestoreStep {
        ARTIFACT_COPY,
        ARTIFACT_FSYNC,
        BACKUP_MANIFEST_FSYNC,
        VALIDATION_REPORT_FSYNC,
        STAGING_DIRECTORY_FSYNC,
        ATOMIC_MOVE,
        PARENT_DIRECTORY_FSYNC
    }

    /**
     * 包内测试使用的确定性故障接缝。
     */
    interface RestoreFaultInjector {

        /**
         * 在指定恢复步骤执行前注入失败。
         *
         * @param step 恢复步骤
         * @param path 当前步骤操作的路径
         * @throws IOException 注入的 I/O 失败
         */
        void before(RestoreStep step, Path path) throws IOException;
    }

    private ShadowRestoreCoordinator() {
    }

    /**
     * 恢复并校验一个 shadow generation。
     *
     * @param activeDatabase 当前活动数据库，仅用于身份和 provider allowlist
     * @param backupBundle 已发布 backup bundle
     * @param shadowDirectory 最终 shadow generation 目录
     * @param options restore/validation 选项
     * @return 已验证并发布的 shadow
     * @throws Exception 路径、checksum、provider、只读打开或发布失败
     */
    public static ShadowRestoreResult stageAndValidate(
            Database activeDatabase, Path backupBundle, Path shadowDirectory,
            ShadowRestoreOptions options) throws Exception {
        return stageAndValidate(activeDatabase, backupBundle, shadowDirectory,
                options, NO_FAULTS);
    }

    static ShadowRestoreResult stageAndValidate(
            Database activeDatabase, Path backupBundle, Path shadowDirectory,
            ShadowRestoreOptions options, RestoreFaultInjector faultInjector)
            throws Exception {
        if (activeDatabase == null || backupBundle == null
                || shadowDirectory == null || options == null
                || faultInjector == null) {
            throw new IllegalArgumentException(
                    "Shadow restore arguments must not be null");
        }
        if (activeDatabase.getOnlineBackupMetadata() == null) {
            throw new IllegalStateException(
                    "Active database has no online backup identity");
        }
        Path bundle = backupBundle.toAbsolutePath().normalize();
        Path target = shadowDirectory.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent,
                LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException(
                    "Shadow parent is not a regular directory");
        }
        Path staging = target.resolveSibling(
                "." + target.getFileName() + ".validation-"
                        + options.getShadowGenerationId());
        Path audit = target.resolveSibling(
                "." + target.getFileName() + "."
                        + options.getShadowGenerationId()
                        + ".validation.json");
        if (!parent.equals(staging.getParent())
                || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shadow validation staging already exists");
        }
        Path noFollowBundle = bundle.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realBundle = bundle.toRealPath();
        if (!noFollowBundle.equals(realBundle)) {
            throw new IOException(
                    "Backup bundle root must not be a link or junction");
        }
        Path realParent = parent.toRealPath();
        if (staging.startsWith(realBundle)
                || target.startsWith(realBundle)
                || realBundle.startsWith(target)
                || realParent.startsWith(realBundle)) {
            throw new IOException(
                    "Backup and shadow paths must not overlap");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return reusePublished(activeDatabase, realBundle, target, parent,
                    audit, options, faultInjector);
        }

        long startedNanos = System.nanoTime();
        long deadlineNanos = ShadowValidationOpener.deadline(
                options.getValidationTimeoutMillis());
        writeAudit(audit, null, options, "STAGING", null);
        OnlineBackupManifest manifest = null;
        boolean published = false;
        Throwable failure = null;
        try {
            BackupBundleVerifier.VerifiedBundle verified =
                    BackupBundleVerifier.verifyAndCopy(realBundle, staging,
                            faultInjector);
            manifest = verified.manifest;
            ShadowValidationOpener.ValidationSummary summary =
                    ShadowValidationOpener.validate(activeDatabase, staging,
                            manifest, options, deadlineNanos);
            long validationMillis = elapsedMillis(startedNanos);
            Path report = staging.resolve("validation-report.json");
            BackupBundleVerifier.writeForced(report,
                    validationReport(manifest, options, "VALIDATED",
                            validationMillis, summary, null),
                    faultInjector, RestoreStep.VALIDATION_REPORT_FSYNC);
            forceDirectory(staging, faultInjector,
                    RestoreStep.STAGING_DIRECTORY_FSYNC);
            try {
                faultInjector.before(RestoreStep.ATOMIC_MOVE, target);
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException(
                        "Atomic shadow publish is not supported", e);
            }
            published = true;
            String parentFsync = forceDirectory(parent, faultInjector,
                    RestoreStep.PARENT_DIRECTORY_FSYNC);
            try {
                writeAudit(audit, manifest, options, "VALIDATED",
                        parentFsync);
            } catch (IOException ignored) {
                // validation-report.json 已在原子发布前强制落盘；旁路操作审计
                // 失败不改变已验证 generation 的事实。
            }
            return new ShadowRestoreResult(target,
                    target.resolve("validation-report.json"), manifest,
                    options.getShadowGenerationId(), validationMillis);
        } catch (Throwable e) {
            failure = e;
            rethrow(e);
            throw new AssertionError();
        } finally {
            if (failure != null && !published) {
                if (Files.isDirectory(staging,
                        LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Path report = staging.resolve(
                                "validation-report.json");
                        Files.deleteIfExists(report);
                        BackupBundleVerifier.writeForced(report,
                                validationReport(manifest, options,
                                        "FAILED",
                                        elapsedMillis(startedNanos),
                                        null,
                                        failure.getClass().getName()));
                    } catch (Throwable reportFailure) {
                        failure.addSuppressed(reportFailure);
                    }
                    if (!options.isKeepFailedShadow()) {
                        try {
                            deleteTree(staging);
                        } catch (Throwable cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                try {
                    writeAudit(audit, manifest, options, "FAILED",
                            failure.getClass().getName());
                } catch (Throwable auditFailure) {
                    failure.addSuppressed(auditFailure);
                }
            }
        }
    }

    private static ShadowRestoreResult reusePublished(Database activeDatabase,
            Path bundle, Path target, Path parent, Path audit,
            ShadowRestoreOptions options, RestoreFaultInjector faultInjector)
            throws Exception {
        Path noFollowTarget = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realTarget = target.toRealPath();
        if (!noFollowTarget.equals(realTarget)
                || !Files.isDirectory(noFollowTarget,
                        LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Existing shadow target is not a regular directory");
        }
        byte[] bundleManifest = readRegularFile(bundle.resolve(
                "manifest.json"), "Backup manifest");
        byte[] shadowManifest = readRegularFile(target.resolve(
                "backup-manifest.json"), "Published shadow manifest");
        if (!Arrays.equals(bundleManifest, shadowManifest)) {
            throw new IOException(
                    "Existing shadow target belongs to a different backup");
        }
        OnlineBackupManifest manifest;
        try {
            manifest = OnlineBackupManifestCodec.decode(bundleManifest);
        } catch (RuntimeException e) {
            throw new IOException("Backup manifest is invalid", e);
        }
        long previousValidationMillis = requireValidatedReport(
                target.resolve("validation-report.json"), manifest, options);
        long deadlineNanos = ShadowValidationOpener.deadline(
                options.getValidationTimeoutMillis());
        ShadowValidationOpener.validate(activeDatabase, target, manifest,
                options, deadlineNanos);
        String parentFsync = forceDirectory(parent, faultInjector,
                RestoreStep.PARENT_DIRECTORY_FSYNC);
        try {
            writeAudit(audit, manifest, options, "VALIDATED", parentFsync);
        } catch (IOException ignored) {
            // final 内报告和重验结果是恢复事实；旁路审计失败不回删 final。
        }
        return new ShadowRestoreResult(target,
                target.resolve("validation-report.json"), manifest,
                options.getShadowGenerationId(), previousValidationMillis);
    }

    private static byte[] readRegularFile(Path file, String description)
            throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IOException(description + " is missing");
        }
        long size = Files.size(file);
        if (size <= 0L || size > MAX_REPORT_BYTES) {
            throw new IOException(description + " size is invalid");
        }
        return Files.readAllBytes(file);
    }

    private static long requireValidatedReport(Path report,
            OnlineBackupManifest manifest, ShadowRestoreOptions options)
            throws IOException {
        JSONObject object;
        try {
            JSONValue value = JSONBytesSource.parse(
                    readRegularFile(report, "Published validation report"),
                    new JSONValueTarget());
            object = requireObject(value, "validation report");
            requireEquals("VALIDATED", requireString(object, "status"),
                    "validation status");
            requireEquals(manifest.getBackupId(),
                    requireUuid(object, "backupId"), "backupId");
            requireEquals(manifest.getCutId(), requireUuid(object, "cutId"),
                    "cutId");
            requireEquals(manifest.getDatabaseId(),
                    requireUuid(object, "databaseId"), "databaseId");
            requireEquals(options.getShadowGenerationId(),
                    requireUuid(object, "shadowGenerationId"),
                    "shadowGenerationId");
            if (manifest.getSchemaEpoch()
                    != requireLong(object, "schemaEpoch")) {
                throw new IllegalArgumentException(
                        "Validation report schemaEpoch mismatch");
            }
            JSONValue readOnlyOpen = requireMember(object, "readOnlyOpen");
            if (!(readOnlyOpen instanceof JSONBoolean)
                    || !((JSONBoolean) readOnlyOpen).getBoolean()) {
                throw new IllegalArgumentException(
                        "Validation report does not prove read-only open");
            }
            return requireLong(object, "validationMillis");
        } catch (RuntimeException e) {
            throw new IOException("Published validation report is invalid", e);
        }
    }

    private static JSONObject requireObject(JSONValue value, String name) {
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException(name + " is not an object");
        }
        JSONObject object = (JSONObject) value;
        HashSet<String> names = new HashSet<>();
        for (Map.Entry<String, JSONValue> entry : object.getMembers()) {
            if (!names.add(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Duplicate " + name + " field: " + entry.getKey());
            }
        }
        return object;
    }

    private static JSONValue requireMember(JSONObject object, String name) {
        JSONValue value = object.getFirst(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing validation report field: " + name);
        }
        return value;
    }

    private static String requireString(JSONObject object, String name) {
        JSONValue value = requireMember(object, name);
        if (!(value instanceof JSONString)) {
            throw new IllegalArgumentException(
                    "Validation report field is not a string: " + name);
        }
        return ((JSONString) value).getString();
    }

    private static UUID requireUuid(JSONObject object, String name) {
        try {
            return UUID.fromString(requireString(object, name));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Validation report UUID is invalid: " + name, e);
        }
    }

    private static long requireLong(JSONObject object, String name) {
        JSONValue value = requireMember(object, name);
        if (!(value instanceof JSONNumber)) {
            throw new IllegalArgumentException(
                    "Validation report field is not a number: " + name);
        }
        return ((JSONNumber) value).getBigDecimal().longValueExact();
    }

    private static void requireEquals(Object expected, Object actual,
            String name) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Validation report " + name + " mismatch");
        }
    }

    private static byte[] validationReport(OnlineBackupManifest manifest,
            ShadowRestoreOptions options, String status, long elapsedMillis,
            ShadowValidationOpener.ValidationSummary summary,
            String detail) {
        JSONByteArrayTarget target = new JSONByteArrayTarget();
        target.startObject();
        number(target, "formatVersion", 1L);
        string(target, "status", status);
        if (manifest != null) {
            string(target, "backupId", manifest.getBackupId().toString());
            string(target, "cutId", manifest.getCutId().toString());
            string(target, "databaseId",
                    manifest.getDatabaseId().toString());
            number(target, "schemaEpoch", manifest.getSchemaEpoch());
        }
        string(target, "shadowGenerationId",
                options.getShadowGenerationId().toString());
        target.member("readOnlyOpen");
        if ("VALIDATED".equals(status)) {
            target.valueTrue();
        } else {
            target.valueFalse();
        }
        number(target, "validationMillis", elapsedMillis);
        target.member("providers");
        target.startArray();
        if (summary != null) {
            for (String provider : summary.coreProviderKeys) {
                target.valueString(provider);
            }
        }
        target.endArray();
        number(target, "participantCount",
                summary == null ? 0L : summary.participantCount);
        if (detail != null) {
            string(target, "FAILED".equals(status)
                    ? "failureType" : "directoryFsync", detail);
        }
        string(target, "updatedAt", Instant.now().toString());
        target.endObject();
        return target.getResult();
    }

    private static void writeAudit(Path audit,
            OnlineBackupManifest manifest, ShadowRestoreOptions options,
            String status, String detail) throws IOException {
        byte[] bytes = validationReport(manifest, options, status, 0L, null,
                detail);
        Path temporary = audit.resolveSibling(
                audit.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        BackupBundleVerifier.writeForced(temporary, bytes);
        try {
            Files.move(temporary, audit, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, audit,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String forceDirectory(Path directory,
            RestoreFaultInjector faultInjector, RestoreStep step)
            throws IOException {
        try {
            faultInjector.before(step, directory);
            try (FileChannel channel = FileChannel.open(directory,
                    StandardOpenOption.READ)) {
                channel.force(true);
            }
            return "SUCCEEDED";
        } catch (AccessDeniedException e) {
            return "UNSUPPORTED";
        } catch (UnsupportedOperationException e) {
            return "UNSUPPORTED";
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root.getParent() == null) {
            throw new IOException("Refusing to delete a root path");
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory,
                    IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void string(JSONByteArrayTarget target, String name,
            String value) {
        target.member(name);
        target.valueString(value);
    }

    private static void number(JSONByteArrayTarget target, String name,
            long value) {
        target.member(name);
        target.valueNumber(BigDecimal.valueOf(value));
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos);
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw (Error) failure;
    }
}
