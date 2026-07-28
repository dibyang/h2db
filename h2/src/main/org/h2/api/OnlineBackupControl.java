/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Embedded 与 TCP server 共用的实验性在线备份管理接口。
 * <p>
 * 通过 {@code Connection.unwrap(OnlineBackupControl.class)}取得。远程连接的
 * bundle/shadow 名称必须是服务端配置 root 下的受限相对名称。
 */
public interface OnlineBackupControl {

    /**
     * 准备一个组合在线备份切点。
     *
     * @param options prepare 选项
     * @return 连接归属的 prepared handle
     * @throws SQLException prepare 或权限检查失败
     */
    OnlineBackupHandle prepareOnlineBackup(OnlineBackupOptions options)
            throws SQLException;

    /**
     * 将 bundle 恢复并校验为 shadow generation。
     *
     * @param bundleName bundle 路径或远程受限名称
     * @param shadowName shadow 路径或远程受限名称
     * @param options restore/validation 选项
     * @return 连接归属的 restore handle
     * @throws SQLException restore、validation 或权限检查失败
     */
    OnlineBackupRestoreHandle stageAndValidateShadow(String bundleName,
            String shadowName, OnlineBackupRestoreOptions options)
            throws SQLException;

    /**
     * 排空旧 generation 并签发 activation token。
     *
     * @param expectedOldGenerationId 当前活动 generation ID
     * @param newGenerationId 已验证的新 generation ID
     * @param timeoutMillis drain 总超时毫秒数
     * @return 连接归属的 activation handle
     * @throws SQLException drain、身份或权限检查失败
     */
    OnlineBackupActivationHandle prepareActivation(
            UUID expectedOldGenerationId, UUID newGenerationId,
            long timeoutMillis) throws SQLException;
}
