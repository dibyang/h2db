/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for deterministic file corruption helpers.
 */
public final class FileCorruptionInjectorTest {

    @TempDir
    public File tempDir;

    @Test
    public void truncateReducesFileSize() throws Exception {
        File file = sampleFile("truncate.bin");

        FileCorruptionInjector.Mutation mutation = new FileCorruptionInjector()
                .mutate(file, FaultInjectionKind.TRUNCATE, new Random(1L), 32);

        assertTrue(file.length() < 256L);
        assertEquals(256L, mutation.getBeforeSize());
        assertEquals(file.length(), mutation.getAfterSize());
    }

    @Test
    public void bitFlipKeepsFileSizeButChangesContent() throws Exception {
        File file = sampleFile("bit.bin");
        byte[] before = Files.readAllBytes(file.toPath());

        FileCorruptionInjector.Mutation mutation = new FileCorruptionInjector()
                .mutate(file, FaultInjectionKind.BIT_FLIP, new Random(2L), 32);
        byte[] after = Files.readAllBytes(file.toPath());

        assertEquals(before.length, after.length);
        assertEquals(1L, mutation.getLength());
        assertTrue(changed(before, after));
    }

    @Test
    public void zeroRangeKeepsFileSizeButChangesContent() throws Exception {
        File file = sampleFile("zero.bin");
        byte[] before = Files.readAllBytes(file.toPath());

        FileCorruptionInjector.Mutation mutation = new FileCorruptionInjector()
                .mutate(file, FaultInjectionKind.ZERO_RANGE, new Random(3L), 32);
        byte[] after = Files.readAllBytes(file.toPath());

        assertEquals(before.length, after.length);
        assertTrue(mutation.getLength() >= 1L);
        assertTrue(changed(before, after));
    }

    @Test
    public void randomRangeKeepsFileSizeButChangesContent() throws Exception {
        File file = sampleFile("random.bin");
        byte[] before = Files.readAllBytes(file.toPath());

        FileCorruptionInjector.Mutation mutation = new FileCorruptionInjector()
                .mutate(file, FaultInjectionKind.RANDOM_RANGE, new Random(4L), 32);
        byte[] after = Files.readAllBytes(file.toPath());

        assertEquals(before.length, after.length);
        assertTrue(mutation.getLength() >= 1L);
        assertTrue(changed(before, after));
    }

    @Test
    public void partialPageStartsOnBlockBoundaryAndKeepsFileSize() throws Exception {
        File file = sampleFile("partial-page.bin", 8 * 1024);
        byte[] before = Files.readAllBytes(file.toPath());

        FileCorruptionInjector.Mutation mutation = new FileCorruptionInjector()
                .mutate(file, FaultInjectionKind.PARTIAL_PAGE, new Random(5L), 8 * 1024);
        byte[] after = Files.readAllBytes(file.toPath());

        assertEquals(before.length, after.length);
        assertEquals(0L, mutation.getOffset() % (4 * 1024));
        assertTrue(mutation.getLength() >= 1L);
        assertTrue(mutation.getLength() <= 4 * 1024L);
        assertTrue(changed(before, after));
    }

    private File sampleFile(String name) throws Exception {
        return sampleFile(name, 256);
    }

    private File sampleFile(String name, int size) throws Exception {
        File file = new File(tempDir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < size; i++) {
                out.write(i + 1);
            }
        }
        return file;
    }

    private static boolean changed(byte[] before, byte[] after) {
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                return true;
            }
        }
        return false;
    }
}
