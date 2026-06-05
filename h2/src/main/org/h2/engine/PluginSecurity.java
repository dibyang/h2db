/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.h2.api.H2Plugin;
import org.h2.api.JdbcUrlPrefixProvider;
import org.h2.api.PluginProvider;
import org.h2.api.StorageEngineProvider;
import org.h2.api.SystemCatalogProvider;
import org.h2.api.TableEngineProvider;
import org.h2.api.TransactionEventProvider;
import org.h2.message.DbException;

/**
 * 外部插件安全边界工具。
 */
public final class PluginSecurity {

    private static final Set<String> ALLOWED_PROVIDER_TYPES = new HashSet<>(Arrays.asList(
            TableEngineProvider.TYPE,
            JdbcUrlPrefixProvider.TYPE,
            SystemCatalogProvider.TYPE,
            StorageEngineProvider.TYPE,
            TransactionEventProvider.TYPE));

    private PluginSecurity() {
    }

    /**
     * 校验插件 provider 类型是否允许。
     *
     * @param plugin 插件
     */
    public static void validateProviderTypes(H2Plugin plugin) {
        for (PluginProvider provider : plugin.getProviders()) {
            if (!ALLOWED_PROVIDER_TYPES.contains(provider.getType())) {
                throw DbException.getUnsupportedException("Configured plugin provider type is not allowed: plugin="
                        + plugin.getId() + ", type=" + provider.getType() + ", id=" + provider.getId());
            }
        }
    }

    /**
     * 脱敏配置文本。
     *
     * @param text 配置文本
     * @return 脱敏后的文本
     */
    public static String maskSensitiveConfig(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?i)(password|secret|token|key)=([^;,\\s]+)", "$1=***");
    }

    /**
     * 尝试释放插件 classloader。
     *
     * @param classLoader classloader
     * @return 成功关闭时返回 true
     */
    public static boolean closeClassLoader(ClassLoader classLoader) {
        if (classLoader instanceof Closeable) {
            try {
                ((Closeable) classLoader).close();
                return true;
            } catch (IOException e) {
                throw DbException.convert(e);
            }
        }
        return false;
    }

    /**
     * 为显式插件路径创建独立 classloader。
     *
     * @param pluginPaths 逗号分隔 jar 或目录路径
     * @return 独立 classloader；路径为空时返回 null
     */
    public static ClassLoader createPluginClassLoader(String pluginPaths) {
        if (pluginPaths == null || pluginPaths.trim().isEmpty()) {
            return null;
        }
        String[] paths = org.h2.util.StringUtils.arraySplit(pluginPaths, ',', true);
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) {
            try {
                urls[i] = new File(paths[i]).toURI().toURL();
            } catch (MalformedURLException e) {
                throw DbException.convert(e);
            }
        }
        return new URLClassLoader(urls, PluginSecurity.class.getClassLoader());
    }
}
