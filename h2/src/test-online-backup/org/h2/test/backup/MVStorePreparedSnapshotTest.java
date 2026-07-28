/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.mvstore.MVStorePreparedSnapshot;
import org.h2.mvstore.MVStorePreparedSnapshot.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3 fixed-cut MVStore prepared snapshot tests.
 */
public class MVStorePreparedSnapshotTest {

    @TempDir
    Path directory;

    /**
     * Fixed copy length and captured header must preserve the prepare cut while
     * later commits continue to append.
     */
    @Test
    public void fixedCutSurvivesConcurrentCommitsAndHeaderChanges()
            throws Exception {
        String sourceName = "fixed-cut";
        try (Connection source = connect(sourceName, UUID.randomUUID(), "");
                Statement statement = source.createStatement()) {
            statement.execute("CREATE TABLE TEST AS "
                    + "SELECT X ID FROM SYSTEM_RANGE(1, 100)");
            Database database = database(source);
            MVStorePreparedSnapshot snapshot =
                    database.prepareOnlineBackupSnapshot(5_000L, 30_000L);
            try {
                assertFalse(database.getStore().getMvStore().isSpaceReused());
                long copyLength = snapshot.getCopyLength();
                assertTrue(copyLength > 8 * 1024L);
                assertNotNull(snapshot.getSourceFingerprint());

                Path target = databaseFile("fixed-cut-copy");
                UUID generationId = snapshotGeneration(source);
                CountDownLatch writerStarted = new CountDownLatch(1);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<Void> writer = executor.submit(() -> {
                        try (Connection connection = connect(sourceName,
                                generationId, "");
                                PreparedStatement insert =
                                        connection.prepareStatement(
                                                "INSERT INTO TEST VALUES(?)")) {
                            for (int i = 101; i <= 300; i++) {
                                insert.setInt(1, i);
                                insert.executeUpdate();
                                if (i == 101) {
                                    writerStarted.countDown();
                                }
                            }
                        }
                        return null;
                    });
                    assertTrue(writerStarted.await(5L, TimeUnit.SECONDS));
                    assertFalse(database.getStore().getMvStore()
                            .compact(100, 1024 * 1024));
                    snapshot.materialize(target.toString());
                    writer.get();
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5L,
                            TimeUnit.SECONDS));
                }
                assertEquals(copyLength, Files.size(target));

