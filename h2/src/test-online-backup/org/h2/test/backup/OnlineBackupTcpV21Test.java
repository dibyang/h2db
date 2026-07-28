/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.api.OnlineBackupActivationHandle;
import org.h2.api.OnlineBackupControl;
import org.h2.api.OnlineBackupDescriptor;
import org.h2.api.OnlineBackupHandle;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupRestoreHandle;
import org.h2.api.OnlineBackupRestoreOptions;
import org.h2.engine.Constants;
import org.h2.engine.ConnectionInfo;
import org.h2.engine.Session;
import org.h2.engine.SessionRemote;
import org.h2.jdbc.JdbcConnection;
import org.h2.server.TcpServer;
import org.h2.tools.Server;
import org.h2.value.Transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P8 TCP v21 在线备份控制面契约测试。
 */
public class OnlineBackupTcpV21Test {

    @TempDir
    Path directory;

    /**
     * 验证远程 unwrap、prepare、publish、restore 和 activation abort。
     * <p>
     * T-H2BR-REMOTE-UNWRAP-01、
     * T-H2BR-REMOTE-PREPARE-MATERIALIZE-01、
     * T-H2BR-REMOTE-RESTORE-ACTIVATION-01。
     */
    @Test
    public void remoteEndToEndControlUsesServerRoots() throws Exception {
        TestServer testServer = startServer("end-to-end");
        try (Connection connection = testServer.connect()) {
            execute(connection,
                    "CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            execute(connection, "INSERT INTO TEST VALUES(1, 'one')");
            OnlineBackupControl control =
                    connection.unwrap(OnlineBackupControl.class);
            assertTrue(connection.isWrapperFor(OnlineBackupControl.class));

            OnlineBackupDescriptor descriptor;
            try (OnlineBackupHandle backup = control.prepareOnlineBackup(
                    options())) {
                descriptor = backup.getDescriptor();
                assertNotNull(descriptor.getBackupId());
                assertEquals("bundle-1",
                        backup.publish("bundle-1").getBundleName());
            }
            assertTrue(Files.isDirectory(
                    testServer.backupRoot.resolve("bundle-1")));

            UUID shadowGenerationId = UUID.randomUUID();
            try (OnlineBackupRestoreHandle restore =
                    control.stageAndValidateShadow("bundle-1", "shadow-1",
                            OnlineBackupRestoreOptions.defaults(
                                    shadowGenerationId))) {
                assertEquals(shadowGenerationId,
                        restore.getReport().getShadowGenerationId());
                assertEquals("shadow-1",
                        restore.getReport().getShadowName());
                assertEquals(descriptor.getCutId(),
                        restore.getReport().getCutId());
            }
            assertTrue(Files.isDirectory(
                    testServer.shadowRoot.resolve("shadow-1")));

            try (OnlineBackupActivationHandle activation =
                    control.prepareActivation(
                            descriptor.getSourceGenerationId(),
                            shadowGenerationId, 5_000L)) {
                assertEquals("PREPARED",
                        activation.getReport().getStatus());
                assertEquals("ABORTED",
                        activation.abortActivation().getStatus());
            }
            execute(connection, "INSERT INTO TEST VALUES(2, 'two')");
        } finally {
            testServer.stop();
        }
    }

    /**
     * 验证新 server 与强制协商 v21 的 client 保持旧 restore report wire 布局。
     */
    @Test
    public void v21RestoreReportOmitsCutIdWithoutCorruptingNextResponse()
            throws Exception {
        TestServer testServer = startServer("v21-restore-report");
        try (Connection connection = testServer.connectWithProtocolMax(
                Constants.TCP_PROTOCOL_VERSION_21)) {
            execute(connection,
                    "CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            execute(connection, "INSERT INTO TEST VALUES(1, 'one')");
            OnlineBackupControl control =
                    connection.unwrap(OnlineBackupControl.class);
            try (OnlineBackupHandle backup =
                    control.prepareOnlineBackup(options())) {
                backup.publish("bundle-v21");
            }

            try (OnlineBackupRestoreHandle restore =
                    control.stageAndValidateShadow(
                            "bundle-v21", "shadow-v21",
                            OnlineBackupRestoreOptions.defaults(
                                    UUID.randomUUID()))) {
                assertNull(restore.getReport().getCutId());
            }
            execute(connection, "SELECT COUNT(*) FROM TEST");
        } finally {
            testServer.stop();
        }
    }

    /**
     * 验证远程名称不能越过配置 root，连接关闭会逆序清理 prepared handle。
     * <p>
     * T-H2BR-REMOTE-PATH-ROOT-01、
     * T-H2BR-REMOTE-DISCONNECT-CLEANUP-01。
     */
    @Test
    public void pathsAreRestrictedAndConnectionCloseCleansHandles()
            throws Exception {
        TestServer testServer = startServer("path-cleanup");
        try {
            Connection first = testServer.connect();
            execute(first, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            OnlineBackupControl firstControl =
                    first.unwrap(OnlineBackupControl.class);
            OnlineBackupHandle leaked =
                    firstControl.prepareOnlineBackup(options());
            SQLException traversal = assertThrows(SQLException.class,
                    () -> leaked.publish("../outside"));
            assertEquals(ErrorCode.INVALID_VALUE_2,
                    traversal.getErrorCode());
            assertFalse(Files.exists(directory.resolve("outside")));
            first.close();

            try (Connection second = testServer.connect();
                    OnlineBackupHandle backup = second
                            .unwrap(OnlineBackupControl.class)
                            .prepareOnlineBackup(options())) {
                assertNotNull(backup.getDescriptor());
            }
        } finally {
            testServer.stop();
        }
    }

    /**
     * 验证 activation commit 后客户端立即呈现 JDBC fence 错误契约，同时
     * activation handle 仍可关闭。
     * <p>
     * T-H2BR-REMOTE-ACTIVATION-ERROR-PARITY-01、
     * T-H2BR-TCP-FENCED-HANDLE-CLOSE-01。
     */
    @Test
    public void activationCommitFencesRemoteJdbcConnection()
            throws Exception {
        TestServer testServer = startServer("fence");
        Connection connection = testServer.connect();
        try {
            execute(connection, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            OnlineBackupControl control =
                    connection.unwrap(OnlineBackupControl.class);
            UUID oldGeneration = testServer.generationId;
            OnlineBackupActivationHandle activation =
                    control.prepareActivation(oldGeneration,
                            UUID.randomUUID(), 5_000L);
            assertEquals("COMMITTED",
                    activation.commitActivation().getStatus());

            SQLException fenced = assertThrows(SQLException.class,
                    () -> execute(connection,
                            "INSERT INTO TEST VALUES(1)"));
            assertEquals(ErrorCode.GENERATION_FENCED_1,
                    fenced.getErrorCode());
            assertEquals("08006", fenced.getSQLState());
            assertTrue(fenced
                    instanceof SQLNonTransientConnectionException);
            assertTrue(connection.isClosed());
            assertFalse(connection.isValid(1));
            activation.close();
        } finally {
            connection.close();
            testServer.stop();
        }
    }

    /**
     * TCP v21 与 embedded 使用相同 quiesce、timeout vendor code、SQLState
     * 和 JDBC 异常类型。
     * <p>
     * T-H2BR-REMOTE-ACTIVATION-ERROR-PARITY-01。
     */
    @Test
    public void activationQuiesceAndTimeoutHaveRemoteErrorParity()
            throws Exception {
        TestServer testServer = startServer("activation-parity");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection active = testServer.connect();
                Connection management = testServer.connect();
                Connection rejected = testServer.connect()) {
            execute(active, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            active.setAutoCommit(false);
            execute(active, "INSERT INTO TEST VALUES(1)");
            OnlineBackupControl control =
                    management.unwrap(OnlineBackupControl.class);

            Future<OnlineBackupActivationHandle> future = executor.submit(
                    () -> control.prepareActivation(
                            testServer.generationId, UUID.randomUUID(),
                            5_000L));
            SQLException quiescing = waitForQuiescing(rejected);
            assertEquals(ErrorCode.ONLINE_BACKUP_QUIESCING_1,
                    quiescing.getErrorCode());
            assertEquals("40001", quiescing.getSQLState());
            assertTrue(quiescing
                    instanceof SQLTransactionRollbackException);
            active.commit();
            try (OnlineBackupActivationHandle activation =
                    future.get(5, TimeUnit.SECONDS)) {
                activation.abortActivation();
            }

            active.setAutoCommit(false);
            execute(active, "INSERT INTO TEST VALUES(2)");
            SQLException timeout = assertThrows(SQLException.class,
                    () -> control.prepareActivation(
                            testServer.generationId, UUID.randomUUID(),
                            30L));
            assertEquals(
                    ErrorCode.ONLINE_BACKUP_ACTIVATION_TIMEOUT_1,
                    timeout.getErrorCode());
            assertEquals("HYT00", timeout.getSQLState());
            assertTrue(timeout instanceof SQLTimeoutException);
            active.rollback();
            execute(rejected, "SELECT COUNT(*) FROM TEST");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            testServer.stop();
        }
    }

    /**
     * 验证 active handle 禁止透明 auto-reconnect，异常断链由服务端释放
     * prepared snapshot。
     * <p>
     * T-H2BR-REMOTE-AUTORECONNECT-01、
     * T-H2BR-REMOTE-DISCONNECT-CLEANUP-01。
     */
    @Test
    public void activeHandleBlocksReconnectAndAbruptDisconnectCleansUp()
            throws Exception {
        TestServer testServer = startServer("abrupt");
        Connection first = testServer.connect(true);
        try {
            execute(first, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            first.unwrap(OnlineBackupControl.class)
                    .prepareOnlineBackup(options());
            closeRemoteSocket(first);
            assertThrows(SQLException.class,
                    () -> execute(first, "SELECT 1"));

            boolean prepared = false;
            SQLException lastFailure = null;
            for (int i = 0; i < 100 && !prepared; i++) {
                try (Connection second = testServer.connect();
                        OnlineBackupHandle ignored = second
                                .unwrap(OnlineBackupControl.class)
                                .prepareOnlineBackup(options())) {
                    prepared = true;
                } catch (SQLException e) {
                    lastFailure = e;
                    Thread.sleep(20L);
                }
            }
            if (!prepared) {
                throw lastFailure;
            }
        } finally {
            try {
                first.close();
            } catch (SQLException ignored) {
                // 已主动破坏 transport，close 仍会完成本地失效。
            }
            testServer.stop();
        }
    }

    /**
     * 新客户端连接仅支持 v20 的服务端时普通 JDBC 保持可用，管理 API 在
     * 发送未知 opcode 前本地失败。
     * <p>
     * T-H2BR-REMOTE-NEW-CLIENT-OLD-SERVER-01。
     */
    @Test
    public void newClientFailsClosedAgainstV20Server() throws Exception {
        TestServer testServer = startLegacyServer("legacy");
        try (Connection connection = testServer.connect()) {
            execute(connection, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            execute(connection, "INSERT INTO TEST VALUES(1)");
            SQLException unsupported = assertThrows(SQLException.class,
                    () -> connection.unwrap(OnlineBackupControl.class)
                            .prepareOnlineBackup(options()));
            assertEquals(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    unsupported.getErrorCode());
            execute(connection, "INSERT INTO TEST VALUES(2)");
        } finally {
            testServer.stop();
        }
    }

    /**
     * v21 server 与最高只协商到 v20 的客户端保持普通 JDBC 和传统
     * {@code BACKUP TO}兼容。
     * <p>
     * T-H2BR-REMOTE-OLD-CLIENT-NEW-SERVER-01。
     */
    @Test
    public void v20ClientBehaviorRemainsCompatibleWithV21Server()
            throws Exception {
        TestServer testServer = startServer("v20-client");
        Path traditionalBackup =
                directory.resolve("traditional-backup.zip").toAbsolutePath();
        try (Connection connection = testServer.connectWithProtocolMax(
                Constants.TCP_PROTOCOL_VERSION_20)) {
            assertEquals(Constants.TCP_PROTOCOL_VERSION_20,
                    ((SessionRemote) ((JdbcConnection) connection)
                            .getSession()).getClientVersion());
            execute(connection, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            execute(connection, "INSERT INTO TEST VALUES(1)");
            execute(connection, "BACKUP TO '"
                    + traditionalBackup.toString().replace("'", "''")
                    + "'");
            assertTrue(Files.isRegularFile(traditionalBackup));
            execute(connection, "INSERT INTO TEST VALUES(2)");
        } finally {
            testServer.stop();
        }
    }

    /**
     * opaque handle 只在创建它的 TCP session 内有效。
     * <p>
     * T-H2BR-REMOTE-HANDLE-OWNERSHIP-01。
     */
    @Test
    public void handleCannotBeUsedByAnotherTcpSession() throws Exception {
        TestServer testServer = startServer("ownership");
        try (Connection owner = testServer.connect();
                Connection other = testServer.connect()) {
            execute(owner, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            SessionRemote ownerSession =
                    (SessionRemote) ((JdbcConnection) owner).getSession();
            SessionRemote otherSession =
                    (SessionRemote) ((JdbcConnection) other).getSession();
            SessionRemote.RemoteBackup backup =
                    ownerSession.prepareOnlineBackup(options());
            try {
                assertThrows(RuntimeException.class,
                        () -> otherSession.closeOnlineBackup(
                                backup.getHandleId()));
            } finally {
                ownerSession.closeOnlineBackup(backup.getHandleId());
            }
        } finally {
            testServer.stop();
        }
    }

    /**
     * 验证管理操作要求 ADMIN，明文 TCP 不接受 shadow 密码，错误响应不泄露
     * 服务端绝对 root。
     * <p>
     * T-H2BR-TCP-AUTHORIZATION-01、T-H2BR-TCP-PASSWORD-TRANSPORT-01、
     * T-H2BR-TCP-PATH-REDACTION-01。
     */
    @Test
    public void remoteManagementIsAuthorizedAndPathRedacted()
            throws Exception {
        TestServer testServer = startServer("security");
        try (Connection admin = testServer.connect()) {
            execute(admin, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            execute(admin, "CREATE USER APP PASSWORD 'secret'");
            try (Connection app = testServer.connect("app", "secret")) {
                SQLException denied = assertThrows(SQLException.class,
                        () -> app.unwrap(OnlineBackupControl.class)
                                .prepareOnlineBackup(options()));
                assertEquals(ErrorCode.ADMIN_RIGHTS_REQUIRED,
                        denied.getErrorCode());
            }

            OnlineBackupRestoreOptions passwordOptions =
                    new OnlineBackupRestoreOptions(UUID.randomUUID(),
                            Collections.<String>emptyList(),
                            Collections.<OnlineBackupRestoreOptions.ProviderSelection>
                                    emptyList(),
                            5_000L, false, "sa",
                            "secret".toCharArray(), null);
            SQLException transport = assertThrows(SQLException.class,
                    () -> admin.unwrap(OnlineBackupControl.class)
                            .stageAndValidateShadow("bundle", "shadow",
                                    passwordOptions));
            assertEquals(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    transport.getErrorCode());

            try (OnlineBackupHandle backup = admin
                    .unwrap(OnlineBackupControl.class)
                    .prepareOnlineBackup(options())) {
                Files.delete(testServer.backupRoot);
                SQLException failure = assertThrows(SQLException.class,
                        () -> backup.publish("bundle"));
                assertFalse(failure.getMessage().contains(
                        testServer.backupRoot.toString()));
            }
        } finally {
            testServer.stop();
        }
    }

    private TestServer startServer(String name) throws Exception {
        Path root = Files.createDirectories(directory.resolve(name));
        Path databaseRoot = Files.createDirectories(root.resolve("database"));
        Path backupRoot = Files.createDirectories(root.resolve("backup"));
        Path shadowRoot = Files.createDirectories(root.resolve("shadow"));
        Server server = Server.createTcpServer("-tcpPort", "0",
                "-ifNotExists", "-baseDir", databaseRoot.toString(),
                "-tcpOnlineBackupRoot", backupRoot.toString(),
                "-tcpShadowRoot", shadowRoot.toString(),
                "-tcpOnlineBackupParticipants", "").start();
        return new TestServer(server, backupRoot, shadowRoot,
                UUID.randomUUID());
    }

    private TestServer startLegacyServer(String name) throws Exception {
        Path root = Files.createDirectories(directory.resolve(name));
        Path databaseRoot = Files.createDirectories(root.resolve("database"));
        Path backupRoot = Files.createDirectories(root.resolve("backup"));
        Path shadowRoot = Files.createDirectories(root.resolve("shadow"));
        Server server = new Server(new LegacyTcpServer(), "-tcpPort", "0",
                "-ifNotExists", "-baseDir", databaseRoot.toString(),
                "-tcpOnlineBackupRoot", backupRoot.toString(),
                "-tcpShadowRoot", shadowRoot.toString(),
                "-tcpOnlineBackupParticipants", "").start();
        return new TestServer(server, backupRoot, shadowRoot,
                UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private static void closeRemoteSocket(Connection connection)
            throws Exception {
        SessionRemote remote = (SessionRemote) ((JdbcConnection) connection)
                .getSession();
        Field field = SessionRemote.class.getDeclaredField("transferList");
        field.setAccessible(true);
        ArrayList<Transfer> transfers =
                (ArrayList<Transfer>) field.get(remote);
        Socket socket = transfers.get(0).getSocket();
        socket.close();
    }

    private static OnlineBackupOptions options() {
        return new OnlineBackupOptions(Collections.<String>emptyList(),
                5_000L, 30_000L);
    }

    private static void execute(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static SQLException waitForQuiescing(Connection connection)
            throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5L);
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try {
                execute(connection, "SELECT COUNT(*) FROM TEST");
            } catch (SQLException e) {
                last = e;
                if (e.getErrorCode()
                        == ErrorCode.ONLINE_BACKUP_QUIESCING_1) {
                    return e;
                }
            }
            Thread.sleep(10L);
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("Quiescing state was not observed");
    }

    private static final class TestServer {

        final Server server;
        final Path backupRoot;
        final Path shadowRoot;
        final UUID generationId;

        TestServer(Server server, Path backupRoot, Path shadowRoot,
                UUID generationId) {
            this.server = server;
            this.backupRoot = backupRoot;
            this.shadowRoot = shadowRoot;
            this.generationId = generationId;
        }

        Connection connect() throws SQLException {
            return connect(false);
        }

        Connection connect(boolean autoReconnect) throws SQLException {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort()
                    + "/test;ONLINE_BACKUP_COORDINATION=TRUE"
                    + ";ONLINE_BACKUP_GENERATION_ID=" + generationId
                    + (autoReconnect ? ";AUTO_RECONNECT=TRUE" : "");
            return DriverManager.getConnection(url, "sa", "");
        }

        Connection connect(String user, String password)
                throws SQLException {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort()
                    + "/test";
            return DriverManager.getConnection(url, user, password);
        }

        Connection connectWithProtocolMax(int protocolVersionMax)
                throws Exception {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort()
                    + "/test;ONLINE_BACKUP_COORDINATION=TRUE"
                    + ";ONLINE_BACKUP_GENERATION_ID=" + generationId;
            ConnectionInfo connectionInfo = new ConnectionInfo(url,
                    new Properties(), "sa", "");
            SessionRemote remote = new SessionRemote(connectionInfo);
            Field field = SessionRemote.class.getDeclaredField(
                    "protocolVersionMax");
            field.setAccessible(true);
            field.setInt(remote, protocolVersionMax);
            Session connected = remote.connectEmbeddedOrServer(false);
            return new JdbcConnection(connected, "SA", url);
        }

        void stop() {
            server.stop();
        }
    }

    private static final class LegacyTcpServer extends TcpServer {

        @Override
        protected int getMaxProtocolVersion() {
            return Constants.TCP_PROTOCOL_VERSION_20;
        }
    }
}
