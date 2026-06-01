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
    private static final String PUBLISH = PREFIX + "publish";
    private static final String JOB_PREFIX = PREFIX + "job.";
    private static final String ACTIVE_JOB = PREFIX + "activeJob";

    private final MVStore store;
    private final MVMap<String, String> metaMap;
    private final String jobId;

    private MVStoreReclamationJournal(MVStore store, String jobId) {
        this.store = store;
        this.metaMap = store.getMetaMap();
        this.jobId = jobId;
    }

    static MVStoreReclamationJournal begin(MVStore store, ArrayList<Integer> candidateChunks,
            MVStoreReclamationRequest request) {
        MVStoreReclamationJournal journal = new MVStoreReclamationJournal(store,
                Long.toHexString(System.currentTimeMillis()) + "-" + store.getCurrentVersion());
        journal.metaMap.put(JOB, journal.jobId);
        journal.metaMap.put(ACTIVE_JOB, journal.jobId);
        journal.metaMap.put(jobKey(journal.jobId, "createdVersion"), Long.toString(store.getCurrentVersion()));
        journal.metaMap.put(jobKey(journal.jobId, "createdTime"), Long.toString(System.currentTimeMillis()));
        journal.metaMap.put(jobKey(journal.jobId, "request"), request.asJournalString());
        journal.metaMap.put(CANDIDATES, candidateChunks.toString());
        for (int i = 0; i < candidateChunks.size(); i++) {
            journal.metaMap.put(jobKey(journal.jobId, "candidate." + candidateChunks.get(i)), "index=" + i);
        }
        journal.store.markMetaChanged();
        journal.phase("ANALYZING");
        return journal;
    }

    void phase(String phase) {
        metaMap.put(PHASE, phase);
        metaMap.put(jobKey(jobId, "phase"), phase);
        store.markMetaChanged();
        store.commit();
    }

    void publish() {
        metaMap.put(PUBLISH, "true");
        metaMap.put(jobKey(jobId, "publish"), "version=" + store.getCurrentVersion());
        store.markMetaChanged();
        phase("PUBLISHED");
    }

    void complete() {
        removeJournalKeys(metaMap);
        store.markMetaChanged();
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
        String jobId = metaMap.get(ACTIVE_JOB);
        if (jobId == null) {
            jobId = metaMap.get(JOB);
        }
        if (phase == null && jobId != null) {
            phase = metaMap.get(jobKey(jobId, "phase"));
        }
        boolean published = "true".equals(metaMap.get(PUBLISH));
        if (!published && jobId != null) {
            published = metaMap.get(jobKey(jobId, "publish")) != null;
        }
        removeJournalKeys(metaMap);
        store.markMetaChanged();
        store.commit();
        return new MVStoreReclamationRecovery(true, (published ? "RECOVERED_PUBLISHED_RECLAMATION_JOURNAL"
                : "RECOVERED_UNPUBLISHED_RECLAMATION_JOURNAL") + " job=" + jobId + " phase=" + phase);
    }

    static void writeRecoveryMarkerForTest(MVStore store, String phase, boolean published) {
        MVMap<String, String> metaMap = store.getMetaMap();
        String jobId = "test";
        metaMap.put(JOB, jobId);
        metaMap.put(ACTIVE_JOB, jobId);
        metaMap.put(PHASE, phase);
        metaMap.put(CANDIDATES, "[]");
        metaMap.put(jobKey(jobId, "createdVersion"), Long.toString(store.getCurrentVersion()));
        metaMap.put(jobKey(jobId, "createdTime"), Long.toString(System.currentTimeMillis()));
        metaMap.put(jobKey(jobId, "request"), "test");
        metaMap.put(jobKey(jobId, "phase"), phase);
        metaMap.put(jobKey(jobId, "candidate.1"), "index=0");
        if (published) {
            metaMap.put(PUBLISH, "true");
            metaMap.put(jobKey(jobId, "publish"), "version=" + store.getCurrentVersion());
        }
        store.markMetaChanged();
        store.commit();
    }

    private static boolean hasJournal(MVMap<String, String> metaMap) {
        return metaMap.get(JOB) != null || metaMap.get(PHASE) != null || metaMap.get(CANDIDATES) != null
                || metaMap.get(PUBLISH) != null || metaMap.get(ACTIVE_JOB) != null;
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

    private static String jobKey(String jobId, String suffix) {
        return JOB_PREFIX + jobId + "." + suffix;
    }
}