                try (Connection restored = connect("fixed-cut-copy",
                        UUID.randomUUID(), "")) {
                    assertEquals(100L, count(restored));
                }
                assertEquals(300L, count(source));
            } finally {
                snapshot.close();
            }
            assertTrue(database.getStore().getMvStore().isSpaceReused());
        }
    }

    /**
     * A single active snapshot blocks another prepare and close is idempotent.
     */
    @Test
    public void closeIsIdempotentAndReleasesSingleSnapshotGuard()
            throws Exception {
        try (Connection connection = connect("close", UUID.randomUUID(), "")) {
            Database database = database(connection);
            MVStorePreparedSnapshot first =
                    database.prepareOnlineBackupSnapshot(5_000L, 30_000L);
            assertThrows(IllegalStateException.class,
                    () -> database.prepareOnlineBackupSnapshot(
                            5_000L, 30_000L));
            first.close();
            first.close();
            assertEquals(State.CLOSED, first.getState());
            MVStorePreparedSnapshot second =
                    database.prepareOnlineBackupSnapshot(5_000L, 30_000L);
            second.close();
        }
    }

    /**
     * An idle expired lease automatically releases its reuse-space pin.
     */
    @Test
    public void idleLeaseExpiryReleasesPin() throws Exception {
        try (Connection connection = connect("expiry", UUID.randomUUID(), "")) {
            Database database = database(connection);
            MVStorePreparedSnapshot snapshot =
                    database.prepareOnlineBackupSnapshot(5_000L, 25L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (snapshot.getState() != State.CLOSED
                    && System.nanoTime() - deadline < 0L) {
                Thread.yield();
            }
            assertEquals(State.CLOSED, snapshot.getState());
            assertTrue(database.getStore().getMvStore().isSpaceReused());
        }
    }

    /**
     * Lease expiry during a slow copy requests cooperative cancellation and
     * removes the incomplete artifact after the reader exits.
     */
    @Test
    public void materializationExpiryCancelsAtChunkBoundary()
            throws Exception {
        try (Connection connection = connect("cancel", UUID.randomUUID(), "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE LARGE_DATA AS SELECT X ID, "
                    + "SECURE_RAND(2048) PAYLOAD FROM SYSTEM_RANGE(1, 8192)");
            Database database = database(connection);
            MVStorePreparedSnapshot snapshot =
                    database.prepareOnlineBackupSnapshot(5_000L, 1L);
            Path target = databaseFile("cancel-copy");
            assertThrows(Exception.class,
                    () -> snapshot.materialize(target.toString()));
            assertEquals(State.CLOSED, snapshot.getState());
            assertEquals(0, snapshot.getActiveReaders());
            assertTrue(database.getStore().getMvStore().isSpaceReused());
            assertFalse(Files.exists(target));
        }
    }

    /**
     * Writes may grow the source while it is pinned, and cleanup restores
     * normal reuse-space behavior.
     */
    @Test
    public void pinnedSnapshotAllowsObservableFileGrowth() throws Exception {
        String name = "file-growth";
        try (Connection connection = connect(name, UUID.randomUUID(), "");
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE GROWTH(ID BIGINT, PAYLOAD VARBINARY)");
            Database database = database(connection);
            long before = Files.size(databaseFile(name));
            MVStorePreparedSnapshot snapshot =
                    database.prepareOnlineBackupSnapshot(5_000L, 30_000L);
            try {
                statement.execute("INSERT INTO GROWTH SELECT X, "
                        + "SECURE_RAND(2048) FROM SYSTEM_RANGE(1, 16384)");
                assertTrue(Files.size(databaseFile(name)) > before);
                assertTrue(snapshot.getSourceGrowthBytes() > 0L);
                assertFalse(database.getStore().getMvStore().isSpaceReused());
            } finally {
                snapshot.close();
            }
            assertTrue(database.getStore().getMvStore().isSpaceReused());
        }
    }

    /**
     * Raw snapshot bytes of an encrypted store remain encrypted and reopenable.
     */
    @Test
    public void encryptedSnapshotCanBeReopened() throws Exception {
        String password = "filePassword userPassword";
        try (Connection source = connect("encrypted-snapshot",
                UUID.randomUUID(), password); Statement statement =
                        source.createStatement()) {
            statement.execute("CREATE TABLE TEST AS "
                    + "SELECT X ID FROM SYSTEM_RANGE(1, 25)");
            MVStorePreparedSnapshot snapshot = database(source)
                    .prepareOnlineBackupSnapshot(5_000L, 30_000L);
            try {
                snapshot.materialize(
                        databaseFile("encrypted-snapshot-copy").toString());
            } finally {
                snapshot.close();
            }
        }
        try (Connection restored = connect("encrypted-snapshot-copy",
                UUID.randomUUID(), password)) {
            assertEquals(25L, count(restored));
        }
    }

    /**
     * Preparing and materializing an identified read-only database does not
     * modify its source file.
     */
    @Test
    public void readOnlySnapshotDoesNotWriteSource() throws Exception {
        String name = "read-only";
        UUID generationId = UUID.randomUUID();
        try (Connection writable = connect(name, generationId, "");
                Statement statement = writable.createStatement()) {
            statement.execute("CREATE TABLE TEST AS "
                    + "SELECT X ID FROM SYSTEM_RANGE(1, 10)");
        }
        Path sourceFile = databaseFile(name);
        byte[] before = Files.readAllBytes(sourceFile);
        String url = databaseUrl(name, UUID.randomUUID(), "")
                + ";ACCESS_MODE_DATA=r";
        try (Connection readOnly = DriverManager.getConnection(url, "sa", "")) {
            MVStorePreparedSnapshot snapshot = database(readOnly)
                    .prepareOnlineBackupSnapshot(5_000L, 30_000L);
            try {
                snapshot.materialize(
                        databaseFile("read-only-copy").toString());
            } finally {
                snapshot.close();
            }
        }
        assertArrayEquals(before, Files.readAllBytes(sourceFile));
        try (Connection restored = connect("read-only-copy",
                UUID.randomUUID(), "")) {
            assertEquals(10L, count(restored));
        }
    }

    private Connection connect(String name, UUID generationId, String password)
            throws Exception {
        return DriverManager.getConnection(databaseUrl(name, generationId,
                password), "sa", password);
    }

    private String databaseUrl(String name, UUID generationId,
            String password) {
        String url = "jdbc:h2:" + filePath(directory.resolve(name))
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + generationId;
        if (!password.isEmpty()) {
            url += ";CIPHER=AES";
        }
        return url;
    }

    private Path databaseFile(String name) {
        return directory.resolve(name + ".mv.db");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static UUID snapshotGeneration(Connection connection) {
        return database(connection).getOnlineBackupMetadata()
                .requireSnapshot().getGenerationId();
    }

    private static long count(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM TEST")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String filePath(Path path) {
        return path.toAbsolutePath().toString()
                .replace(File.separatorChar, '/');
    }
}
