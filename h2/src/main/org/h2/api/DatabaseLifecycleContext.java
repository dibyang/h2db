/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import org.h2.engine.Database;

/**
 * Context passed to database lifecycle providers.
 */
public interface DatabaseLifecycleContext {

    /**
     * Returns the database that is emitting the lifecycle event.
     *
     * @return database
     */
    Database getDatabase();

    /**
     * Returns the internal database name.
     *
     * @return database name
     */
    String getDatabaseName();

    /**
     * Returns the JDBC URL used to open the database.
     *
     * @return database URL
     */
    String getDatabaseUrl();

    /**
     * Returns the selected storage engine id.
     *
     * @return storage engine id
     */
    String getStorageEngineId();

    /**
     * Whether the database is persistent.
     *
     * @return {@code true} for persistent databases
     */
    boolean isPersistent();

    /**
     * Whether the close was initiated by the shutdown hook.
     *
     * @return {@code true} when closing from the shutdown hook
     */
    boolean isFromShutdownHook();
}
