/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.h2.mvstore.db.MVStoreTableEngineProvider;
import org.h2.mvstore.db.SecondaryMVStoreStorageEngineProvider;

/**
 * H2 内置插件注册入口。
 */
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    /**
     * 注册阶段一内置 provider。
     *
     * @param registry 插件注册中心
     */
    public static void register(PluginRegistry registry) {
        registry.registerProvider("h2.mvstore", Constants.VERSION,
                new MVStoreStorageEngineProvider(), PluginSource.BUILTIN);
        registry.registerProvider("h2.mvstore.secondary", Constants.VERSION,
                new SecondaryMVStoreStorageEngineProvider(), PluginSource.BUILTIN);
        registry.registerProvider("h2.mvstore", Constants.VERSION,
                new MVStoreTableEngineProvider(), PluginSource.BUILTIN);
    }
}
