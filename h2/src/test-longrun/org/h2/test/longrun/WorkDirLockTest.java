/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for exclusive longrun work directory locking.
 */
public final class WorkDirLockTest {

    @TempDir
    public File tempDir;

    @Test
    public void sameWorkDirCannotBeLockedTwice() throws Exception {
        try (WorkDirLock ignored = WorkDirLock.acquire(tempDir)) {
            assertThrows(IOException.class, () -> WorkDirLock.acquire(tempDir));
        }
    }

    @Test
    public void workDirCanBeLockedAgainAfterRelease() throws Exception {
        WorkDirLock first = WorkDirLock.acquire(tempDir);
        first.close();

        try (WorkDirLock ignored = WorkDirLock.acquire(tempDir)) {
            assertTrue(new File(tempDir, ".longrun.lock").isFile());
        }
    }
}
