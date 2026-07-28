/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.h2.api.ErrorCode;
import org.h2.api.PluginCapability;
import org.h2.api.TransactionContext;
import org.h2.api.TransactionEventProvider;
import org.h2.engine.Database;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.DatabaseIdentityMetadata;
import org.h2.engine.backup.DatabaseIdentityMetadata.Snapshot;
import org.h2.jdbc.JdbcConnection;
import org.h2.mvstore.MVStore;
import org.h2.tools.Recover;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P2 database identity, schema epoch, and runtime generation tests.
 */
public class DatabaseIdentityMetadataTest {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @TempDir
    Path directory;

    /**
     * T-H2BR-DATABASE-ID-01 / T-H2BR-GENERATION-RUNTIME-ID-01。
     */
    @Test
    public void generationIsRequiredValidatedAndNotPersisted() throws Exception {
        String name = "generation";
        UUID firstGeneration = UUID.randomUUID();
        UUID secondGeneration = UUID.randomUUID();

        SQLException missing = assertThrows(SQLException.class,
                () -> connect(enabledUrl("missing-generation", null)));
        assertEquals(ErrorCode.INVALID_VALUE_2, missing.getErrorCode());

        SQLException disabled = assertThrows(SQLException.class,
                () -> connect(databaseUrl("disabled-generation")
                        + ";ONLINE_BACKUP_GENERATION_ID=" + firstGeneration));
        assertEquals(ErrorCode.UNSUPPORTED_SETTING_COMBINATION,
                disabled.getErrorCode());

        Snapshot initial;
        try (Connection keeper = connect(enabledUrl(name, firstGeneration))) {
            initial = requireSnapshot(keeper);
            assertEquals(firstGeneration, initial.getGenerationId());
            assertEquals(0L, initial.getSchemaEpoch());

            try (Connection inherited = connect(databaseUrl(name))) {
                assertEquals(initial.getDatabaseId(),
                        requireSnapshot(inherited).getDatabaseId());
                assertEquals(firstGeneration,
                        requireSnapshot(inherited).getGenerationId());
            }

            SQLException mismatch = assertThrows(SQLException.class,
                    () -> connect(enabledUrl(name, secondGeneration)));
            assertEquals(ErrorCode.ONLINE_BACKUP_GENERATION_MISMATCH_2,
                    mismatch.getErrorCode());
        }

        try (Connection reopened =
                connect(enabledUrl(name, secondGeneration))) {
            Snapshot current = requireSnapshot(reopened);
            assertEquals(initial.getDatabaseId(), current.getDatabaseId());
            assertEquals(secondGeneration, current.getGenerationId());
        }
    }

