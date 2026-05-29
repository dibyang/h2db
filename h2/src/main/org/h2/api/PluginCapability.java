/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 插件能力名称常量。
 */
public final class PluginCapability {

    /**
     * 表引擎创建表能力。
     */
    public static final String TABLE_CREATE = "table.create";

    /**
     * 支持持久化数据库。
     */
    public static final String STORAGE_PERSISTENT = "storage.persistent";

    /**
     * 支持事务。
     */
    public static final String STORAGE_TRANSACTIONAL = "storage.transactional";

    /**
     * 支持 MVCC。
     */
    public static final String STORAGE_MVCC = "storage.mvcc";

    /**
     * 支持一致性备份。
     */
    public static final String STORAGE_BACKUP = "storage.backup";

    /**
     * 支持关闭态 compact。
     */
    public static final String STORAGE_COMPACT_CLOSED = "storage.compact.closed";

    /**
     * 支持维护态在线 compact。
     */
    public static final String STORAGE_COMPACT_ONLINE_MAINTENANCE = "storage.compact.online.maintenance";

    /**
     * 支持在线空间回收。
     */
    public static final String STORAGE_VACUUM_ONLINE = "storage.vacuum.online";

    /**
     * 支持 crash-safe metadata publish。
     */
    public static final String STORAGE_PUBLISH_CRASH_SAFE = "storage.publish.crashSafe";

    /**
     * 支持安全物理截断。
     */
    public static final String STORAGE_TRUNCATE_SAFE = "storage.truncate.safe";

    private PluginCapability() {
    }
}
