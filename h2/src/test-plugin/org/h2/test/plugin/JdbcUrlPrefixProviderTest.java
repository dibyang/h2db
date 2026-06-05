/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;

import org.h2.Driver;
import org.h2.api.H2Plugin;
import org.h2.api.JdbcUrlPrefixProvider;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.table.Table;
import org.junit.jupiter.api.Test;

/**
 * JDBC URL prefix provider contract tests.
 */
public class JdbcUrlPrefixProviderTest {

    /**
     * T-PLUGIN-P5-JDBC-PREFIX-AUTO-01.
     */
    @Test
    public void serviceLoaderMapsVendorJdbcPrefixAndRegistersDatabaseProviders() throws Exception {
        java.sql.Driver driver = Driver.load();

        assertTrue(driver.acceptsURL("jdbc:vendor:mem:pluginJdbcPrefixAuto"));

        try (Connection conn = DriverManager.getConnection("jdbc:vendor:mem:pluginJdbcPrefixAuto", "sa", "")) {
            assertFalse(conn.createStatement().execute("create table test(id int)"));
            assertTrue(database(conn).getPluginRegistry().findProvider(TableEngineProvider.TYPE,
                    ServicePrefixPlugin.TABLE_PROVIDER_ID) instanceof TableEngineProvider);
        }
    }

    /**
     * T-PLUGIN-P5-JDBC-PREFIX-SERVICE-01.
     */
    @Test
    public void serviceLoaderDriverPluginMapsCustomJdbcPrefix() throws Exception {
        java.sql.Driver driver = Driver.load();

        assertTrue(driver.acceptsURL("jdbc:svc:mem:pluginJdbcPrefixService"));

        try (Connection conn = DriverManager.getConnection("jdbc:svc:mem:pluginJdbcPrefixService", "sa", "")) {
            assertTrue(conn.createStatement().execute("select 1"));
        }
    }

    /**
     * T-PLUGIN-P5-JDBC-PREFIX-NOMATCH-01.
     */
    @Test
    public void customJdbcPrefixIsIgnoredWhenNoDiscoveredProviderMatches() throws Exception {
        assertFalse(Driver.load().acceptsURL("jdbc:unknownprefix:mem:pluginJdbcPrefixNoMatch"));
    }

    private static Database database(Connection conn) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
        return session.getDatabase();
    }

    public static final class ServicePrefixPlugin implements H2Plugin {
        static final String TABLE_PROVIDER_ID = "jdbc_prefix_table";

        @Override
        public String getId() {
            return "test.jdbc.prefix.service";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "Service JDBC Prefix Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new PrefixProvider("vendor_prefix", "jdbc:vendor:"),
                    new PrefixProvider("service_prefix", "jdbc:svc:"),
                    new TestTableProvider(TABLE_PROVIDER_ID));
        }
    }

    private static final class PrefixProvider implements JdbcUrlPrefixProvider {
        private final String id;
        private final String prefix;

        PrefixProvider(String id, String prefix) {
            this.id = id;
            this.prefix = prefix;
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
            return false;
        }

        @Override
        public String getUrlPrefix() {
            return prefix;
        }

        @Override
        public String toH2Url(String url) {
            return "jdbc:h2:" + url.substring(prefix.length());
        }
    }

    private static final class TestTableProvider implements TableEngineProvider {
        private final String id;

        TestTableProvider(String id) {
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
            return PluginCapability.TABLE_CREATE.equals(capability);
        }

        @Override
        public Table createTable(CreateTableData data, org.h2.api.TableEngineContext context) {
            throw new UnsupportedOperationException();
        }
    }

}
