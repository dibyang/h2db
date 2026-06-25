/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Experimental execution-time context for DML fast-path providers.
 */
public interface DmlExecutionContext {

    /**
     * Get the current session object.
     *
     * @return current session
     */
    Object getSession();

    /**
     * Get the target table object.
     *
     * @return target table
     */
    Object getTable();

    /**
     * Get current single-row parameters.
     *
     * @return bound parameter view
     */
    BoundParameterView getParameters();

    /**
     * Get current batch parameters.
     *
     * @return bound batch parameter view
     */
    BoundParameterBatchView getBatchParameters();

    /**
     * Whether this execution is a batch.
     *
     * @return true for batch execution
     */
    boolean isBatch();

    /**
     * Whether the session is in auto-commit mode.
     *
     * @return true for auto-commit
     */
    boolean isAutoCommit();
}
