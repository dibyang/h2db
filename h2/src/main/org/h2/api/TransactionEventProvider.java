/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Provider for database transaction lifecycle events.
 */
public interface TransactionEventProvider extends PluginProvider {

    /**
     * Provider type.
     */
    String TYPE = "transaction";

    /**
     * Called before a transaction is committed.
     *
     * @param context transaction context
     */
    default void beforeCommit(TransactionContext context) {
    }

    /**
     * Called after a transaction is committed and transaction state is cleared.
     *
     * @param context transaction context
     */
    default void afterCommit(TransactionContext context) {
    }

    /**
     * Called before a transaction is rolled back.
     *
     * @param context transaction context
     */
    default void beforeRollback(TransactionContext context) {
    }

    /**
     * Called after a transaction is rolled back and session state is cleaned up.
     *
     * @param context transaction context
     */
    default void afterRollback(TransactionContext context) {
    }
}
