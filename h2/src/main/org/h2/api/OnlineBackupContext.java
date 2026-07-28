/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Immutable identity and deadline context shared by all backup participants.
 */
public final class OnlineBackupContext {

    private final UUID backupId;
    private final UUID cutId;
    private final UUID databaseId;
    private final UUID generationId;
    private final long schemaEpoch;
    private final long deadlineNanos;

    /**
     * Create a context.
     *
     * @param backupId backup identity
     * @param cutId cut identity
     * @param databaseId logical database identity
     * @param generationId runtime generation identity
     * @param schemaEpoch committed schema epoch
     * @param deadlineNanos absolute deadline in {@link System#nanoTime()} domain
     */
    public OnlineBackupContext(UUID backupId, UUID cutId, UUID databaseId,
            UUID generationId, long schemaEpoch, long deadlineNanos) {
        this.backupId = requireNonNull(backupId, "backupId");
        this.cutId = requireNonNull(cutId, "cutId");
        this.databaseId = requireNonNull(databaseId, "databaseId");
        this.generationId = requireNonNull(generationId, "generationId");
        if (schemaEpoch < 0L) {
            throw new IllegalArgumentException(
                    "schemaEpoch must not be negative");
        }
        this.schemaEpoch = schemaEpoch;
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * @return backup identity
     */
    public UUID getBackupId() {
        return backupId;
    }

    /**
     * @return cut identity
     */
    public UUID getCutId() {
        return cutId;
    }

    /**
     * @return logical database identity
     */
    public UUID getDatabaseId() {
        return databaseId;
    }

    /**
     * @return runtime generation identity
     */
    public UUID getGenerationId() {
        return generationId;
    }

    /**
     * @return committed schema epoch
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }

    /**
     * @return absolute monotonic deadline
     */
    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Get the remaining shared prepare budget.
     *
     * @return remaining milliseconds, rounded up
     */
    public long getRemainingMillis() {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            return 0L;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        return millis == Long.MAX_VALUE || TimeUnit.MILLISECONDS
                .toNanos(millis) == remaining ? millis : millis + 1L;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
