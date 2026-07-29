/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Restricted artifact output assigned to one participant.
 */
public interface ParticipantArtifactTarget {

    /**
     * Create a new relative artifact file.
     *
     * @param relativePath safe relative path
     * @return artifact output stream
     * @throws IOException if creation fails
     */
    OutputStream create(String relativePath) throws IOException;

    /**
     * 判断所属备份会话是否请求协作取消。
     * 长时间运行的 provider 应在有界工作单元之间轮询此标记。
     *
     * @return 是否应停止物化
     */
    default boolean isCancellationRequested() {
        return false;
    }
}
