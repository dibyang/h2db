/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 插件依赖描述。
 */
public final class PluginDependency {

    private final String pluginId;
    private final String version;

    /**
     * 创建插件依赖描述。
     *
     * @param pluginId 依赖插件标识
     * @param version 依赖版本；当前阶段仅用于诊断
     */
    public PluginDependency(String pluginId, String version) {
        this.pluginId = pluginId;
        this.version = version;
    }

    /**
     * 获取依赖插件标识。
     *
     * @return 依赖插件标识
     */
    public String getPluginId() {
        return pluginId;
    }

    /**
     * 获取依赖版本。
     *
     * @return 依赖版本
     */
    public String getVersion() {
        return version;
    }
}
