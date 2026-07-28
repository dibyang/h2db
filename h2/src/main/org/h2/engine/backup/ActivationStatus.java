/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

/**
 * 进程内 activation token 的稳定状态。
 */
public enum ActivationStatus {

    /**
     * 旧 generation 已完成 drain，等待路由切换结果。
     */
    PREPARED,

    /**
     * Token 已提交，旧 generation 已永久 fence。
     */
    COMMITTED,

    /**
     * Token 已取消，旧 generation 已恢复事务准入。
     */
    ABORTED
}
