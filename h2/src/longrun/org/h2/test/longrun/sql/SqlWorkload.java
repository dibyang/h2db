/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun.sql;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Random;
import java.util.zip.CRC32;
import org.h2.mvstore.MVStoreOnlineReclamationResult;
import org.h2.test.longrun.LongRunConfig;
import org.h2.test.longrun.LongRunState;
import org.h2.test.longrun.LongRunWorkload;
import org.h2.tools.Server;

/**
 * JDBC / SQL workload for the standalone long-running stress test.
 */
public final class SqlWorkload implements LongRunWorkload {

    private final LongRunConfig config;
    private final LongRunState state;
    private final Random random;
    private final File file;
    private final String fileJdbcUrl;
    private final String workloadJdbcUrl;
    private final Server tcpServer;
    private Connection connection;
    private PreparedStatement putStatement;
    private PreparedStatement getStatement;
    private PreparedStatement deleteStatement;
    private PreparedStatement existsStatement;
    private PreparedStatement ledgerStatement;
    private PreparedStatement dataCounterStatement;
    private PreparedStatement activeCounterStatement;
    private PreparedStatement incrementCounterStatement;

    public SqlWorkload(LongRunConfig config, LongRunState state) throws Exception {
        this.config = config;
        this.state = state;
        random = new Random(config.getSeed());
        file = new File(config.getWorkDir(), "sql-longrun");
        fileJdbcUrl = "jdbc:h2:" + file.getAbsolutePath() + ";MODE=REGULAR";
        if (!config.isResume()) {
            File mv = new File(file.getPath() + ".mv.db");
            if (mv.exists() && !mv.delete()) {
                throw new IllegalStateException("Could not delete old SQL longrun store: " + mv);
            }
        }
        tcpServer = startTcpServer();
        workloadJdbcUrl = "jdbc:h2:tcp://127.0.0.1:" + tcpServer.getPort() + "/" + file.getName() + ";MODE=REGULAR";
        try {
            open();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    @Override
    public void step() throws Exception {
        int operation = random.nextInt(100);
        long key = random.nextInt(config.getKeySpace());
        if (operation < 45) {
            put(key);
        } else if (operation < 80) {
            get(key);
        } else if (operation < 95) {
            remove(key);
        } else {
            rollbackProbe(key);
        }
        if (state.getOperationSequence() % 500L == 0L) {
            commit();
        }
    }

    @Override
    public void commit() throws Exception {
        connection.commit();
        state.commit();
    }

    @Override
    public void verify() throws Exception {
        long active = scalar("SELECT COUNT(*) FROM LONGRUN_DATA");
        long expected = counter("activeKeys");
        if (active != expected) {
            throw new IllegalStateException("SQL active counter mismatch: expected=" + expected + " actual=" + active);
        }
        try (Statement stat = connection.createStatement();
                ResultSet rs = stat.executeQuery("SELECT ID, PAYLOAD, CHECKSUM FROM LONGRUN_DATA")) {
            while (rs.next()) {
                long key = rs.getLong(1);
                String payload = rs.getString(2);
                long checksum = rs.getLong(3);
                if (checksum(payload) != checksum) {
                    throw new IllegalStateException("SQL checksum mismatch for key " + key);
                }
            }
        }
    }

    @Override
    public void reopenAndVerify() throws Exception {
        commit();
        closeConnection();
        open();
        verify();
    }

    @Override
    public String getJdbcUrl() {
        return fileJdbcUrl;
    }

    @Override
    public MVStoreOnlineReclamationResult runReclamation() {
        return null;
    }

    @Override
    public void close() throws Exception {
        Exception stopError = null;
        try {
            closeConnection();
        } catch (Exception e) {
            stopError = e;
        }
        try {
            if (tcpServer != null) {
                tcpServer.stop();
            }
        } catch (Exception e) {
            if (stopError == null) {
                stopError = e;
            } else {
                stopError.addSuppressed(e);
            }
        }
        if (stopError != null) {
            throw stopError;
        }
    }

    private void closeConnection() throws Exception {
        Exception stopError = null;
        try {
            closeIfNotNull(putStatement);
            closeIfNotNull(getStatement);
            closeIfNotNull(deleteStatement);
            closeIfNotNull(existsStatement);
            closeIfNotNull(ledgerStatement);
            closeIfNotNull(dataCounterStatement);
            closeIfNotNull(activeCounterStatement);
            closeIfNotNull(incrementCounterStatement);
            putStatement = null;
            getStatement = null;
            deleteStatement = null;
            existsStatement = null;
            ledgerStatement = null;
            dataCounterStatement = null;
            activeCounterStatement = null;
            incrementCounterStatement = null;
        } catch (Exception e) {
            if (stopError == null) {
                stopError = e;
            } else {
                stopError.addSuppressed(e);
            }
        }
        try {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        } catch (Exception e) {
            if (stopError == null) {
                stopError = e;
            } else {
                stopError.addSuppressed(e);
            }
        }
        if (stopError != null) {
            throw stopError;
        }
    }

    private Server startTcpServer() throws Exception {
        File baseDir = file.getParentFile();
        if (baseDir == null) {
            throw new IllegalStateException("Could not determine SQL longrun base dir: " + file);
        }
        return Server.createTcpServer(
                "-tcpPort", "0",
                "-ifNotExists",
                "-baseDir", baseDir.getAbsolutePath()).start();
    }

    private void open() throws Exception {
        connection = DriverManager.getConnection(workloadJdbcUrl);
        connection.setAutoCommit(false);
        initSchema();
        prepareStatements();
    }

    private void prepareStatements() throws Exception {
        putStatement = connection.prepareStatement("MERGE INTO LONGRUN_DATA KEY(ID) VALUES(?, ?, ?, ?)");
        getStatement = connection.prepareStatement("SELECT PAYLOAD, CHECKSUM FROM LONGRUN_DATA WHERE ID = ?");
        deleteStatement = connection.prepareStatement("DELETE FROM LONGRUN_DATA WHERE ID = ?");
        existsStatement = connection.prepareStatement("SELECT COUNT(*) FROM LONGRUN_DATA WHERE ID = ?");
        ledgerStatement = connection.prepareStatement("INSERT INTO LONGRUN_LEDGER(SEQ, OP) VALUES(?, ?)");
        activeCounterStatement = connection.prepareStatement("SELECT COUNTER_VALUE FROM LONGRUN_COUNTERS WHERE NAME = ?");
        incrementCounterStatement = connection.prepareStatement(
                "UPDATE LONGRUN_COUNTERS SET COUNTER_VALUE = COUNTER_VALUE + ? WHERE NAME = ?");
        dataCounterStatement = connection.prepareStatement("MERGE INTO LONGRUN_COUNTERS KEY(NAME) VALUES(?, ?)");
    }

    private void initSchema() throws Exception {
        try (Statement stat = connection.createStatement()) {
            stat.execute("CREATE TABLE IF NOT EXISTS LONGRUN_DATA("
                    + "ID BIGINT PRIMARY KEY, VERSION BIGINT, PAYLOAD VARCHAR, CHECKSUM BIGINT)");
            stat.execute("CREATE TABLE IF NOT EXISTS LONGRUN_LEDGER("
                    + "SEQ BIGINT PRIMARY KEY, OP VARCHAR)");
            stat.execute("CREATE TABLE IF NOT EXISTS LONGRUN_COUNTERS("
                    + "NAME VARCHAR PRIMARY KEY, COUNTER_VALUE BIGINT)");
            stat.execute("CREATE INDEX IF NOT EXISTS IDX_LONGRUN_DATA_VERSION ON LONGRUN_DATA(VERSION)");
        }
        if (!hasCounterRaw("activeKeys")) {
            setCounterRaw("activeKeys", 0L);
        }
        connection.commit();
    }

    private void put(long key) throws Exception {
        long sequence = state.nextSequence();
        boolean existed = exists(key);
        String payload = payload(key, sequence);
        putStatement.setLong(1, key);
        putStatement.setLong(2, sequence);
        putStatement.setString(3, payload);
        putStatement.setLong(4, checksum(payload));
        putStatement.executeUpdate();
        ledger(sequence, "PUT:" + key);
        if (!existed) {
            incrementCounter("activeKeys", 1L);
        }
        state.write();
    }

    private void get(long key) throws Exception {
        state.nextSequence();
        getStatement.setLong(1, key);
        try (ResultSet rs = getStatement.executeQuery()) {
            if (rs.next() && checksum(rs.getString(1)) != rs.getLong(2)) {
                throw new IllegalStateException("SQL checksum mismatch for key " + key);
            }
        }
        state.read();
    }

    private void remove(long key) throws Exception {
        long sequence = state.nextSequence();
        int removed;
        deleteStatement.setLong(1, key);
        removed = deleteStatement.executeUpdate();
        ledger(sequence, "REMOVE:" + key);
        if (removed > 0) {
            incrementCounter("activeKeys", -1L);
        }
        state.remove();
    }

    private void rollbackProbe(long key) throws Exception {
        long sequence = state.nextSequence();
        String payload = payload(key, sequence);
        Savepoint savepoint = connection.setSavepoint();
        putStatement.setLong(1, -key - 1);
        putStatement.setLong(2, sequence);
        putStatement.setString(3, payload);
        putStatement.setLong(4, checksum(payload));
        putStatement.executeUpdate();
        connection.rollback(savepoint);
        if (exists(-key - 1)) {
            throw new IllegalStateException("Rolled-back SQL row is visible: " + (-key - 1));
        }
        state.write();
    }

    private boolean exists(long key) throws Exception {
        existsStatement.setLong(1, key);
        try (ResultSet rs = existsStatement.executeQuery()) {
            rs.next();
            return rs.getLong(1) > 0L;
        }
    }

    private boolean hasCounter(String name) throws Exception {
        activeCounterStatement.setString(1, name);
        try (ResultSet rs = activeCounterStatement.executeQuery()) {
            return rs.next();
        }
    }

    private void ledger(long sequence, String operation) throws Exception {
        ledgerStatement.setLong(1, sequence);
        ledgerStatement.setString(2, operation);
        ledgerStatement.executeUpdate();
    }

    private long counter(String name) throws Exception {
        activeCounterStatement.setString(1, name);
        try (ResultSet rs = activeCounterStatement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void incrementCounter(String name, long delta) throws Exception {
        incrementCounterStatement.setLong(1, delta);
        incrementCounterStatement.setString(2, name);
        if (incrementCounterStatement.executeUpdate() == 0) {
            setCounter(name, delta);
        }
    }

    private void setCounter(String name, long value) throws Exception {
        dataCounterStatement.setString(1, name);
        dataCounterStatement.setLong(2, value);
        dataCounterStatement.executeUpdate();
    }

    private boolean hasCounterRaw(String name) throws Exception {
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT 1 FROM LONGRUN_COUNTERS WHERE NAME = ?")) {
            check.setString(1, name);
            try (ResultSet rs = check.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void setCounterRaw(String name, long value) throws Exception {
        try (PreparedStatement set = connection.prepareStatement(
                "MERGE INTO LONGRUN_COUNTERS KEY(NAME) VALUES(?, ?)")) {
            set.setString(1, name);
            set.setLong(2, value);
            set.executeUpdate();
        }
    }

    private static void closeIfNotNull(AutoCloseable closeable) throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }

    private long scalar(String sql) throws Exception {
        try (Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String payload(long key, long sequence) {
        int size = config.getValueSizeMin();
        if (config.getValueSizeMax() > config.getValueSizeMin()) {
            size += random.nextInt(config.getValueSizeMax() - config.getValueSizeMin() + 1);
        }
        StringBuilder builder = new StringBuilder(size + 64);
        builder.append(key).append(':').append(sequence).append(':');
        while (builder.length() < size) {
            builder.append((char) ('a' + random.nextInt(26)));
        }
        return builder.toString();
    }

    private static long checksum(String value) {
        CRC32 crc32 = new CRC32();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }
}
