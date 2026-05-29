/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护操作的诊断事件监听器。
 */
public interface MVStoreSpaceReclamationListener {

    /**
     * 接收诊断事件。
     *
     * @param event 诊断事件
     */
    void onEvent(MVStoreSpaceReclamationEvent event);
}
