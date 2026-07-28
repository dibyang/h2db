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
import java.util.concurrent.TimeUnit;

import org.h2.engine.Database;
import org.h2.engine.backup.OnlineBackupManifest;
import org.h2.util.json.JSONByteArrayTarget;

/**
 * 将已发布 bundle 恢复到全新 staging，完成 fail-closed validation 后再
 * 原子发布 shadow generation。
 */
public final class ShadowRestoreCoordinator {

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
        if (activeDatabase == null || backupBundle == null
                || shadowDirectory == null || options == null) {
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
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shadow target already exists");
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

        long startedNanos = System.nanoTime();
        long deadlineNanos = ShadowValidationOpener.deadline(
                options.getValidationTimeoutMillis());
        writeAudit(audit, null, options, "STAGING", null);
        OnlineBackupManifest manifest = null;
        boolean published = false;
        Throwable failure = null;
        try {
            BackupBundleVerifier.VerifiedBundle verified =
                    BackupBundleVerifier.verifyAndCopy(realBundle, staging);
            manifest = verified.manifest;
            ShadowValidationOpener.ValidationSummary summary =
                    ShadowValidationOpener.validate(activeDatabase, staging,
                            manifest, options, deadlineNanos);
            long validationMillis = elapsedMillis(startedNanos);
            Path report = staging.resolve("validation-report.json");
            BackupBundleVerifier.writeForced(report,
                    validationReport(manifest, options, "VALIDATED",
                            validationMillis, summary, null));
            forceDirectory(staging);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException(
                        "Atomic shadow publish is not supported", e);
            }
            published = true;
            String parentFsync = forceDirectory(parent);
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
                        if (!Files.exists(report,
                                LinkOption.NOFOLLOW_LINKS)) {
                            BackupBundleVerifier.writeForced(report,
                                    validationReport(manifest, options,
                                            "FAILED",
                                            elapsedMillis(startedNanos),
                                            null,
                                            failure.getClass().getName()));
                        }
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

    private static String forceDirectory(Path directory)
            throws IOException {
        try (FileChannel channel = FileChannel.open(directory,
                StandardOpenOption.READ)) {
            channel.force(true);
            return "SUCCEEDED";
        } catch (AccessDeniedException e) {
            return "UNSUPPORTED";
        } catch (UnsupportedOperationException e) {
            return "UNSUPPORTED";
        } catch (IOException e) {
            // 目录 fsync 在部分文件系统上不可用或实现不完整；它是
            // best-effort durability signal，不应推翻已完成的文件 fsync。
            return "FAILED";
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
