/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.h2.message.DbException;
import org.h2.store.fs.FileUtils;
import org.h2.util.IOUtils;

/**
 * MVStore 空间回收维护工具。
 *
 * <p>当前入口只处理已经关闭的 MVStore 文件，不接入 SQL 或自动调度路径。
 * 它用于先提供可回滚的维护态 full compact 能力，为后续在线 shadow compact
 * 打基础。</p>
 */
public final class MVStoreSpaceReclamation {

    private static final String SHADOW_SUFFIX = ".reclaim.shadow";
    private static final String BACKUP_SUFFIX = ".reclaim.backup";

    private MVStoreSpaceReclamation() {
    }

    /**
     * 对已经关闭的 MVStore 文件执行维护态空间回收。
     *
     * @param fileName MVStore 文件名
     * @param options 回收配置，null 时使用默认配置
     * @return 回收结果
     */
    public static MVStoreSpaceReclamationResult compactClosedStore(String fileName,
            MVStoreSpaceReclamationOptions options) {
        if (options == null) {
            options = MVStoreSpaceReclamationOptions.DEFAULT;
        }
        if (!FileUtils.exists(fileName)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT, "File not found: {0}", fileName);
        }
        String shadowFileName = fileName + SHADOW_SUFFIX;
        String backupFileName = fileName + BACKUP_SUFFIX;
        FileUtils.delete(shadowFileName);
        FileUtils.delete(backupFileName);

        long sourceSize = FileUtils.size(fileName);
        try {
            MVStoreTool.compact(fileName, shadowFileName, options.compress);
            if (options.verifyAfterCompact) {
                verifyStore(shadowFileName);
            }
            long compactedSize = FileUtils.size(shadowFileName);
            copyFile(fileName, backupFileName);
            try {
                MVStoreTool.moveAtomicReplace(shadowFileName, fileName);
            } catch (RuntimeException e) {
                restoreBackup(fileName, backupFileName);
                throw e;
            }
            if (!options.keepBackup) {
                FileUtils.delete(backupFileName);
            }
            return new MVStoreSpaceReclamationResult(fileName, shadowFileName, backupFileName,
                    sourceSize, compactedSize, true);
        } catch (IOException e) {
            FileUtils.delete(shadowFileName);
            restoreBackup(fileName, backupFileName);
            throw DbException.convertIOException(e, fileName);
        } catch (RuntimeException e) {
            FileUtils.delete(shadowFileName);
            restoreBackup(fileName, backupFileName);
            throw e;
        }
    }

    /**
     * 基于已经关闭的 MVStore 文件生成 shadow compact 文件，但不替换源文件。
     *
     * @param fileName MVStore 文件名
     * @param options 回收配置，null 时使用默认配置
     * @return shadow compact 结果
     */
    public static MVStoreSpaceReclamationResult compactToShadow(String fileName,
            MVStoreSpaceReclamationOptions options) {
        if (options == null) {
            options = MVStoreSpaceReclamationOptions.DEFAULT;
        }
        if (!FileUtils.exists(fileName)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT, "File not found: {0}", fileName);
        }
        String shadowFileName = fileName + SHADOW_SUFFIX;
        FileUtils.delete(shadowFileName);
        long sourceSize = FileUtils.size(fileName);
        try {
            MVStoreTool.compact(fileName, shadowFileName, options.compress);
            if (options.verifyAfterCompact) {
                verifyStore(shadowFileName);
            }
            return new MVStoreSpaceReclamationResult(fileName, shadowFileName, fileName + BACKUP_SUFFIX,
                    sourceSize, FileUtils.size(shadowFileName), false);
        } catch (RuntimeException e) {
            FileUtils.delete(shadowFileName);
            throw e;
        }
    }

    /**
     * 清理维护态空间回收残留文件。
     *
     * @param fileName MVStore 文件名
     */
    public static void cleanUp(String fileName) {
        FileUtils.delete(fileName + SHADOW_SUFFIX);
        if (FileUtils.exists(fileName + BACKUP_SUFFIX) && FileUtils.exists(fileName)) {
            FileUtils.delete(fileName + BACKUP_SUFFIX);
        }
    }

    private static void verifyStore(String fileName) {
        try (MVStore ignored = new MVStore.Builder().fileName(fileName).readOnly().open()) {
            // open is the verification
        }
    }

    private static void copyFile(String source, String target) throws IOException {
        try (InputStream in = FileUtils.newInputStream(source);
                OutputStream out = FileUtils.newOutputStream(target, false)) {
            IOUtils.copy(in, out);
        }
    }

    private static void restoreBackup(String fileName, String backupFileName) {
        if (!FileUtils.exists(backupFileName)) {
            return;
        }
        if (FileUtils.exists(fileName)) {
            FileUtils.delete(fileName);
        }
        MVStoreTool.moveAtomicReplace(backupFileName, fileName);
    }
}
