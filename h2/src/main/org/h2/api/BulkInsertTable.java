/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Optional table-side bulk insert capability for DML fast-path providers.
 */
public interface BulkInsertTable {

    /**
     * Add a batch of rows.
     *
     * @param context DML execution context
     * @param rows bound parameter rows
     * @return update count
     */
    long addRows(DmlExecutionContext context, BoundParameterBatchView rows);
}
