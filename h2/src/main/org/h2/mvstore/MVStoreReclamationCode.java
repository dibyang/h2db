/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * Stable diagnostic codes for MVStore online reclamation.
 */
public final class MVStoreReclamationCode {

    public static final String NO_RECLAMATION_CANDIDATE = "NO_RECLAMATION_CANDIDATE";
    public static final String DRY_RUN = "DRY_RUN";
    public static final String RECLAMATION_FAILED = "RECLAMATION_FAILED";
    public static final String NO_OPEN_MAP_RELOCATION_PROGRESS = "NO_OPEN_MAP_RELOCATION_PROGRESS";
    public static final String RECLAMATION_PAUSED_BY_TIME_BUDGET = "RECLAMATION_PAUSED_BY_TIME_BUDGET";
    public static final String RECLAMATION_ROUND_FINISHED = "RECLAMATION_ROUND_FINISHED";
    public static final String RECLAMATION_SCHEDULER_DISABLED = "RECLAMATION_SCHEDULER_DISABLED";
    public static final String RECLAMATION_SCHEDULER_BACKOFF = "RECLAMATION_SCHEDULER_BACKOFF";

    private MVStoreReclamationCode() {
    }
}
