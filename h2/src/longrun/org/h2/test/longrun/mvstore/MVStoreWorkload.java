/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun.mvstore;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Random;
import java.util.zip.CRC32;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreOnlineReclamationResult;
import org.h2.test.longrun.FaultInjectionKind;
import org.h2.test.longrun.FaultInjectionResult;
import org.h2.test.longrun.FileCorruptionInjector;
import org.h2.test.longrun.LongRunConfig;
import org.h2.test.longrun.LongRunState;
import org.h2.test.longrun.LongRunWorkload;
import org.h2.test.longrun.ReclamationObserver;

/**
 * Deterministic MVStore workload for the first longrun smoke phase.
 */
public final class MVStoreWorkload implements LongRunWorkload {

    private static final Method ONLINE_RECLAMATION_MAX_CANDIDATE_CHUNKS = lookupBuilderIntOption(
            "onlineReclamationMaxCandidateChunks");
    private static final Method ONLINE_RECLAMATION_MAX_LIVE_BYTES_TO_REWRITE = lookupBuilderIntOption(
            "onlineReclamationMaxLiveBytesToRewrite");
    private static final Method ONLINE_RECLAMATION_MAX_RUN_MILLIS = lookupBuilderIntOption(
            "onlineReclamationMaxRunMillis");
    private static final Method ONLINE_RECLAMATION_MAX_TAIL_COMPACTION_MILLIS = lookupBuilderIntOption(
            "onlineReclamationMaxTailCompactionMillis");
    private static final Method ONLINE_RECLAMATION_MIN_INTERVAL_MILLIS = lookupBuilderIntOption(
            "onlineReclamationMinIntervalMillis");

    private final LongRunConfig config;
    private final LongRunState state;
    private final Random random;
    private final ReclamationObserver reclamationObserver = new ReclamationObserver();
    private final File file;
    private MVStore store;
    private MVMap<Long, String> data;
    private MVMap<Long, String> ledger;
    private MVMap<String, Long> counters;
    private boolean onlineReclamationBuilderOptionsApplied;
    private int faultKindIndex;

    public MVStoreWorkload(LongRunConfig config, LongRunState state) {
        this.config = config;
        this.state = state;
        random = new Random(config.getSeed());
        file = new File(config.getWorkDir(), "mvstore-longrun.mv.db");
        if (!config.isResume() && file.exists() && !file.delete()) {
            throw new IllegalStateException("Could not delete old longrun store: " + file);
        }
        openStore();
        if (config.isResume()) {
            long committedSequence = counter("lastSequence");
            state.ensureOperationSequenceAtLeast(committedSequence);
        }
    }

    @Override
    public void step() {
        int operation = random.nextInt(100);
        long key = random.nextInt(config.getKeySpace());
        if (operation < 50) {
            put(key);
        } else if (operation < 85) {
            get(key);
        } else {
            remove(key);
        }
        if (state.getOperationSequence() % 1_000L == 0L) {
            commit();
        }
    }

    @Override
    public void commit() {
        store.commit();
        state.commit();
    }

    @Override
    public MVStoreOnlineReclamationResult runReclamation() {
        return reclamationObserver.observe(store);
    }

