/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Describes and validates a candidate external H2 jar used by longrun tests.
 */
public final class ExternalH2JarMetadata {

    private final File file;
    private final long sizeBytes;
    private final String sha256;
    private final String implementationTitle;
    private final String implementationVersion;

    private ExternalH2JarMetadata(File file, long sizeBytes, String sha256, String implementationTitle,
            String implementationVersion) {
        this.file = file;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.implementationTitle = implementationTitle;
        this.implementationVersion = implementationVersion;
    }

    public static ExternalH2JarMetadata inspect(File file) throws IOException {
        if (file == null) {
            return null;
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("Candidate H2 jar does not exist: " + file.getAbsolutePath());
        }
        Manifest manifest;
        try (JarFile jarFile = new JarFile(file)) {
            manifest = jarFile.getManifest();
        }
        Attributes attributes = manifest == null ? null : manifest.getMainAttributes();
        String title = attribute(attributes, Attributes.Name.IMPLEMENTATION_TITLE);
        String version = attribute(attributes, Attributes.Name.IMPLEMENTATION_VERSION);
        return new ExternalH2JarMetadata(file.getCanonicalFile(), file.length(), sha256(file), title, version);
    }

    public File getFile() {
        return file;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getImplementationTitle() {
        return implementationTitle;
    }

    public String getImplementationVersion() {
        return implementationVersion;
    }

    public String summary() {
        return "h2Jar=" + file.getPath() +
                ", h2JarSizeBytes=" + sizeBytes +
                ", h2JarSha256=" + sha256 +
                ", h2JarTitle=" + implementationTitle +
                ", h2JarVersion=" + implementationVersion;
    }

    private static String attribute(Attributes attributes, Attributes.Name name) {
        if (attributes == null) {
            return "";
        }
        String value = attributes.getValue(name);
        return value == null ? "" : value;
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (DigestInputStream in = new DigestInputStream(new FileInputStream(file), digest)) {
                while (in.read(buffer) >= 0) {
                    // DigestInputStream updates the digest while bytes are read.
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }
}
