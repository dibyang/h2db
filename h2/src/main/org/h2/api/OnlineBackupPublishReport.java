/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.UUID;

/**
 * Bundle 原子发布结果，不暴露服务端绝对路径。
 */
public final class OnlineBackupPublishReport {

    private final String bundleName;
    private final UUID backupId;
    private final UUID cutId;
    private final boolean reused;
    private final String stagingDirectoryFsync;
    private final String parentDirectoryFsync;

    /**
     * 创建发布报告。
     *
     * @param bundleName 调用方提交的名称
     * @param backupId backup ID
     * @param cutId cut ID
     * @param reused 是否复用既有相同 bundle
     * @param stagingDirectoryFsync staging 目录 fsync 状态
     * @param parentDirectoryFsync parent 目录 fsync 状态
     */
    public OnlineBackupPublishReport(String bundleName, UUID backupId,
            UUID cutId, boolean reused, String stagingDirectoryFsync,
            String parentDirectoryFsync) {
        this.bundleName = requireNonNull(bundleName, "bundleName");
        this.backupId = requireNonNull(backupId, "backupId");
        this.cutId = requireNonNull(cutId, "cutId");
        this.reused = reused;
        this.stagingDirectoryFsync = requireNonNull(
                stagingDirectoryFsync, "stagingDirectoryFsync");
        this.parentDirectoryFsync = requireNonNull(
                parentDirectoryFsync, "parentDirectoryFsync");
    }

    /**
     * @return 调用方提交的 bundle 名称
     */
    public String getBundleName() {
        return bundleName;
    }

    /**
     * @return backup ID
     */
    public UUID getBackupId() {
        return backupId;
    }

    /**
     * @return cut ID
     */
    public UUID getCutId() {
        return cutId;
    }

    /**
     * @return 是否复用既有相同 bundle
     */
    public boolean isReused() {
        return reused;
    }

    /**
     * @return staging 目录 fsync 状态
     */
    public String getStagingDirectoryFsync() {
        return stagingDirectoryFsync;
    }

    /**
     * @return parent 目录 fsync 状态
     */
    public String getParentDirectoryFsync() {
        return parentDirectoryFsync;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
