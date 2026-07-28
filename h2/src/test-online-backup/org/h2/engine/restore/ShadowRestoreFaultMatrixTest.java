/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;

import org.h2.api.OnlineBackupOptions;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.OnlineBackupSession;
import org.h2.engine.restore.ShadowRestoreCoordinator.RestoreFaultInjector;
import org.h2.engine.restore.ShadowRestoreCoordinator.RestoreStep;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P9.2 shadow copy、fsync 和 atomic rename 确定性故障矩阵。
 */
public class ShadowRestoreFaultMatrixTest {

    @TempDir
    Path directory;

    /**
     * T-H2BR-SHADOW-FAULT-MATRIX-01：atomic move 前任一步骤失败都不能
     * 暴露 final，并应默认清理本 generation 的 staging。
     */
    @Test
    public void prePublishFailuresLeaveNoFinalOrStaging() throws Exception {
        RestoreStep[] steps = {
                RestoreStep.ARTIFACT_COPY,
                RestoreStep.ARTIFACT_FSYNC,
                RestoreStep.BACKUP_MANIFEST_FSYNC,
                RestoreStep.VALIDATION_REPORT_FSYNC,
                RestoreStep.STAGING_DIRECTORY_FSYNC,
                RestoreStep.ATOMIC_MOVE
        };
        Path bundle = directory.resolve("bundle-pre-publish");
        try (Connection active = connect("active-pre-publish");
                Statement statement = active.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            publish(database(active), bundle);
            for (int i = 0; i < steps.length; i++) {
                RestoreStep step = steps[i];
                UUID generation = UUID.randomUUID();
                Path shadow = directory.resolve(
                        "shadow-" + step.name().toLowerCase());
                IOException failure = assertThrows(IOException.class,
                        () -> ShadowRestoreCoordinator.stageAndValidate(
                                database(active), bundle, shadow,
                                ShadowRestoreOptions.defaults(generation),
                                failingAt(step)));
                assertTrue(failure.getMessage().contains(step.name()));
                assertFalse(Files.exists(shadow));
                assertFalse(Files.exists(staging(shadow, generation)));
                statement.executeUpdate(
                        "INSERT INTO TEST VALUES(" + i + ')');
            }
            assertEquals(steps.length,
                    scalar(active, "SELECT COUNT(*) FROM TEST"));
        }
    }

