/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * Persistent old-page-position to new-page-position map for online reclamation.
 */
public final class MVStoreReclamationRelocationMap {

    private static final String PREFIX = "reclaim.s2.relocation.";

    private MVStoreReclamationRelocationMap() {
    }

    public static void put(MVStore store, long oldPosition, long newPosition) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        store.getMetaMap().put(key(oldPosition), Long.toHexString(newPosition));
        store.commit();
    }

    public static long resolve(MVStore store, long position) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        return resolve(store.getMetaMap(), position);
    }

    static long resolve(MVMap<String, String> metaMap, long position) {
        String value = metaMap.get(key(position));
        if (value == null) {
            return position;
        }
        try {
            return Long.parseLong(value, 16);
        } catch (NumberFormatException e) {
            return position;
        }
    }

    private static String key(long position) {
        return PREFIX + Long.toHexString(position);
    }
}
