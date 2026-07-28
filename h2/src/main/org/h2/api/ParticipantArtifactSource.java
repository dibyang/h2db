/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 只读 participant artifact 视图。
 * <p>
 * validation provider 只能通过该接口读取 manifest 已声明的文件，不能取得
 * shadow 根目录、活动 generation 路径或可写文件句柄。
 */
public interface ParticipantArtifactSource {

    /**
     * 获取稳定排序的相对路径。
     *
     * @return artifact 相对路径
     */
    List<String> getRelativePaths();

    /**
     * 打开一个已声明 artifact 的只读流。
     *
     * @param relativePath artifact 相对路径
     * @return 输入流
     * @throws IOException 文件不存在、路径未声明或打开失败
     */
    InputStream open(String relativePath) throws IOException;
}
