/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Read-only prepare-time context for experimental DML fast-path providers.
 */
public interface DmlPrepareContext {

    /**
     * Statement type for simple INSERT VALUES plans.
     */
    String INSERT_VALUES = "INSERT_VALUES";

    /**
     * Get the DML statement type.
     *
     * @return statement type
     */
    String getStatementType();

    /**
     * Get the target table name.
     *
     * @return target table name
     */
    String getTableName();

    /**
     * Get the table engine provider id when it is known.
     *
     * @return table engine provider id, or null
     */
    String getTableEngineProviderId();

    /**
     * Get the insert column count.
     *
     * @return column count
     */
    int getColumnCount();

    /**
     * Whether the plan can later be used by a prepared statement batch.
     *
     * @return true if batch capable
     */
    boolean isBatchCapable();

    /**
     * Whether generated keys are requested.
     *
     * @return true if generated keys are requested
     */
    boolean requestsGeneratedKeys();
}
