/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 插件扩展点 provider 的父接口。
 */
public interface PluginProvider {

    /**
     * 获取 provider 类型，例如 table 或 storage。
     *
     * @return provider 类型
     */
    String getType();

    /**
     * 获取 provider 在同一类型内的稳定标识。
     *
     * @return provider 标识
     */
    String getId();

    /**
     * 判断 provider 是否支持指定能力。
     *
     * @param capability 能力名称
     * @return 支持时返回 true
     */
    boolean supports(String capability);
}
