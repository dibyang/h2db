/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.nio.file.Path;

/**
 * Result of atomically publishing a coordinated backup bundle.
 */
public final class OnlineBackupPublishResult {

    /**
     * Directory fsync result.
     */
    public enum DirectoryFsync {
        SUCCEEDED,
        UNSUPPORTED,
        NOT_APPLICABLE
    }

    private final Path bundleDirectory;
    private final Path auditFile;
    private final OnlineBackupManifest manifest;
    private final boolean reused;
    private final DirectoryFsync stagingDirectoryFsync;
    private final DirectoryFsync parentDirectoryFsync;

    OnlineBackupPublishResult(Path bundleDirectory, Path auditFile,
            OnlineBackupManifest manifest, boolean reused,
            DirectoryFsync stagingDirectoryFsync,
            DirectoryFsync parentDirectoryFsync) {
        this.bundleDirectory = bundleDirectory;
        this.auditFile = auditFile;
        this.manifest = manifest;
        this.reused = reused;
        this.stagingDirectoryFsync = stagingDirectoryFsync;
        this.parentDirectoryFsync = parentDirectoryFsync;
    }

    /**
     * @return final bundle directory
     */
    public Path getBundleDirectory() {
        return bundleDirectory;
    }

    /**
     * @return independent operation audit file
     */
    public Path getAuditFile() {
        return auditFile;
    }

    /**
     * @return immutable published manifest
     */
    public OnlineBackupManifest getManifest() {
        return manifest;
    }

    /**
     * @return whether an existing identical published bundle was reused
     */
    public boolean isReused() {
        return reused;
    }

    /**
     * @return staging directory fsync result
     */
    public DirectoryFsync getStagingDirectoryFsync() {
        return stagingDirectoryFsync;
    }

    /**
     * @return final parent directory fsync result
     */
    public DirectoryFsync getParentDirectoryFsync() {
        return parentDirectoryFsync;
    }
}
