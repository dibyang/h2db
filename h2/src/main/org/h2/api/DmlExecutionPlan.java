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
}
