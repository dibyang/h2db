/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.api.ErrorCode;
import org.h2.api.MaterializedParticipantArtifact;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.OnlineBackupValidationContext;
import org.h2.api.ParticipantArtifactSource;
import org.h2.api.ParticipantArtifactTarget;
import org.h2.api.PluginCapability;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.api.Trigger;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.Database;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.OnlineBackupSession;
import org.h2.engine.backup.OnlineBackupManifest;
import org.h2.engine.backup.OnlineBackupManifestCodec;
import org.h2.engine.restore.ShadowRestoreCoordinator;
import org.h2.engine.restore.ShadowRestoreOptions;
import org.h2.engine.restore.ShadowRestoreResult;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVStoreBackedStorageEngine;
import org.h2.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P6 shadow staging、校验、provider 隔离和活动库不变测试。
 */
public class ShadowRestoreCoordinatorTest {

    @TempDir
    Path directory;

    /**
     * H2-only bundle 可恢复、只读试打开并在成功后原子发布。
     */
    @Test
    public void restoresAndValidatesH2OnlyBundle() throws Exception {
        Path bundle = directory.resolve("bundle-h2");
        Path shadow = directory.resolve("shadow-h2");
        UUID shadowGeneration = UUID.randomUUID();
        try (Connection active = connect("active-h2")) {
            execute(active, "CREATE TABLE TEST(ID INT PRIMARY KEY, V VARCHAR)");
            execute(active, "INSERT INTO TEST VALUES(1, 'ok')");
            publish(database(active), bundle,
                    Collections.<String>emptyList());

            ShadowRestoreResult result =
                    ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            ShadowRestoreOptions.defaults(shadowGeneration));
            assertEquals(shadow.toAbsolutePath(),
                    result.getShadowDirectory());
            assertEquals(shadowGeneration, result.getShadowGenerationId());
            assertTrue(Files.isRegularFile(result.getValidationReport()));
            assertTrue(new String(Files.readAllBytes(
                    result.getValidationReport()), StandardCharsets.UTF_8)
                            .contains("\"status\":\"VALIDATED\""));
            assertEquals(1, scalar(active, "SELECT COUNT(*) FROM TEST"));
            assertShadowData(shadow, shadowGeneration);
        }
    }

    /**
     * 加密库沿用调用方密钥试打开，manifest 和报告不写入密钥；错误密钥不会
     * 发布 shadow。
     */
    @Test
    public void validatesEncryptedShadowWithoutPersistingKey()
            throws Exception {
        Path bundle = directory.resolve("bundle-encrypted");
        Path failedShadow = directory.resolve("shadow-encrypted-failed");
        String password = "file-secret user-secret";
        try (Connection active = connectEncrypted("active-encrypted",
                password)) {
            execute(active, "CREATE TABLE TEST(ID INT)");
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            String manifest = new String(Files.readAllBytes(
                    bundle.resolve("manifest.json")),
                    StandardCharsets.UTF_8);
            assertFalse(manifest.contains("file-secret"));
            assertFalse(manifest.contains("user-secret"));

            ShadowRestoreOptions wrong = new ShadowRestoreOptions(
                    UUID.randomUUID(), Collections.<String>emptyList(),
                    Collections.<ShadowRestoreOptions.ProviderSelection>
                            emptyList(),
                    30_000L, false, "sa",
                    "wrong user-secret".toCharArray(), "AES");
            assertThrows(Exception.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, failedShadow, wrong));
            assertFalse(Files.exists(failedShadow));

            ShadowRestoreOptions correct = new ShadowRestoreOptions(
                    UUID.randomUUID(), Collections.<String>emptyList(),
                    Collections.<ShadowRestoreOptions.ProviderSelection>
                            emptyList(),
                    30_000L, false, "sa", password.toCharArray(), "AES");
            ShadowRestoreResult result =
                    ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle,
                            directory.resolve("shadow-encrypted"), correct);
            assertTrue(Files.isDirectory(result.getShadowDirectory()));
            assertFalse(new String(Files.readAllBytes(
                    result.getValidationReport()), StandardCharsets.UTF_8)
                            .contains("file-secret"));
        }
    }

    /**
     * checksum 失败不创建 final，且活动数据库继续可写。
     */
    @Test
    public void checksumFailureLeavesActiveDatabaseUnchanged()
            throws Exception {
        Path bundle = directory.resolve("bundle-checksum");
        Path shadow = directory.resolve("shadow-checksum");
        try (Connection active = connect("active-checksum")) {
            execute(active, "CREATE TABLE TEST(ID INT)");
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            Path artifact = bundle.resolve("h2/database.mv.db");
            byte[] bytes = Files.readAllBytes(artifact);
            bytes[bytes.length - 1] ^= 1;
            Files.write(artifact, bytes);

            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            ShadowRestoreOptions.defaults(
                                    UUID.randomUUID())));
            assertFalse(Files.exists(shadow));
            execute(active, "INSERT INTO TEST VALUES(1)");
            assertEquals(1, scalar(active, "SELECT COUNT(*) FROM TEST"));
        }
    }

    /**
     * manifest 中的 traversal path 在创建 staging artifact 前被拒绝。
     */
    @Test
    public void rejectsManifestPathTraversal() throws Exception {
        Path bundle = directory.resolve("bundle-traversal");
        try (Connection active = connect("active-traversal")) {
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            Path manifest = bundle.resolve("manifest.json");
            String json = new String(Files.readAllBytes(manifest),
                    StandardCharsets.UTF_8).replace(
                            "h2/database.mv.db", "../database.mv.db");
            Files.write(manifest, json.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle,
                            directory.resolve("shadow-traversal"),
                            ShadowRestoreOptions.defaults(
                                    UUID.randomUUID())));
        }
    }

    /**
     * bundle 中任何 symlink 都被拒绝，不能借此读取 root 外文件。
     */
    @Test
    public void rejectsSymbolicLinkArtifact() throws Exception {
        Path bundle = directory.resolve("bundle-symlink");
        try (Connection active = connect("active-symlink")) {
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            Path linkedBundle = directory.resolve("bundle-link");
            createDirectoryLink(linkedBundle, bundle);
            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), linkedBundle,
                            directory.resolve("shadow-symlink"),
                            ShadowRestoreOptions.defaults(
                                    UUID.randomUUID())));
        }
    }

    /**
     * 目标已存在时不覆盖任何文件。
     */
    @Test
    public void neverOverwritesExistingShadow() throws Exception {
        Path bundle = directory.resolve("bundle-existing");
        Path shadow = Files.createDirectory(
                directory.resolve("shadow-existing"));
        Path sentinel = shadow.resolve("sentinel");
        Files.write(sentinel, new byte[] { 7 });
        try (Connection active = connect("active-existing")) {
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            assertThrows(IOException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            ShadowRestoreOptions.defaults(
                                    UUID.randomUUID())));
            assertEquals(7, Files.readAllBytes(sentinel)[0]);
        }
    }

    /**
     * 其它逻辑数据库的备份不能恢复为当前活动数据库的 shadow。
     */
    @Test
    public void rejectsDatabaseIdentityMismatch() throws Exception {
        Path bundle = directory.resolve("bundle-foreign");
        try (Connection source = connect("source-foreign");
                Connection active = connect("active-local")) {
            publish(database(source), bundle,
                    Collections.<String>emptyList());
            UUID activeId = database(active).getOnlineBackupMetadata()
                    .requireSnapshot().getDatabaseId();
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle,
                            directory.resolve("shadow-foreign"),
                            ShadowRestoreOptions.defaults(
                                    UUID.randomUUID())));
            assertTrue(failure.getMessage().contains("databaseId"));
            assertEquals(activeId, database(active).getOnlineBackupMetadata()
                    .requireSnapshot().getDatabaseId());
        }
    }

    /**
     * participant 必须同时在 allowlist 中、版本匹配并声明 validation
     * capability；artifact 只能通过受限只读 source 访问。
     */
    @Test
    public void participantValidationIsAllowlistedAndRestricted()
            throws Exception {
        Path bundle = directory.resolve("bundle-participant");
        ValidatingProvider provider = new ValidatingProvider("external",
                true, false);
        try (Connection active = connect("active-participant")) {
            register(database(active), provider);
            publish(database(active), bundle,
                    Collections.singletonList("external"));

            DbException notAllowed = assertThrows(DbException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle,
                            directory.resolve("shadow-not-allowed"),
                            ShadowRestoreOptions.defaults(
                                    UUID.randomUUID())));
            assertEquals(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                    notAllowed.getErrorCode());

            UUID shadowGeneration = UUID.randomUUID();
            ShadowRestoreOptions options = options(shadowGeneration,
                    Collections.singletonList("external"), false);
            ShadowRestoreCoordinator.stageAndValidate(database(active),
                    bundle, directory.resolve("shadow-participant"), options);
            assertEquals(1, provider.validationCount.get());
            assertEquals(shadowGeneration,
                    provider.lastContext.getShadowGenerationId());
            assertNotEquals(provider.lastContext.getSourceGenerationId(),
                    provider.lastContext.getShadowGenerationId());
        }
    }

    /**
     * 旧 participant 即使被 allowlist，也因未声明 capability 而 fail-closed。
     */
    @Test
    public void rejectsLegacyParticipantWithoutValidationCapability()
            throws Exception {
        Path bundle = directory.resolve("bundle-legacy");
        try (Connection active = connect("active-legacy")) {
            register(database(active), new ValidatingProvider(
                    "legacy", false, false));
            publish(database(active), bundle,
                    Collections.singletonList("legacy"));
            DbException failure = assertThrows(DbException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle,
                            directory.resolve("shadow-legacy"),
                            options(UUID.randomUUID(),
                                    Collections.singletonList("legacy"),
                                    false)));
            assertEquals(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                    failure.getErrorCode());
        }
    }

    /**
     * catalog 实际使用的 custom table provider 必须由启动描述显式选择且
     * 声明 validation.open；缺失时不回退到反射类名加载。
     */
    @Test
    public void additionalRequiredProviderIsCapabilityCheckedAndAllowlisted()
            throws Exception {
        Path bundle = directory.resolve("bundle-table-provider");
        SafeTableProvider provider = new SafeTableProvider("SAFE_TABLE");
        SafeTableProvider unsafe = new SafeTableProvider("UNSAFE_TABLE");
        unsafe.validationCapable = false;
        try (Connection active = connect("active-table-provider")) {
            database(active).getPluginRegistry().registerProvider(
                    "test.safe-table", "1", provider,
                    PluginSource.CONFIGURED_CLASS);
            database(active).getPluginRegistry().registerProvider(
                    "test.unsafe-table", "1", unsafe,
                    PluginSource.CONFIGURED_CLASS);
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            OnlineBackupManifest manifest = OnlineBackupManifestCodec.decode(
                    Files.readAllBytes(bundle.resolve("manifest.json")));
            assertEquals(2, manifest.getRequiredProviders().size());

            DbException missing = assertThrows(DbException.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle,
                            directory.resolve("shadow-table-missing"),
                            new ShadowRestoreOptions(UUID.randomUUID(),
                                    Collections.<String>emptyList(),
                                    Collections.singletonList(
                                            new ShadowRestoreOptions
                                                    .ProviderSelection(
                                                            TableEngineProvider
                                                                    .TYPE,
                                                            "UNSAFE_TABLE")),
                                    30_000L, false, "sa", new char[0],
                                    null)));
            assertEquals(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                    missing.getErrorCode());

            ShadowRestoreOptions options = new ShadowRestoreOptions(
                    UUID.randomUUID(), Collections.<String>emptyList(),
                    Collections.singletonList(
                            new ShadowRestoreOptions.ProviderSelection(
                                    TableEngineProvider.TYPE, "SAFE_TABLE")),
                    30_000L, false, "sa", new char[0], null);
            ShadowRestoreCoordinator.stageAndValidate(database(active),
                    bundle, directory.resolve("shadow-table-ok"), options);
            assertTrue(provider.validationChecks.get() > 0);
        }
    }

    /**
     * ONLINE_BACKUP_VALIDATION 不能由普通 JDBC URL 绕过 coordinator 的
     * 一次性 provider 上下文。
     */
    @Test
    public void validationModeCannotBeEnabledDirectly() throws Exception {
        Path bundle = directory.resolve("bundle-direct-validation");
        try (Connection active = connect("active-direct-validation")) {
            publish(database(active), bundle,
                    Collections.<String>emptyList());
            String url = "jdbc:h2:"
                    + bundle.resolve("h2/database").toAbsolutePath()
                            .toString().replace(File.separatorChar, '/')
                    + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE"
                    + ";ONLINE_BACKUP_COORDINATION=TRUE"
                    + ";ONLINE_BACKUP_VALIDATION=TRUE"
                    + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
            SQLException failure = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(url, "sa", ""));
            assertEquals(ErrorCode.UNVALIDATABLE_PROVIDER_1,
                    failure.getErrorCode());
        }
    }

    /**
     * validation open 不初始化或关闭 catalog 中的业务 trigger。
     */
    @Test
    public void validationOpenDoesNotRunTriggerLifecycle() throws Exception {
        Path bundle = directory.resolve("bundle-trigger");
        SideEffectTrigger.reset();
        try (Connection active = connect("active-trigger")) {
            execute(active, "CREATE TABLE TEST(ID INT)");
            execute(active, "CREATE TRIGGER TRG BEFORE INSERT ON TEST CALL '"
                    + SideEffectTrigger.class.getName() + "'");
            SideEffectTrigger.reset();
            publish(database(active), bundle,
                    Collections.<String>emptyList());

            ShadowRestoreCoordinator.stageAndValidate(database(active),
                    bundle, directory.resolve("shadow-trigger"),
                    ShadowRestoreOptions.defaults(UUID.randomUUID()));
            assertEquals(0, SideEffectTrigger.initCount.get());
            assertEquals(0, SideEffectTrigger.closeCount.get());
        }
    }

    /**
     * validation 失败时可按选项保留 staging 和 FAILED report，但 final
     * 始终不可见。
     */
    @Test
    public void canRetainFailedShadowForDiagnostics() throws Exception {
        Path bundle = directory.resolve("bundle-retained");
        Path shadow = directory.resolve("shadow-retained");
        UUID generation = UUID.randomUUID();
        ValidatingProvider provider = new ValidatingProvider("failing",
                true, true);
        try (Connection active = connect("active-retained")) {
            register(database(active), provider);
            publish(database(active), bundle,
                    Collections.singletonList("failing"));
            assertThrows(Exception.class,
                    () -> ShadowRestoreCoordinator.stageAndValidate(
                            database(active), bundle, shadow,
                            options(generation,
                                    Collections.singletonList("failing"),
                                    true)));
            assertFalse(Files.exists(shadow));
            Path retained = directory.resolve(
                    ".shadow-retained.validation-" + generation);
            assertTrue(Files.isDirectory(retained));
            assertTrue(new String(Files.readAllBytes(retained.resolve(
                    "validation-report.json")), StandardCharsets.UTF_8)
                            .contains("\"status\":\"FAILED\""));
        }
    }

    private void publish(Database database, Path bundle,
            List<String> participants) throws Exception {
        try (OnlineBackupSession session = OnlineBackupSession.prepare(
                database, new OnlineBackupOptions(participants,
                        5_000L, 30_000L))) {
            session.publish(bundle);
        }
    }

    private ShadowRestoreOptions options(UUID generation,
            List<String> participants, boolean keepFailed) {
        return new ShadowRestoreOptions(generation, participants,
                Collections.<ShadowRestoreOptions.ProviderSelection>emptyList(),
                30_000L, keepFailed, "sa", new char[0], null);
    }

    private static void register(Database database,
            OnlineBackupParticipantProvider provider) {
        database.getPluginRegistry().registerProvider(
                "test." + provider.getId(), "1", provider,
                PluginSource.CONFIGURED_CLASS);
    }

    private Connection connect(String name) throws Exception {
        String url = "jdbc:h2:"
                + directory.resolve(name).toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        return DriverManager.getConnection(url, "sa", "");
    }

    private Connection connectEncrypted(String name, String password)
            throws Exception {
        String url = "jdbc:h2:"
                + directory.resolve(name).toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";CIPHER=AES;ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        return DriverManager.getConnection(url, "sa", password);
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static void execute(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void createDirectoryLink(Path link, Path target)
            throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink",
                    "/J", link.toString(), target.toString()).start();
            if (process.waitFor() != 0) {
                throw new IOException("Unable to create test junction");
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private static void assertShadowData(Path shadow, UUID generation)
            throws Exception {
        String url = "jdbc:h2:"
                + shadow.resolve("h2/database").toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE"
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + generation;
        try (Connection connection = DriverManager.getConnection(
                url, "sa", "")) {
            assertTrue(connection.isReadOnly());
            assertEquals(1, scalar(connection,
                    "SELECT COUNT(*) FROM TEST"));
        }
    }

    /**
     * 用于证明 shadow validation 不运行普通 trigger lifecycle。
     */
    public static final class SideEffectTrigger implements Trigger {

        static final AtomicInteger initCount = new AtomicInteger();
        static final AtomicInteger closeCount = new AtomicInteger();

        static void reset() {
            initCount.set(0);
            closeCount.set(0);
        }

        @Override
        public void init(Connection connection, String schemaName,
                String triggerName, String tableName, boolean before,
                int type) {
            initCount.incrementAndGet();
        }

        @Override
        public void fire(Connection connection, Object[] oldRow,
                Object[] newRow) {
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class ValidatingProvider
            implements OnlineBackupParticipantProvider {

        private final String id;
        private final boolean validationCapable;
        private final boolean failValidation;
        final AtomicInteger validationCount = new AtomicInteger();
        OnlineBackupValidationContext lastContext;

        ValidatingProvider(String id, boolean validationCapable,
                boolean failValidation) {
            this.id = id;
            this.validationCapable = validationCapable;
            this.failValidation = failValidation;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.ONLINE_BACKUP_PREPARE.equals(capability)
                    || validationCapable
                            && PluginCapability.ONLINE_BACKUP_VALIDATE.equals(
                                    capability);
        }

        @Override
        public PreparedBackupParticipant prepare(
                OnlineBackupContext context) {
            return new PreparedBackupParticipant() {
                @Override
                public PreparedParticipantMetadata getPreparedMetadata() {
                    return new PreparedParticipantMetadata(id,
                            "snapshot-" + id,
                            Collections.singletonMap("cut", "stable"));
                }

                @Override
                public MaterializedParticipantArtifact materialize(
                        ParticipantArtifactTarget target) throws Exception {
                    try (OutputStream output = target.create("data.bin")) {
                        output.write(new byte[] { 1, 2, 3 });
                    }
                    return new MaterializedParticipantArtifact(id,
                            Collections.singletonList("data.bin"));
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public void validateRestore(OnlineBackupValidationContext context,
                PreparedParticipantMetadata metadata,
                ParticipantArtifactSource artifacts) throws Exception {
            validationCount.incrementAndGet();
            lastContext = context;
            assertEquals(id, metadata.getParticipantId());
            assertEquals(Collections.singletonList("data.bin"),
                    artifacts.getRelativePaths());
            assertThrows(IOException.class,
                    () -> artifacts.open("../data.bin"));
            try (InputStream input = artifacts.open("data.bin")) {
                assertEquals(1, input.read());
                assertEquals(2, input.read());
                assertEquals(3, input.read());
                assertEquals(-1, input.read());
            }
            if (failValidation) {
                throw new Exception("participant validation failed");
            }
        }
    }

    private static final class SafeTableProvider
            implements TableEngineProvider {

        private final String id;
        final AtomicInteger createCount = new AtomicInteger();
        final AtomicInteger validationChecks = new AtomicInteger();
        boolean validationCapable = true;

        SafeTableProvider(String id) {
            this.id = id;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean supports(String capability) {
            if (PluginCapability.VALIDATION_OPEN.equals(capability)) {
                validationChecks.incrementAndGet();
                return validationCapable;
            }
            return PluginCapability.TABLE_CREATE.equals(capability);
        }

        @Override
        public Table createTable(CreateTableData data,
                TableEngineContext context) {
            createCount.incrementAndGet();
            return ((MVStoreBackedStorageEngine) context.getStorageEngine())
                    .getStore().createTable(data);
        }
    }
}
