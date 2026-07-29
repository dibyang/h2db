/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 已在 barrier 外完成准备、等待在协调切点冻结状态的备份参与者。
 * <p>
 * {@link #capture(OnlineBackupContext)}成功后，资源所有权转移给返回的
 * {@link PreparedBackupParticipant}，协调器不再调用本对象的
 * {@link #abort()}。capture 失败或尚未 capture 就取消时，abort 必须可安全
 * 重复调用。
 */
public interface ArmedBackupParticipant extends AutoCloseable {

    /**
     * 在数据库备份 barrier 内冻结与 H2 相同的协调切点。
     * 此方法不得执行文件复制、网络上传、压缩或全量校验。
     *
     * @param context 不可变备份上下文
     * @return 已冻结的 participant
     * @throws Exception capture 失败
     */
    PreparedBackupParticipant capture(OnlineBackupContext context)
            throws Exception;

    /**
     * 放弃尚未成功转移所有权的 armed 资源。
     *
     * @throws Exception 清理失败
     */
    void abort() throws Exception;

    @Override
    default void close() throws Exception {
        abort();
    }
}
