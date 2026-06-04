/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreOnlineReclamationResult;

/**
 * Observes one low-intensity S2 automatic reclamation housekeeping round.
 */
public final class ReclamationObserver {

    private static final Method ONLINE_RECLAMATION_HOUSEKEEPING = lookupOnlineReclamationHousekeeping();

    public MVStoreOnlineReclamationResult observe(MVStore store) {
        if (ONLINE_RECLAMATION_HOUSEKEEPING == null) {
            return null;
        }
        try {
            return (MVStoreOnlineReclamationResult) ONLINE_RECLAMATION_HOUSEKEEPING.invoke(store);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access MVStore online reclamation housekeeping", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Could not run MVStore online reclamation housekeeping", cause);
        }
    }

    private static Method lookupOnlineReclamationHousekeeping() {
        try {
            return MVStore.class.getMethod("runOnlineReclamationHousekeeping");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
