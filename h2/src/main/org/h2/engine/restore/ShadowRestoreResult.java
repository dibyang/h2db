/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import java.nio.file.Path;
import java.util.UUID;

import org.h2.engine.backup.OnlineBackupManifest;

/**
 * 已完成校验并原子发布的 shadow generation。
 */
public final class ShadowRestoreResult {

    private final Path shadowDirectory;
    private final Path validationReport;
    private final OnlineBackupManifest manifest;
    private final UUID shadowGenerationId;
    private final long validationMillis;

    ShadowRestoreResult(Path shadowDirectory, Path validationReport,
            OnlineBackupManifest manifest, UUID shadowGenerationId,
            long validationMillis) {
        this.shadowDirectory = shadowDirectory;
        this.validationReport = validationReport;
        this.manifest = manifest;
        this.shadowGenerationId = shadowGenerationId;
        this.validationMillis = validationMillis;
    }

    /**
     * @return 原子发布后的 shadow 目录
     */
    public Path getShadowDirectory() {
        return shadowDirectory;
    }

    /**
     * @return shadow 内不可变 validation report
     */
    public Path getValidationReport() {
        return validationReport;
    }

    /**
     * @return 源 backup manifest
     */
    public OnlineBackupManifest getManifest() {
        return manifest;
    }

    /**
     * @return 新 shadow generation ID
     */
    public UUID getShadowGenerationId() {
        return shadowGenerationId;
    }

    /**
     * @return staging、校验和发布总耗时
     */
    public long getValidationMillis() {
        return validationMillis;
    }
}
