/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JUnit checks for UTF-8 longrun documentation that contains Chinese text.
 */
public final class LongRunDocumentationEncodingTest {

    private static final String[] MOJIBAKE_MARKERS = {
            "\uFFFD", "鍙", "榛", "鐨", "鏂", "瑕", "乣", "鎶", "骞", "绌洪棿"
    };

    @Test
    public void chineseLongRunDocsAreReadableUtf8WithoutMojibake() throws Exception {
        List<File> files = chineseLongRunDocs();
        assertFalse(files.isEmpty());
        for (File file : files) {
            String text = decodeUtf8(file);
            for (String marker : MOJIBAKE_MARKERS) {
                assertFalse(text.contains(marker), "Mojibake marker " + marker + " found in " + file);
            }
        }
    }

    private static List<File> chineseLongRunDocs() {
        ArrayList<File> files = new ArrayList<>();
        addIfExists(files, new File("src/longrun/dist/README.md"));
        File docsDir = new File("../docs/longrun");
        File[] docs = docsDir.listFiles((dir, name) -> name.endsWith(".md") && !name.endsWith(".en.md"));
        if (docs != null) {
            for (File doc : docs) {
                files.add(doc);
            }
        }
        return files;
    }

    private static void addIfExists(List<File> files, File file) {
        if (file.isFile()) {
            files.add(file);
        }
    }

    private static String decodeUtf8(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError("Invalid UTF-8 in " + file, e);
        }
    }
}
