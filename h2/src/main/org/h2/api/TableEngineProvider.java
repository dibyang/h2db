/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import org.h2.command.ddl.CreateTableData;
import org.h2.table.Table;

/**
 * 表引擎 provider。
 */
public interface TableEngineProvider extends PluginProvider {

    /**
     * Provider 类型。
     */
    String TYPE = "table";

    /**
     * 创建表。
     *
     * @param data 建表数据
     * @param context 表引擎上下文
     * @return 创建出的表
     */
    Table createTable(CreateTableData data, TableEngineContext context);
}
