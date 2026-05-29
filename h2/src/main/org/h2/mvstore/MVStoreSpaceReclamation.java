/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.store.fs.FileUtils;
import org.h2.util.IOUtils;
import org.h2.util.StringUtils;

/**
 * MVStore 空间回收维护工具。
 *
 * <p>当前入口只处理已经关闭的 MVStore 文件，不接入 SQL 或自动调度路径。
 * 它用于先提供可回滚的维护态 full compact 能力，为后续在线 shadow compact
 * 打基础。</p>
 */
public final class MVStoreSpaceReclamation {

    /**
     * 当前公开 API 稳定级别。
     */
    public static final String API_STATUS = "EXPERIMENTAL_MAINTENANCE_API";

    /**
     * 当前支持的公开入口形态。
     */
    public static final String ENTRY_POINT = "JAVA_MAINTENANCE_API";

    private static final String SHADOW_SUFFIX = ".reclaim.shadow";
    private static final String BACKUP_SUFFIX = ".reclaim.backup";
    private static final String SOURCE_DIGEST_ALGORITHM = "SHA-256";

    private MVStoreSpaceReclamation() {
    }

    /**
     * 获取当前公开 API 稳定级别。
     *
     * @return 稳定级别
     */
    public static String getApiStatus() {
        return API_STATUS;
    }

