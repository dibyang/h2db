/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 存储引擎 provider。
 */
public interface StorageEngineProvider extends PluginProvider {

    /**
     * Provider 类型。
     */
    String TYPE = "storage";

    /**
     * 打开存储引擎。
     *
     * @param context 存储引擎上下文
     * @return 存储引擎
     */
    StorageEngine open(StorageEngineContext context);
}