    @Override
    public void collectReportProperties(Properties properties) {
        long dataEntries = data.sizeAsLong();
        long ledgerEntries = ledger.sizeAsLong();
        long activeKeys = counter("activeKeys");
        long averageValueSize = (config.getValueSizeMin() + config.getValueSizeMax()) / 2L;
        long estimatedLiveDataBytes = Math.max(1L, dataEntries) * Math.max(1L, averageValueSize);
        properties.setProperty("mvstore.dataEntries", Long.toString(dataEntries));
        properties.setProperty("mvstore.ledgerEntries", Long.toString(ledgerEntries));
        properties.setProperty("mvstore.ledgerMode", config.getLedgerMode());
        properties.setProperty("mvstore.ledgerMaxEntries", Long.toString(config.getLedgerMaxEntries()));
        properties.setProperty("mvstore.retentionTimeMillis", Integer.toString(config.getRetentionTimeMillis()));
        properties.setProperty("mvstore.versionsToKeep", Integer.toString(config.getVersionsToKeep()));
        properties.setProperty("mvstore.activeKeys", Long.toString(activeKeys));
        properties.setProperty("mvstore.fileSizeBytes", Long.toString(file.length()));
        properties.setProperty("mvstore.estimatedLiveDataBytes", Long.toString(estimatedLiveDataBytes));
        properties.setProperty("mvstore.sizeAmplification",
                Double.toString(file.length() / (double) estimatedLiveDataBytes));
        properties.setProperty("mvstore.onlineReclamationBuilderOptionsApplied",
                Boolean.toString(onlineReclamationBuilderOptionsApplied));
    }

    private void put(long key) {
        long sequence = state.nextSequence();
        String old = data.put(key, value(key, sequence));
        recordLedger(sequence, "PUT:" + key);
        if (old == null) {
            incrementCounter("activeKeys", 1L);
        }
        counters.put("lastSequence", sequence);
        state.write();
    }

    private void get(long key) {
        state.nextSequence();
        String value = data.get(key);
        if (value != null) {
            verifyValue(key, value);
        }
        state.read();
    }

    private void remove(long key) {
        long sequence = state.nextSequence();
        String old = data.remove(key);
        recordLedger(sequence, "REMOVE:" + key);
        if (old != null) {
            incrementCounter("activeKeys", -1L);
        }
        counters.put("lastSequence", sequence);
        state.remove();
    }

    @Override
    public void reopenAndVerify() {
        commit();
        closeStore();
        openStore();
        verify();
    }

    @Override
    public FaultInjectionResult runFaultInjection(long eventId) throws Exception {
        commit();
        verify();
        closeStore();
        try {
            FaultInjectionResult result = runFaultInjectionOnCopy(eventId);
            openStore();
            verify();
            return result;
        } catch (Exception e) {
            openStore();
            verify();
            throw e;
        }
    }

    @Override
    public void verify() {
        long active = 0L;
        for (Long key : data.keySet()) {
            verifyValue(key.longValue(), data.get(key));
            active++;
        }
        long expectedActive = counter("activeKeys");
        if (expectedActive != active) {
            throw new IllegalStateException("Active key counter mismatch: expected=" + expectedActive
                    + " actual=" + active);
        }
        Long lastSequence = counters.get("lastSequence");
        if (lastSequence != null && !containsLedgerSequence(lastSequence.longValue())) {
            throw new IllegalStateException("Ledger missing last sequence " + lastSequence);
        }
    }

    private FaultInjectionResult runFaultInjectionOnCopy(long eventId) throws IOException {
        File faultDir = new File(config.getWorkDir(), "fault");
        if (!faultDir.isDirectory() && !faultDir.mkdirs()) {
            throw new IOException("Could not create fault dir: " + faultDir);
        }
        File target = new File(faultDir, "fault-" + eventId + ".mv.db");
        Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        FaultInjectionKind kind = nextFaultKind();
        Random faultRandom = new Random(config.getSeed() ^ eventId ^ (kind.ordinal() * 31L));
        FileCorruptionInjector.Mutation mutation = new FileCorruptionInjector()
                .mutate(target, kind, faultRandom, config.getFaultMaxBytes());
        FaultInjectionResult result = verifyFaultCopy(eventId, kind, target, mutation);
        pruneFaultCopies(faultDir, eventId);
        return result;
    }

    private FaultInjectionKind nextFaultKind() {
        FaultInjectionKind[] kinds = config.getFaultKinds();
        FaultInjectionKind kind = kinds[faultKindIndex % kinds.length];
        faultKindIndex++;
        return kind;
    }

