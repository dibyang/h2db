/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.h2.engine.StorageEngineResolver;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.h2.mvstore.db.SecondaryMVStoreStorageEngineProvider;
import org.h2.store.fs.FileUtils;
import org.junit.jupiter.api.Test;

/**
 * storage engine id 解析边界的 JUnit 验证。
 */
public class StorageEngineResolverTest {

    private final String baseDir = new File("build/plugin-db/storage-id").getAbsolutePath().replace('\\', '/');

    /**
     * T-PLUGIN-F2-OLD-DB-DEFAULT-01.
     */
    @Test
    public void oldDatabaseWithoutPersistedIdDefaultsToMvStore() {
        assertEquals(MVStoreStorageEngineProvider.ID, StorageEngineResolver.resolvePersisted(null));
        assertEquals(MVStoreStorageEngineProvider.ID, StorageEngineResolver.resolvePersisted(""));
    }

    /**
     * T-PLUGIN-F2-STORAGE-ID-MISMATCH-01.
     */
    @Test
    public void rejectsRequestedStorageIdMismatch() {
        DbException e = assertThrows(DbException.class, () ->
                StorageEngineResolver.validateMatch("other", MVStoreStorageEngineProvider.ID));

        assertTrue(e.getMessage().contains("requested=other"));
        assertTrue(e.getMessage().contains("persisted=mvstore"));
    }

    /**
     * T-PLUGIN-F2-ROLLBACK-01.
     */
    @Test
    public void validatesDefaultMvStoreForRollbackPath() {
        StorageEngineResolver.validateMatch(MVStoreStorageEngineProvider.ID, null);
    }

    /**
     * T-PLUGIN-R2-STORAGE-ID-PERSIST-01.
     */
    @Test
    public void persistsStorageEngineIdBesideDatabase() throws Exception {
        String databaseName = baseDir + "/persistedId";
        FileUtils.deleteRecursive(baseDir, true);
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName, "sa", "")) {
                conn.createStatement().execute("create table test(id int)");
            }

            assertEquals(MVStoreStorageEngineProvider.ID,
                    StorageEngineResolver.readPersistedStorageEngineId(databaseName));
        } finally {
            FileUtils.deleteRecursive(baseDir, true);
        }
    }

    /**
     * T-PLUGIN-R2-UPGRADE-ROLLBACK-01.
     */
    @Test
    public void rejectsStorageEngineMismatchFromPersistedMetadata() throws Exception {
        String databaseName = baseDir + "/mismatch";
        FileUtils.deleteRecursive(baseDir, true);
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName, "sa", "")) {
                conn.createStatement().execute("create table test(id int)");
            }

            SQLException e = assertThrows(SQLException.class, () ->
                    DriverManager.getConnection("jdbc:h2:" + databaseName + ";STORAGE_ENGINE=other", "sa", ""));
            assertTrue(e.getMessage().contains("Storage engine mismatch"));
            assertTrue(e.getMessage().contains("requested=other"));
            assertTrue(e.getMessage().contains("persisted=mvstore"));
        } finally {
            FileUtils.deleteRecursive(baseDir, true);
        }
    }

    /**
     * T-PLUGIN-P6-PERSISTED-SECONDARY-MISMATCH-01.
     */
    @Test
    public void rejectsDefaultOpenWhenPersistedStorageEngineIsSecondary() throws Exception {
        String databaseName = baseDir + "/secondaryMismatch";
        FileUtils.deleteRecursive(baseDir, true);
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName + ";STORAGE_ENGINE="
                    + SecondaryMVStoreStorageEngineProvider.ID, "sa", "")) {
                conn.createStatement().execute("create table test(id int)");
            }

            assertEquals(SecondaryMVStoreStorageEngineProvider.ID,
                    StorageEngineResolver.readPersistedStorageEngineId(databaseName));
            SQLException e = assertThrows(SQLException.class, () ->
                    DriverManager.getConnection("jdbc:h2:" + databaseName, "sa", ""));
            assertTrue(e.getMessage().contains("Storage engine mismatch"));
            assertTrue(e.getMessage().contains("requested=mvstore"));
            assertTrue(e.getMessage().contains("persisted=" + SecondaryMVStoreStorageEngineProvider.ID));
        } finally {
            FileUtils.deleteRecursive(baseDir, true);
        }
    }

    /**
     * T-PLUGIN-F3-MISSING-STORAGE-FAIL-01.
     */
    @Test
    public void missingStorageProviderFailsOpen() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:h2:mem:missingStorage;STORAGE_ENGINE=missing", "sa", ""));

        assertTrue(e.getMessage().contains("Missing storage engine provider"));
        assertTrue(e.getMessage().contains("type=storage"));
        assertTrue(e.getMessage().contains("id=missing"));
    }

    /**
     * T-PLUGIN-F3-MISSING-STORAGE-READONLY-01.
     */
    @Test
    public void missingStorageProviderReadOnlyDowngradeIsDisabled() {
        assertFalse(StorageEngineResolver.isMissingStorageReadOnlyDowngradeAllowed());
        assertFalse(StorageEngineResolver.isMissingStorageReadOnlyDowngradeAllowed(false, true));
        assertFalse(StorageEngineResolver.isMissingStorageReadOnlyDowngradeAllowed(true, false));
        assertTrue(StorageEngineResolver.isMissingStorageReadOnlyDowngradeAllowed(true, true));
    }

    /**
     * T-PLUGIN-F3-NO-MVSTORE-FALLBACK-01.
     */
    @Test
    public void missingStorageProviderDoesNotFallbackToMvStore() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:h2:mem:noFallback;STORAGE_ENGINE=other", "sa", ""));

        assertTrue(e.getMessage().contains("id=other"));
        assertFalse(e.getMessage().contains("opened"));
    }

    /**
     * T-PLUGIN-R4-READONLY-DEGRADE-01.
     */
    @Test
    public void missingStorageProviderCanDowngradeToReadOnlyWhenExplicit() throws Exception {
        String databaseName = baseDir + "/readOnlyDowngrade";
        FileUtils.deleteRecursive(baseDir, true);
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName, "sa", "")) {
                conn.createStatement().execute("create table test(id int primary key, name varchar)");
                conn.createStatement().execute("insert into test values(1, 'read-only')");
            }
            StorageEngineResolver.writePersistedStorageEngineId(databaseName, "missing");

            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName
                    + ";ACCESS_MODE_DATA=r;STORAGE_ENGINE=missing;MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE",
                    "sa", "")) {
                assertTrue(conn.isReadOnly());
                assertTrue(conn.createStatement().executeQuery("select name from test where id = 1").next());
            }
        } finally {
            FileUtils.deleteRecursive(baseDir, true);
        }
    }

    /**
     * T-PLUGIN-R4-NO-WRITE-ON-DEGRADE-01.
     */
    @Test
    public void readOnlyDowngradeDoesNotAllowWrites() throws Exception {
        String databaseName = baseDir + "/readOnlyDowngradeNoWrite";
        FileUtils.deleteRecursive(baseDir, true);
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName, "sa", "")) {
                conn.createStatement().execute("create table test(id int)");
            }
            StorageEngineResolver.writePersistedStorageEngineId(databaseName, "missing");

            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + databaseName
                    + ";ACCESS_MODE_DATA=r;STORAGE_ENGINE=missing;MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE",
                    "sa", "")) {
                assertThrows(SQLException.class, () -> conn.createStatement().execute("insert into test values(1)"));
            }
        } finally {
            FileUtils.deleteRecursive(baseDir, true);
        }
    }
}