    /**
     * 获取当前支持的公开入口形态。
     *
     * @return 入口形态
     */
    public static String getEntryPoint() {
        return ENTRY_POINT;
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

        try {
            SourceFingerprint sourceFingerprint = sourceFingerprint(fileName);
            emit(options, MVStoreSpaceReclamationPhase.PREPARING, fileName, "Preparing closed-store compact");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.PREPARING.name(), shadowFileName, backupFileName,
                    sourceFingerprint);
            MVStoreTool.compact(fileName, shadowFileName, options.compress);
            emit(options, MVStoreSpaceReclamationPhase.VERIFYING, fileName, "Verifying compacted shadow");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.VERIFYING.name(), shadowFileName, backupFileName,
                    sourceFingerprint);
            if (options.verifyAfterCompact) {
                verifyStore(shadowFileName);
            }
            long compactedSize = FileUtils.size(shadowFileName);
            pauseForTesting(options.ioDelayMillis);
            copyFile(fileName, backupFileName);
            emit(options, MVStoreSpaceReclamationPhase.SWITCHING, fileName, "Switching compacted shadow");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.SWITCHING.name(), shadowFileName, backupFileName,
                    sourceFingerprint);
            try {
                pauseForTesting(options.ioDelayMillis);
                MVStoreTool.moveAtomicReplace(shadowFileName, fileName);
            } catch (RuntimeException e) {
                restoreBackup(fileName, backupFileName);
                throw e;
            }
            if (!options.keepBackup) {
                FileUtils.delete(backupFileName);
            }
            FileUtils.delete(manifestFileName(fileName));
            MVStoreSpaceReclamationResult result = new MVStoreSpaceReclamationResult(fileName, shadowFileName,
                    backupFileName,
                    sourceFingerprint.size, compactedSize, true);
            emit(options, MVStoreSpaceReclamationPhase.COMPLETED, fileName, result.getDiagnosticSummary());
            return result;
        } catch (IOException e) {
            FileUtils.delete(shadowFileName);
            restoreBackup(fileName, backupFileName);
            emit(options, MVStoreSpaceReclamationPhase.ABORTED, fileName, e.getMessage());
            throw DbException.convertIOException(e, fileName);
        } catch (RuntimeException e) {
            FileUtils.delete(shadowFileName);
            restoreBackup(fileName, backupFileName);
            emit(options, MVStoreSpaceReclamationPhase.ABORTED, fileName, e.getMessage());
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
        try {
            SourceFingerprint sourceFingerprint = sourceFingerprint(fileName);
            emit(options, MVStoreSpaceReclamationPhase.PREPARING, fileName, "Preparing shadow compact");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.PREPARING.name(), shadowFileName,
                    fileName + BACKUP_SUFFIX, sourceFingerprint);
            MVStoreTool.compact(fileName, shadowFileName, options.compress);
            emit(options, MVStoreSpaceReclamationPhase.VERIFYING, fileName, "Verifying prepared shadow");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.VERIFYING.name(), shadowFileName,
                    fileName + BACKUP_SUFFIX, sourceFingerprint);
            if (options.verifyAfterCompact) {
                verifyStore(shadowFileName);
            }
            pauseForTesting(options.ioDelayMillis);
            emit(options, MVStoreSpaceReclamationPhase.SHADOW_READY, fileName, "Prepared shadow is ready");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.SHADOW_READY.name(), shadowFileName,
                    fileName + BACKUP_SUFFIX, sourceFingerprint);
            MVStoreSpaceReclamationResult result = new MVStoreSpaceReclamationResult(fileName, shadowFileName,
                    fileName + BACKUP_SUFFIX,
                    sourceFingerprint.size, FileUtils.size(shadowFileName), false);
            emit(options, MVStoreSpaceReclamationPhase.COMPLETED, fileName, result.getDiagnosticSummary());
            return result;
        } catch (IOException e) {
            FileUtils.delete(shadowFileName);
            emit(options, MVStoreSpaceReclamationPhase.ABORTED, fileName, e.getMessage());
            throw DbException.convertIOException(e, fileName);
        } catch (RuntimeException e) {
            FileUtils.delete(shadowFileName);
            emit(options, MVStoreSpaceReclamationPhase.ABORTED, fileName, e.getMessage());
            throw e;
        }
    }

    /**
     * 将已准备好的 shadow 文件切换为当前 MVStore 文件。
     *
     * @param fileName MVStore 文件名
     * @param options 回收配置，null 时使用默认配置
     * @return 切换结果
     */
    public static MVStoreSpaceReclamationResult switchToShadow(String fileName,
            MVStoreSpaceReclamationOptions options) {
        if (options == null) {
            options = MVStoreSpaceReclamationOptions.DEFAULT;
        }
        if (!FileUtils.exists(fileName)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT, "File not found: {0}", fileName);
        }
        String shadowFileName = fileName + SHADOW_SUFFIX;
        String backupFileName = fileName + BACKUP_SUFFIX;
        if (!FileUtils.exists(shadowFileName)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT,
                    "Shadow file not found: {0}", shadowFileName);
        }
        long compactedSize = FileUtils.size(shadowFileName);
        try {
            SourceFingerprint sourceFingerprint;
            try {
                sourceFingerprint = assertSourceUnchanged(fileName);
            } catch (MVStoreException e) {
                if (options.refreshShadowIfSourceChanged) {
                    emit(options, MVStoreSpaceReclamationPhase.FALLBACK_FULL_COPY, fileName,
                            "Prepared shadow is stale; falling back to closed-store compact");
                    return compactClosedStore(fileName, options);
                }
                throw e;
            }
            emit(options, MVStoreSpaceReclamationPhase.VERIFYING, fileName, "Verifying prepared shadow");
            if (options.verifyAfterCompact) {
                verifyStore(shadowFileName);
            }
            pauseForTesting(options.ioDelayMillis);
            copyFile(fileName, backupFileName);
            emit(options, MVStoreSpaceReclamationPhase.SWITCHING, fileName, "Switching prepared shadow");
            writeManifest(fileName, MVStoreSpaceReclamationPhase.SWITCHING.name(), shadowFileName, backupFileName,
                    sourceFingerprint);
            try {
                pauseForTesting(options.ioDelayMillis);
                MVStoreTool.moveAtomicReplace(shadowFileName, fileName);
            } catch (RuntimeException e) {
                restoreBackup(fileName, backupFileName);
                throw e;
            }
            if (!options.keepBackup) {
                FileUtils.delete(backupFileName);
            }
            FileUtils.delete(manifestFileName(fileName));
            MVStoreSpaceReclamationResult result = new MVStoreSpaceReclamationResult(fileName, shadowFileName,
                    backupFileName,
                    sourceFingerprint.size, compactedSize, true);
            emit(options, MVStoreSpaceReclamationPhase.COMPLETED, fileName, result.getDiagnosticSummary());
            return result;
        } catch (IOException e) {
            restoreBackup(fileName, backupFileName);
            emit(options, MVStoreSpaceReclamationPhase.ABORTED, fileName, e.getMessage());
            throw DbException.convertIOException(e, fileName);
        } catch (RuntimeException e) {
            restoreBackup(fileName, backupFileName);
            emit(options, MVStoreSpaceReclamationPhase.ABORTED, fileName, e.getMessage());
            throw e;
        }
    }

    /**
     * 分析已准备好的 shadow 文件是否仍可安全切换。
     *
     * @param fileName MVStore 文件名
     * @return 切换前分析结果
     */
    public static MVStoreSpaceReclamationAnalysis analyzePreparedShadow(String fileName) {
        String shadowFileName = fileName + SHADOW_SUFFIX;
        if (!FileUtils.exists(shadowFileName)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT,
                    "Shadow file not found: {0}", shadowFileName);
        }
        try {
            assertSourceUnchanged(fileName);
            return new MVStoreSpaceReclamationAnalysis(true, false, false,
                    "Prepared shadow matches the current source fingerprint.");
        } catch (IOException e) {
            throw DbException.convertIOException(e, fileName);
        } catch (MVStoreException e) {
            return new MVStoreSpaceReclamationAnalysis(false, false, true,
                    "Source changed after shadow compact; version-scan catch-up is not available in this stage.");
        }
    }

    /**
     * 清理维护态空间回收残留文件。
     *
     * @param fileName MVStore 文件名
     */
    public static void cleanUp(String fileName) {
        FileUtils.delete(fileName + SHADOW_SUFFIX);
        FileUtils.delete(manifestFileName(fileName));
        if (FileUtils.exists(fileName + BACKUP_SUFFIX) && FileUtils.exists(fileName)) {
            FileUtils.delete(fileName + BACKUP_SUFFIX);
        }
    }

    /**
     * 恢复上一次未完成的维护态空间回收。
     *
     * @param fileName MVStore 文件名
     */
    public static void recover(String fileName) {
        String shadowFileName = fileName + SHADOW_SUFFIX;
        String backupFileName = fileName + BACKUP_SUFFIX;
        if (!FileUtils.exists(fileName) && FileUtils.exists(backupFileName)) {
            MVStoreTool.moveAtomicReplace(backupFileName, fileName);
        } else if (!FileUtils.exists(fileName) && FileUtils.exists(shadowFileName)) {
            MVStoreTool.moveAtomicReplace(shadowFileName, fileName);
        }
        if (FileUtils.exists(fileName)) {
            FileUtils.delete(shadowFileName);
            FileUtils.delete(backupFileName);
            FileUtils.delete(manifestFileName(fileName));
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

    private static void writeManifest(String fileName, String phase, String shadowFileName,
            String backupFileName, SourceFingerprint sourceFingerprint) throws IOException {
        String text = "magic=H2_MVSTORE_SPACE_RECLAMATION\n" +
                "manifestVersion=1\n" +
                "phase=" + phase + "\n" +
                "sourceFile=" + fileName + "\n" +
                "shadowFile=" + shadowFileName + "\n" +
                "backupFile=" + backupFileName + "\n" +
                "sourceSize=" + sourceFingerprint.size + "\n" +
                "sourceLastModified=" + sourceFingerprint.lastModified + "\n" +
                "sourceDigest=" + sourceFingerprint.digest + "\n" +
                "updatedAt=" + System.currentTimeMillis() + "\n";
        try (OutputStream out = FileUtils.newOutputStream(manifestFileName(fileName), false)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private static SourceFingerprint assertSourceUnchanged(String fileName) throws IOException {
        String manifestFileName = manifestFileName(fileName);
        if (!FileUtils.exists(manifestFileName)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT,
                    "Space reclamation manifest not found: {0}", manifestFileName);
        }
        String sourceSize = readManifestValue(manifestFileName, "sourceSize");
        String sourceLastModified = readManifestValue(manifestFileName, "sourceLastModified");
        String sourceDigest = readManifestValue(manifestFileName, "sourceDigest");
        if (sourceSize == null || sourceLastModified == null || sourceDigest == null) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_FILE_CORRUPT,
                    "Space reclamation manifest misses source fingerprint: {0}", manifestFileName);
        }
        SourceFingerprint current = sourceFingerprint(fileName);
        if (Long.parseLong(sourceSize) != current.size ||
                Long.parseLong(sourceLastModified) != current.lastModified ||
                !sourceDigest.equals(current.digest)) {
            throw DataUtils.newMVStoreException(DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                    "Source file changed after shadow compact: {0}", fileName);
        }
        return current;
    }

    private static SourceFingerprint sourceFingerprint(String fileName) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SOURCE_DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = FileUtils.newInputStream(fileName)) {
            byte[] buffer = new byte[Constants.IO_BUFFER_SIZE];
            while (true) {
                int len = in.read(buffer);
                if (len < 0) {
                    break;
                }
                digest.update(buffer, 0, len);
            }
        }
        return new SourceFingerprint(FileUtils.size(fileName), FileUtils.lastModified(fileName),
                SOURCE_DIGEST_ALGORITHM + ':' + StringUtils.convertBytesToHex(digest.digest()));
    }

    private static String readManifestValue(String fileName, String key) throws IOException {
        try (InputStream in = FileUtils.newInputStream(fileName)) {
            String text = new String(IOUtils.readBytesAndClose(in, -1), StandardCharsets.UTF_8);
            String prefix = key + '=';
            int start = 0;
            while (start < text.length()) {
                int end = text.indexOf('\n', start);
                if (end < 0) {
                    end = text.length();
                }
                if (text.startsWith(prefix, start)) {
                    return text.substring(start + prefix.length(), end).trim();
                }
                start = end + 1;
            }
            return null;
        }
    }

    private static String manifestFileName(String fileName) {
        return fileName + ".reclaim.manifest";
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

    private static void pauseForTesting(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw DataUtils.newMVStoreException(DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                    "Interrupted during space reclamation IO delay");
        }
    }

    private static void emit(MVStoreSpaceReclamationOptions options, MVStoreSpaceReclamationPhase phase,
            String fileName, String message) {
        MVStoreSpaceReclamationListener listener = options.diagnosticListener;
        if (listener != null) {
            try {
                listener.onEvent(new MVStoreSpaceReclamationEvent(phase, fileName, message));
            } catch (RuntimeException ignore) {
                // 诊断回调不得改变维护操作结果。
            }
        }
    }

    private static final class SourceFingerprint {
        final long size;
        final long lastModified;
        final String digest;

        SourceFingerprint(long size, long lastModified, String digest) {
            this.size = size;
            this.lastModified = lastModified;
            this.digest = digest;
        }
    }
}
