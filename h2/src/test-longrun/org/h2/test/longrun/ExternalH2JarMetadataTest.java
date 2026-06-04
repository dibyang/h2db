/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for external H2 jar metadata used by longrun external mode.
 */
public final class ExternalH2JarMetadataTest {

    @TempDir
    public File tempDir;

    @Test
    public void inspectReadsManifestAndChecksum() throws Exception {
        File jar = new File(tempDir, "h2-candidate.jar");
        writeJar(jar);

        ExternalH2JarMetadata metadata = ExternalH2JarMetadata.inspect(jar);

        assertEquals(jar.getCanonicalFile(), metadata.getFile());
        assertEquals(jar.length(), metadata.getSizeBytes());
        assertEquals("H2 Candidate", metadata.getImplementationTitle());
        assertEquals("test-version", metadata.getImplementationVersion());
        assertNotNull(metadata.getSha256());
        assertEquals(64, metadata.getSha256().length());
    }

    @Test
    public void inspectRejectsMissingJar() {
        File jar = new File(tempDir, "missing.jar");

        assertThrows(IllegalArgumentException.class, () -> ExternalH2JarMetadata.inspect(jar));
    }

    private static void writeJar(File file) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "H2 Candidate");
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "test-version");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file), manifest)) {
            out.putNextEntry(new JarEntry("candidate.txt"));
            out.write(new byte[] { 1, 2, 3 });
            out.closeEntry();
        }
    }
}
