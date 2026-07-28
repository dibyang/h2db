/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import org.h2.api.OnlineBackupOptions;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.OnlineBackupSession;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P9 Gradle 制品、运行时版本和 bundle manifest 一致性测试。
 */
public class VersionMetadataConsistencyTest {

    @TempDir
    Path directory;

    /**
     * T-H2BR-VERSION-METADATA-CONSISTENCY-01。
     */
    @Test
    public void gradleRuntimeAndManifestVersionsMatch() throws Exception {
        Properties gradle = new Properties();
        try (java.io.InputStream input = Files.newInputStream(
                Paths.get(System.getProperty("user.dir"),
                        "gradle.properties"))) {
            gradle.load(input);
        }
        String artifactVersion = gradle.getProperty("version");
        assertEquals(artifactVersion, Constants.VERSION);
        assertTrue(Constants.FULL_VERSION.startsWith(
                artifactVersion + " ("));
        assertEquals(Constants.TCP_PROTOCOL_VERSION_22,
                Constants.TCP_PROTOCOL_VERSION_MAX_SUPPORTED);

        UUID generationId = UUID.randomUUID();
        String databasePath = directory.resolve("version-database")
                .toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:" + databasePath
                        + ";ONLINE_BACKUP_COORDINATION=TRUE"
                        + ";ONLINE_BACKUP_GENERATION_ID=" + generationId,
                "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            }
            Database database = ((SessionLocal) ((JdbcConnection) connection)
                    .getSession()).getDatabase();
            Path bundle = directory.resolve("version-bundle");
            try (OnlineBackupSession backup = OnlineBackupSession.prepare(
                    database, new OnlineBackupOptions(
                            Collections.<String>emptyList(), 5_000L,
                            30_000L))) {
                backup.publish(bundle);
            }
            String manifest = new String(Files.readAllBytes(
                    bundle.resolve("manifest.json")),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"h2dbVersion\":\""
                    + artifactVersion + '"'));
        }
    }
}
