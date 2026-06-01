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
        put(store, oldPosition, newPosition, -1, -1L, -1L);
    }

    public static void put(MVStore store, long oldPosition, long newPosition, int mapId, long sourceVersion,
            long expireVersion) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        MVMap<String, String> metaMap = store.getMetaMap();
        metaMap.put(FEATURE, "true");
        metaMap.put(key(oldPosition), value(newPosition, mapId, sourceVersion, expireVersion));
        store.markMetaChanged();
        store.commit();
    }

    public static long resolve(MVStore store, long position) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        return resolve(store.getMetaMap(), position);
    }

    public static long resolve(MVStore store, long position, int mapId, long oldestVersionToKeep) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        return resolve(store.getMetaMap(), position, mapId, oldestVersionToKeep);
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

    public static int removeExpired(MVStore store, long oldestVersionToKeep) {
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
            RelocationEntry entry = parse(metaMap.get(key));
            if (entry.expireVersion >= 0L && oldestVersionToKeep > entry.expireVersion) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            metaMap.remove(key);
        }
        if (!keys.isEmpty()) {
            store.markMetaChanged();
            store.commit();
        }
        return keys.size();
    }

    static long resolve(MVMap<String, String> metaMap, long position) {
        return resolve(metaMap, position, -1, -1L);
    }

    static long resolve(MVMap<String, String> metaMap, long position, int mapId, long oldestVersionToKeep) {
        String value = metaMap.get(key(position));
        if (value == null) {
            return position;
        }
        RelocationEntry entry = parse(value);
        if (entry.newPosition < 0L) {
            return position;
        }
        if (entry.mapId >= 0 && mapId >= 0 && entry.mapId != mapId) {
            return position;
        }
        if (entry.expireVersion >= 0L && oldestVersionToKeep > entry.expireVersion) {
            return position;
        }
        return entry.newPosition;
    }

    public static int countMappings(MVStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        MVMap<String, String> metaMap = store.getMetaMap();
        int count = 0;
        for (Iterator<String> it = metaMap.keyIterator(PREFIX); it.hasNext();) {
            String key = it.next();
            if (!key.startsWith(PREFIX)) {
                break;
            }
            count++;
        }
        return count;
    }

    private static RelocationEntry parse(String value) {
        if (value == null) {
            return RelocationEntry.NONE;
        }
        if (value.indexOf('=') < 0) {
            try {
                return new RelocationEntry(Long.parseLong(value, 16), -1, -1L, -1L);
            } catch (NumberFormatException e) {
                return RelocationEntry.NONE;
            }
        }
        long newPosition = -1L;
        int mapId = -1;
        long sourceVersion = -1L;
        long expireVersion = -1L;
        String[] fields = value.split(",");
        for (String field : fields) {
            int idx = field.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String name = field.substring(0, idx);
            String fieldValue = field.substring(idx + 1);
            try {
                if ("new".equals(name)) {
                    newPosition = Long.parseLong(fieldValue, 16);
                } else if ("map".equals(name)) {
                    mapId = Integer.parseInt(fieldValue);
                } else if ("source".equals(name)) {
                    sourceVersion = Long.parseLong(fieldValue);
                } else if ("expire".equals(name)) {
                    expireVersion = Long.parseLong(fieldValue);
                }
            } catch (NumberFormatException e) {
                return RelocationEntry.NONE;
            }
        }
        return new RelocationEntry(newPosition, mapId, sourceVersion, expireVersion);
    }

    private static String value(long newPosition, int mapId, long sourceVersion, long expireVersion) {
        return "new=" + Long.toHexString(newPosition) + ",map=" + mapId + ",source=" + sourceVersion
                + ",expire=" + expireVersion;
    }

    private static final class RelocationEntry {
        static final RelocationEntry NONE = new RelocationEntry(-1L, -1, -1L, -1L);

        final long newPosition;
        final int mapId;
        final long sourceVersion;
        final long expireVersion;

        RelocationEntry(long newPosition, int mapId, long sourceVersion, long expireVersion) {
            this.newPosition = newPosition;
            this.mapId = mapId;
            this.sourceVersion = sourceVersion;
            this.expireVersion = expireVersion;
        }
    }

    static boolean hasMappings(MVMap<String, String> metaMap) {
        return "true".equals(metaMap.get(FEATURE));
    }

    private static String key(long position) {
        return PREFIX + Long.toHexString(position);
    }
}
