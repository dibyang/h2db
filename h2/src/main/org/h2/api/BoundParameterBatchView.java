/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Read-only view over prepared statement batch parameters.
 */
public interface BoundParameterBatchView {

    /**
     * Get batch row count.
     *
     * @return batch row count
     */
    int size();

    /**
     * Get one batch row by zero-based index.
     *
     * @param rowIndex zero-based batch row index
     * @return row parameter view
     */
    BoundParameterView get(int rowIndex);
}
