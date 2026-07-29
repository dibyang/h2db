/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 插件能力名称常量。
 */
public final class PluginCapability {

    /**
     * 表引擎创建表能力。
     */
    public static final String TABLE_CREATE = "table.create";

    /**
     * 支持系统元数据目录。
     */
    public static final String SYSTEM_CATALOG = "system.catalog";

    /**
     * 支持监听事务事件。
     */
    public static final String TRANSACTION_EVENTS = "transaction.events";

    /**
     * Supports database lifecycle events.
     */
    public static final String DATABASE_LIFECYCLE = "database.lifecycle";

    /**
     * Supports matching simple INSERT VALUES statements for a DML fast path.
     */
    public static final String DML_INSERT_VALUES_FAST_PATH = "dml.insert.values.fastPath";

    /**
     * Supports reading H2 bound parameters through a read-only view.
     */
    public static final String PARAMETERS_BOUND_VIEW = "parameters.bound.view";

    /**
     * Supports JDBC batch INSERT fast path.
     */
    public static final String DML_INSERT_BATCH_FAST_PATH = "dml.insert.batch.fastPath";

    /**
     * Supports table-side bulk insert.
     */
    public static final String TABLE_BULK_INSERT = "table.bulkInsert";

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

    /**
     * Supports coordinated online backup prepare and materialization.
     */
    public static final String ONLINE_BACKUP_PREPARE =
            "onlineBackup.prepare";

    /**
     * Participant 支持 barrier 外 arm、barrier 内 capture 的分阶段准备。
     */
    public static final String ONLINE_BACKUP_PHASED_PREPARE =
            "onlineBackup.phasedPrepare";

    /**
     * Provider 可在无普通 lifecycle 和外部副作用的模式下参与只读数据库打开。
     */
    public static final String VALIDATION_OPEN = "validation.open";

    /**
     * Participant 可在受限上下文中校验 shadow artifact。
     */
    public static final String ONLINE_BACKUP_VALIDATE =
            "onlineBackup.validate";

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            TABLE_CREATE,
            SYSTEM_CATALOG,
            TRANSACTION_EVENTS,
            DATABASE_LIFECYCLE,
            DML_INSERT_VALUES_FAST_PATH,
            PARAMETERS_BOUND_VIEW,
            DML_INSERT_BATCH_FAST_PATH,
            TABLE_BULK_INSERT,
            STORAGE_PERSISTENT,
            STORAGE_TRANSACTIONAL,
            STORAGE_MVCC,
            STORAGE_BACKUP,
            STORAGE_COMPACT_CLOSED,
            STORAGE_COMPACT_ONLINE_MAINTENANCE,
            STORAGE_VACUUM_ONLINE,
            STORAGE_PUBLISH_CRASH_SAFE,
            STORAGE_TRUNCATE_SAFE,
            ONLINE_BACKUP_PREPARE,
            ONLINE_BACKUP_PHASED_PREPARE,
            VALIDATION_OPEN,
            ONLINE_BACKUP_VALIDATE));

    /**
     * 获取当前版本已知的 capability 名称。
     *
     * @return 已知 capability 名称的只读列表
     */
    public static List<String> all() {
        return ALL;
    }

    private PluginCapability() {
    }
}
