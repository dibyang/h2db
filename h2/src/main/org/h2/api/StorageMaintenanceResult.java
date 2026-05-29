/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 存储维护操作结果。
 */
public final class StorageMaintenanceResult {

    /**
     * 操作被当前存储引擎拒绝或不支持。
     */
    public static final StorageMaintenanceResult UNSUPPORTED =
            new StorageMaintenanceResult(false, true, false, "UNSUPPORTED");

    private final boolean success;
    private final boolean unsupported;
    private final boolean skipped;
    private final String message;

    private StorageMaintenanceResult(boolean success, boolean unsupported, boolean skipped, String message) {
        this.success = success;
        this.unsupported = unsupported;
        this.skipped = skipped;
        this.message = message;
    }

    /**
     * 创建成功结果。
     *
     * @param message 结果消息
     * @return 成功结果
     */
    public static StorageMaintenanceResult success(String message) {
        return new StorageMaintenanceResult(true, false, false, message);
    }

    /**
     * 创建跳过结果。
     *
     * @param message 结果消息
     * @return 跳过结果
     */
    public static StorageMaintenanceResult skipped(String message) {
        return new StorageMaintenanceResult(false, false, true, message);
    }

    /**
     * 判断操作是否成功。
     *
     * @return 成功时返回 true
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 判断操作是否不支持。
     *
     * @return 不支持时返回 true
     */
    public boolean isUnsupported() {
        return unsupported;
    }

    /**
     * 判断操作是否被跳过。
     *
     * @return 跳过时返回 true
     */
    public boolean isSkipped() {
        return skipped;
    }

    /**
     * 获取结果消息。
     *
     * @return 结果消息
     */
    public String getMessage() {
        return message;
    }
}
