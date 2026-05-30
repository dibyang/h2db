/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Persistent old-page-position to new-page-position map for online reclamation.
 */
public final class MVStoreReclamationRelocationMap {

    private static final String PREFIX = "reclaim.s2.relocation.";
    private static final String FEATURE = "reclaim.s2.feature.relocationMap";

    private MVStoreReclamationRelocationMap() {
    }

    public static void put(MVStore store, long oldPosition, long newPosition) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        MVMap<String, String> metaMap = store.getMetaMap();
        metaMap.put(FEATURE, "true");
        metaMap.put(key(oldPosition), Long.toHexString(newPosition));
        store.markMetaChanged();
        store.commit();
    }

    public static long resolve(MVStore store, long position) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        return resolve(store.getMetaMap(), position);
    }

    public static boolean hasMappings(MVStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        return hasMappings(store.getMetaMap());
    }

    public static void clear(MVStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        MVMap<String, String> metaMap = store.getMetaMap();
        ArrayList<String> keys = new ArrayList<>();
        for (Iterator<String> it = metaMap.keyIterator(PREFIX); it.hasNext();) {
            String key = it.next();
            if (!key.startsWith(PREFIX)) {
                break;
            }
            keys.add(key);
        }
        for (String key : keys) {
            metaMap.remove(key);
        }
        metaMap.remove(FEATURE);
        store.markMetaChanged();
        store.commit();
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

    static boolean hasMappings(MVMap<String, String> metaMap) {
        return "true".equals(metaMap.get(FEATURE));
    }

    private static String key(long position) {
        return PREFIX + Long.toHexString(position);
    }
}
