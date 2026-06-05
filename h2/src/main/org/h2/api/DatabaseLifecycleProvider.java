/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Provider for database lifecycle events.
 */
public interface DatabaseLifecycleProvider extends PluginProvider {

    /**
     * Provider type.
     */
    String TYPE = "database_lifecycle";

    /**
     * Called after the database has entered the close path and before storage
     * resources are closed.
     *
     * @param context database lifecycle context
     */
    default void beforeClose(DatabaseLifecycleContext context) {
    }

    /**
     * Called after storage resources are closed and before the trace system is
     * closed.
     *
     * @param context database lifecycle context
     */
    default void afterClose(DatabaseLifecycleContext context) {
    }
}
