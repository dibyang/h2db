/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.h2.api.MaterializedParticipantArtifact;
import org.h2.api.ParticipantArtifactTarget;
import org.h2.engine.Constants;
import org.h2.engine.backup.OnlineBackupManifest.Artifact;
import org.h2.engine.backup.OnlineBackupManifest.Participant;
import org.h2.engine.backup.OnlineBackupPublishResult.DirectoryFsync;
import org.h2.util.json.JSONByteArrayTarget;

/**
 * Materializes prepared snapshots into a directory bundle and publishes it
 * with a same-parent atomic move.
 */
final class OnlineBackupBundlePublisher {

    private static final String MANIFEST_NAME = "manifest.json";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final long MAX_MANIFEST_BYTES = 4L * 1024L * 1024L;
    private static final PublishFaultInjector NO_FAULTS =
            new PublishFaultInjector() {
                @Override
                public void before(PublishStep step, Path path)
                        throws IOException {
                    // Production publication does not inject failures.
                }
            };

    /**
     * 包内测试使用的发布故障点，不属于公开 API。
     */
    enum PublishStep {
        CHECKSUM,
        ARTIFACT_FSYNC,
        MANIFEST_FSYNC,
        STAGING_DIRECTORY_FSYNC,
        ATOMIC_MOVE,
        PARENT_DIRECTORY_FSYNC
    }

    /**
     * 包内测试使用的确定性故障接缝。
     */
    interface PublishFaultInjector {

        /**
         * 在指定发布步骤执行前注入失败。
         *
         * @param step 发布步骤
         * @param path 当前步骤操作的路径
         * @throws IOException 注入的 I/O 失败
         */
        void before(PublishStep step, Path path) throws IOException;
    }

    private OnlineBackupBundlePublisher() {
    }

    static OnlineBackupPublishResult publish(OnlineBackupSession session,
            Path finalDirectory) throws Exception {
        return publish(session, finalDirectory, NO_FAULTS);
    }

