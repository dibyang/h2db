/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;

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
import org.h2.engine.backup.OnlineBackupBundlePublisher.PublishStep;
import org.h2.jdbc.JdbcConnection;

/**
 * 子进程故障探针：在已持久化 staging 的 atomic rename 前直接退出 JVM。
 */
public final class OnlineBackupCrashProbe {

    static final int EXIT_CODE = 23;
    private static final String PARTICIPANT_ID = "crash_probe";

    private OnlineBackupCrashProbe() {
    }

    /**
     * 创建真实 prepared participant 和完整 staging，并在 rename 前 halt。
     *
     * @param args 数据库路径和 final bundle 路径
     * @throws Exception 探针准备失败
     */
    public static void main(String... args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "database path and bundle path are required");
        }
        Path databasePath = Paths.get(args[0]);
        Path bundle = Paths.get(args[1]);
        String url = "jdbc:h2:"
                + databasePath.toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url,
                "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO TEST VALUES(1)");
            Database database = database(connection);
            database.getPluginRegistry().registerProvider(
                    "test.crash-probe", "1", new CrashProbeProvider(),
                    PluginSource.CONFIGURED_CLASS);
            OnlineBackupOptions options = new OnlineBackupOptions(
                    Collections.singletonList(PARTICIPANT_ID),
                    5_000L, 30_000L);
            try (OnlineBackupSession session =
                    OnlineBackupSession.prepare(database, options)) {
                OnlineBackupBundlePublisher.publish(session, bundle,
                        (step, path) -> {
                            if (step == PublishStep.ATOMIC_MOVE) {
                                Runtime.getRuntime().halt(EXIT_CODE);
                            }
                        });
            }
        }
        throw new AssertionError("atomic move fault point was not reached");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static final class CrashProbeProvider
            implements OnlineBackupParticipantProvider {

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return PARTICIPANT_ID;
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
                    return new PreparedParticipantMetadata(PARTICIPANT_ID,
                            "snapshot", Collections.<String, String>emptyMap());
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    try (OutputStream output =
                            target.create("participant.bin")) {
                        output.write(new byte[] { 1, 2, 3 });
                    }
                    return new MaterializedParticipantArtifact(
                            PARTICIPANT_ID,
                            Collections.singletonList("participant.bin"));
                }

                @Override
                public void abort() {
                }
            };
        }
    }
}
