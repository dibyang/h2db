/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.h2.engine.StorageEngineResolver;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.junit.jupiter.api.Test;

/**
 * storage engine id 解析边界的 JUnit 验证。
 */
public class StorageEngineResolverTest {

    /**
     * T-PLUGIN-F2-OLD-DB-DEFAULT-01.
     */
    @Test
    public void oldDatabaseWithoutPersistedIdDefaultsToMvStore() {
        assertEquals(MVStoreStorageEngineProvider.ID, StorageEngineResolver.resolvePersisted(null));
        assertEquals(MVStoreStorageEngineProvider.ID, StorageEngineResolver.resolvePersisted(""));
    }

    /**
     * T-PLUGIN-F2-STORAGE-ID-MISMATCH-01.
     */
    @Test
    public void rejectsRequestedStorageIdMismatch() {
        DbException e = assertThrows(DbException.class, () ->
                StorageEngineResolver.validateMatch("other", MVStoreStorageEngineProvider.ID));

        assertEquals(true, e.getMessage().contains("requested=other"));
        assertEquals(true, e.getMessage().contains("persisted=mvstore"));
    }

    /**
     * T-PLUGIN-F2-ROLLBACK-01.
     */
    @Test
    public void validatesDefaultMvStoreForRollbackPath() {
        StorageEngineResolver.validateMatch(MVStoreStorageEngineProvider.ID, null);
    }
}
