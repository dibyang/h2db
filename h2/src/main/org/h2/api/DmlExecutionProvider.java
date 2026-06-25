/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Experimental provider for DML execution fast-path planning.
 */
public interface DmlExecutionProvider extends PluginProvider {

    /**
     * Provider type for DML execution extensions.
     */
    String TYPE = "dml-execution";

    /**
     * Inspect a prepared DML command and return a supported plan when this
     * provider can take over a later execution phase.
     *
     * @param context prepare-time DML context
     * @return execution plan, or {@link DmlExecutionPlan#NONE}
     */
    DmlExecutionPlan prepareDml(DmlPrepareContext context);
}
