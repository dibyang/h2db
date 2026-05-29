/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * MVStore 空间回收维护操作的配置。
 */
public final class MVStoreSpaceReclamationOptions {

    /**
     * 默认配置。
     */
    public static final MVStoreSpaceReclamationOptions DEFAULT = new Builder().build();

    final boolean compress;
    final boolean verifyAfterCompact;
    final boolean keepBackup;
    final boolean refreshShadowIfSourceChanged;
    final long ioDelayMillis;
    final MVStoreSpaceReclamationListener diagnosticListener;

    private MVStoreSpaceReclamationOptions(Builder builder) {
        this.compress = builder.compress;
        this.verifyAfterCompact = builder.verifyAfterCompact;
        this.keepBackup = builder.keepBackup;
        this.refreshShadowIfSourceChanged = builder.refreshShadowIfSourceChanged;
        this.ioDelayMillis = builder.ioDelayMillis;
        this.diagnosticListener = builder.diagnosticListener;
    }

    /**
     * 创建配置构造器。
     *
     * @return 配置构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 是否压缩目标文件。
     *
     * @return true 表示压缩
     */
    public boolean isCompress() {
        return compress;
    }

    /**
     * 是否在替换前校验 shadow 文件可打开。
     *
     * @return true 表示校验
     */
    public boolean isVerifyAfterCompact() {
        return verifyAfterCompact;
    }

    /**
     * 成功后是否保留源文件备份。
     *
     * @return true 表示保留备份
     */
    public boolean isKeepBackup() {
        return keepBackup;
    }

    /**
     * prepared shadow 过期时是否降级为维护态 full copy。
     *
     * @return true 表示允许降级重建 shadow 并替换
     */
    public boolean isRefreshShadowIfSourceChanged() {
        return refreshShadowIfSourceChanged;
    }

    /**
     * 人工 IO 延迟，主要用于慢盘路径测试。
     *
     * @return 延迟毫秒数
     */
    public long getIoDelayMillis() {
        return ioDelayMillis;
    }

    /**
     * 诊断事件监听器。
     *
     * @return 监听器，未设置时为 null
     */
    public MVStoreSpaceReclamationListener getDiagnosticListener() {
        return diagnosticListener;
    }

    /**
     * MVStore 空间回收维护操作配置构造器。
     */
    public static final class Builder {
        private boolean compress;
        private boolean verifyAfterCompact = true;
        private boolean keepBackup;
        private boolean refreshShadowIfSourceChanged;
        private long ioDelayMillis;
        private MVStoreSpaceReclamationListener diagnosticListener;

        private Builder() {
        }

        /**
         * 设置是否压缩目标文件。
         *
         * @param compress true 表示压缩
         * @return 当前构造器
         */
        public Builder compress(boolean compress) {
            this.compress = compress;
            return this;
        }

        /**
         * 设置替换前是否校验 shadow 文件。
         *
         * @param verifyAfterCompact true 表示校验
         * @return 当前构造器
         */
        public Builder verifyAfterCompact(boolean verifyAfterCompact) {
            this.verifyAfterCompact = verifyAfterCompact;
            return this;
        }

        /**
         * 设置成功后是否保留源文件备份。
         *
         * @param keepBackup true 表示保留备份
         * @return 当前构造器
         */
        public Builder keepBackup(boolean keepBackup) {
            this.keepBackup = keepBackup;
            return this;
        }

        /**
         * 设置 prepared shadow 过期时是否降级为维护态 full copy。
         *
         * @param refreshShadowIfSourceChanged true 表示允许降级
         * @return 当前构造器
         */
        public Builder refreshShadowIfSourceChanged(boolean refreshShadowIfSourceChanged) {
            this.refreshShadowIfSourceChanged = refreshShadowIfSourceChanged;
            return this;
        }

        /**
         * 设置人工 IO 延迟，主要用于慢盘路径测试。
         *
         * @param ioDelayMillis 延迟毫秒数
         * @return 当前构造器
         */
        public Builder ioDelayMillis(long ioDelayMillis) {
            this.ioDelayMillis = ioDelayMillis;
            return this;
        }

        /**
         * 设置诊断事件监听器。
         *
         * @param diagnosticListener 诊断事件监听器
         * @return 当前构造器
         */
        public Builder diagnosticListener(MVStoreSpaceReclamationListener diagnosticListener) {
            this.diagnosticListener = diagnosticListener;
            return this;
        }

        /**
         * 构建配置。
         *
         * @return 配置对象
         */
        public MVStoreSpaceReclamationOptions build() {
            return new MVStoreSpaceReclamationOptions(this);
        }
    }
}
