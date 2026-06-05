/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import java.util.Collections;
import java.util.List;

import org.h2.api.PluginCapability;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.api.TableProviderSupport;
import org.h2.command.ddl.CreateTableData;
import org.h2.mvstore.db.MVStoreBackedStorageEngine;
import org.h2.table.Table;

/**
 * Table provider fixture that records the context passed by Schema#createTable.
 */
public final class ContractTableProvider implements TableEngineProvider {

    static final String PLUGIN_ID = "contract.table.provider";
    static final String ID = "contract_table";
    static String tableName;
    static String schemaName;
    static List<String> tableEngineParams = Collections.emptyList();
    static boolean databaseAvailable;
    static boolean traceAvailable;
    static String storageEngineId;
    static boolean persistent;
    static boolean readOnly;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean supports(String capability) {
        return PluginCapability.TABLE_CREATE.equals(capability);
    }

    @Override
    public Table createTable(CreateTableData data, TableEngineContext context) {
        TableProviderSupport.requireWritable(context, data, ID);
        tableName = data.tableName;
        schemaName = context.getSchema().getName();
        tableEngineParams = context.getTableEngineParams();
        databaseAvailable = context.getDatabase() != null;
        traceAvailable = context.getTrace() != null;
        storageEngineId = context.getStorageEngineId();
        persistent = context.isPersistent();
        readOnly = context.isReadOnly();
        return TableProviderSupport.requireStorageEngine(context, MVStoreBackedStorageEngine.class, ID, data)
                .getStore().createTable(data);
    }

    static String normalizedTableName() {
        return tableName == null ? null : tableName.toUpperCase(java.util.Locale.ROOT);
    }

    static void reset() {
        tableName = null;
        schemaName = null;
        tableEngineParams = Collections.emptyList();
        databaseAvailable = false;
        traceAvailable = false;
        storageEngineId = null;
        persistent = false;
        readOnly = false;
    }
}

final class FailingContractTableProvider implements TableEngineProvider {
    static final String ID = "contract_failing_table";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean supports(String capability) {
        return PluginCapability.TABLE_CREATE.equals(capability);
    }

    @Override
    public Table createTable(CreateTableData data, TableEngineContext context) {
        throw new IllegalStateException("simulated provider failure");
    }
}
