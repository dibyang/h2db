/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.ServiceLoader;

import org.h2.api.H2Plugin;
import org.h2.api.PluginDependency;
import org.h2.message.DbException;
import org.h2.util.JdbcUtils;
import org.h2.util.StringUtils;

/**
 * 显式外部插件加载器。
 * <p>
 * 第一版只接受配置中明确列出的 {@link H2Plugin} 类名，不做自动发现。
 */
public final class PluginLoader {

    private PluginLoader() {
    }

    /**
     * 加载显式配置的插件类。
     *
     * @param registry 插件注册中心
     * @param pluginClasses 逗号分隔插件类名
     */
    public static void loadConfiguredPlugins(PluginRegistry registry, String pluginClasses) {
        loadConfiguredPlugins(registry, pluginClasses, null);
    }

    /**
     * 加载显式配置的插件类。
     *
     * @param registry 插件注册中心
     * @param pluginClasses 逗号分隔插件类名
     * @param pluginPaths 逗号分隔插件 jar 或目录路径
     */
    public static void loadConfiguredPlugins(PluginRegistry registry, String pluginClasses, String pluginPaths) {
        if (pluginClasses == null || pluginClasses.trim().isEmpty()) {
            return;
        }
        ClassLoader classLoader = PluginSecurity.createPluginClassLoader(pluginPaths);
        String[] classNames = StringUtils.arraySplit(pluginClasses, ',', true);
        try {
            ArrayList<H2Plugin> plugins = new ArrayList<>();
            for (String className : classNames) {
                if (className.isEmpty()) {
                    continue;
                }
                H2Plugin plugin = newPlugin(className, classLoader);
                validateVersionAndSecurity(registry, plugin, className);
                plugins.add(plugin);
            }
            registerPluginsInDependencyOrder(registry, plugins, PluginSource.CONFIGURED_CLASS);
        } finally {
            PluginSecurity.closeClassLoader(classLoader);
        }
    }

    /**
     * 按开关加载 ServiceLoader 发现的插件。
     *
     * @param registry 插件注册中心
     * @param enabled 是否启用
     * @return 加载插件数量
     */
    public static int loadServiceLoaderPlugins(PluginRegistry registry, boolean enabled) {
        return loadDiscoveredPlugins(registry, enabled, ServiceLoader.load(H2Plugin.class), PluginSource.SERVICE_LOADER);
    }

    /**
     * 加载已发现的插件。
     *
     * @param registry 插件注册中心
     * @param enabled 是否启用
     * @param plugins 已发现插件
     * @param source 插件来源
     * @return 加载插件数量
     */
    public static int loadDiscoveredPlugins(PluginRegistry registry, boolean enabled, Iterable<H2Plugin> plugins,
            PluginSource source) {
        if (!enabled) {
            return 0;
        }
        int count = 0;
        ArrayList<H2Plugin> discovered = new ArrayList<>();
        for (H2Plugin plugin : plugins) {
            validateVersionAndSecurity(registry, plugin, plugin.getClass().getName());
            discovered.add(plugin);
            count++;
        }
        registerPluginsInDependencyOrder(registry, discovered, source);
        return count;
    }

    private static void validateVersionAndSecurity(PluginRegistry registry, H2Plugin plugin, String className) {
        String range = plugin.getH2VersionRange();
        if (!isVersionInRange(Constants.VERSION, range)) {
            throw DbException.getUnsupportedException("Configured plugin class " + className
                    + " does not support this H2 version: required=" + range + ", actual=" + Constants.VERSION);
        }
        PluginSecurity.validateProviderTypes(plugin);
    }

    private static void registerPluginsInDependencyOrder(PluginRegistry registry, ArrayList<H2Plugin> plugins,
            PluginSource source) {
        HashSet<String> registered = new HashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (H2Plugin plugin : plugins) {
                if (registered.contains(plugin.getId())) {
                    continue;
                }
                if (dependenciesAvailable(registry, registered, plugin)) {
                    registry.registerPlugin(plugin, source);
                    registered.add(plugin.getId());
                    progressed = true;
                }
            }
        } while (progressed);
        for (H2Plugin plugin : plugins) {
            if (!registered.contains(plugin.getId())) {
                throw missingDependencyException(registry, registered, plugin);
            }
        }
    }

    private static boolean dependenciesAvailable(PluginRegistry registry, HashSet<String> registered,
            H2Plugin plugin) {
        for (PluginDependency dependency : plugin.getDependencies()) {
            if (!registry.hasPlugin(dependency.getPluginId()) && !registered.contains(dependency.getPluginId())) {
                return false;
            }
        }
        return true;
    }

    private static DbException missingDependencyException(PluginRegistry registry, HashSet<String> registered,
            H2Plugin plugin) {
        for (PluginDependency dependency : plugin.getDependencies()) {
            if (!registry.hasPlugin(dependency.getPluginId()) && !registered.contains(dependency.getPluginId())) {
                return DbException.getUnsupportedException("Configured plugin dependency is missing: plugin="
                        + plugin.getId() + ", dependency=" + dependency.getPluginId()
                        + ", version=" + dependency.getVersion());
            }
        }
        return DbException.getUnsupportedException("Configured plugin dependency cycle: plugin=" + plugin.getId());
    }

    static boolean isVersionInRange(String version, String range) {
        if (range == null || range.isEmpty() || "*".equals(range) || version.equals(range)) {
            return true;
        }
        if (range.length() < 3 || (range.charAt(0) != '[' && range.charAt(0) != '(')) {
            return false;
        }
        char end = range.charAt(range.length() - 1);
        if (end != ']' && end != ')') {
            return false;
        }
        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            return false;
        }
        String min = body.substring(0, comma).trim();
        String max = body.substring(comma + 1).trim();
        boolean minOk = min.isEmpty() || compareVersions(version, min) > 0
                || range.charAt(0) == '[' && compareVersions(version, min) == 0;
        boolean maxOk = max.isEmpty() || compareVersions(version, max) < 0
                || end == ']' && compareVersions(version, max) == 0;
        return minOk && maxOk;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            int l = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int r = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                break;
            }
            result = result * 10 + ch - '0';
        }
        return result;
    }

    private static H2Plugin newPlugin(String className, ClassLoader classLoader) {
        try {
            Class<?> pluginClass = classLoader == null ? JdbcUtils.loadUserClass(className)
                    : Class.forName(className, true, classLoader);
            Object plugin = pluginClass.getDeclaredConstructor().newInstance();
            if (!(plugin instanceof H2Plugin)) {
                throw DbException.getUnsupportedException("Configured plugin class does not implement H2Plugin: "
                        + className);
            }
            return (H2Plugin) plugin;
        } catch (DbException e) {
            throw DbException.getUnsupportedException("Could not load configured plugin class: " + className
                    + ", cause=" + e.getMessage());
        } catch (Exception e) {
            throw DbException.getUnsupportedException("Could not load configured plugin class: " + className
                    + ", cause=" + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
