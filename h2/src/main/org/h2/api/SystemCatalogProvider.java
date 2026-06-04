/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 系统元数据 provider。
 * <p>
 * 该 provider 是非 MVStore 主路径的前置扩展点，用于承接系统元数据表、LOB、
 * 事务日志和临时结果等当前仍依赖 MVStore {@code Store} 的能力。当前版本只
 * 稳定 provider 注册、诊断和 capability 边界，不接管默认系统表创建流程。
 */
public interface SystemCatalogProvider extends PluginProvider {

    /**
     * Provider 类型。
     */
    String TYPE = "system_catalog";

    /**
     * 校验 provider 是否可服务当前数据库上下文。
     *
     * @param context 系统元数据上下文
     */
    void validate(SystemCatalogContext context);
}
