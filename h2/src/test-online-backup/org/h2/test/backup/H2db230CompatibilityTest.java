/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.api.OnlineBackupControl;
import org.h2.api.OnlineBackupOptions;
import org.h2.tools.Restore;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P9 已发布 2.3.0 数据文件、传统备份和 TCP 双向兼容矩阵。
 */
public class H2db230CompatibilityTest {

    @TempDir
    Path directory;

    /**
     * 2.3.0 创建的数据文件可由 2.4.x 打开和继续写入，2.3.0 的传统 zip
     * 仍可由当前 Restore 恢复。
     * <p>
     * T-H2BR-23X-DATAFILE-UPGRADE-01、
     * T-H2BR-23X-TRADITIONAL-BACKUP-01。
     */
    @Test
    public void opens230DataFileAndTraditionalBackup() throws Exception {
        Path legacyJar = legacyJar();
        Path database = directory.resolve("legacy");
        Path backup = directory.resolve("legacy-backup.zip");
        String url = "jdbc:h2:" + path(database);
        runLegacyShell(legacyJar, url,
                "CREATE TABLE TEST(ID INT PRIMARY KEY);"
                        + "INSERT INTO TEST VALUES(1),(2);"
                        + "BACKUP TO '" + sqlPath(backup) + "'");
        assertTrue(Files.isRegularFile(
                directory.resolve("legacy.mv.db")));
        assertTrue(Files.isRegularFile(backup));

        try (Connection connection =
                DriverManager.getConnection(url, "sa", "")) {
            assertEquals(2, count(connection));
            execute(connection, "INSERT INTO TEST VALUES(3)");
            assertEquals(3, count(connection));
        }

        Path restoreDirectory = Files.createDirectories(
                directory.resolve("restored"));
        Restore.execute(backup.toString(), restoreDirectory.toString(),
                null);
        try (Connection restored = DriverManager.getConnection(
                "jdbc:h2:" + path(restoreDirectory.resolve("legacy")),
                "sa", "")) {
            assertEquals(2, count(restored));
        }
    }

    /**
     * 真实 2.3.0 client 可连接 v21 server；2.4.x client 连接真实 v20
     * server 时普通 JDBC 可用，管理 API 在本地 fail-closed。
     * <p>
     * T-H2BR-23X-TCP-COMPAT-01。
     */
    @Test
    public void tcp230And24AreBidirectionallyCompatible()
            throws Exception {
        Path legacyJar = legacyJar();
        Path currentServerDirectory = Files.createDirectories(
                directory.resolve("current-server"));
        Path oldClientBackup =
                directory.resolve("old-client-backup.zip");
        Server currentServer = Server.createTcpServer("-tcpPort", "0",
                "-ifNotExists", "-baseDir",
                currentServerDirectory.toString()).start();
        try {
            String url = "jdbc:h2:tcp://localhost:"
                    + currentServer.getPort() + "/test";
            runLegacyShell(legacyJar, url,
                    "CREATE TABLE TEST(ID INT PRIMARY KEY);"
                            + "INSERT INTO TEST VALUES(1);"
                            + "BACKUP TO '" + sqlPath(oldClientBackup)
                            + "'");
            assertTrue(Files.isRegularFile(oldClientBackup));
            try (Connection currentClient =
                    DriverManager.getConnection(url, "sa", "")) {
                assertEquals(1, count(currentClient));
                execute(currentClient,
                        "INSERT INTO TEST VALUES(2)");
            }
        } finally {
            currentServer.stop();
        }

        int port = freePort();
        Path oldServerDirectory = Files.createDirectories(
                directory.resolve("old-server"));
        Path serverLog = directory.resolve("old-server.log");
        Process oldServer = startLegacyServer(legacyJar, port,
                oldServerDirectory, serverLog);
        try {
            String url = "jdbc:h2:tcp://localhost:" + port + "/test";
            try (Connection connection =
                    waitForConnection(url, oldServer, serverLog)) {
                execute(connection,
                        "CREATE TABLE TEST(ID INT PRIMARY KEY)");
                execute(connection, "INSERT INTO TEST VALUES(1)");
                SQLException unsupported =
                        assertThrows(SQLException.class,
                                () -> connection
                                        .unwrap(OnlineBackupControl.class)
                                        .prepareOnlineBackup(
                                                new OnlineBackupOptions(
                                                        Collections
                                                                .<String>
                                                                        emptyList(),
                                                        5_000L, 30_000L)));
                assertEquals(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        unsupported.getErrorCode());
                assertEquals(1, count(connection));
            }
        } finally {
            stopProcess(oldServer);
        }
    }

    private static Path legacyJar() {
        String configured = System.getProperty("h2db.compat23.jar");
        Path jar = configured != null ? Paths.get(configured)
                : Paths.get(System.getProperty("user.dir"), "build",
                        "libs", "h2db-2.3.0.jar");
        assertTrue(Files.isRegularFile(jar),
                "缺少 Gradle 解析的 2.3.0 兼容验证 jar: " + jar);
        return jar;
    }

    private static void runLegacyShell(Path jar, String url, String sql)
            throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp",
                jar.toString(), "org.h2.tools.Shell", "-url", url,
                "-user", "sa", "-password", "", "-sql", sql)
                .redirectErrorStream(true).start();
        boolean completed = process.waitFor(30L, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("2.3.0 Shell timed out");
        }
        String output = readOutput(process.getInputStream());
        assertEquals(0, process.exitValue(), output);
    }

    private static Process startLegacyServer(Path jar, int port,
            Path databaseDirectory, Path log) throws IOException {
        return new ProcessBuilder(javaExecutable(), "-cp", jar.toString(),
                "org.h2.tools.Server", "-tcp", "-tcpPort",
                Integer.toString(port), "-ifNotExists", "-baseDir",
                databaseDirectory.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    private static Connection waitForConnection(String url,
            Process server, Path log) throws Exception {
        SQLException last = null;
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(15L);
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                throw new AssertionError("2.3.0 server exited: "
                        + new String(Files.readAllBytes(log),
                                StandardCharsets.UTF_8));
            }
            try {
                return DriverManager.getConnection(url, "sa", "");
            } catch (SQLException e) {
                last = e;
                Thread.sleep(50L);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("2.3.0 server did not start");
    }

    private static void stopProcess(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(5L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5L, TimeUnit.SECONDS));
        }
        assertFalse(process.isAlive());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM TEST")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = input) {
            byte[] buffer = new byte[4096];
            for (int read; (read = stream.read(buffer)) >= 0;) {
                output.write(buffer, 0, read);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name")
                .startsWith("Windows") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin",
                executable).toString();
    }

    private static String sqlPath(Path path) {
        return path(path).replace("'", "''");
    }

    private static String path(Path path) {
        return path.toAbsolutePath().toString()
                .replace(File.separatorChar, '/');
    }
}
