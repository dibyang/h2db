/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.PluginCapability;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.message.DbException;
import org.h2.table.Table;

/**
 * 内置 MVStore 表引擎 provider。
 * <p>
 * 当前 provider 适配 MVStore-backed storage engine，并委托现有
 * {@link Store#createTable(CreateTableData)} 完成真实建表。
 */
public final class MVStoreTableEngineProvider implements TableEngineProvider {

    /**
     * 内置 MVStore table provider id。
     */
    public static final String ID = "mvstore";

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
        if (!(context.getStorageEngine() instanceof MVStoreBackedStorageEngine)) {
            throw DbException.getUnsupportedException("MVStore table provider requires MVStore-backed storage engine");
        }
        return ((MVStoreBackedStorageEngine) context.getStorageEngine()).getStore().createTable(data);
    }
}
