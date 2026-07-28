/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.util.UUID;

import org.h2.api.ErrorCode;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.message.DbException;
import org.h2.mvstore.tx.TransactionMap;
import org.h2.mvstore.type.StringDataType;

/**
 * Persistent logical database identity and runtime generation identity.
 *
 * <p>The logical identity and schema epoch are stored transactionally in the
 * database file. The generation identity is supplied by the process that owns
 * routing and is never written to this map.</p>
 */
public final class DatabaseIdentityMetadata {

    /**
     * Internal transactional map name.
     */
    public static final String MAP_NAME = "h2.onlineBackup.meta";

    private static final String DATABASE_ID = "databaseId";
    private static final String SCHEMA_EPOCH = "schemaEpoch";

    private final UUID generationId;

    private volatile UUID databaseId;
    private volatile long schemaEpoch;
    private volatile boolean available;
    private volatile boolean trackingEnabled;

    /**
     * Create metadata state for one database generation.
     *
     * @param generationId configured generation UUID
     */
    public DatabaseIdentityMetadata(String generationId) {
        this.generationId = parseGenerationId(generationId);
    }

    /**
     * Parse and strictly validate a configured generation UUID.
     *
     * @param value configured value
     * @return parsed UUID
     */
    public static UUID parseGenerationId(String value) {
        if (value == null || value.isEmpty()) {
            throw DbException.getInvalidValueException(
                    "ONLINE_BACKUP_GENERATION_ID", value);
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw DbException.getInvalidValueException(
                    "ONLINE_BACKUP_GENERATION_ID", value);
        }
    }

    /**
     * Initialize or read the transactional identity map.
     *
     * @param database database
     * @param session system session
     */
    public void initialize(Database database, SessionLocal session) {
        boolean mapExists = database.getStore().getTransactionStore()
                .hasMap(MAP_NAME);
        if (!mapExists && database.isReadOnly()) {
            return;
        }
        try {
            TransactionMap<String, String> map = openMap(session);
            String databaseIdValue = map.get(DATABASE_ID);
            String schemaEpochValue = map.get(SCHEMA_EPOCH);
            if (databaseIdValue == null && schemaEpochValue == null) {
                if (database.isReadOnly()) {
                    session.rollback();
                    return;
                }
                UUID newDatabaseId = UUID.randomUUID();
                map.put(DATABASE_ID, newDatabaseId.toString());
                map.put(SCHEMA_EPOCH, "0");
                session.commit(false);
                databaseId = newDatabaseId;
                schemaEpoch = 0L;
            } else {
                UUID parsedDatabaseId = parseDatabaseId(databaseIdValue);
                long parsedSchemaEpoch = parseSchemaEpoch(schemaEpochValue);
                session.rollback();
                databaseId = parsedDatabaseId;
                schemaEpoch = parsedSchemaEpoch;
            }
            available = true;
        } catch (RuntimeException | Error e) {
            try {
                session.rollback();
            } catch (Throwable rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    /**
     * Write the next schema epoch in the current catalog transaction.
     *
     * @param session committing session
     * @return pending epoch
     */
    public long prepareSchemaEpochIncrement(SessionLocal session) {
        Snapshot snapshot = requireSnapshot();
        TransactionMap<String, String> map = openMap(session);
        UUID storedDatabaseId = parseDatabaseId(map.get(DATABASE_ID));
        if (!snapshot.databaseId.equals(storedDatabaseId)) {
            throw DbException.get(ErrorCode.FILE_CORRUPTED_1, MAP_NAME);
        }
        long storedEpoch = parseSchemaEpoch(map.get(SCHEMA_EPOCH));
        long nextEpoch;
        try {
            nextEpoch = Math.addExact(storedEpoch, 1L);
        } catch (ArithmeticException e) {
            throw DbException.get(ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1,
                    Long.toString(storedEpoch));
        }
        map.put(SCHEMA_EPOCH, Long.toString(nextEpoch));
        return nextEpoch;
    }

    /**
     * Publish an epoch after its transaction has committed.
     *
     * @param committedEpoch committed epoch
     */
    public synchronized void schemaEpochCommitted(long committedEpoch) {
        if (committedEpoch > schemaEpoch) {
            schemaEpoch = committedEpoch;
        }
    }

    /**
     * Get the runtime generation identity.
     *
     * @return generation UUID
     */
    public UUID getGenerationId() {
        return generationId;
    }

    /**
     * Whether persistent logical identity metadata is available.
     *
     * @return whether identity metadata is available
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Enable schema epoch tracking after database bootstrap has completed.
     */
    public void startTracking() {
        trackingEnabled = available;
    }

    /**
     * Whether catalog mutations should be tracked.
     *
     * @return whether tracking is enabled
     */
    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    /**
     * Get metadata when it exists.
     *
     * @return snapshot, or {@code null} for a read-only legacy database
     */
    public Snapshot getSnapshot() {
        return available
                ? new Snapshot(databaseId, generationId, schemaEpoch) : null;
    }

    /**
     * Require persistent identity metadata.
     *
     * @return metadata snapshot
     */
    public Snapshot requireSnapshot() {
        Snapshot snapshot = getSnapshot();
        if (snapshot == null) {
            throw DbException.get(ErrorCode.ONLINE_BACKUP_IDENTITY_REQUIRED,
                    MAP_NAME);
        }
        return snapshot;
    }

    /**
     * Validate that a management request targets this generation.
     *
     * @param expected expected generation UUID
     */
    public void validateGenerationId(UUID expected) {
        if (!generationId.equals(expected)) {
            throw DbException.get(
                    ErrorCode.ONLINE_BACKUP_GENERATION_MISMATCH_2,
                    expected.toString(), generationId.toString());
        }
    }

    private static TransactionMap<String, String> openMap(
            SessionLocal session) {
        return session.getTransaction().openMap(MAP_NAME,
                StringDataType.INSTANCE, StringDataType.INSTANCE);
    }

    private static UUID parseDatabaseId(String value) {
        if (value == null) {
            throw DbException.get(ErrorCode.FILE_CORRUPTED_1, MAP_NAME);
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw DbException.get(ErrorCode.FILE_CORRUPTED_1, MAP_NAME);
        }
    }

    private static long parseSchemaEpoch(String value) {
        if (value == null) {
            throw DbException.get(ErrorCode.FILE_CORRUPTED_1, MAP_NAME);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw DbException.get(ErrorCode.FILE_CORRUPTED_1, MAP_NAME);
        }
    }

    /**
     * Immutable database and generation identity snapshot.
     */
    public static final class Snapshot {

        private final UUID databaseId;
        private final UUID generationId;
        private final long schemaEpoch;

        private Snapshot(UUID databaseId, UUID generationId,
                long schemaEpoch) {
            this.databaseId = databaseId;
            this.generationId = generationId;
            this.schemaEpoch = schemaEpoch;
        }

        /**
         * Get the persistent logical database identity.
         *
         * @return database UUID
         */
        public UUID getDatabaseId() {
            return databaseId;
        }

        /**
         * Get the runtime generation identity.
         *
         * @return generation UUID
         */
        public UUID getGenerationId() {
            return generationId;
        }

        /**
         * Get the committed schema epoch.
         *
         * @return schema epoch
         */
        public long getSchemaEpoch() {
            return schemaEpoch;
        }
    }
}
