/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护操作的结果。
 */
public final class MVStoreSpaceReclamationResult {

    private final String sourceFileName;
    private final String shadowFileName;
    private final String backupFileName;
    private final long sourceSize;
    private final long compactedSize;
    private final boolean replaced;

    MVStoreSpaceReclamationResult(String sourceFileName, String shadowFileName, String backupFileName,
            long sourceSize, long compactedSize, boolean replaced) {
        this.sourceFileName = sourceFileName;
        this.shadowFileName = shadowFileName;
        this.backupFileName = backupFileName;
        this.sourceSize = sourceSize;
        this.compactedSize = compactedSize;
        this.replaced = replaced;
    }

    /**
     * 源 MVStore 文件名。
     *
     * @return 源文件名
     */
    public String getSourceFileName() {
        return sourceFileName;
    }

    /**
     * shadow 文件名。
     *
     * @return shadow 文件名
     */
    public String getShadowFileName() {
        return shadowFileName;
    }

    /**
     * 备份文件名。
     *
     * @return 备份文件名
     */
    public String getBackupFileName() {
        return backupFileName;
    }

    /**
     * 回收前文件大小。
     *
     * @return 回收前文件大小
     */
    public long getSourceSize() {
        return sourceSize;
    }

    /**
     * 回收后文件大小。
     *
     * @return 回收后文件大小
     */
    public long getCompactedSize() {
        return compactedSize;
    }

    /**
     * 是否完成源文件替换。
     *
     * @return true 表示已替换
     */
    public boolean isReplaced() {
        return replaced;
    }
}
