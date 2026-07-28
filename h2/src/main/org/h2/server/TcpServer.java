/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.h2.api.ErrorCode;
import org.h2.engine.Constants;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.util.MathUtils;
import org.h2.util.NetUtils;
import org.h2.util.StringUtils;
import org.h2.util.Tool;
import org.h2.util.Utils10;

/**
 * The TCP server implements the native H2 database server protocol.
 * It supports multiple client connections to multiple databases
 * (many to many). The same database may be opened by multiple clients.
 * Also supported is the mixed mode: opening databases in embedded mode,
 * and at the same time start a TCP server to allow clients to connect to
 * the same database over the network.
 */
public class TcpServer implements Service {

    private static final int SHUTDOWN_NORMAL = 0;
    private static final int SHUTDOWN_FORCE = 1;

    private static final Pattern ONLINE_BACKUP_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /**
     * The name of the in-memory management database used by the TCP server
     * to keep the active sessions.
     */
    private static final String MANAGEMENT_DB_PREFIX = "management_db_";

    private static final ConcurrentHashMap<Integer, TcpServer> SERVERS = new ConcurrentHashMap<>();

    private int port;
    private boolean portIsSet;
    private boolean trace;
    private boolean ssl;
    private boolean stop;
    private ShutdownHandler shutdownHandler;
    private ServerSocket serverSocket;
    private final Set<TcpServerThread> running =
            Collections.synchronizedSet(new HashSet<TcpServerThread>());
    private String baseDir;
    private boolean allowOthers;
    private boolean isDaemon;
    private boolean ifExists = true;
    private JdbcConnection managementDb;
    private PreparedStatement managementDbAdd;
    private PreparedStatement managementDbRemove;
    private String managementPassword = "";
    private Thread listenerThread;
    private int nextThreadId;
    private String key, keyDatabase;
    private Path onlineBackupRoot;
    private Path shadowRoot;
    private Set<String> onlineBackupParticipantAllowlist =
            Collections.emptySet();

    /**
     * Get the database name of the management database.
     * The management database contains a table with active sessions (SESSIONS).
     *
     * @param port the TCP server port
     * @return the database name (usually starting with mem:)
     */
    public static String getManagementDbName(int port) {
        return "mem:" + MANAGEMENT_DB_PREFIX + port;
    }

    private void initManagementDb() throws SQLException {
        if (managementPassword.isEmpty()) {
            managementPassword = StringUtils.convertBytesToHex(MathUtils.secureRandomBytes(32));
        }
        // avoid using the driver manager
        JdbcConnection conn = new JdbcConnection("jdbc:h2:" + getManagementDbName(port), null, "", managementPassword,
                false);
        managementDb = conn;

        try (Statement stat = conn.createStatement()) {
            stat.execute("CREATE ALIAS IF NOT EXISTS STOP_SERVER FOR '" + TcpServer.class.getName() + ".stopServer'");
            stat.execute("CREATE TABLE IF NOT EXISTS SESSIONS" +
                    "(ID INT PRIMARY KEY, URL VARCHAR, `USER` VARCHAR, " +
                    "CONNECTED TIMESTAMP(9) WITH TIME ZONE)");
            managementDbAdd = conn.prepareStatement(
                    "INSERT INTO SESSIONS VALUES(?, ?, ?, CURRENT_TIMESTAMP(9))");
            managementDbRemove = conn.prepareStatement(
                    "DELETE FROM SESSIONS WHERE ID=?");
        }
        SERVERS.put(port, this);
    }

    /**
     * Shut down this server.
     */
    void shutdown() {
        if (shutdownHandler != null) {
            shutdownHandler.shutdown();
        }
    }

    public void setShutdownHandler(ShutdownHandler shutdownHandler) {
        this.shutdownHandler = shutdownHandler;
    }

    /**
     * Add a connection to the management database.
     *
     * @param id the connection id
     * @param url the database URL
     * @param user the user name
     */
    synchronized void addConnection(int id, String url, String user) {
        try {
            managementDbAdd.setInt(1, id);
            managementDbAdd.setString(2, url);
            managementDbAdd.setString(3, user);
            managementDbAdd.execute();
        } catch (SQLException e) {
            DbException.traceThrowable(e);
        }
    }

    /**
     * Remove a connection from the management database.
     *
     * @param id the connection id
     */
    synchronized void removeConnection(int id) {
        try {
            managementDbRemove.setInt(1, id);
            managementDbRemove.execute();
        } catch (SQLException e) {
            DbException.traceThrowable(e);
        }
    }

