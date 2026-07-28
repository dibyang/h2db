/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 公开 shadow restore 和 validation 选项。
 */
public final class OnlineBackupRestoreOptions {

    private final UUID shadowGenerationId;
    private final List<String> participantAllowlist;
    private final List<ProviderSelection> additionalRequiredProviders;
    private final long validationTimeoutMillis;
    private final boolean keepFailedShadow;
    private final String user;
    private final char[] password;
    private final String cipher;

    /**
     * 创建 restore 选项。
     *
     * @param shadowGenerationId 新 shadow generation ID
     * @param participantAllowlist participant 精确 allowlist
     * @param additionalRequiredProviders 额外必要 core provider
     * @param validationTimeoutMillis validation 总超时毫秒数
     * @param keepFailedShadow 失败时是否保留 staging
     * @param user shadow 只读打开用户
     * @param password shadow 只读打开密码
     * @param cipher 加密算法，非加密库传 {@code null}
     */
    public OnlineBackupRestoreOptions(UUID shadowGenerationId,
            List<String> participantAllowlist,
            List<ProviderSelection> additionalRequiredProviders,
            long validationTimeoutMillis, boolean keepFailedShadow,
            String user, char[] password, String cipher) {
        if (shadowGenerationId == null || participantAllowlist == null
                || additionalRequiredProviders == null || user == null
                || password == null || validationTimeoutMillis < 0L) {
            throw new IllegalArgumentException(
                    "Invalid shadow restore options");
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
        this.cipher = cipher;
    }

    /**
     * 创建 H2-only、无加密的默认选项。
     *
     * @param shadowGenerationId 新 shadow generation ID
     * @return 默认选项
     */
    public static OnlineBackupRestoreOptions defaults(
            UUID shadowGenerationId) {
        return new OnlineBackupRestoreOptions(shadowGenerationId,
                Collections.<String>emptyList(),
                Collections.<ProviderSelection>emptyList(), 30_000L,
                false, "sa", new char[0], null);
    }

    /**
     * @return 新 shadow generation ID
     */
    public UUID getShadowGenerationId() {
        return shadowGenerationId;
    }

    /**
     * @return participant 精确 allowlist
     */
    public List<String> getParticipantAllowlist() {
        return participantAllowlist;
    }

    /**
     * @return 额外必要 core provider
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
     * @return shadow 只读打开用户
     */
    public String getUser() {
        return user;
    }

    /**
     * @return shadow 只读打开密码副本
     */
    public char[] getPassword() {
        return password.clone();
    }

    /**
     * @return 加密算法，非加密库为 {@code null}
     */
    public String getCipher() {
        return cipher;
    }

    /**
     * 额外必要 provider 的公开选择。
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
                        "Provider selection must not be empty");
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
