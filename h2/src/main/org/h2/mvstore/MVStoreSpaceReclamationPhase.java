/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护操作的阶段。
 */
public enum MVStoreSpaceReclamationPhase {

    /**
     * 准备 manifest、shadow 和源文件指纹。
     */
    PREPARING,

    /**
     * 校验 shadow 文件。
     */
    VERIFYING,

    /**
     * shadow 文件已经准备好，等待切换。
     */
    SHADOW_READY,

    /**
     * prepared shadow 已过期，降级为维护态 full copy。
     */
    FALLBACK_FULL_COPY,

    /**
     * 正在替换源文件。
     */
    SWITCHING,

    /**
     * 操作完成。
     */
    COMPLETED,

    /**
     * 操作失败并中止。
     */
    ABORTED
}
