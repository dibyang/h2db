/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;
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
