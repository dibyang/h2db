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

/**
 * JDBC / SQL workload for the standalone long-running stress test.
 */
public final class SqlWorkload implements LongRunWorkload {

    private final LongRunConfig config;
    private final LongRunState state;
    private final Random random;
    private final File file;
    private Connection connection;

    public SqlWorkload(LongRunConfig config, LongRunState state) throws Exception {
        this.config = config;
        this.state = state;
        random = new Random(config.getSeed());
        file = new File(config.getWorkDir(), "sql-longrun");
        if (!config.isResume()) {
            File mv = new File(file.getPath() + ".mv.db");
            if (mv.exists() && !mv.delete()) {
                throw new IllegalStateException("Could not delete old SQL longrun store: " + mv);
            }
        }
        open();
        initSchema();
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
        close();
        open();
        verify();
    }

    @Override
    public MVStoreOnlineReclamationResult runReclamation() {
        return null;
    }

    @Override
    public void close() throws Exception {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:" + file.getAbsolutePath() + ";MODE=REGULAR");
        connection.setAutoCommit(false);
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
        if (!hasCounter("activeKeys")) {
            setCounter("activeKeys", 0L);
        }
        connection.commit();
    }

    private void put(long key) throws Exception {
        long sequence = state.nextSequence();
        boolean existed = exists(key);
        String payload = payload(key, sequence);
        try (PreparedStatement prep = connection.prepareStatement(
                "MERGE INTO LONGRUN_DATA KEY(ID) VALUES(?, ?, ?, ?)")) {
            prep.setLong(1, key);
            prep.setLong(2, sequence);
            prep.setString(3, payload);
            prep.setLong(4, checksum(payload));
            prep.executeUpdate();
        }
        ledger(sequence, "PUT:" + key);
        if (!existed) {
            incrementCounter("activeKeys", 1L);
        }
        state.write();
    }

    private void get(long key) throws Exception {
        state.nextSequence();
        try (PreparedStatement prep = connection.prepareStatement(
                "SELECT PAYLOAD, CHECKSUM FROM LONGRUN_DATA WHERE ID = ?")) {
            prep.setLong(1, key);
            try (ResultSet rs = prep.executeQuery()) {
                if (rs.next() && checksum(rs.getString(1)) != rs.getLong(2)) {
                    throw new IllegalStateException("SQL checksum mismatch for key " + key);
                }
            }
        }
        state.read();
    }

    private void remove(long key) throws Exception {
        long sequence = state.nextSequence();
        int removed;
        try (PreparedStatement prep = connection.prepareStatement("DELETE FROM LONGRUN_DATA WHERE ID = ?")) {
            prep.setLong(1, key);
            removed = prep.executeUpdate();
        }
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
        try (PreparedStatement prep = connection.prepareStatement(
                "MERGE INTO LONGRUN_DATA KEY(ID) VALUES(?, ?, ?, ?)")) {
            prep.setLong(1, -key - 1);
            prep.setLong(2, sequence);
            prep.setString(3, payload);
            prep.setLong(4, checksum(payload));
            prep.executeUpdate();
        }
        connection.rollback(savepoint);
        if (exists(-key - 1)) {
            throw new IllegalStateException("Rolled-back SQL row is visible: " + (-key - 1));
        }
        state.write();
    }

    private boolean exists(long key) throws Exception {
        try (PreparedStatement prep = connection.prepareStatement("SELECT COUNT(*) FROM LONGRUN_DATA WHERE ID = ?")) {
            prep.setLong(1, key);
            try (ResultSet rs = prep.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0L;
            }
        }
    }

    private void ledger(long sequence, String operation) throws Exception {
        try (PreparedStatement prep = connection.prepareStatement(
                "INSERT INTO LONGRUN_LEDGER(SEQ, OP) VALUES(?, ?)")) {
            prep.setLong(1, sequence);
            prep.setString(2, operation);
            prep.executeUpdate();
        }
    }

    private boolean hasCounter(String name) throws Exception {
        try (PreparedStatement prep = connection.prepareStatement(
                "SELECT COUNT(*) FROM LONGRUN_COUNTERS WHERE NAME = ?")) {
            prep.setString(1, name);
            try (ResultSet rs = prep.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0L;
            }
        }
    }

    private long counter(String name) throws Exception {
        try (PreparedStatement prep = connection.prepareStatement(
                "SELECT COUNTER_VALUE FROM LONGRUN_COUNTERS WHERE NAME = ?")) {
            prep.setString(1, name);
            try (ResultSet rs = prep.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void incrementCounter(String name, long delta) throws Exception {
        setCounter(name, counter(name) + delta);
    }

    private void setCounter(String name, long value) throws Exception {
        try (PreparedStatement prep = connection.prepareStatement(
                "MERGE INTO LONGRUN_COUNTERS KEY(NAME) VALUES(?, ?)")) {
            prep.setString(1, name);
            prep.setLong(2, value);
            prep.executeUpdate();
        }
    }

    private long scalar(String sql) throws Exception {
        try (Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String payload(long key, long sequence) {
        return key + ":" + sequence + ":" + random.nextLong();
    }

    private static long checksum(String value) {
        CRC32 crc32 = new CRC32();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }
}
