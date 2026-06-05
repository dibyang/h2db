/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import org.h2.engine.Database;

/**
 * Context passed to transaction event providers.
 */
public interface TransactionContext {

    /**
     * Returns the current database.
     *
     * @return database
     */
    Database getDatabase();

    /**
     * Returns the session id that owns the transaction event.
     *
     * @return session id
     */
    int getSessionId();

    /**
     * Whether the event belongs to a DDL-triggered transaction boundary.
     *
     * @return {@code true} for DDL transaction boundaries
     */
    boolean isDdl();

    /**
     * Whether the session is in auto-commit mode when the event is emitted.
     *
     * @return {@code true} when auto-commit is enabled
     */
    boolean isAutoCommit();

    /**
     * Whether the session had a transaction when the event was created.
     *
     * @return {@code true} when a transaction was active
     */
    boolean hasTransaction();
}