    private FaultInjectionResult verifyFaultCopy(long eventId, FaultInjectionKind kind, File target,
            FileCorruptionInjector.Mutation mutation) {
        MVStore copy = null;
        try {
            copy = new MVStore.Builder().fileName(target.getPath()).readOnly().open();
            verifyCopy(copy);
            closeStore(copy);
            copy = null;
            return result(eventId, kind, "RECOVERED", "copy opened and verified", target, mutation);
        } catch (MVStoreException e) {
            return result(eventId, kind, "DETECTED", rootMessage(e), target, mutation);
        } catch (RuntimeException e) {
            return result(eventId, kind, "DETECTED_BY_VERIFY", rootMessage(e), target, mutation);
        } finally {
            closeStoreImmediately(copy);
        }
    }

    private static void verifyCopy(MVStore copy) {
        MVMap<Long, String> copyData = copy.openMap("data");
        MVMap<Long, String> copyLedger = copy.openMap("ledger");
        MVMap<String, Long> copyCounters = copy.openMap("counters");
        long active = 0L;
        for (Long key : copyData.keySet()) {
            verifyValue(key.longValue(), copyData.get(key));
            active++;
        }
        Long expectedActive = copyCounters.get("activeKeys");
        if (expectedActive != null && expectedActive.longValue() != active) {
            throw new IllegalStateException("Active key counter mismatch in fault copy: expected="
                    + expectedActive + " actual=" + active);
        }
        Long lastSequence = copyCounters.get("lastSequence");
        Long lastLedgerKey = copyCounters.get("lastLedgerKey");
        if (lastSequence != null && lastLedgerKey != null) {
            String entry = copyLedger.get(lastLedgerKey);
            if (entry == null || !entry.startsWith(lastSequence + ":")) {
                throw new IllegalStateException("Ledger missing last sequence in fault copy " + lastSequence);
            }
        }
    }

    private static FaultInjectionResult result(long eventId, FaultInjectionKind kind, String status, String message,
            File target, FileCorruptionInjector.Mutation mutation) {
        return new FaultInjectionResult(eventId, kind, status, message, target, mutation.getOffset(),
                mutation.getLength(), mutation.getBeforeSize(), mutation.getAfterSize());
    }

    private void pruneFaultCopies(File faultDir, long currentEventId) throws IOException {
        int retainedCopies = config.getFaultRetainedCopies();
        long deleteThrough = currentEventId - retainedCopies;
        if (deleteThrough < 1L) {
            return;
        }
        File[] files = faultDir.listFiles((dir, name) -> name.startsWith("fault-") && name.endsWith(".mv.db"));
        if (files == null) {
            return;
        }
        for (File old : files) {
            long eventId = parseFaultCopyEventId(old.getName());
            if (eventId > 0L && eventId <= deleteThrough && !old.delete()) {
                throw new IOException("Could not delete old fault copy: " + old);
            }
        }
    }

