/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收与备份类文件操作的互斥门禁。
 */
public final class MVStoreSpaceReclamationOperationGate {

    private boolean spaceReclamationActive;
    private boolean backupActive;

    /**
     * 尝试进入空间回收操作。
     *
     * @return true 表示进入成功
     */
    public boolean tryEnterSpaceReclamation() {
        if (backupActive || spaceReclamationActive) {
            return false;
        }
        spaceReclamationActive = true;
        return true;
    }

    /**
     * 退出空间回收操作。
     */
    public void exitSpaceReclamation() {
        spaceReclamationActive = false;
    }

    /**
     * 尝试进入备份操作。
     *
     * @return true 表示进入成功
     */
    public boolean tryEnterBackup() {
        if (spaceReclamationActive || backupActive) {
            return false;
        }
        backupActive = true;
        return true;
    }

    /**
     * 退出备份操作。
     */
    public void exitBackup() {
        backupActive = false;
    }
}