    private synchronized void stopManagementDb() {
        if (managementDb != null) {
            try {
                managementDb.close();
            } catch (SQLException e) {
                DbException.traceThrowable(e);
            }
            managementDb = null;
        }
    }

    @Override
    public void init(String... args) {
        port = Constants.DEFAULT_TCP_PORT;
        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if (Tool.isOption(a, "-trace")) {
                trace = true;
            } else if (Tool.isOption(a, "-tcpSSL")) {
                ssl = true;
            } else if (Tool.isOption(a, "-tcpPort")) {
                port = Integer.decode(args[++i]);
                portIsSet = true;
            } else if (Tool.isOption(a, "-tcpPassword")) {
                managementPassword = args[++i];
            } else if (Tool.isOption(a, "-baseDir")) {
                baseDir = args[++i];
            } else if (Tool.isOption(a, "-key")) {
                key = args[++i];
                keyDatabase = args[++i];
            } else if (Tool.isOption(a, "-tcpAllowOthers")) {
                allowOthers = true;
            } else if (Tool.isOption(a, "-tcpDaemon")) {
                isDaemon = true;
            } else if (Tool.isOption(a, "-ifExists")) {
                ifExists = true;
            } else if (Tool.isOption(a, "-ifNotExists")) {
                ifExists = false;
            } else if (Tool.isOption(a, "-tcpOnlineBackupRoot")) {
                onlineBackupRoot = requireManagementRoot(a, args[++i]);
            } else if (Tool.isOption(a, "-tcpShadowRoot")) {
                shadowRoot = requireManagementRoot(a, args[++i]);
            } else if (Tool.isOption(a,
                    "-tcpOnlineBackupParticipants")) {
                onlineBackupParticipantAllowlist =
                        parseParticipantAllowlist(args[++i]);
            }
        }
    }

    private static Path requireManagementRoot(String option, String value) {
        try {
            Path configured = Paths.get(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(configured)) {
                throw DbException.getInvalidValueException(option, value);
            }
            Path noFollow = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path real = configured.toRealPath();
            if (!noFollow.equals(real)) {
                throw DbException.getInvalidValueException(option,
                        "root must not be a link or junction");
            }
            return real;
        } catch (IOException e) {
            throw DbException.convertIOException(e, option);
        }
    }

    private static Set<String> parseParticipantAllowlist(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        HashSet<String> result = new HashSet<>();
        for (String participant : Arrays.asList(value.split(","))) {
            String id = participant.trim();
            if (id.isEmpty()) {
                throw DbException.getInvalidValueException(
                        "-tcpOnlineBackupParticipants", value);
            }
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public String getURL() {
        return (ssl ? "ssl" : "tcp") + "://" + NetUtils.getLocalAddress() + ":" + port;
    }

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Returns whether a secure protocol is used.
     *
     * @return {@code true} if SSL socket is used, {@code false} if plain socket
     *         is used
     */
    public boolean getSSL() {
        return ssl;
    }

    /**
     * Check if this socket may connect to this server. Remote connections are
     * not allowed if the flag allowOthers is set.
     *
     * @param socket the socket
     * @return true if this client may connect
     */
    boolean allow(Socket socket) {
        if (allowOthers) {
            return true;
        }
        try {
            return NetUtils.isLocalAddress(socket);
        } catch (UnknownHostException e) {
            traceError(e);
            return false;
        }
    }

    @Override
    public synchronized void start() throws SQLException {
        stop = false;
        try {
            serverSocket = NetUtils.createServerSocket(port, ssl);
        } catch (DbException e) {
            if (!portIsSet) {
                serverSocket = NetUtils.createServerSocket(0, ssl);
            } else {
                throw e;
            }
        }
        port = serverSocket.getLocalPort();
        initManagementDb();
    }

    @Override
    public void listen() {
        listenerThread = Thread.currentThread();
        String threadName = listenerThread.getName();
        try {
            while (!stop) {
                Socket s = serverSocket.accept();
                Utils10.setTcpQuickack(s, true);
                int id = nextThreadId++;
                TcpServerThread c = new TcpServerThread(s, this, id);
                running.add(c);
                Thread thread = new Thread(c, threadName + " thread-" + id);
                thread.setDaemon(isDaemon);
                c.setThread(thread);
                thread.start();
            }
            serverSocket = NetUtils.closeSilently(serverSocket);
        } catch (Exception e) {
            if (!stop) {
                DbException.traceThrowable(e);
            }
        }
        stopManagementDb();
    }

    @Override
    public synchronized boolean isRunning(boolean traceError) {
        if (serverSocket == null) {
            return false;
        }
        try {
            Socket s = NetUtils.createLoopbackSocket(port, ssl);
            s.close();
            return true;
        } catch (Exception e) {
            if (traceError) {
                traceError(e);
            }
            return false;
        }
    }

    @Override
    public void stop() {
        // TODO server: share code between web and tcp servers
        // need to remove the server first, otherwise the connection is broken
        // while the server is still registered in this map
        SERVERS.remove(port);
        if (!stop) {
            stopManagementDb();
            stop = true;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    DbException.traceThrowable(e);
                } catch (NullPointerException e) {
                    // ignore
                }
                serverSocket = null;
            }
            if (listenerThread != null) {
                try {
                    listenerThread.join(1000);
                } catch (InterruptedException e) {
                    DbException.traceThrowable(e);
                }
            }
        }
        // TODO server: using a boolean 'now' argument? a timeout?
        for (TcpServerThread c : new ArrayList<>(running)) {
            if (c != null) {
                c.close();
                try {
                    c.getThread().join(100);
                } catch (Exception e) {
                    DbException.traceThrowable(e);
                }
            }
        }
    }

    /**
     * Stop a running server. This method is called via reflection from the
     * STOP_SERVER function.
     *
     * @param port the port where the server runs, or 0 for all running servers
     * @param password the password (or null)
     * @param shutdownMode the shutdown mode, SHUTDOWN_NORMAL or SHUTDOWN_FORCE.
     */
    public static void stopServer(int port, String password, int shutdownMode) {
        if (port == 0) {
            for (int p : SERVERS.keySet().toArray(new Integer[0])) {
                if (p != 0) {
                    stopServer(p, password, shutdownMode);
                }
            }
            return;
        }
        TcpServer server = SERVERS.get(port);
        if (server == null) {
            return;
        }
        if (!server.managementPassword.equals(password)) {
            return;
        }
        if (shutdownMode == SHUTDOWN_NORMAL) {
            server.stopManagementDb();
            server.stop = true;
            try {
                Socket s = NetUtils.createLoopbackSocket(port, false);
                s.close();
            } catch (Exception e) {
                // try to connect - so that accept returns
            }
        } else if (shutdownMode == SHUTDOWN_FORCE) {
            server.stop();
        }
        server.shutdown();
    }

    /**
     * Remove a thread from the list.
     *
     * @param t the thread to remove
     */
    void remove(TcpServerThread t) {
        running.remove(t);
    }

    /**
     * Get the configured base directory.
     *
     * @return the base directory
     */
    String getBaseDir() {
        return baseDir;
    }

    /**
     * 将远程 bundle 名称解析为受控 backup root 的直接子目录。
     *
     * @param name 简单 bundle 名称
     * @return 服务端绝对路径
     */
    Path resolveOnlineBackupBundle(String name) {
        return resolveManagementName(onlineBackupRoot, name,
                "-tcpOnlineBackupRoot");
    }

    /**
     * 将远程 shadow 名称解析为受控 shadow root 的直接子目录。
     *
     * @param name 简单 shadow 名称
     * @return 服务端绝对路径
     */
    Path resolveShadow(String name) {
        return resolveManagementName(shadowRoot, name, "-tcpShadowRoot");
    }

    /**
     * 检查远程显式 participant 是否为服务端 allowlist 的子集。
     *
     * @param participantIds participant ID
     */
    void checkOnlineBackupParticipants(List<String> participantIds) {
        for (String participantId : participantIds) {
            if (!onlineBackupParticipantAllowlist.contains(participantId)) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "online backup participant is not allowed: "
                                + participantId);
            }
        }
    }

    /**
     * 从远程管理错误中移除服务端绝对目录。
     *
     * @param value 原始文本
     * @return 脱敏文本
     */
    String redactOnlineBackupPaths(String value) {
        if (value == null) {
            return null;
        }
        String redacted = redactPath(value, onlineBackupRoot,
                "<online-backup-root>");
        redacted = redactPath(redacted, shadowRoot, "<shadow-root>");
        if (baseDir != null) {
            redacted = redactPath(redacted,
                    Paths.get(baseDir).toAbsolutePath().normalize(),
                    "<database-root>");
        }
        return redacted;
    }

    private static String redactPath(String value, Path path,
            String replacement) {
        if (path == null) {
            return value;
        }
        String nativePath = path.toString();
        String redacted = value.replace(nativePath, replacement);
        String slashPath = nativePath.replace('\\', '/');
        if (!slashPath.equals(nativePath)) {
            redacted = redacted.replace(slashPath, replacement);
        }
        return redacted;
    }

    private static Path resolveManagementName(Path root, String name,
            String option) {
        if (root == null) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    option + " is not configured");
        }
        if (name == null || !ONLINE_BACKUP_NAME.matcher(name).matches()
                || ".".equals(name) || "..".equals(name)) {
            throw DbException.getInvalidValueException(
                    "online backup management name", name);
        }
        Path target = root.resolve(name).normalize();
        if (!root.equals(target.getParent()) || !target.startsWith(root)) {
            throw DbException.getInvalidValueException(
                    "online backup management name", name);
        }
        return target;
    }

    /**
     * Print a message if the trace flag is enabled.
     *
     * @param s the message
     */
    void trace(String s) {
        if (trace) {
            System.out.println(s);
        }
    }
    /**
     * Print a stack trace if the trace flag is enabled.
     *
     * @param e the exception
     */
    void traceError(Throwable e) {
        if (trace) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean getAllowOthers() {
        return allowOthers;
    }

    @Override
    public String getType() {
        return "TCP";
    }

    @Override
    public String getName() {
        return "H2 TCP Server";
    }

    boolean getIfExists() {
        return ifExists;
    }

    /**
     * 返回该服务端实例可协商的最高 TCP 协议版本。
     * 子类可降低此值用于兼容代理或协议验证。
     *
     * @return 最高 TCP 协议版本
     */
    protected int getMaxProtocolVersion() {
        return Constants.TCP_PROTOCOL_VERSION_MAX_SUPPORTED;
    }

    /**
     * Stop the TCP server with the given URL.
     *
     * @param url the database URL
     * @param password the password
     * @param force if the server should be stopped immediately
     * @param all whether all TCP servers that are running in the JVM should be
     *            stopped
     * @throws SQLException on failure
     */
    public static synchronized void shutdown(String url, String password,
            boolean force, boolean all) throws SQLException {
        try {
            int port = Constants.DEFAULT_TCP_PORT;
            int idx = url.lastIndexOf(':');
            if (idx >= 0) {
                String p = url.substring(idx + 1);
                if (StringUtils.isNumber(p)) {
                    port = Integer.decode(p);
                }
            }
            String db = getManagementDbName(port);
            for (int i = 0; i < 2; i++) {
                try (JdbcConnection conn = new JdbcConnection("jdbc:h2:" + url + '/' + db, null, "", password, true)) {
                    PreparedStatement prep = conn.prepareStatement("CALL STOP_SERVER(?, ?, ?)");
                    prep.setInt(1, all ? 0 : port);
                    prep.setString(2, password);
                    prep.setInt(3, force ? SHUTDOWN_FORCE : SHUTDOWN_NORMAL);
                    try {
                        prep.execute();
                    } catch (SQLException e) {
                        if (force) {
                            // ignore
                        } else {
                            if (e.getErrorCode() != ErrorCode.CONNECTION_BROKEN_1) {
                                throw e;
                            }
                        }
                    }
                    break;
                } catch (SQLException e) {
                    if (i == 1) {
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            throw DbException.toSQLException(e);
        }
    }

    /**
     * Cancel a running statement.
     *
     * @param sessionId the session id
     * @param statementId the statement id
     */
    void cancelStatement(String sessionId, int statementId) {
        for (TcpServerThread c : new ArrayList<>(running)) {
            if (c != null) {
                c.cancelStatement(sessionId, statementId);
            }
        }
    }

    /**
     * If no key is set, return the original database name. If a key is set,
     * check if the key matches. If yes, return the correct database name. If
     * not, throw an exception.
     *
     * @param db the key to test (or database name if no key is used)
     * @return the database name
     * @throws DbException if a key is set but doesn't match
     */
    public String checkKeyAndGetDatabaseName(String db) {
        if (key == null) {
            return db;
        }
        if (key.equals(db)) {
            return keyDatabase;
        }
        throw DbException.get(ErrorCode.WRONG_USER_OR_PASSWORD);
    }

    @Override
    public boolean isDaemon() {
        return isDaemon;
    }

}
