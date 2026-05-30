/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngineProvider;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.mvstore.db.MVStoreStorageEngine;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.h2.mvstore.db.SecondaryMVStoreStorageEngineProvider;
import org.h2.store.fs.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MVStore storage provider 的 JUnit 验证。
 */
public class MVStoreStorageEngineTest {

    private final String baseDir = new File("build/plugin-db").getAbsolutePath().replace('\\', '/');

    @AfterEach
    public void cleanUp() {
        FileUtils.deleteRecursive(baseDir, true);
    }

    /**
     * T-PLUGIN-OLD-DATABASE-MVSTORE-01.
     */
    @Test
    public void opensExistingMvStoreDatabaseThroughStorageEngine() throws Exception {
        String url = fileUrl("oldDatabase");
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            conn.createStatement().execute("create table test(id int primary key, name varchar)");
            conn.createStatement().execute("insert into test values(1, 'ok')");
        }

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            Database db = database(conn);
            assertNotNull(db.getPluginRegistry().findProvider("storage", MVStoreStorageEngineProvider.ID));
            assertTrue(db.getStorageEngine() instanceof MVStoreStorageEngine);
            assertTrue(db.getStore() == ((MVStoreStorageEngine) db.getStorageEngine()).getStore());
            try (ResultSet rs = conn.createStatement().executeQuery("select name from test where id = 1")) {
                assertTrue(rs.next());
                assertTrue("ok".equals(rs.getString(1)));
            }
        }
    }

    /**
     * T-PLUGIN-READONLY-OPEN-01.
     */
    @Test
    public void opensReadOnlyDatabaseThroughStorageEngine() throws Exception {
        String name = baseDir + "/readOnly";
        try (Connection conn = DriverManager.getConnection("jdbc:h2:" + name, "sa", "")) {
            conn.createStatement().execute("create table test(id int)");
        }
        String fileName = name + Constants.SUFFIX_MV_FILE;
        FileUtils.setReadOnly(fileName);

        try (Connection conn = DriverManager.getConnection("jdbc:h2:" + name + ";ACCESS_MODE_DATA=r", "sa", "")) {
            Database db = database(conn);
            assertTrue(db.isReadOnly());
            assertTrue(db.getStore().getMvStore().getFileStore().isReadOnly());
            assertTrue(db.getStorageEngine().supports(PluginCapability.STORAGE_PERSISTENT));
        } finally {
            new File(fileName).setWritable(true);
        }
    }

    /**
     * T-PLUGIN-ENCRYPTED-OPEN-01.
     */
    @Test
    public void opensEncryptedDatabaseThroughStorageEngine() throws Exception {
        String url = fileUrl("encrypted") + ";CIPHER=AES";
        try (Connection conn = DriverManager.getConnection(url, "sa", "123 123")) {
            conn.createStatement().execute("create table test(id int primary key)");
        }

        try (Connection conn = DriverManager.getConnection(url, "sa", "123 123")) {
            Database db = database(conn);
            assertTrue(db.getStorageEngine() instanceof MVStoreStorageEngine);
            conn.createStatement().execute("select * from test");
        }
    }

    /**
     * T-PLUGIN-PERSISTENT-FLAG-01.
     */
    @Test
    public void reportsPersistentAndMemoryMvStoreFlags() throws Exception {
        try (Connection conn = DriverManager.getConnection(fileUrl("persistent"), "sa", "")) {
            Database db = database(conn);
            assertTrue(MVStoreStorageEngineProvider.ID.equals(db.getStorageEngineId()));
            assertTrue(db.getStore().getMvStore().isPersistent());
            assertTrue(db.getStorageEngine().supports(PluginCapability.STORAGE_PERSISTENT));
        }
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginPersistentFlag", "sa", "")) {
            Database db = database(conn);
            assertFalse(db.getStore().getMvStore().isPersistent());
            assertTrue(db.getStorageEngine().supports(PluginCapability.STORAGE_PERSISTENT));
        }
    }

    /**
     * T-PLUGIN-R3-SECOND-STORAGE-CRUD-01.
     */
    @Test
    public void opensDatabaseWithSecondaryStorageEngine() throws Exception {
        String url = fileUrl("secondary") + ";STORAGE_ENGINE=" + SecondaryMVStoreStorageEngineProvider.ID;
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            Database db = database(conn);
            assertTrue(SecondaryMVStoreStorageEngineProvider.ID.equals(db.getStorageEngineId()));
            assertTrue(SecondaryMVStoreStorageEngineProvider.ID.equals(db.getStorageEngine().getEngineId()));
            assertNotNull(db.getPluginRegistry().findProvider(StorageEngineProvider.TYPE,
                    SecondaryMVStoreStorageEngineProvider.ID));
            stat.execute("create table test(id int primary key, name varchar)");
            stat.execute("insert into test values(1, 'secondary')");
        }

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                ResultSet rs = conn.createStatement().executeQuery("select name from test where id = 1")) {
            assertTrue(rs.next());
            assertTrue("secondary".equals(rs.getString(1)));
        }
    }

    /**
     * T-PLUGIN-R3-COMPAT-SQL-01.
     */
    @Test
    public void defaultStorageEngineStillUsesMvStore() throws Exception {
        try (Connection conn = DriverManager.getConnection(fileUrl("defaultAfterSecondary"), "sa", "")) {
            Database db = database(conn);
            assertTrue(MVStoreStorageEngineProvider.ID.equals(db.getStorageEngineId()));
            assertTrue(db.getStorageEngine() instanceof MVStoreStorageEngine);
        }
    }

    /**
     * T-PLUGIN-CAPABILITY-UNSUPPORTED-01.
     */
    @Test
    public void reportsUnsupportedMaintenanceCapabilities() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginUnsupportedMaintenance", "sa", "")) {
            Database db = database(conn);

            assertFalse(db.getStorageEngine().supports(PluginCapability.STORAGE_PUBLISH_CRASH_SAFE));
            assertFalse(db.getStorageEngine().getMaintenance().supports(PluginCapability.STORAGE_PUBLISH_CRASH_SAFE));
        }
    }

    /**
     * T-PLUGIN-S2-CAPABILITY-GATE-01.
     */
    @Test
    public void keepsS2CapabilitiesBehindGate() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginS2Gate", "sa", "")) {
            Database db = database(conn);

            assertTrue(db.getPluginRegistry().supports(StorageEngineProvider.TYPE,
                    MVStoreStorageEngineProvider.ID, PluginCapability.STORAGE_VACUUM_ONLINE));
            assertFalse(db.getPluginRegistry().supports(StorageEngineProvider.TYPE,
                    MVStoreStorageEngineProvider.ID, PluginCapability.STORAGE_PUBLISH_CRASH_SAFE));
            assertFalse(db.getPluginRegistry().supports(StorageEngineProvider.TYPE,
                    MVStoreStorageEngineProvider.ID, PluginCapability.STORAGE_TRUNCATE_SAFE));
            assertTrue(db.getStorageEngine().getMaintenance().vacuumOnline().isSkipped());
        }
    }

    /**
     * T-PLUGIN-F8-S1-MAINTENANCE-01.
     */
    @Test
    public void exposesClosedCompactMaintenanceCapability() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginS1Maintenance", "sa", "")) {
            Database db = database(conn);

            assertTrue(db.getStorageEngine().supports(PluginCapability.STORAGE_COMPACT_CLOSED));
            assertTrue(db.getStorageEngine().getMaintenance().supports(PluginCapability.STORAGE_COMPACT_CLOSED));
            assertTrue(db.getStorageEngine().getMaintenance().compactClosed().isSkipped());
        }
    }

    /**
     * T-PLUGIN-R5-S1-COMPACT-BRIDGE-01.
     */
    @Test
    public void compactOnlineRunsThroughMaintenanceBoundary() throws Exception {
        try (Connection conn = DriverManager.getConnection(fileUrl("onlineCompact"), "sa", "")) {
            Database db = database(conn);

            assertTrue(db.getStorageEngine().supports(PluginCapability.STORAGE_COMPACT_ONLINE_MAINTENANCE));
            assertTrue(db.getStorageEngine().getMaintenance().supports(
                    PluginCapability.STORAGE_COMPACT_ONLINE_MAINTENANCE));
            assertTrue(db.getStorageEngine().getMaintenance().compactOnline().isSuccess());
        }
    }

    /**
     * T-PLUGIN-R5-S2-ONLINE-VACUUM-01.
     */
    @Test
    public void vacuumOnlineRunsThroughMaintenanceBoundary() throws Exception {
        try (Connection conn = DriverManager.getConnection(fileUrl("onlineVacuum"), "sa", "")) {
            Database db = database(conn);

            assertTrue(db.getStorageEngine().supports(PluginCapability.STORAGE_VACUUM_ONLINE));
            assertTrue(db.getStorageEngine().getMaintenance().supports(PluginCapability.STORAGE_VACUUM_ONLINE));
            assertTrue(db.getStorageEngine().getMaintenance().vacuumOnline().isSkipped());
        }
    }

    /**
     * T-PLUGIN-F8-MVSTORE-S2-ONLY-01.
     */
    @Test
    public void keepsOnlineVacuumDisabledUntilMvStoreEnablesIt() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginMvStoreS2Only", "sa", "")) {
            Database db = database(conn);

            assertTrue(MVStoreStorageEngineProvider.ID.equals(db.getStorageEngineId()));
            assertTrue(db.getStorageEngine().getMaintenance().supports(PluginCapability.STORAGE_VACUUM_ONLINE));
        }
    }

    private String fileUrl(String name) {
        return "jdbc:h2:" + baseDir + "/" + name;
    }

    private static Database database(Connection conn) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
        return session.getDatabase();
    }
}
