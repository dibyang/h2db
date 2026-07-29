/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.h2.api.MaterializedParticipantArtifact;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.ParticipantArtifactTarget;
import org.h2.api.PluginCapability;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.engine.Database;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.OnlineBackupBundlePublisher.PublishFaultInjector;
import org.h2.engine.backup.OnlineBackupBundlePublisher.PublishStep;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P9 bundle checksum、fsync 和 atomic rename 确定性故障矩阵。
 */
public class OnlineBackupBundleFaultMatrixTest {

    @TempDir
    Path directory;

    /**
     * T-H2BR-CRASH-MATRIX-01：最终目录可见前任一步骤失败都必须清理本次
     * staging，并保持活动数据库可写。
     */
    @Test
    public void prePublishFailuresLeaveNoFinalOrStaging() throws Exception {
        PublishStep[] steps = {
                PublishStep.CHECKSUM,
                PublishStep.ARTIFACT_FSYNC,
                PublishStep.MANIFEST_FSYNC,
                PublishStep.STAGING_DIRECTORY_FSYNC,
                PublishStep.ATOMIC_MOVE
        };
        try (Connection connection = connect("pre-publish");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            for (int i = 0; i < steps.length; i++) {
                PublishStep step = steps[i];
                Path bundle = directory.resolve(
                        "bundle-" + step.name().toLowerCase());
                try (OnlineBackupSession session =
                        OnlineBackupSession.prepare(database(connection),
                                options())) {
                    IOException failure = assertThrows(IOException.class,
                            () -> OnlineBackupBundlePublisher.publish(session,
                                    bundle, failingAt(step)));
                    assertTrue(failure.getMessage().contains(step.name()));
                }
                assertFalse(Files.exists(bundle));
                assertNoStaging(bundle);
                statement.executeUpdate("INSERT INTO TEST VALUES(" + i + ')');
            }
        }
    }

