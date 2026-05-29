/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收切换前的可行性分析结果。
 */
public final class MVStoreSpaceReclamationAnalysis {

    private final boolean sourceUnchanged;
    private final boolean versionScanCatchUpAvailable;
    private final boolean fullCopyRequired;
    private final String reason;

    MVStoreSpaceReclamationAnalysis(boolean sourceUnchanged, boolean versionScanCatchUpAvailable,
            boolean fullCopyRequired, String reason) {
        this.sourceUnchanged = sourceUnchanged;
        this.versionScanCatchUpAvailable = versionScanCatchUpAvailable;
        this.fullCopyRequired = fullCopyRequired;
        this.reason = reason;
    }

    /**
     * 源文件从 shadow 生成后是否保持不变。
     *
     * @return true 表示可直接切换 prepared shadow
     */
    public boolean isSourceUnchanged() {
        return sourceUnchanged;
    }

    /**
     * 当前实现是否具备版本扫描增量追平能力。
     *
     * @return true 表示可以基于版本扫描追平源文件变化
     */
    public boolean isVersionScanCatchUpAvailable() {
        return versionScanCatchUpAvailable;
    }

    /**
     * 是否必须降级为维护态 full copy。
     *
     * @return true 表示 prepared shadow 已不能安全切换
     */
    public boolean isFullCopyRequired() {
        return fullCopyRequired;
    }

    /**
     * 分析原因。
     *
     * @return 原因说明
     */
    public String getReason() {
        return reason;
    }
}
