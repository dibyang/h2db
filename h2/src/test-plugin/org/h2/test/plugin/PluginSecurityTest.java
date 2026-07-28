/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;

import org.h2.api.H2Plugin;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.PreparedBackupParticipant;
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

    /**
     * T-PLUGIN-R6-CLASSLOADER-ISOLATION-01.
     */
    @Test
    public void createsIsolatedClassLoaderForPluginPaths() {
        ClassLoader classLoader = PluginSecurity.createPluginClassLoader(new File("build").getAbsolutePath());

        assertNotNull(classLoader);
        assertTrue(classLoader instanceof URLClassLoader);
        assertTrue(PluginSecurity.closeClassLoader(classLoader));
    }

    /**
     * T-PLUGIN-R6-RESOURCE-CLOSE-01.
     */
    @Test
    public void emptyPluginPathsReuseCurrentClasspath() {
        assertFalse(PluginSecurity.closeClassLoader(PluginSecurity.createPluginClassLoader(null)));
        assertFalse(PluginSecurity.closeClassLoader(PluginSecurity.createPluginClassLoader("")));
    }

    /**
     * T-H2BR-PARTICIPANT-PROVIDER-SECURITY-01.
     */
    @Test
    public void allowsOnlineBackupParticipantProviderType() {
        OnlineBackupParticipantProvider provider =
                new OnlineBackupParticipantProvider() {
                    @Override
                    public String getType() {
                        return TYPE;
                    }

                    @Override
                    public String getId() {
                        return "backup";
                    }

                    @Override
                    public boolean supports(String capability) {
                        return PluginCapability.ONLINE_BACKUP_PREPARE
                                .equals(capability);
                    }

                    @Override
                    public PreparedBackupParticipant prepare(
                            OnlineBackupContext context) {
                        throw new UnsupportedOperationException();
                    }
                };
        H2Plugin plugin = new H2Plugin() {
            @Override
            public String getId() {
                return "backup.plugin";
            }

            @Override
            public String getVersion() {
                return "1";
            }

            @Override
            public String getDisplayName() {
                return "Backup Plugin";
            }

            @Override
            public Iterable<String> getAllowedProviderTypes() {
                return Collections.singletonList(
                        OnlineBackupParticipantProvider.TYPE);
            }

            @Override
            public Iterable<? extends PluginProvider> getProviders() {
                return Collections.singletonList(provider);
            }
        };
        assertDoesNotThrow(
                () -> PluginSecurity.validateProviderTypes(plugin));
    }
}
