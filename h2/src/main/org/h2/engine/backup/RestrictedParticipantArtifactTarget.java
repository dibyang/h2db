/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.h2.api.ParticipantArtifactTarget;

/**
 * Restricts participant materialization to tracked regular files below one
 * participant root.
 */
final class RestrictedParticipantArtifactTarget
        implements ParticipantArtifactTarget {

    private final Path root;
    private final OnlineBackupSession session;
    private final TreeSet<String> createdPaths = new TreeSet<>();
    private final Set<TrackedOutputStream> openStreams = new HashSet<>();
    private boolean finished;

    RestrictedParticipantArtifactTarget(Path root,
            OnlineBackupSession session) {
        this.root = root;
        this.session = session;
    }

    @Override
    public synchronized OutputStream create(String relativePath)
            throws IOException {
        checkWriteAllowed();
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
        TrackedOutputStream stream = new TrackedOutputStream(this, channel);
        openStreams.add(stream);
        return stream;
    }

    @Override
    public boolean isCancellationRequested() {
        return session.isMaterializationCancellationRequested();
    }

    synchronized void finish() throws IOException {
        finished = true;
        if (openStreams.isEmpty()) {
            return;
        }
        IOException failure = new IOException(
                "Participant left artifact streams open");
        ArrayList<TrackedOutputStream> copy = new ArrayList<>(openStreams);
        for (TrackedOutputStream stream : copy) {
            try {
                stream.close();
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    synchronized void checkWriteAllowed() throws IOException {
        if (finished) {
            throw new IOException(
                    "Participant artifact target is already finished");
        }
        session.checkMaterializationAllowed();
    }

    synchronized void closed(TrackedOutputStream stream) {
        openStreams.remove(stream);
    }

    synchronized Set<String> getCreatedPaths() {
        return Collections.unmodifiableSet(new TreeSet<>(createdPaths));
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
        return OnlineBackupBundleVerifier.normalizeArtifactPath(relativePath);
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

    private static final class TrackedOutputStream
            extends FilterOutputStream {

        private final RestrictedParticipantArtifactTarget owner;
        private final FileChannel channel;
        private boolean closed;

        TrackedOutputStream(RestrictedParticipantArtifactTarget owner,
                FileChannel channel) {
            super(Channels.newOutputStream(channel));
            this.owner = owner;
            this.channel = channel;
        }

        @Override
        public void write(int value) throws IOException {
            owner.checkWriteAllowed();
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length)
                throws IOException {
            owner.checkWriteAllowed();
            out.write(bytes, offset, length);
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
