/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.PluginCapability;
import org.h2.api.SystemCatalogContext;
import org.h2.api.SystemCatalogProvider;
import org.h2.message.DbException;

/**
 * MVStore-backed system catalog provider.
 * <p>
 * This provider currently wires system catalog capability into provider
 * selection and validation. The actual system tables still use the existing
 * MVStore {@link Store}. A non-MVStore main path needs an equivalent provider
 * before system tables, LOBs, transaction log, and temporary results can move.
 */
public final class MVStoreSystemCatalogProvider implements SystemCatalogProvider {

    private final String id;

    /**
     * Creates an MVStore-backed system catalog provider.
     *
     * @param id provider id, expected to match the storage provider id
     */
    public MVStoreSystemCatalogProvider(String id) {
        this.id = id;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean supports(String capability) {
        return PluginCapability.SYSTEM_CATALOG.equals(capability);
    }

    @Override
    public void validate(SystemCatalogContext context) {
        if (!(context.getStorageEngine() instanceof MVStoreBackedStorageEngine)) {
            throw DbException.getUnsupportedException("MVStore system catalog provider requires "
                    + "MVStore-backed storage engine: " + context.getStorageEngineId());
        }
    }
}
