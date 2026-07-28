/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;

import org.h2.api.OnlineBackupActivationHandle;
import org.h2.api.OnlineBackupControl;
import org.h2.api.OnlineBackupDescriptor;
import org.h2.api.OnlineBackupHandle;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupRestoreHandle;
import org.h2.api.OnlineBackupRestoreOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P8 embedded JDBC 在线备份控制面契约测试。
 */
public class OnlineBackupJdbcControlTest {

    @TempDir
    Path directory;

    /**
     * 本地和远程连接通过相同 unwrap API 驱动同一套 core coordinator。
     * <p>
     * T-H2BR-JDBC-CONTROL-LOCAL-01。
     */
    @Test
    public void embeddedControlUsesCallerPaths() throws Exception {
        UUID oldGeneration = UUID.randomUUID();
        Path database = directory.resolve("database");
        Path backup = Files.createDirectories(directory.resolve("backups"))
                .resolve("bundle");
        Path shadow = Files.createDirectories(directory.resolve("shadows"))
                .resolve("shadow");
        String url = "jdbc:h2:"
                + database.toAbsolutePath().toString().replace('\\', '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + oldGeneration;
        try (Connection connection =
                DriverManager.getConnection(url, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE TEST(ID INT PRIMARY KEY)");
                statement.execute("INSERT INTO TEST VALUES(1)");
            }
            OnlineBackupControl control =
                    connection.unwrap(OnlineBackupControl.class);
            OnlineBackupDescriptor descriptor;
            try (OnlineBackupHandle handle = control.prepareOnlineBackup(
                    new OnlineBackupOptions(
                            Collections.<String>emptyList(),
                            5_000L, 30_000L))) {
                descriptor = handle.getDescriptor();
                assertEquals(backup.toString(),
                        handle.publish(backup.toString()).getBundleName());
            }
            assertTrue(Files.isDirectory(backup));

            UUID shadowGeneration = UUID.randomUUID();
            try (OnlineBackupRestoreHandle restore =
                    control.stageAndValidateShadow(backup.toString(),
                            shadow.toString(),
                            OnlineBackupRestoreOptions.defaults(
                                    shadowGeneration))) {
                assertEquals(shadowGeneration,
                        restore.getReport().getShadowGenerationId());
                assertEquals(descriptor.getCutId(),
                        restore.getReport().getCutId());
            }
            assertTrue(Files.isDirectory(shadow));

            try (OnlineBackupActivationHandle activation =
                    control.prepareActivation(
                            descriptor.getSourceGenerationId(),
                            shadowGeneration, 5_000L)) {
                assertEquals("ABORTED",
                        activation.abortActivation().getStatus());
            }
        }
    }
}
