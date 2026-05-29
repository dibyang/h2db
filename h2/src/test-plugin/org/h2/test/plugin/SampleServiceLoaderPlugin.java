/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import java.util.Arrays;

import org.h2.api.H2Plugin;
import org.h2.api.PluginProvider;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.table.Table;

/**
 * 通过真实 META-INF/services 发现的测试插件。
 */
public final class SampleServiceLoaderPlugin implements H2Plugin {

    @Override
    public String getId() {
        return "test.service.loader";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public String getDisplayName() {
        return "Service Loader Plugin";
    }

    @Override
    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new ServiceTableProvider());
    }

    private static final class ServiceTableProvider implements TableEngineProvider {

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return "service_resource_table";
        }

        @Override
        public boolean supports(String capability) {
            return false;
        }

        @Override
        public Table createTable(CreateTableData data, org.h2.api.TableEngineContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
