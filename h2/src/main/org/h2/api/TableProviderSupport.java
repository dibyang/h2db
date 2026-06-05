/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.List;

import org.h2.command.ddl.CreateTableData;
import org.h2.message.DbException;

/**
 * Convenience checks for {@link TableEngineProvider} implementations.
 */
public final class TableProviderSupport {

    private TableProviderSupport() {
    }

    /**
     * Rejects table creation in read-only databases.
     *
     * @param context table provider context
     * @param data table creation data
     * @param providerId table provider id
     */
    public static void requireWritable(TableEngineContext context, CreateTableData data, String providerId) {
        if (context.isReadOnly()) {
            throw DbException.getUnsupportedException("Table provider cannot create table in read-only database: "
                    + diagnostic(providerId, data));
        }
    }

    /**
     * Requires the active storage engine to be of the expected type.
     *
     * @param context table provider context
     * @param storageType expected storage engine type
     * @param providerId table provider id
     * @param data table creation data
     * @param <T> expected storage engine type
     * @return active storage engine cast to the expected type
     */
    public static <T extends StorageEngine> T requireStorageEngine(TableEngineContext context, Class<T> storageType,
            String providerId, CreateTableData data) {
        StorageEngine storage = context.getStorageEngine();
        if (!storageType.isInstance(storage)) {
            String actual = storage == null ? "null" : storage.getClass().getName();
            throw DbException.getUnsupportedException("Table provider requires incompatible storage engine: "
                    + diagnostic(providerId, data) + ", required=" + storageType.getName() + ", actual=" + actual);
        }
        return storageType.cast(storage);
    }

    /**
     * Wraps provider failures with stable table/provider diagnostics.
     *
     * @param providerId table provider id
     * @param data table creation data
     * @param message failure message
     * @param cause original failure
     * @return database exception with the original cause attached
     */
    public static DbException createTableException(String providerId, CreateTableData data, String message,
            Throwable cause) {
        return DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, cause,
                "Table provider createTable failed: " + diagnostic(providerId, data) + ", reason=" + message);
    }

    private static String diagnostic(String providerId, CreateTableData data) {
        return "provider=" + providerId + ", table=" + tableName(data) + ", params=" + params(data);
    }

    private static String tableName(CreateTableData data) {
        if (data == null || data.tableName == null) {
            return "<unknown>";
        }
        return data.tableName;
    }

    private static String params(CreateTableData data) {
        if (data == null) {
            return "[]";
        }
        List<String> params = data.tableEngineParams;
        return params == null ? "[]" : params.toString();
    }
}
