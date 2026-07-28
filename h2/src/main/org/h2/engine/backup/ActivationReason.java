/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

/**
 * Activation report 使用的稳定、非本地化 reason。
 */
public enum ActivationReason {

    /**
     * Drain 已完成，token 等待消费。
     */
    PREPARED,

    /**
     * 旧 generation 已永久 fence。
     */
    GENERATION_FENCED,

    /**
     * 切换未提交，事务准入已恢复。
     */
    ACTIVATION_ABORTED
}
