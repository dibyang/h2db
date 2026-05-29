/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

/**
 * 插件 provider 注册来源。
 */
public enum PluginSource {

    /**
     * H2 内置 provider。
     */
    BUILTIN,

    /**
     * 旧类名加载路径。
     */
    LEGACY_CLASS_NAME,

    /**
     * 显式配置的 provider。
     */
    CONFIGURED_CLASS,

    /**
     * ServiceLoader 发现的 provider。
     */
    SERVICE_LOADER
}
