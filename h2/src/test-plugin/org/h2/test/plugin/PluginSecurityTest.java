/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;

import org.h2.engine.PluginSecurity;
import org.junit.jupiter.api.Test;

/**
 * 插件安全边界的 JUnit 验证。
 */
public class PluginSecurityTest {

    /**
     * T-PLUGIN-F7-SENSITIVE-TRACE-01.
     */
    @Test
    public void masksSensitiveConfigurationValues() {
        String masked = PluginSecurity.maskSensitiveConfig("password=abc;token=xyz;user=sa;key=k1");

        assertFalse(masked.contains("abc"));
        assertFalse(masked.contains("xyz"));
        assertFalse(masked.contains("k1"));
        assertTrue(masked.contains("user=sa"));
    }

    /**
     * T-PLUGIN-F7-CLASSLOADER-CLOSE-01.
     */
    @Test
    public void closesCloseableClassLoader() {
        URLClassLoader classLoader = new URLClassLoader(new URL[0]);

        assertTrue(PluginSecurity.closeClassLoader(classLoader));
    }
}
