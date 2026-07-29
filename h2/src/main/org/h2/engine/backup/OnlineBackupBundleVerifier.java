/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.h2.engine.backup.OnlineBackupManifest.Artifact;
import org.h2.engine.backup.OnlineBackupManifest.Participant;

/**
 * Verifies that a published backup bundle still matches its immutable manifest.
 */
final class OnlineBackupBundleVerifier {

    private static final int DIGEST_BUFFER_SIZE = 64 * 1024;
    private static final String MANIFEST_NAME = "manifest.json";

    private OnlineBackupBundleVerifier() {
    }

    /**
     * Verify all registered artifacts and reject unregistered files.
     *
     * @param bundle published bundle root
     * @param manifest decoded published manifest
     * @throws IOException if the artifact tree is missing, unsafe, or corrupt
     */
    static void verify(Path bundle, OnlineBackupManifest manifest)
            throws IOException {
        final Map<String, Artifact> expected = new HashMap<>();
        addExpected(expected, manifest.getH2dbArtifact());
        for (Participant participant : manifest.getParticipants()) {
            for (Artifact artifact : participant.getArtifacts()) {
                addExpected(expected, artifact);
            }
        }
        final Set<String> verified = new HashSet<>();
        Files.walkFileTree(bundle, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                    BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException(
                            "Symbolic link in published bundle directory");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()
                        || Files.isSymbolicLink(file)) {
                    throw new IOException(
                            "Non-regular file in published bundle: " + file);
                }
                String relative = bundle.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                if (MANIFEST_NAME.equals(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                Artifact artifact = expected.get(relative);
                if (artifact == null) {
                    throw new IOException(
                            "Unregistered file in published bundle: "
                                    + relative);
                }
                verifyArtifact(file, artifact, attributes.size());
                verified.add(relative);
                return FileVisitResult.CONTINUE;
            }
        });
        if (verified.size() != expected.size()) {
            HashSet<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(verified);
            throw new IOException(
                    "Published bundle artifacts are missing: " + missing);
        }
    }

    /**
     * Normalize and validate a bundle-relative artifact path.
     *
     * @param relativePath artifact path
     * @return canonical slash-separated path
     */
    static String normalizeArtifactPath(String relativePath) {
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

    private static void addExpected(Map<String, Artifact> expected,
            Artifact artifact) throws IOException {
        String normalized;
        try {
            normalized = normalizeArtifactPath(artifact.getPath());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid artifact path in manifest", e);
        }
        if (!normalized.equals(artifact.getPath())
                || MANIFEST_NAME.equals(normalized)) {
            throw new IOException(
                    "Non-canonical artifact path in manifest: "
                            + artifact.getPath());
        }
        if (expected.put(normalized, artifact) != null) {
            throw new IOException(
                    "Duplicate artifact path in manifest: " + normalized);
        }
    }

    private static void verifyArtifact(Path file, Artifact artifact,
            long actualLength) throws IOException {
        if (actualLength != artifact.getLength()) {
            throw new IOException(
                    "Published artifact length mismatch: "
                            + artifact.getPath());
        }
        String actualDigest = sha256(file);
        if (!actualDigest.equals(artifact.getSha256())) {
            throw new IOException(
                    "Published artifact checksum mismatch: "
                            + artifact.getPath());
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[DIGEST_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
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
}
