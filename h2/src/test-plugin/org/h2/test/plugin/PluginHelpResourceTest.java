/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * 插件帮助资源的 JUnit 验证。
 */
public class PluginHelpResourceTest {

    /**
     * T-PLUGIN-R9-HELP-RESOURCE-01.
     */
    @Test
    public void exposesPluginHelpTopic() throws Exception {
        try (InputStream in = PluginHelpResourceTest.class.getResourceAsStream("/org/h2/res/help.csv")) {
            assertTrue(in != null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int len; (len = in.read(buffer)) >= 0;) {
                out.write(buffer, 0, len);
            }
            String text = new String(out.toByteArray(), "UTF-8");
            assertTrue(text.contains("\"Advanced\",\"Plugins\""));
            assertTrue(text.contains("META-INF/services/org.h2.api.H2Plugin"));
            assertTrue(text.contains("discovers these plugins automatically"));
            assertTrue(text.contains("STORAGE_ENGINE"));
        }
    }
}
