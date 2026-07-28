/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.tx.TransactionMap;
import org.h2.mvstore.type.StringDataType;
import org.h2.tools.Recover;
import org.h2.tools.Restore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OQ-05 身份元数据内部事务型 MVStore map 的可行性原型。
 *
 * <p>这些测试只验证候选存储方案，不代表正式功能、API 或磁盘格式已经确定。</p>
 */
public class OnlineBackupIdentityMetadataPrototypeTest {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @TempDir
    Path directory;

    @AfterEach
    public void disablePrototype() {
        IdentityMetadataPrototypePlugin.disable();
    }

    /**
     * T-H2BR-IDENTITY-SPIKE-TRANSACTION-01 /
     * T-H2BR-IDENTITY-SPIKE-FAILURE-01 /
     * T-H2BR-IDENTITY-SPIKE-REOPEN-01。
     */
    @Test
    public void metadataCommitsAndRollsBackWithCatalog() throws Exception {
        String url = databaseUrl("transaction");
        Metadata committed;
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE COMMITTED_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            committed = readMetadata(connection);
        }

        assertNotNull(committed);
        UUID.fromString(committed.databaseId);
        assertEquals(1L, committed.schemaEpoch);

        try (Connection connection = connect(url)) {
            assertTableExists(connection, "COMMITTED_TABLE", true);
            assertEquals(committed, readMetadata(connection));
        }

        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(true);
            SQLException failure = assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE REJECTED_TABLE(ID INT)"));
            assertTrue(failure.getMessage().contains("Transaction event provider failed"));
            IdentityMetadataPrototypePlugin.disable();
        }

        try (Connection connection = connect(url)) {
            assertTableExists(connection, "COMMITTED_TABLE", true);
            assertTableExists(connection, "REJECTED_TABLE", false);
            assertEquals(committed, readMetadata(connection));
        }
    }

    /**
     * 记录现有 transaction event 接缝的边界：失败 DDL 也可能触发 DDL commit 事件。
     */
    @Test
    public void transactionEventCannotIdentifyUnchangedFailedDdl() throws Exception {
        String url = databaseUrl("failedDdlSignal");
        Metadata initial;
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE DUPLICATE_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            initial = readMetadata(connection);
        }
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE DUPLICATE_TABLE(ID INT)"));
            IdentityMetadataPrototypePlugin.disable();
            Metadata afterFailure = readMetadata(connection);
            assertEquals(initial.schemaEpoch + 1L, afterFailure.schemaEpoch,
                    "该结果证明正式实现需要成功 catalog 变更标记，不能直接把 isDdl 当作 epoch 条件");
        }
    }

    /**
     * 并发 DDL 必须串行形成无丢失的 epoch。
     */
    @Test
    public void concurrentDdlDoesNotLoseEpochUpdates() throws Exception {
        String url = databaseUrl("concurrent");
        int workers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (Connection keeper = connect(url)) {
            IdentityMetadataPrototypePlugin.enable(false);
            for (int i = 0; i < workers; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE CONCURRENT_" + index + "(ID INT)");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
            IdentityMetadataPrototypePlugin.disable();
            assertEquals(workers, readMetadata(keeper).schemaEpoch);
        } finally {
            IdentityMetadataPrototypePlugin.disable();
            executor.shutdownNow();
        }
    }

    /**
     * T-H2BR-IDENTITY-SPIKE-BACKUP-RESTORE-01 /
     * T-H2BR-IDENTITY-SPIKE-COMPACT-01。
     */
    @Test
    public void physicalBackupRestoreAndCompactPreserveMetadata() throws Exception {
        String sourceUrl = databaseUrl("physicalSource");
        Metadata expected;
        Path backup = directory.resolve("identity-backup.zip");
        try (Connection connection = connect(sourceUrl); Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE BACKED_UP_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            expected = readMetadata(connection);
            statement.execute("BACKUP TO '" + sqlPath(backup) + "'");
        }

        Path copiedDatabase = directory.resolve("shadowCopy.mv.db");
        Files.copy(directory.resolve("physicalSource.mv.db"), copiedDatabase);
        try (Connection connection = connect(databaseUrl("shadowCopy"))) {
            assertEquals(expected, readMetadata(connection));
            assertTableExists(connection, "BACKED_UP_TABLE", true);
        }

        Path restoreDirectory = directory.resolve("restored");
        Files.createDirectories(restoreDirectory);
        Restore.execute(backup.toString(), restoreDirectory.toString(), null);
        String restoredUrl = "jdbc:h2:" + filePath(restoreDirectory.resolve("physicalSource"));

        try (Connection connection = connect(restoredUrl)) {
            assertEquals(expected, readMetadata(connection));
            assertTableExists(connection, "BACKED_UP_TABLE", true);
        }

        try (Connection connection = connect(restoredUrl); Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN COMPACT");
        }
        try (Connection connection = connect(restoredUrl)) {
            assertEquals(expected, readMetadata(connection));
            assertTableExists(connection, "BACKED_UP_TABLE", true);
        }
    }

    /**
     * T-H2BR-IDENTITY-SPIKE-FEATURE-OFF-01 /
     * T-H2BR-IDENTITY-SPIKE-READONLY-01 /
     * T-H2BR-IDENTITY-SPIKE-ENCRYPTED-01。
     */
    @Test
    public void featureOffReadOnlyAndEncryptedModesAreIsolated() throws Exception {
        String disabledName = "featureOff";
        try (Connection connection = connect(databaseUrl(disabledName));
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE DEFAULT_STORAGE(ID INT)");
        }
        assertFalse(rawHasMetadataMap(disabledName));

        try (Connection connection = connect(databaseUrl(disabledName) + ";ACCESS_MODE_DATA=r")) {
            Database database = localDatabase(connection);
            assertTrue(database.isReadOnly());
            assertFalse(database.getStore().getMvStore().hasMap(IdentityMetadataPrototypePlugin.MAP_NAME));
        }

        String readOnlyName = "readOnlyExisting";
        Metadata readOnlyExpected;
        try (Connection connection = connect(databaseUrl(readOnlyName));
                Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE READ_ONLY_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            readOnlyExpected = readMetadata(connection);
        }
        try (Connection connection = connect(databaseUrl(readOnlyName) + ";ACCESS_MODE_DATA=r")) {
            assertTrue(localDatabase(connection).isReadOnly());
            assertEquals(readOnlyExpected, readMetadata(connection));
        }

        String encryptedUrl = databaseUrl("encrypted") + ";CIPHER=AES";
        String encryptedPassword = "filePassword userPassword";
        Metadata encryptedExpected;
        try (Connection connection = DriverManager.getConnection(encryptedUrl, USER, encryptedPassword);
                Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE ENCRYPTED_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            encryptedExpected = readMetadata(connection);
        }
        try (Connection connection = DriverManager.getConnection(encryptedUrl, USER, encryptedPassword)) {
            assertEquals(encryptedExpected, readMetadata(connection));
        }
    }

    /**
     * T-H2BR-IDENTITY-SPIKE-23X-ROUNDTRIP-01。
     *
     * <p>同时记录已知限制：旧版本执行 DDL 时不会维护新 epoch。</p>
     */
    @Test
    public void legacy23RoundTripPreservesMapButLegacyDdlLeavesEpochStale() throws Exception {
        Path legacyJar = Paths.get(System.getProperty("user.dir"), "build", "libs", "h2db-2.3.0.jar");
        Assumptions.assumeTrue(Files.isRegularFile(legacyJar), "缺少 2.3.0 兼容验证 jar");

        String name = "legacyRoundTrip";
        String url = databaseUrl(name);
        Metadata expected;
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE CURRENT_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            expected = readMetadata(connection);
        }

        runLegacy(legacyJar, url, "SELECT COUNT(*) FROM CURRENT_TABLE; SHUTDOWN COMPACT");
        try (Connection connection = connect(url)) {
            assertEquals(expected, readMetadata(connection));
        }

        runLegacy(legacyJar, url, "CREATE TABLE LEGACY_DDL(ID INT)");
        try (Connection connection = connect(url)) {
            assertTableExists(connection, "LEGACY_DDL", true);
            assertEquals(expected, readMetadata(connection),
                    "旧版本能够保留 map，但不会递增新版本定义的 schemaEpoch");
        }
    }

    /**
     * 当前 Recover 只恢复 catalog table，原型 map 需要正式实现时显式扩展。
     */
    @Test
    public void recoverScriptCurrentlyDoesNotRecreatePrototypeMap() throws Exception {
        String sourceName = "recoverSource";
        try (Connection connection = connect(databaseUrl(sourceName));
                Statement statement = connection.createStatement()) {
            IdentityMetadataPrototypePlugin.enable(false);
            statement.execute("CREATE TABLE RECOVERED_TABLE(ID INT)");
            IdentityMetadataPrototypePlugin.disable();
            assertNotNull(readMetadata(connection));
        }

        Recover.execute(directory.toString(), sourceName);
        Path script = directory.resolve(sourceName + ".h2.sql");
        assertTrue(Files.isRegularFile(script));

        String rebuiltName = "recoverRebuilt";
        try (Connection connection = connect(databaseUrl(rebuiltName));
                Statement statement = connection.createStatement()) {
            statement.execute("RUNSCRIPT FROM '" + sqlPath(script) + "'");
            assertTableExists(connection, "RECOVERED_TABLE", true);
            assertFalse(localDatabase(connection).getStore().getMvStore()
                    .hasMap(IdentityMetadataPrototypePlugin.MAP_NAME));
        }
    }

    private String databaseUrl(String name) {
        return "jdbc:h2:" + filePath(directory.resolve(name));
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, USER, PASSWORD);
    }

    private static Database localDatabase(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession()).getDatabase();
    }

    private static Metadata readMetadata(Connection connection) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) connection).getSession();
        if (!session.getDatabase().getStore().getMvStore().hasMap(IdentityMetadataPrototypePlugin.MAP_NAME)) {
            return null;
        }
        TransactionMap<String, String> map = session.getTransaction().openMap(
                IdentityMetadataPrototypePlugin.MAP_NAME, StringDataType.INSTANCE, StringDataType.INSTANCE);
        String databaseId = map.get(IdentityMetadataPrototypePlugin.DATABASE_ID);
        String epoch = map.get(IdentityMetadataPrototypePlugin.SCHEMA_EPOCH);
        session.rollback();
        return new Metadata(databaseId, Long.parseLong(epoch));
    }

    private boolean rawHasMetadataMap(String name) {
        Path file = directory.resolve(name + ".mv.db");
        try (MVStore store = new MVStore.Builder().fileName(file.toString()).readOnly().open()) {
            return store.hasMap(IdentityMetadataPrototypePlugin.MAP_NAME);
        }
    }

    private static void assertTableExists(Connection connection, String tableName, boolean expected)
            throws SQLException {
        try (ResultSet result = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '" + tableName + "'")) {
            assertTrue(result.next());
            assertEquals(expected ? 1L : 0L, result.getLong(1));
        }
    }

    private static void runLegacy(Path jar, String url, String sql) throws Exception {
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", jar.toString(), "org.h2.tools.Shell",
                "-url", url, "-user", USER, "-password", PASSWORD, "-sql", sql)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) >= 0;) {
                output.write(buffer, 0, read);
            }
        }
        int exitCode = process.waitFor();
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(0, exitCode, text);
        if (text.contains("Exception")) {
            assertTrue(sql.contains("SHUTDOWN") && text.contains("[90121-"),
                    "只允许旧 Shell 在 SHUTDOWN 后报告连接已关闭：" + text);
        }
    }

    private static String sqlPath(Path path) {
        return filePath(path).replace("'", "''");
    }

    private static String filePath(Path path) {
        return path.toAbsolutePath().toString().replace(File.separatorChar, '/');
    }

    private static final class Metadata {
        final String databaseId;
        final long schemaEpoch;

        Metadata(String databaseId, long schemaEpoch) {
            this.databaseId = databaseId;
            this.schemaEpoch = schemaEpoch;
        }

        @Override
        public int hashCode() {
            return databaseId.hashCode() * 31 + Long.hashCode(schemaEpoch);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Metadata)) {
                return false;
            }
            Metadata other = (Metadata) obj;
            return schemaEpoch == other.schemaEpoch && databaseId.equals(other.databaseId);
        }

        @Override
        public String toString() {
            return databaseId + '@' + schemaEpoch;
        }
    }
}
