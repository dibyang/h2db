/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Experimental prepared DML execution plan.
 */
public interface DmlExecutionPlan {

    /**
     * Empty plan used when a provider declines a DML command.
     */
    DmlExecutionPlan NONE = new DmlExecutionPlan() {

        @Override
        public boolean isSupported() {
            return false;
        }

        @Override
        public long execute(DmlExecutionContext context) {
            throw new UnsupportedOperationException("DML fast path plan is not supported");
        }
    };

    /**
     * Whether this plan can handle the command.
     *
     * @return true if supported
     */
    boolean isSupported();

    /**
     * Execute the plan.
     *
     * @param context execution context
     * @return update count
     */
    long execute(DmlExecutionContext context);

    /**
     * 为后续 JDBC batch 契约保留的执行入口。
     * <p>
     * 当前 H2 按元素执行 JDBC batch，使提交、回滚、部分失败 update count 和
     * generated key 行为继续受标准 command lifecycle 管理。该实验性方法仅为
     * 源码和二进制兼容保留，当前不会由 H2 调用。
     *
     * @param context 包含 batch 参数的执行上下文
     * @return 逐元素 update count，或 {@code null}
     */
    default long[] executeBatch(DmlExecutionContext context) {
        return null;
    }
}
