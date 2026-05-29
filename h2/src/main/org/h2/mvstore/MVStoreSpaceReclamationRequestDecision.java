/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护态下对外请求的准入决策。
 */
public enum MVStoreSpaceReclamationRequestDecision {

    /**
     * 允许请求继续执行。
     */
    ALLOW,

    /**
     * 请求需要等待维护窗口结束或活跃事务结束。
     */
    WAIT,

    /**
     * 请求应立即返回忙碌或维护态错误。
     */
    BUSY
}