    /**
     * T-H2BR-SCHEMA-EPOCH-COMMIT-01 /
     * T-H2BR-SCHEMA-EPOCH-ROLLBACK-01 /
     * T-H2BR-SCHEMA-EPOCH-REOPEN-01。
     */
    @Test
    public void epochTracksOnlySuccessfulCatalogChanges() throws Exception {
        String name = "epoch";
        UUID generationId = UUID.randomUUID();
        UUID databaseId;
        long expectedEpoch;
        try (Connection connection = connect(enabledUrl(name, generationId));
                Statement statement = connection.createStatement()) {
            databaseId = requireSnapshot(connection).getDatabaseId();
            assertEquals(0L, epoch(connection));

            statement.execute("SET DEFAULT_LOCK_TIMEOUT 4321");
            long afterPersistentSetting = epoch(connection);
            assertTrue(afterPersistentSetting > 0L);
            statement.execute("SET DEFAULT_LOCK_TIMEOUT 4321");
            assertEquals(afterPersistentSetting, epoch(connection));

            statement.execute("CREATE TABLE TEST("
                    + "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)");
            long afterCreate = epoch(connection);
            assertTrue(afterCreate > afterPersistentSetting);

            statement.executeUpdate("INSERT INTO TEST DEFAULT VALUES");
            assertEquals(afterCreate, epoch(connection));

            statement.execute("CREATE TABLE IF NOT EXISTS TEST("
                    + "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)");
            assertEquals(afterCreate, epoch(connection));

            assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE TEST(ID INT)"));
            assertEquals(afterCreate, epoch(connection));

            statement.execute("EXECUTE IMMEDIATE "
                    + "'CREATE TABLE DYNAMIC_TABLE(ID INT)'");
            long afterDynamic = epoch(connection);
            assertTrue(afterDynamic > afterCreate);

            statement.execute("CREATE TABLE LIST_ONE(ID INT); "
                    + "CREATE TABLE LIST_TWO(ID INT)");
            long afterList = epoch(connection);
            assertTrue(afterList > afterDynamic);
            assertTableExists(connection, "LIST_ONE", true);
            assertTableExists(connection, "LIST_TWO", true);

            statement.execute("ALTER TABLE TEST ADD COLUMN NAME VARCHAR");
            expectedEpoch = epoch(connection);
            assertTrue(expectedEpoch > afterList);
        }

        try (Connection reopened = connect(enabledUrl(name, generationId))) {
            assertEquals(databaseId, requireSnapshot(reopened).getDatabaseId());
            assertEquals(expectedEpoch, epoch(reopened));
        }
    }

    /**
     * Failure after the metadata write and before commit must roll back both
     * catalog and epoch.
     */
    @Test
    public void epochWriteRollsBackWithCatalogTransaction() throws Exception {
        String name = "rollback";
        UUID generationId = UUID.randomUUID();
        try (Connection connection = connect(enabledUrl(name, generationId));
                Statement statement = connection.createStatement()) {
            Database database = localDatabase(connection);
            database.getPluginRegistry().registerProvider("test.rollback", "1",
                    new FailFirstDdlCommitProvider(),
                    PluginSource.CONFIGURED_CLASS);

            assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE REJECTED(ID INT)"));
            assertEquals(0L, epoch(connection));
        }
        try (Connection connection = connect(enabledUrl(name, generationId));
                Statement statement = connection.createStatement()) {
            assertEquals(0L, epoch(connection));
            assertTableExists(connection, "REJECTED", false);
            statement.execute("CREATE TABLE COMMITTED(ID INT)");
            assertEquals(1L, epoch(connection));
            assertTableExists(connection, "COMMITTED", true);
        }
    }