    private static long parseFaultCopyEventId(String name) {
        int dash = name.indexOf('-');
        int dot = name.indexOf('.', dash + 1);
        if (dash < 0 || dot < 0) {
            return -1L;
        }
        try {
            return Long.parseLong(name.substring(dash + 1, dot));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void recordLedger(long sequence, String event) {
        long ledgerKey = ledgerKey(sequence);
        ledger.put(ledgerKey, sequence + ":" + event);
        counters.put("lastLedgerKey", ledgerKey);
    }

    private boolean containsLedgerSequence(long sequence) {
        String entry = ledger.get(ledgerKey(sequence));
        return entry != null && entry.startsWith(sequence + ":");
    }

    private long ledgerKey(long sequence) {
        if ("append-only".equals(config.getLedgerMode())) {
            return sequence;
        }
        return sequence % config.getLedgerMaxEntries();
    }

    private String value(long key, long sequence) {
        int size = config.getValueSizeMin();
        if (config.getValueSizeMax() > config.getValueSizeMin()) {
            size += random.nextInt(config.getValueSizeMax() - config.getValueSizeMin() + 1);
        }
        StringBuilder builder = new StringBuilder(size + 64);
        builder.append(key).append(':').append(sequence).append(':');
        while (builder.length() < size) {
            builder.append((char) ('a' + random.nextInt(26)));
        }
        String body = builder.toString();
        return body + ':' + checksum(body);
    }

    private static void verifyValue(long key, String value) {
        int checksumSeparator = value.lastIndexOf(':');
        if (checksumSeparator < 0) {
            throw new IllegalStateException("Missing checksum for key " + key);
        }
        String body = value.substring(0, checksumSeparator);
        long expected = Long.parseLong(value.substring(checksumSeparator + 1));
        long actual = checksum(body);
        if (expected != actual) {
            throw new IllegalStateException("Checksum mismatch for key " + key);
        }
    }

    private static long checksum(String value) {
        CRC32 crc32 = new CRC32();
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ":" + root.getMessage();
    }

    private void openStore() {
        MVStore.Builder builder = new MVStore.Builder()
                .fileName(file.getPath())
                .autoCommitDisabled();
        boolean applied = applyBuilderIntOption(builder, ONLINE_RECLAMATION_MAX_CANDIDATE_CHUNKS,
                config.getReclamationMaxCandidateChunks());
        applied &= applyBuilderIntOption(builder, ONLINE_RECLAMATION_MAX_LIVE_BYTES_TO_REWRITE,
                config.getReclamationMaxLiveBytesToRewrite());
        applied &= applyBuilderIntOption(builder, ONLINE_RECLAMATION_MAX_RUN_MILLIS,
                config.getReclamationMaxRunMillis());
        applied &= applyBuilderIntOption(builder, ONLINE_RECLAMATION_MAX_TAIL_COMPACTION_MILLIS,
                config.getReclamationMaxTailCompactionMillis());
        applied &= applyBuilderIntOption(builder, ONLINE_RECLAMATION_MIN_INTERVAL_MILLIS,
                config.getReclamationMinSchedulerIntervalMillis());
        onlineReclamationBuilderOptionsApplied = applied;
        store = builder.open();
        store.setRetentionTime(config.getRetentionTimeMillis());
        store.setVersionsToKeep(config.getVersionsToKeep());
        data = store.openMap("data");
        ledger = store.openMap("ledger");
        counters = store.openMap("counters");
        if (!counters.containsKey("activeKeys")) {
            counters.put("activeKeys", 0L);
        }
    }

    private long counter(String name) {
        Long value = counters.get(name);
        return value == null ? 0L : value.longValue();
    }

    private void incrementCounter(String name, long delta) {
        counters.put(name, counter(name) + delta);
    }

    @Override
    public void close() {
        closeStore();
    }

    private void closeStore() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    private static void closeStore(MVStore store) {
        if (store != null) {
            store.close();
        }
    }

    private static void closeStoreImmediately(MVStore store) {
        if (store != null) {
            try {
                store.closeImmediately();
            } catch (RuntimeException ignore) {
                // ignore
            }
        }
    }

    /**
     * Applies an optional integer MVStore builder method when the tested H2 jar provides it.
     *
     * @param builder MVStore builder instance
     * @param methodName optional method name
     * @param value integer option value
     * @return whether the method existed and was invoked
     */
    public static boolean applyBuilderIntOption(Object builder, String methodName, int value) {
        return applyBuilderIntOption(builder, lookupBuilderIntOption(builder.getClass(), methodName), value);
    }

    private static Method lookupBuilderIntOption(String methodName) {
        return lookupBuilderIntOption(MVStore.Builder.class, methodName);
    }

    private static Method lookupBuilderIntOption(Class<?> builderClass, String methodName) {
        try {
            return builderClass.getMethod(methodName, Integer.TYPE);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static boolean applyBuilderIntOption(Object builder, Method method, int value) {
        if (method == null) {
            return false;
        }
        try {
            method.invoke(builder, Integer.valueOf(value));
            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access MVStore builder method " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Could not invoke MVStore builder method " + method.getName(), cause);
        }
    }
}