    /**
     * final rename 后 parent fsync 失败时不得删除已经发布的 bundle；同一 cut
     * 重试应收敛到幂等复用。
     */
    @Test
    public void parentFsyncFailureConvergesByIdempotentRetry()
            throws Exception {
        Path bundle = directory.resolve("bundle-parent-fsync");
        try (Connection connection = connect("parent-fsync");
                OnlineBackupSession session =
                        OnlineBackupSession.prepare(database(connection),
                                options())) {
            IOException failure = assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle,
                            failingAt(PublishStep.PARENT_DIRECTORY_FSYNC)));
            assertTrue(failure.getMessage().contains(
                    PublishStep.PARENT_DIRECTORY_FSYNC.name()));
            assertTrue(Files.isDirectory(bundle));
            assertTrue(OnlineBackupBundlePublisher.publish(session, bundle)
                    .isReused());
        }
    }

    /**
     * 已发布 artifact 被篡改后不得仅凭 manifest identity 返回幂等成功。
     */
    @Test
    public void corruptedPublishedArtifactRejectsIdempotentReuse()
            throws Exception {
        Path bundle = directory.resolve("bundle-corrupt-reuse");
        try (Connection connection = connect("corrupt-reuse");
                OnlineBackupSession session =
                        OnlineBackupSession.prepare(database(connection),
                                options())) {
            assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle,
                            failingAt(PublishStep.PARENT_DIRECTORY_FSYNC)));
            Path artifact = bundle.resolve("h2/database.mv.db");
            byte[] bytes = Files.readAllBytes(artifact);
            bytes[bytes.length - 1] ^= 1;
            Files.write(artifact, bytes);

            IOException failure = assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle));
            assertTrue(failure.getMessage().contains("checksum mismatch"));

            Files.write(artifact, new byte[] { 1 });
            failure = assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle));
            assertTrue(failure.getMessage().contains("length mismatch"));

            Files.delete(artifact);
            failure = assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle));
            assertTrue(failure.getMessage().contains("artifacts are missing"));
            assertTrue(Files.isDirectory(bundle));
        }
    }

    /**
     * final bundle 中出现 manifest 未登记文件时不得复用。
     */
    @Test
    public void unregisteredPublishedFileRejectsIdempotentReuse()
            throws Exception {
        Path bundle = directory.resolve("bundle-extra-file");
        try (Connection connection = connect("extra-file");
                OnlineBackupSession session =
                        OnlineBackupSession.prepare(database(connection),
                                options())) {
            assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle,
                            failingAt(PublishStep.PARENT_DIRECTORY_FSYNC)));
            Files.write(bundle.resolve("unexpected.bin"), new byte[] { 1 });

            IOException failure = assertThrows(IOException.class,
                    () -> OnlineBackupBundlePublisher.publish(session, bundle));
            assertTrue(failure.getMessage().contains("Unregistered file"));
            assertTrue(Files.isDirectory(bundle));
        }
    }

    /**
     * participant materialize 失败必须走与文件发布失败相同的清理路径。
     */
    @Test
    public void participantMaterializeFailureLeavesNoFinalOrStaging()
            throws Exception {
        Path bundle = directory.resolve("bundle-materialize");
        try (Connection connection = connect("materialize");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            Database database = database(connection);
            database.getPluginRegistry().registerProvider(
                    "test.materialize-failure", "1",
                    new MaterializeFailureProvider(),
                    PluginSource.CONFIGURED_CLASS);
            try (OnlineBackupSession session =
                    OnlineBackupSession.prepare(database,
                            new OnlineBackupOptions(
                                    Collections.singletonList(
                                            MaterializeFailureProvider.ID),
                                    5_000L, 30_000L))) {
                IOException failure = assertThrows(IOException.class,
                        () -> session.publish(bundle));
                assertTrue(failure.getMessage().contains(
                        "injected participant materialize failure"));
            }
            assertFalse(Files.exists(bundle));
            assertNoStaging(bundle);
            statement.executeUpdate("INSERT INTO TEST VALUES(1)");
        }
    }

    /**
     * participant 已全部物化且 staging 已 fsync 后进程直接退出，final 仍必须
     * 不可见；源库重开后可以用新任务继续备份。
     */
    @Test
    public void processExitBeforeAtomicRenameNeverPublishesStaging()
            throws Exception {
        Path databasePath = directory.resolve("crash-source");
        Path bundle = directory.resolve("bundle-crash");
        Process process = new ProcessBuilder(javaExecutable(), "-cp",
                System.getProperty("java.class.path"),
                OnlineBackupCrashProbe.class.getName(),
                databasePath.toString(), bundle.toString())
                        .redirectErrorStream(true).start();
        assertTrue(process.waitFor(15L, TimeUnit.SECONDS),
                "crash probe did not exit");
        String output = readOutput(process.getInputStream());
        assertEquals(OnlineBackupCrashProbe.EXIT_CODE, process.exitValue(),
                output);
        assertFalse(Files.exists(bundle));
        Path staging = findStaging(bundle);
        assertTrue(Files.isRegularFile(staging.resolve("manifest.json")));
        assertTrue(Files.isDirectory(staging.resolve("participants")));

        Path recoveredBundle = directory.resolve("bundle-after-crash");
        try (Connection connection = connectPath(databasePath);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO TEST VALUES(2)");
            try (OnlineBackupSession session =
                    OnlineBackupSession.prepare(database(connection),
                            options())) {
                session.publish(recoveredBundle);
            }
        }
        assertTrue(Files.isDirectory(recoveredBundle));
        assertFalse(Files.exists(bundle));
    }

    private OnlineBackupOptions options() {
        return new OnlineBackupOptions(UUID.randomUUID(),
                Collections.<String>emptyList(), 5_000L, 30_000L);
    }

    private static PublishFaultInjector failingAt(
            final PublishStep expected) {
        return new PublishFaultInjector() {
            private boolean failed;

            @Override
            public void before(PublishStep step, Path path)
                    throws IOException {
                if (!failed && step == expected) {
                    failed = true;
                    throw new IOException("injected " + step.name()
                            + " failure at " + path);
                }
            }
        };
    }

    private void assertNoStaging(Path bundle) throws Exception {
        String prefix = "." + bundle.getFileName() + ".staging-";
        try (Stream<Path> paths = Files.list(bundle.getParent())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                    .startsWith(prefix)));
        }
    }

    private Path findStaging(Path bundle) throws Exception {
        String prefix = "." + bundle.getFileName() + ".staging-";
        try (Stream<Path> paths = Files.list(bundle.getParent())) {
            return paths.filter(path -> path.getFileName().toString()
                    .startsWith(prefix)).findFirst().get();
        }
    }

    private Connection connect(String name) throws Exception {
        return connectPath(directory.resolve(name));
    }

    private Connection connectPath(Path path) throws Exception {
        String url = "jdbc:h2:" + path.toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        return DriverManager.getConnection(url, "sa", "");
    }

    private static String javaExecutable() {
        return new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name", "").startsWith("Windows")
                        ? "java.exe" : "java").getAbsolutePath();
    }

    private static String readOutput(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toString("UTF-8");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static final class MaterializeFailureProvider
            implements OnlineBackupParticipantProvider {

        static final String ID = "materialize_failure";

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return ID;
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.ONLINE_BACKUP_PREPARE.equals(capability);
        }

        @Override
        public PreparedBackupParticipant prepare(OnlineBackupContext context) {
            return new PreparedBackupParticipant() {
                @Override
                public PreparedParticipantMetadata getPreparedMetadata() {
                    return new PreparedParticipantMetadata(ID, "snapshot",
                            Collections.<String, String>emptyMap());
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    throw new IOException(
                            "injected participant materialize failure");
                }

                @Override
                public void abort() {
                }
            };
        }
    }
}