    /**
     * validation report 的 force 失败后即使保留 staging，也只能报告 FAILED。
     */
    @Test
    public void retainedFailureReportNeverClaimsValidated() throws Exception {
        Path bundle = directory.resolve("bundle-retained");
        Path shadow = directory.resolve("shadow-retained");
        UUID generation = UUID.randomUUID();
        try (Connection active = connect("active-retained")) {
            publish(database(active), bundle);
            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            options(generation, true),
                            failingAt(RestoreStep.VALIDATION_REPORT_FSYNC)));
            assertFalse(Files.exists(shadow));
            Path retained = staging(shadow, generation);
            assertTrue(Files.isDirectory(retained));
            String report = new String(Files.readAllBytes(retained.resolve(
                    "validation-report.json")), StandardCharsets.UTF_8);
            assertTrue(report.contains("\"status\":\"FAILED\""));
            assertFalse(report.contains("\"status\":\"VALIDATED\""));
        }
    }

    /**
     * atomic move 后 parent fsync 失败不得回删 final；相同身份重试应重验并
     * 收敛成功。
     */
    @Test
    public void parentFsyncFailureConvergesByIdempotentRetry()
            throws Exception {
        Path bundle = directory.resolve("bundle-parent-fsync");
        Path shadow = directory.resolve("shadow-parent-fsync");
        UUID generation = UUID.randomUUID();
        ShadowRestoreOptions options = options(generation, false);
        try (Connection active = connect("active-parent-fsync");
                Statement statement = active.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            statement.execute("INSERT INTO TEST VALUES(1)");
            publish(database(active), bundle);
            IOException failure = assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow, options,
                            failingAt(RestoreStep.PARENT_DIRECTORY_FSYNC)));
            assertTrue(failure.getMessage().contains(
                    RestoreStep.PARENT_DIRECTORY_FSYNC.name()));
            assertTrue(Files.isDirectory(shadow));
            assertFalse(Files.exists(staging(shadow, generation)));

            ShadowRestoreResult result =
                    ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow, options);
            assertEquals(shadow.toAbsolutePath(),
                    result.getShadowDirectory());
            statement.execute("INSERT INTO TEST VALUES(2)");
            assertEquals(2L, scalar(active,
                    "SELECT COUNT(*) FROM TEST"));
        }
    }

    /**
     * Windows 目录句柄 AccessDenied 是已知平台能力缺失，应记录
     * UNSUPPORTED 并完成已具备文件 fsync 的发布。
     */
    @Test
    public void directoryAccessDeniedIsRecordedAsUnsupported()
            throws Exception {
        Path bundle = directory.resolve("bundle-access-denied");
        Path shadow = directory.resolve("shadow-access-denied");
        UUID generation = UUID.randomUUID();
        try (Connection active = connect("active-access-denied")) {
            publish(database(active), bundle);
            ShadowRestoreResult result =
                    ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            options(generation, false),
                            directoryAccessDenied());
            assertTrue(Files.isDirectory(result.getShadowDirectory()));
            String audit = new String(Files.readAllBytes(
                    validationAudit(shadow, generation)),
                    StandardCharsets.UTF_8);
            assertTrue(audit.contains(
                    "\"directoryFsync\":\"UNSUPPORTED\""));
        }
    }

    /**
     * 幂等复用只接受完全相同的 bundle、cut、database 和 shadow generation；
     * 任一身份冲突都不得覆盖已发布 final。
     */
    @Test
    public void publishedRetryRejectsIdentityConflicts() throws Exception {
        Path bundle = directory.resolve("bundle-identity");
        Path differentBundle = directory.resolve("bundle-identity-other");
        Path shadow = directory.resolve("shadow-identity");
        UUID generation = UUID.randomUUID();
        ShadowRestoreOptions options = options(generation, false);
        try (Connection active = connect("active-identity");
                Connection foreign = connect("foreign-identity")) {
            publish(database(active), bundle);
            ShadowRestoreCoordinator.stageAndValidate(database(active),
                    bundle, shadow, options);
            byte[] report = Files.readAllBytes(
                    shadow.resolve("validation-report.json"));

            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            options(UUID.randomUUID(), false)));

            publish(database(active), differentBundle);
            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), differentBundle, shadow,
                            options));

            assertThrows(IllegalArgumentException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(foreign), bundle, shadow, options));
            assertTrue(Files.isDirectory(shadow));
            assertEquals(new String(report, StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(
                            shadow.resolve("validation-report.json")),
                            StandardCharsets.UTF_8));
        }
    }

    private void publish(Database database, Path bundle) throws Exception {
        try (OnlineBackupSession session = OnlineBackupSession.prepare(
                database, new OnlineBackupOptions(
                        Collections.<String>emptyList(), 5_000L, 30_000L))) {
            session.publish(bundle);
        }
    }

    private static ShadowRestoreOptions options(UUID generation,
            boolean keepFailed) {
        return new ShadowRestoreOptions(generation,
                Collections.<String>emptyList(),
                Collections.<ShadowRestoreOptions.ProviderSelection>emptyList(),
                30_000L, keepFailed, "sa", new char[0], null);
    }

    private static RestoreFaultInjector failingAt(
            final RestoreStep expected) {
        return new RestoreFaultInjector() {
            private boolean failed;

            @Override
            public void before(RestoreStep step, Path path)
                    throws IOException {
                if (!failed && step == expected) {
                    failed = true;
                    throw new IOException("injected " + step.name()
                            + " failure at " + path);
                }
            }
        };
    }

    private static RestoreFaultInjector directoryAccessDenied() {
        return new RestoreFaultInjector() {
            @Override
            public void before(RestoreStep step, Path path)
                    throws IOException {
                if (step == RestoreStep.STAGING_DIRECTORY_FSYNC
                        || step == RestoreStep.PARENT_DIRECTORY_FSYNC) {
                    throw new AccessDeniedException(path.toString());
                }
            }
        };
    }

    private static Path staging(Path shadow, UUID generation) {
        return shadow.resolveSibling("." + shadow.getFileName()
                + ".validation-" + generation);
    }

    private static Path validationAudit(Path shadow, UUID generation) {
        return shadow.resolveSibling("." + shadow.getFileName() + "."
                + generation + ".validation.json");
    }

    private Connection connect(String name) throws Exception {
        String url = "jdbc:h2:"
                + directory.resolve(name).toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        return DriverManager.getConnection(url, "sa", "");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static long scalar(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