    /**
     * Concurrent catalog commits must not lose epoch increments.
     */
    @Test
    public void concurrentDdlProducesMonotonicEpoch() throws Exception {
        String name = "concurrent";
        UUID generationId = UUID.randomUUID();
        int workers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (Connection keeper = connect(enabledUrl(name, generationId))) {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try (Connection connection =
                            connect(enabledUrl(name, generationId));
                            Statement statement =
                                    connection.createStatement()) {
                        statement.execute("CREATE TABLE CONCURRENT_" + index
                                + "(ID INT)");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
            assertEquals(workers, epoch(keeper));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * T-H2BR-READONLY-IDENTITY-PRESENT-01 /
     * T-H2BR-READONLY-IDENTITY-MISSING-REJECT-01 /
     * T-H2BR-READONLY-NO-SIDECAR-OR-TEMP-ID-01。
     */
    @Test
    public void readOnlyLegacyDatabaseDoesNotAcquireTemporaryIdentity()
            throws Exception {
        String legacyName = "readonly-legacy";
        try (Connection connection = connect(databaseUrl(legacyName));
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE LEGACY_TABLE(ID INT)");
        }
        assertFalse(rawHasMetadataMap(legacyName));

        try (Connection readOnly = connect(enabledUrl(legacyName,
                UUID.randomUUID()) + ";ACCESS_MODE_DATA=r")) {
            DatabaseIdentityMetadata metadata =
                    localDatabase(readOnly).getOnlineBackupMetadata();
            assertNull(metadata.getSnapshot());
            org.h2.message.DbException required = assertThrows(
                    org.h2.message.DbException.class,
                    metadata::requireSnapshot);
            assertEquals(ErrorCode.ONLINE_BACKUP_IDENTITY_REQUIRED,
                    required.getErrorCode());
        }
        assertFalse(rawHasMetadataMap(legacyName));

        String identifiedName = "readonly-identified";
        UUID databaseId;
        try (Connection writable = connect(enabledUrl(identifiedName,
                UUID.randomUUID()))) {
            databaseId = requireSnapshot(writable).getDatabaseId();
        }
        UUID readOnlyGeneration = UUID.randomUUID();
        try (Connection readOnly = connect(enabledUrl(identifiedName,
                readOnlyGeneration) + ";ACCESS_MODE_DATA=r")) {
            Snapshot snapshot = requireSnapshot(readOnly);
            assertEquals(databaseId, snapshot.getDatabaseId());
            assertEquals(readOnlyGeneration, snapshot.getGenerationId());
        }
    }

    /**
     * Encryption uses the database encryption context, and clone onboarding
     * creates a new lineage.
     */
    @Test
    public void encryptionAndCloneOnboardingPreserveOwnershipRules()
            throws Exception {
        String encryptedName = "encrypted";
        UUID encryptedGeneration = UUID.randomUUID();
        String encryptedUrl = enabledUrl(encryptedName, encryptedGeneration)
                + ";CIPHER=AES";
        String encryptedPassword = "filePassword userPassword";
        UUID encryptedDatabaseId;
        try (Connection connection = DriverManager.getConnection(encryptedUrl,
                USER, encryptedPassword)) {
            encryptedDatabaseId = requireSnapshot(connection).getDatabaseId();
        }
        try (Connection connection = DriverManager.getConnection(encryptedUrl,
                USER, encryptedPassword)) {
            assertEquals(encryptedDatabaseId,
                    requireSnapshot(connection).getDatabaseId());
        }

        String sourceName = "onboarding-source";
        try (Connection source = connect(databaseUrl(sourceName));
                Statement statement = source.createStatement()) {
            statement.execute("CREATE TABLE SOURCE_TABLE(ID INT)");
        }
        Files.copy(databaseFile(sourceName), databaseFile("onboarding-a"));
        Files.copy(databaseFile(sourceName), databaseFile("onboarding-b"));

        UUID firstId;
        try (Connection first = connect(enabledUrl("onboarding-a",
                UUID.randomUUID()))) {
            firstId = requireSnapshot(first).getDatabaseId();
        }
        try (Connection second = connect(enabledUrl("onboarding-b",
                UUID.randomUUID()))) {
            assertNotEquals(firstId, requireSnapshot(second).getDatabaseId());
        }
    }

    /**
     * T-H2BR-IDENTITY-ONBOARDING-NEW-LINEAGE-01。
     */
    @Test
    public void logicalRecoverStartsANewBackupLineage() throws Exception {
        String sourceName = "recover-source";
        UUID sourceId;
        try (Connection source = connect(enabledUrl(sourceName,
                UUID.randomUUID())); Statement statement =
                        source.createStatement()) {
            statement.execute("CREATE TABLE RECOVERED_TABLE(ID INT)");
            sourceId = requireSnapshot(source).getDatabaseId();
        }
        Recover.execute(directory.toString(), sourceName);
        Path script = directory.resolve(sourceName + ".h2.sql");
        assertTrue(Files.isRegularFile(script));

        try (Connection rebuilt = connect(enabledUrl("recover-rebuilt",
                UUID.randomUUID())); Statement statement =
                        rebuilt.createStatement()) {
            statement.execute("RUNSCRIPT FROM '" + sqlPath(script) + "'");
            assertNotEquals(sourceId, requireSnapshot(rebuilt).getDatabaseId());
            assertTableExists(rebuilt, "RECOVERED_TABLE", true);
        }
    }

    /**
     * T-H2BR-METADATA-OLD-VERSION-01。
     */
    @Test
    public void legacyVersionReadOnlyOpenPreservesMetadata() throws Exception {
        Path legacyJar = Paths.get(System.getProperty("user.dir"), "build",
                "libs", "h2db-2.3.0.jar");
        Assumptions.assumeTrue(Files.isRegularFile(legacyJar),
                "缺少 2.3.0 兼容验证 jar");

        String name = "legacy-readonly";
        Snapshot expected;
        try (Connection connection = connect(enabledUrl(name,
                UUID.randomUUID())); Statement statement =
                        connection.createStatement()) {
            statement.execute("CREATE TABLE CURRENT_TABLE(ID INT)");
            expected = requireSnapshot(connection);
        }

        runLegacy(legacyJar, databaseUrl(name) + ";ACCESS_MODE_DATA=r",
                "SELECT COUNT(*) FROM CURRENT_TABLE");

        try (Connection connection = connect(enabledUrl(name,
                UUID.randomUUID()))) {
            Snapshot actual = requireSnapshot(connection);
            assertEquals(expected.getDatabaseId(), actual.getDatabaseId());
            assertEquals(expected.getSchemaEpoch(), actual.getSchemaEpoch());
        }
    }

    private String enabledUrl(String name, UUID generationId) {
        String url = databaseUrl(name) + ";ONLINE_BACKUP_COORDINATION=TRUE";
        return generationId == null ? url
                : url + ";ONLINE_BACKUP_GENERATION_ID=" + generationId;
    }

    private String databaseUrl(String name) {
        return "jdbc:h2:" + filePath(directory.resolve(name));
    }

    private Path databaseFile(String name) {
        return directory.resolve(name + ".mv.db");
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, USER, PASSWORD);
    }

    private static Database localDatabase(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static Snapshot requireSnapshot(Connection connection) {
        DatabaseIdentityMetadata metadata =
                localDatabase(connection).getOnlineBackupMetadata();
        assertNotNull(metadata);
        return metadata.requireSnapshot();
    }

    private static long epoch(Connection connection) {
        return requireSnapshot(connection).getSchemaEpoch();
    }

    private boolean rawHasMetadataMap(String name) {
        try (MVStore store = new MVStore.Builder()
                .fileName(databaseFile(name).toString()).readOnly().open()) {
            return store.hasMap(DatabaseIdentityMetadata.MAP_NAME);
        }
    }

    private static void assertTableExists(Connection connection,
            String tableName, boolean expected) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_NAME = '" + tableName + "'")) {
            assertTrue(result.next());
            assertEquals(expected ? 1L : 0L, result.getLong(1));
        }
    }

    private static void runLegacy(Path jar, String url, String sql)
            throws Exception {
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows")
                        ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", jar.toString(),
                "org.h2.tools.Shell", "-url", url, "-user", USER, "-password",
                PASSWORD, "-sql", sql).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) >= 0;) {
                output.write(buffer, 0, read);
            }
        }
        int exitCode = process.waitFor();
        assertEquals(0, exitCode,
                new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String sqlPath(Path path) {
        return filePath(path).replace("'", "''");
    }

    private static String filePath(Path path) {
        return path.toAbsolutePath().toString()
                .replace(File.separatorChar, '/');
    }

    private static final class FailFirstDdlCommitProvider
            implements TransactionEventProvider {

        private boolean fail = true;

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return "online_backup_epoch_rollback";
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.TRANSACTION_EVENTS.equals(capability);
        }

        @Override
        public void beforeCommit(TransactionContext context) {
            if (fail && context.isDdl()) {
                fail = false;
                throw new IllegalStateException(
                        "simulated failure before catalog commit");
            }
        }
    }
}
