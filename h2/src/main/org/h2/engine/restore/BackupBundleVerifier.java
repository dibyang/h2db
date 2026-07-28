/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.restore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.h2.engine.backup.OnlineBackupManifest;
import org.h2.engine.backup.OnlineBackupManifest.Artifact;
import org.h2.engine.backup.OnlineBackupManifest.Participant;
import org.h2.engine.backup.OnlineBackupManifestCodec;

/**
 * 对 bundle 执行路径、类型、长度和 SHA-256 校验，并复制到全新 staging。
 */
final class BackupBundleVerifier {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_MANIFEST_BYTES = 4L * 1024L * 1024L;

    private BackupBundleVerifier() {
    }

    static VerifiedBundle verifyAndCopy(Path bundle, Path staging,
            ShadowRestoreCoordinator.RestoreFaultInjector faultInjector)
            throws IOException {
        if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(bundle)) {
            throw new IOException("Backup bundle is not a regular directory");
        }
        Path manifestPath = bundle.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifestPath)) {
            throw new IOException("Backup manifest is missing");
        }
        long manifestLength = Files.size(manifestPath);
        if (manifestLength <= 0L || manifestLength > MAX_MANIFEST_BYTES) {
            throw new IOException("Backup manifest size is invalid");
        }
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        OnlineBackupManifest manifest;
        try {
            manifest = OnlineBackupManifestCodec.decode(manifestBytes);
        } catch (RuntimeException e) {
            throw new IOException("Backup manifest is invalid", e);
        }
        TreeMap<String, Artifact> artifacts = declaredArtifacts(manifest);
        verifyBundleTree(bundle, artifacts.keySet());
        Files.createDirectory(staging);
        for (Artifact artifact : artifacts.values()) {
            copyAndVerify(bundle, staging, artifact, faultInjector);
        }
        writeForced(staging.resolve("backup-manifest.json"), manifestBytes,
                faultInjector,
                ShadowRestoreCoordinator.RestoreStep.BACKUP_MANIFEST_FSYNC);
        return new VerifiedBundle(manifest, artifacts.keySet());
    }

    private static TreeMap<String, Artifact> declaredArtifacts(
            OnlineBackupManifest manifest) throws IOException {
        TreeMap<String, Artifact> artifacts = new TreeMap<>();
        addArtifact(artifacts, manifest.getH2dbArtifact());
        for (Participant participant : manifest.getParticipants()) {
            for (Artifact artifact : participant.getArtifacts()) {
                addArtifact(artifacts, artifact);
            }
        }
        if (!"h2/database.mv.db".equals(
                manifest.getH2dbArtifact().getPath())) {
            throw new IOException(
                    "Unsupported H2 artifact path in formatVersion=1");
        }
        return artifacts;
    }

    private static void addArtifact(TreeMap<String, Artifact> artifacts,
            Artifact artifact) throws IOException {
        String path = normalizeRelativePath(artifact.getPath());
        if (artifacts.put(path, artifact) != null) {
            throw new IOException("Duplicate artifact path: " + path);
        }
    }

    private static void verifyBundleTree(Path bundle,
            Set<String> declaredArtifacts) throws IOException {
        final HashSet<String> remaining = new HashSet<>(declaredArtifacts);
        Files.walkFileTree(bundle, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                    BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Symbolic link in backup bundle");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()
                        || Files.isSymbolicLink(file)) {
                    throw new IOException(
                            "Non-regular file in backup bundle");
                }
                String relative = bundle.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                if ("manifest.json".equals(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!remaining.remove(relative)) {
                    throw new IOException(
                            "Undeclared file in backup bundle: " + relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!remaining.isEmpty()) {
            throw new IOException(
                    "Declared backup artifact is missing: "
                            + remaining.iterator().next());
        }
    }

    private static void copyAndVerify(Path bundle, Path staging,
            Artifact artifact,
            ShadowRestoreCoordinator.RestoreFaultInjector faultInjector)
            throws IOException {
        String relative = normalizeRelativePath(artifact.getPath());
        Path source = resolveInside(bundle, relative);
        Path target = resolveInside(staging, relative);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new IOException(
                    "Backup artifact is not a regular file: " + relative);
        }
        if (Files.size(source) != artifact.getLength()) {
            throw new IOException(
                    "Backup artifact length mismatch: " + relative);
        }
        Files.createDirectories(target.getParent());
        MessageDigest digest = sha256();
        long copied = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try (FileChannel input = FileChannel.open(source,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                FileChannel output = FileChannel.open(target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            faultInjector.before(
                    ShadowRestoreCoordinator.RestoreStep.ARTIFACT_COPY,
                    target);
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                copied += read;
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
            faultInjector.before(
                    ShadowRestoreCoordinator.RestoreStep.ARTIFACT_FSYNC,
                    target);
            output.force(true);
        }
        if (copied != artifact.getLength()
                || Files.size(source) != artifact.getLength()
                || !hex(digest.digest()).equals(artifact.getSha256())) {
            throw new IOException(
                    "Backup artifact checksum mismatch: " + relative);
        }
    }

    static Path resolveInside(Path root, String relative) throws IOException {
        String normalized = normalizeRelativePath(relative);
        Path resolved = root.resolve(normalized.replace('/',
                java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Path escapes root: " + relative);
        }
        return resolved;
    }

    static String normalizeRelativePath(String relative) throws IOException {
        if (relative == null || relative.trim().isEmpty()
                || relative.indexOf('\0') >= 0) {
            throw new IOException("Artifact path must not be empty");
        }
        String path = relative.replace('\\', '/');
        if (path.startsWith("/") || path.endsWith("/")
                || path.matches("^[A-Za-z]:.*")) {
            throw new IOException("Artifact path must be relative");
        }
        String[] elements = path.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String element : elements) {
            if (element.isEmpty() || ".".equals(element)
                    || "..".equals(element) || unsafeElement(element)) {
                throw new IOException("Unsafe artifact path: " + relative);
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(element);
        }
        return result.toString();
    }

    static void writeForced(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    static void writeForced(Path file, byte[] bytes,
            ShadowRestoreCoordinator.RestoreFaultInjector faultInjector,
            ShadowRestoreCoordinator.RestoreStep step) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            faultInjector.before(step, file);
            channel.force(true);
        }
    }

    private static boolean unsafeElement(String element) {
        if (element.endsWith(".")
                || Character.isWhitespace(
                        element.charAt(element.length() - 1))) {
            return true;
        }
        for (int i = 0; i < element.length(); i++) {
            char c = element.charAt(i);
            if (c < ' ' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                return true;
            }
        }
        return false;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0xf];
        }
        return new String(result);
    }

    static final class VerifiedBundle {

        final OnlineBackupManifest manifest;
        final List<String> artifactPaths;

        VerifiedBundle(OnlineBackupManifest manifest,
                Set<String> artifactPaths) {
            this.manifest = manifest;
            this.artifactPaths = Collections.unmodifiableList(
                    new ArrayList<>(artifactPaths));
        }
    }
}
