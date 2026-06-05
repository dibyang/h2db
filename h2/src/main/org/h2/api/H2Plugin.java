/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.Collections;

/**
 * H2 插件描述入口。
 * <p>
 * 一个插件可以提供多个 provider，例如同时提供表引擎和存储引擎扩展。
 */
public interface H2Plugin {

    /**
     * 获取插件稳定标识。
     *
     * @return 插件标识
     */
    String getId();

    /**
     * 获取插件版本。
     *
     * @return 插件版本
     */
    String getVersion();

    /**
     * 获取插件展示名称。
     *
     * @return 展示名称
     */
    String getDisplayName();

    /**
     * 获取该插件允许注册的 provider 类型清单。
     * <p>
     * 仅在安全边界收紧时使用。返回空集合时表示不限制 provider 类型（使用系统允许列表）。
     * 返回集合时，仅允许其中列出的 provider 类型注册到该插件。
     *
     * @return 允许的 provider 类型列表
     */
    default Iterable<String> getAllowedProviderTypes() {
        return Collections.emptyList();
    }

    /**
     * 获取插件提供的扩展点。
     *
     * @return provider 集合
     */
    Iterable<? extends PluginProvider> getProviders();

    /**
     * 获取插件支持的 H2 版本范围。
     * <p>
     * 支持 {@code *}、精确版本号和形如 {@code [2.2,3.0)} 的区间。
     *
     * @return H2 版本范围
     */
    default String getH2VersionRange() {
        return "*";
    }

    /**
     * 获取插件依赖。
     *
     * @return 插件依赖列表
     */
    default Iterable<PluginDependency> getDependencies() {
        return Collections.emptyList();
    }
}