    /**
     * 使用指定包内故障接缝发布 bundle。
     *
     * @param session 已准备的备份会话
     * @param finalDirectory 最终 bundle 目录
     * @param faultInjector 确定性故障接缝
     * @return 发布结果
     * @throws Exception 物化或持久化失败
     */
    static OnlineBackupPublishResult publish(OnlineBackupSession session,
            Path finalDirectory, PublishFaultInjector faultInjector)
            throws Exception {
        if (faultInjector == null) {
            throw new IllegalArgumentException("faultInjector is required");
        }
        Path parent = finalDirectory.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Final bundle directory must have a parent");
        }
        if (!Files.isDirectory(parent) || Files.isSymbolicLink(parent)) {
            throw new IOException(
                    "Final bundle parent is not a regular directory: "
                            + parent);
        }
        Path auditFile = auditPath(finalDirectory,
                session.getContext().getBackupId().toString());
        if (Files.exists(finalDirectory,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            OnlineBackupManifest existing = readPublishedManifest(
                    finalDirectory);
            requireSameCut(session, existing);
            DirectoryFsync parentFsync = forceDirectory(parent, faultInjector,
                    PublishStep.PARENT_DIRECTORY_FSYNC);
            writeAudit(auditFile, session, "PUBLISHED", true,
                    DirectoryFsync.NOT_APPLICABLE, parentFsync, null);
            return new OnlineBackupPublishResult(finalDirectory, auditFile,
                    existing, true, DirectoryFsync.NOT_APPLICABLE,
                    parentFsync);
        }

        Path staging = stagingPath(finalDirectory,
                session.getContext().getBackupId().toString());
        requireSameParent(parent, staging);
        if (Files.exists(staging,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(staging);
        }
        writeAudit(auditFile, session, "MATERIALIZING", false,
                DirectoryFsync.NOT_APPLICABLE,
                DirectoryFsync.NOT_APPLICABLE, null);
        boolean published = false;
        Throwable failure = null;
        try {
            Files.createDirectory(staging);
            Files.createDirectory(staging.resolve("h2"));
            Path participantDirectory = Files.createDirectory(
                    staging.resolve("participants"));
            String h2RelativePath = "h2/database.mv.db";
            Path h2File = staging.resolve(h2RelativePath);
            session.getH2Snapshot().materialize(h2File.toString());
            Artifact h2Artifact = checksum(staging, h2File, faultInjector);

            ArrayList<Participant> participantManifests = new ArrayList<>();
            for (OnlineBackupSession.ParticipantMaterializer materializer
                    : session.getParticipantMaterializers()) {
                participantManifests.add(materializeParticipant(staging,
                        participantDirectory, materializer, faultInjector));
            }
            OnlineBackupManifest manifest = new OnlineBackupManifest(
                    OnlineBackupManifest.FORMAT_VERSION,
                    OnlineBackupManifest.STATUS_PUBLISHED,
                    session.getContext().getBackupId(),
                    session.getContext().getCutId(), session.getDatabaseName(),
                    session.getContext().getDatabaseId(),
                    session.getContext().getGenerationId(),
                    session.getContext().getSchemaEpoch(), Constants.VERSION,
                    session.getStorageEngineId(),
                    session.getRequiredValidationProviders(),
                    Instant.now().toString(), session.getPreparePauseMillis(),
                    h2Artifact, participantManifests);
            byte[] manifestBytes = OnlineBackupManifestCodec.encode(manifest);
            writeForcedFile(staging.resolve(MANIFEST_NAME), manifestBytes,
                    faultInjector, PublishStep.MANIFEST_FSYNC);
            DirectoryFsync stagingFsync = forceDirectory(staging,
                    faultInjector, PublishStep.STAGING_DIRECTORY_FSYNC);
            try {
                faultInjector.before(PublishStep.ATOMIC_MOVE, finalDirectory);
                Files.move(staging, finalDirectory,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException(
                        "Atomic directory publish is not supported", e);
            } catch (FileAlreadyExistsException e) {
                OnlineBackupManifest existing = readPublishedManifest(
                        finalDirectory);
                requireSameCut(session, existing);
                deleteTree(staging);
                DirectoryFsync parentFsync = forceDirectory(parent,
                        faultInjector, PublishStep.PARENT_DIRECTORY_FSYNC);
                writeAudit(auditFile, session, "PUBLISHED", true,
                        stagingFsync, parentFsync, null);
                return new OnlineBackupPublishResult(finalDirectory,
                        auditFile, existing, true, stagingFsync,
                        parentFsync);
            }
            published = true;
            DirectoryFsync parentFsync = forceDirectory(parent, faultInjector,
                    PublishStep.PARENT_DIRECTORY_FSYNC);
            writeAudit(auditFile, session, "PUBLISHED", false, stagingFsync,
                    parentFsync, null);
            return new OnlineBackupPublishResult(finalDirectory, auditFile,
                    manifest, false, stagingFsync, parentFsync);
        } catch (Throwable e) {
            failure = e;
            rethrow(e);
            throw new AssertionError();
        } finally {
            if (!published && Files.exists(staging,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    deleteTree(staging);
                } catch (Throwable cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null && !published) {
                try {
                    writeAudit(auditFile, session, "FAILED", false,
                            DirectoryFsync.NOT_APPLICABLE,
                            DirectoryFsync.NOT_APPLICABLE,
                            failure.getClass().getName());
                } catch (Throwable auditFailure) {
                    failure.addSuppressed(auditFailure);
                }
            }
        }
    }

    private static Participant materializeParticipant(Path staging,
            Path participantDirectory,
            OnlineBackupSession.ParticipantMaterializer materializer,
            PublishFaultInjector faultInjector)
            throws Exception {
        OnlineBackupSession.ParticipantSnapshot snapshot =
                materializer.snapshot;
        String rootName = "p-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(snapshot.getParticipantId()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path root = Files.createDirectory(participantDirectory.resolve(
                rootName));
        RestrictedArtifactTarget target = new RestrictedArtifactTarget(root);
        MaterializedParticipantArtifact materialized = null;
        Throwable failure = null;
        try {
            materialized = materializer.prepared.materialize(target);
        } catch (Throwable e) {
            failure = e;
            rethrow(e);
            throw new AssertionError();
        } finally {
            try {
                target.finish();
            } catch (Throwable closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else {
                    rethrow(closeFailure);
                }
            }
        }
        target.verifyTree();
        if (materialized == null || !snapshot.getParticipantId().equals(
                materialized.getParticipantId())) {
            throw new IllegalStateException(
                    "Participant materialization id mismatch: "
                            + snapshot.getParticipantId());
        }
        TreeSet<String> reported = new TreeSet<>();
        for (String path : materialized.getRelativePaths()) {
            if (!reported.add(RestrictedArtifactTarget.normalize(path))) {
                throw new IllegalStateException(
                        "Duplicate participant artifact: " + path);
            }
        }
        if (!reported.equals(target.getCreatedPaths())) {
            throw new IllegalStateException(
                    "Participant artifact report does not match created files: "
                            + snapshot.getParticipantId());
        }
        ArrayList<Artifact> artifacts = new ArrayList<>();
        for (String relative : reported) {
            artifacts.add(checksum(staging, root.resolve(
                    relative.replace('/', java.io.File.separatorChar)),
                    faultInjector));
        }
        return new Participant(snapshot.getParticipantId(),
                snapshot.getPluginId(), snapshot.getPluginVersion(),
                snapshot.getMetadata().getSnapshotId(),
                snapshot.getMetadata().getAttributes(), artifacts);
    }

    private static Artifact checksum(Path bundleRoot, Path file,
            PublishFaultInjector faultInjector) throws IOException {
        faultInjector.before(PublishStep.CHECKSUM, file);
        if (!Files.isRegularFile(file,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IOException("Artifact is not a regular file: " + file);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        forceFile(file, faultInjector);
        String relative = bundleRoot.relativize(file).toString()
                .replace(java.io.File.separatorChar, '/');
        return new Artifact(relative, Files.size(file), hex(digest.digest()));
    }

    private static void forceFile(Path file,
            PublishFaultInjector faultInjector) throws IOException {
        faultInjector.before(PublishStep.ARTIFACT_FSYNC, file);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void writeForcedFile(Path file, byte[] bytes)
            throws IOException {
        writeForcedFile(file, bytes, NO_FAULTS,
                PublishStep.MANIFEST_FSYNC);
    }

    private static void writeForcedFile(Path file, byte[] bytes,
            PublishFaultInjector faultInjector, PublishStep step)
            throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            faultInjector.before(step, file);
            channel.force(true);
        }
    }

    private static DirectoryFsync forceDirectory(Path directory)
            throws IOException {
        return forceDirectory(directory, NO_FAULTS,
                PublishStep.PARENT_DIRECTORY_FSYNC);
    }

    private static DirectoryFsync forceDirectory(Path directory,
            PublishFaultInjector faultInjector, PublishStep step)
            throws IOException {
        faultInjector.before(step, directory);
        try (FileChannel channel = FileChannel.open(directory,
                StandardOpenOption.READ)) {
            channel.force(true);
            return DirectoryFsync.SUCCEEDED;
        } catch (AccessDeniedException e) {
            return DirectoryFsync.UNSUPPORTED;
        } catch (UnsupportedOperationException e) {
            return DirectoryFsync.UNSUPPORTED;
        }
    }

    private static void requireSameCut(OnlineBackupSession session,
            OnlineBackupManifest manifest) {
        if (!manifest.getBackupId().equals(
                session.getContext().getBackupId())
                || !manifest.getCutId().equals(
                        session.getContext().getCutId())
                || !manifest.getDatabaseId().equals(
                        session.getContext().getDatabaseId())
                || manifest.getSchemaEpoch()
                        != session.getContext().getSchemaEpoch()
                || !manifest.getStorageEngineId().equals(
                        session.getStorageEngineId())) {
            throw new IllegalStateException(
                    "Final bundle already exists with different identity");
        }
        List<OnlineBackupManifest.RequiredProvider> expectedProviders =
                session.getRequiredValidationProviders();
        List<OnlineBackupManifest.RequiredProvider> actualProviders =
                manifest.getRequiredProviders();
        if (expectedProviders.size() != actualProviders.size()) {
            throw new IllegalStateException(
                    "Final bundle required providers differ");
        }
        for (int i = 0; i < expectedProviders.size(); i++) {
            OnlineBackupManifest.RequiredProvider left =
                    expectedProviders.get(i);
            OnlineBackupManifest.RequiredProvider right =
                    actualProviders.get(i);
            if (!left.getType().equals(right.getType())
                    || !left.getId().equals(right.getId())
                    || !left.getPluginId().equals(right.getPluginId())
                    || !left.getPluginVersion().equals(
                            right.getPluginVersion())) {
                throw new IllegalStateException(
                        "Final bundle required providers differ");
            }
        }
        List<OnlineBackupSession.ParticipantSnapshot> expected =
                session.getParticipants();
        List<Participant> actual = manifest.getParticipants();
        if (expected.size() != actual.size()) {
            throw new IllegalStateException(
                    "Final bundle participant selection differs");
        }
        for (int i = 0; i < expected.size(); i++) {
            OnlineBackupSession.ParticipantSnapshot left = expected.get(i);
            Participant right = actual.get(i);
            if (!left.getParticipantId().equals(right.getParticipantId())
                    || !left.getPluginId().equals(right.getPluginId())
                    || !left.getPluginVersion().equals(
                            right.getPluginVersion())
                    || !left.getMetadata().getSnapshotId().equals(
                            right.getSnapshotId())
                    || !left.getMetadata().getAttributes().equals(
                            right.getAttributes())) {
                throw new IllegalStateException(
                        "Final bundle participant metadata differs: "
                                + left.getParticipantId());
            }
        }
    }

    private static OnlineBackupManifest readPublishedManifest(Path bundle)
            throws IOException {
        if (!Files.isDirectory(bundle) || Files.isSymbolicLink(bundle)) {
            throw new IOException(
                    "Final bundle path is not a regular directory");
        }
        Path manifest = bundle.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifest)) {
            throw new IOException("Published manifest is missing");
        }
        if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
            throw new IOException("Published manifest is too large");
        }
        return OnlineBackupManifestCodec.decode(Files.readAllBytes(manifest));
    }

    private static Path stagingPath(Path finalDirectory, String backupId) {
        return finalDirectory.resolveSibling(
                "." + finalDirectory.getFileName() + ".staging-" + backupId);
    }

    private static Path auditPath(Path finalDirectory, String backupId) {
        return finalDirectory.resolveSibling(
                "." + finalDirectory.getFileName() + "." + backupId
                        + ".audit.json");
    }

    private static void requireSameParent(Path parent, Path path) {
        if (!parent.equals(path.getParent())) {
            throw new IllegalArgumentException(
                    "Staging and final directories must share a parent");
        }
    }

    private static void writeAudit(Path auditFile, OnlineBackupSession session,
            String status, boolean reused, DirectoryFsync stagingFsync,
            DirectoryFsync parentFsync, String failureType)
            throws IOException {
        JSONByteArrayTarget target = new JSONByteArrayTarget();
        target.startObject();
        auditString(target, "backupId",
                session.getContext().getBackupId().toString());
        auditString(target, "cutId",
                session.getContext().getCutId().toString());
        auditString(target, "databaseId",
                session.getContext().getDatabaseId().toString());
        auditString(target, "status", status);
        target.member("reused");
        if (reused) {
            target.valueTrue();
        } else {
            target.valueFalse();
        }
        auditString(target, "stagingDirectoryFsync", stagingFsync.name());
        auditString(target, "parentDirectoryFsync", parentFsync.name());
        auditString(target, "updatedAt", Instant.now().toString());
        if (failureType != null) {
            auditString(target, "failureType", failureType);
        }
        target.endObject();
        Path temporary = auditFile.resolveSibling(
                auditFile.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        writeForcedFile(temporary, target.getResult());
        try {
            Files.move(temporary, auditFile,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, auditFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void auditString(JSONByteArrayTarget target, String name,
            String value) {
        target.member(name);
        target.valueString(value);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root.getParent() == null) {
            throw new IOException("Refusing to delete a root path");
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory,
                    IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
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

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw (Error) failure;
    }

    private static final class RestrictedArtifactTarget
            implements ParticipantArtifactTarget {

        private final Path root;
        private final TreeSet<String> createdPaths = new TreeSet<>();
        private final Set<TrackedOutputStream> openStreams = new HashSet<>();

        RestrictedArtifactTarget(Path root) {
            this.root = root;
        }

        @Override
        public synchronized OutputStream create(String relativePath)
                throws IOException {
            String normalized = normalize(relativePath);
            if (!createdPaths.add(normalized)) {
                throw new FileAlreadyExistsException(normalized);
            }
            Path file = root.resolve(normalized.replace('/',
                    java.io.File.separatorChar)).normalize();
            if (!file.startsWith(root)) {
                throw new IOException("Participant artifact escapes root");
            }
            Path parent = file.getParent();
            Files.createDirectories(parent);
            ensureNoSymlink(root, parent);
            FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            TrackedOutputStream stream = new TrackedOutputStream(this,
                    channel);
            openStreams.add(stream);
            return stream;
        }

        synchronized void finish() throws IOException {
            if (openStreams.isEmpty()) {
                return;
            }
            IOException failure = new IOException(
                    "Participant left artifact streams open");
            ArrayList<TrackedOutputStream> copy =
                    new ArrayList<>(openStreams);
            for (TrackedOutputStream stream : copy) {
                try {
                    stream.close();
                } catch (IOException e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        }

        synchronized void closed(TrackedOutputStream stream) {
            openStreams.remove(stream);
        }

        synchronized Set<String> getCreatedPaths() {
            return Collections.unmodifiableSet(
                    new TreeSet<>(createdPaths));
        }

        void verifyTree() throws IOException {
            final TreeSet<String> actual = new TreeSet<>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory,
                        BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new IOException(
                                "Symbolic link in participant artifact tree");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile()
                            || Files.isSymbolicLink(file)) {
                        throw new IOException(
                                "Non-regular participant artifact");
                    }
                    actual.add(root.relativize(file).toString()
                            .replace(java.io.File.separatorChar, '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
            if (!actual.equals(getCreatedPaths())) {
                throw new IOException(
                        "Participant created untracked artifact files");
            }
        }

        static String normalize(String relativePath) {
            if (relativePath == null || relativePath.trim().isEmpty()
                    || relativePath.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Artifact path must not be empty");
            }
            String slashPath = relativePath.replace('\\', '/');
            if (slashPath.startsWith("/") || slashPath.endsWith("/")
                    || slashPath.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException(
                        "Artifact path must be relative: " + relativePath);
            }
            String[] elements = slashPath.split("/", -1);
            StringBuilder normalized = new StringBuilder();
            for (String element : elements) {
                if (element.isEmpty() || ".".equals(element)
                        || "..".equals(element) || isUnsafeElement(element)) {
                    throw new IllegalArgumentException(
                            "Unsafe artifact path: " + relativePath);
                }
                if (normalized.length() > 0) {
                    normalized.append('/');
                }
                normalized.append(element);
            }
            return normalized.toString();
        }

        private static boolean isUnsafeElement(String element) {
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

        private static void ensureNoSymlink(Path root, Path directory)
                throws IOException {
            Path current = root;
            Path relative = root.relativize(directory);
            for (Path element : relative) {
                current = current.resolve(element);
                if (Files.isSymbolicLink(current)) {
                    throw new IOException(
                            "Symbolic link in participant artifact path");
                }
            }
        }
    }

    private static final class TrackedOutputStream
            extends FilterOutputStream {

        private final RestrictedArtifactTarget owner;
        private final FileChannel channel;
        private boolean closed;

        TrackedOutputStream(RestrictedArtifactTarget owner,
                FileChannel channel) {
            super(Channels.newOutputStream(channel));
            this.owner = owner;
            this.channel = channel;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                flush();
                channel.force(true);
            } catch (IOException e) {
                failure = e;
            }
            try {
                super.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                owner.closed(this);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
