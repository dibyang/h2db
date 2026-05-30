/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Optional durable journal scaffold for MVStore online reclamation.
 */
final class MVStoreReclamationJournal {

    static final String PREFIX = "reclaim.s2.";

    private static final String JOB = PREFIX + "job";
    private static final String PHASE = PREFIX + "phase";
    private static final String CANDIDATES = PREFIX + "candidates";

    private final MVStore store;
    private final MVMap<String, String> metaMap;
    private final String jobId;

    private MVStoreReclamationJournal(MVStore store, String jobId) {
        this.store = store;
        this.metaMap = store.getMetaMap();
        this.jobId = jobId;
    }

    static MVStoreReclamationJournal begin(MVStore store, ArrayList<Integer> candidateChunks) {
        MVStoreReclamationJournal journal = new MVStoreReclamationJournal(store,
                Long.toHexString(System.currentTimeMillis()) + "-" + store.getCurrentVersion());
        journal.metaMap.put(JOB, journal.jobId);
        journal.metaMap.put(CANDIDATES, candidateChunks.toString());
        journal.phase("ANALYZING");
        return journal;
    }

    void phase(String phase) {
        metaMap.put(PHASE, phase);
        store.commit();
    }

    void complete() {
        removeJournalKeys(metaMap);
        store.commit();
    }

    static MVStoreReclamationRecovery recover(MVStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        MVMap<String, String> metaMap = store.getMetaMap();
        if (!hasJournal(metaMap)) {
            return new MVStoreReclamationRecovery(false, "NO_RECLAMATION_JOURNAL");
        }
        String phase = metaMap.get(PHASE);
        removeJournalKeys(metaMap);
        store.commit();
        return new MVStoreReclamationRecovery(true, "RECOVERED_RECLAMATION_JOURNAL phase=" + phase);
    }

    static void writeRecoveryMarkerForTest(MVStore store, String phase) {
        MVMap<String, String> metaMap = store.getMetaMap();
        metaMap.put(JOB, "test");
        metaMap.put(PHASE, phase);
        metaMap.put(CANDIDATES, "[]");
        store.commit();
    }

    private static boolean hasJournal(MVMap<String, String> metaMap) {
        return metaMap.get(JOB) != null || metaMap.get(PHASE) != null || metaMap.get(CANDIDATES) != null;
    }

    private static void removeJournalKeys(MVMap<String, String> metaMap) {
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
    }
}
