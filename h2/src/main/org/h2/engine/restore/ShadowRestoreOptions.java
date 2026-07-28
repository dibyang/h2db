/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Shadow restore 和 fail-closed validation 的不可变选项。
 */
public final class ShadowRestoreOptions {

    private final UUID shadowGenerationId;
    private final List<String> participantAllowlist;
    private final List<ProviderSelection> additionalRequiredProviders;
    private final long validationTimeoutMillis;
    private final boolean keepFailedShadow;
    private final String user;
    private final char[] password;
    private final String cipher;

    /**
     * 创建选项。
     *
     * @param shadowGenerationId ADB 分配的新 generation ID
     * @param participantAllowlist 允许执行 validation 的 participant ID
     * @param additionalRequiredProviders 启动描述声明的额外必要 provider
     * @param validationTimeoutMillis validation 总 deadline
     * @param keepFailedShadow 失败时是否保留 staging 供诊断
     * @param user shadow 只读试打开用户
     * @param password 数据库密码；加密库时包含调用方提供的文件密码
     * @param cipher 加密算法，非加密库传 {@code null}
     */
    public ShadowRestoreOptions(UUID shadowGenerationId,
            List<String> participantAllowlist,
            List<ProviderSelection> additionalRequiredProviders,
            long validationTimeoutMillis, boolean keepFailedShadow,
            String user, char[] password, String cipher) {
        if (shadowGenerationId == null || participantAllowlist == null
                || additionalRequiredProviders == null) {
            throw new IllegalArgumentException(
                    "Shadow restore identities and lists must not be null");
        }
        if (validationTimeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "validationTimeoutMillis must be positive");
        }
        if (user == null || password == null) {
            throw new IllegalArgumentException(
                    "Validation credentials must not be null");
        }
        this.shadowGenerationId = shadowGenerationId;
        this.participantAllowlist = Collections.unmodifiableList(
                new ArrayList<>(participantAllowlist));
        this.additionalRequiredProviders = Collections.unmodifiableList(
                new ArrayList<>(additionalRequiredProviders));
        this.validationTimeoutMillis = validationTimeoutMillis;
        this.keepFailedShadow = keepFailedShadow;
        this.user = user;
        this.password = password.clone();
        this.cipher = cipher == null || cipher.trim().isEmpty()
                ? null : cipher;
    }

    /**
     * 创建无 participant、无加密的默认选项。
     *
     * @param shadowGenerationId 新 generation ID
     * @return 默认选项
     */
    public static ShadowRestoreOptions defaults(UUID shadowGenerationId) {
        return new ShadowRestoreOptions(shadowGenerationId,
                Collections.<String>emptyList(),
                Collections.<ProviderSelection>emptyList(), 30_000L, false,
                "sa", new char[0], null);
    }

    /**
     * @return 新 shadow generation ID
     */
    public UUID getShadowGenerationId() {
        return shadowGenerationId;
    }

    /**
     * @return participant validation allowlist
     */
    public List<String> getParticipantAllowlist() {
        return participantAllowlist;
    }

    /**
     * @return 启动描述声明的额外必要 provider
     */
    public List<ProviderSelection> getAdditionalRequiredProviders() {
        return additionalRequiredProviders;
    }

    /**
     * @return validation 总超时毫秒数
     */
    public long getValidationTimeoutMillis() {
        return validationTimeoutMillis;
    }

    /**
     * @return 失败时是否保留 staging
     */
    public boolean isKeepFailedShadow() {
        return keepFailedShadow;
    }

    /**
     * @return 只读试打开用户
     */
    public String getUser() {
        return user;
    }

    char[] copyPassword() {
        return password.clone();
    }

    /**
     * @return 加密算法，非加密库为 {@code null}
     */
    public String getCipher() {
        return cipher;
    }

    /**
     * 启动描述中额外必要 provider 的类型和 ID。
     */
    public static final class ProviderSelection {

        private final String type;
        private final String id;

        /**
         * 创建 provider 选择。
         *
         * @param type provider 类型
         * @param id provider ID
         */
        public ProviderSelection(String type, String id) {
            if (type == null || type.trim().isEmpty() || id == null
                    || id.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Provider type and id must not be empty");
            }
            this.type = type;
            this.id = id;
        }

        /**
         * @return provider 类型
         */
        public String getType() {
            return type;
        }

        /**
         * @return provider ID
         */
        public String getId() {
            return id;
        }
    }
}
