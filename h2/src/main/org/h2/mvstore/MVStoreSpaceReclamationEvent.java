/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护操作的诊断事件。
 */
public final class MVStoreSpaceReclamationEvent {

    private final MVStoreSpaceReclamationPhase phase;
    private final String fileName;
    private final String message;

    MVStoreSpaceReclamationEvent(MVStoreSpaceReclamationPhase phase, String fileName, String message) {
        this.phase = phase;
        this.fileName = fileName;
        this.message = message;
    }

    /**
     * 当前阶段。
     *
     * @return 阶段
     */
    public MVStoreSpaceReclamationPhase getPhase() {
        return phase;
    }

    /**
     * MVStore 文件名。
     *
     * @return 文件名
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * 诊断消息。
     *
     * @return 消息
     */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "phase=" + phase + ", file=" + fileName + ", message=" + message;
    }
}
