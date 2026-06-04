/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Random;

/**
 * Applies deterministic byte-level corruption to a copied test file.
 */
public final class FileCorruptionInjector {

    private static final int MVSTORE_BLOCK_SIZE = 4 * 1024;

    public Mutation mutate(File file, FaultInjectionKind kind, Random random, int maxBytes) throws IOException {
        if (!file.isFile()) {
            throw new IOException("fault target does not exist: " + file);
        }
        long beforeSize = file.length();
        if (beforeSize <= 0L) {
            throw new IOException("fault target is empty: " + file);
        }
        int boundedMaxBytes = Math.max(1, maxBytes);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            if (kind == FaultInjectionKind.TRUNCATE) {
                long maxLoss = Math.min(beforeSize, boundedMaxBytes);
                long loss = 1L + nextLong(random, maxLoss);
                long newLength = Math.max(0L, beforeSize - loss);
                raf.setLength(newLength);
                return new Mutation(0L, loss, beforeSize, newLength);
            }
            long offset = selectOffset(kind, random, beforeSize);
            int length = selectLength(kind, random, beforeSize, offset, boundedMaxBytes);
            if (kind == FaultInjectionKind.BIT_FLIP) {
                raf.seek(offset);
                int value = raf.read();
                if (value < 0) {
                    throw new IOException("could not read byte at " + offset);
                }
                raf.seek(offset);
                raf.write(value ^ (1 << random.nextInt(8)));
                return new Mutation(offset, 1L, beforeSize, file.length());
            }
            byte[] bytes = new byte[length];
            if (kind == FaultInjectionKind.RANDOM_RANGE || kind == FaultInjectionKind.PARTIAL_PAGE) {
                random.nextBytes(bytes);
            }
            raf.seek(offset);
            raf.write(bytes);
            return new Mutation(offset, length, beforeSize, file.length());
        }
    }

    private static long selectOffset(FaultInjectionKind kind, Random random, long size) {
        if (kind == FaultInjectionKind.PARTIAL_PAGE && size > MVSTORE_BLOCK_SIZE) {
            long blockCount = Math.max(1L, size / MVSTORE_BLOCK_SIZE);
            return nextLong(random, blockCount) * MVSTORE_BLOCK_SIZE;
        }
        return nextLong(random, size);
    }

    private static int selectLength(FaultInjectionKind kind, Random random, long size, long offset, int maxBytes) {
        int available = (int) Math.max(1L, Math.min(size - offset, maxBytes));
        if (kind == FaultInjectionKind.PARTIAL_PAGE) {
            available = Math.min(available, MVSTORE_BLOCK_SIZE);
        }
        return 1 + random.nextInt(available);
    }

    private static long nextLong(Random random, long bound) {
        long value = random.nextLong() & Long.MAX_VALUE;
        return value % bound;
    }

    /**
     * Describes the byte range changed by one mutation.
     */
    public static final class Mutation {
        private final long offset;
        private final long length;
        private final long beforeSize;
        private final long afterSize;

        Mutation(long offset, long length, long beforeSize, long afterSize) {
            this.offset = offset;
            this.length = length;
            this.beforeSize = beforeSize;
            this.afterSize = afterSize;
        }

        public long getOffset() {
            return offset;
        }

        public long getLength() {
            return length;
        }

        public long getBeforeSize() {
            return beforeSize;
        }

        public long getAfterSize() {
            return afterSize;
        }
    }
}
