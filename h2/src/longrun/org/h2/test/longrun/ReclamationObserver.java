/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreOnlineReclamationResult;

/**
 * Observes one low-intensity S2 automatic reclamation housekeeping round.
 */
public final class ReclamationObserver {

    public MVStoreOnlineReclamationResult observe(MVStore store) {
        return store.runOnlineReclamationHousekeeping();
    }
}
