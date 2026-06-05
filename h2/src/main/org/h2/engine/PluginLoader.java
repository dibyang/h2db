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
import org.h2.api.PluginProvider;
import org.h2.message.DbException;
import org.h2.util.JdbcUtils;
import org.h2.util.StringUtils;

/**
 * 显式外部插件加载器。
 * <p>
 * 支持配置中明确列出的 {@link H2Plugin} 类名，也支持按开关启用
 * {@link ServiceLoader} 自动发现。
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
        validatePluginDescriptor(plugin, className);
        String range = plugin.getH2VersionRange();
        if (!isVersionInRange(Constants.VERSION, range)) {
            throw DbException.getUnsupportedException("Configured plugin class " + className
                    + " does not support this H2 version: required=" + range + ", actual=" + Constants.VERSION);
        }
        PluginSecurity.validateProviderTypes(plugin);
    }

    private static void registerPluginsInDependencyOrder(PluginRegistry registry, ArrayList<H2Plugin> plugins,
            PluginSource source) {
        validateUniquePluginVersions(plugins);
        HashSet<String> registered = new HashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (H2Plugin plugin : plugins) {
                String key = pluginKey(plugin);
                if (registered.contains(key)) {
                    continue;
                }
                if (dependenciesAvailable(registry, registered, plugin)) {
                    registry.registerPlugin(plugin, source);
                    registered.add(key);
                    progressed = true;
                }
            }
        } while (progressed);
        for (H2Plugin plugin : plugins) {
            if (!registered.contains(pluginKey(plugin))) {
                throw unresolvedDependencyException(registry, registered, plugins, plugin);
            }
        }
    }

    private static boolean dependenciesAvailable(PluginRegistry registry, HashSet<String> registered,
            H2Plugin plugin) {
        for (PluginDependency dependency : plugin.getDependencies()) {
            if (!registry.hasPlugin(dependency.getPluginId(), dependency.getVersion())
                    && !registeredContains(registered, dependency.getPluginId(), dependency.getVersion())) {
                return false;
            }
        }
        return true;
    }

    private static DbException unresolvedDependencyException(PluginRegistry registry, HashSet<String> registered,
            ArrayList<H2Plugin> plugins, H2Plugin plugin) {
        for (PluginDependency dependency : plugin.getDependencies()) {
            if (!registry.hasPlugin(dependency.getPluginId(), dependency.getVersion())
                    && !registeredContains(registered, dependency.getPluginId(), dependency.getVersion())
                    && !discoveredContains(plugins, dependency.getPluginId(), dependency.getVersion())) {
                return DbException.getUnsupportedException("Configured plugin dependency is missing: plugin="
                        + plugin.getId() + ", dependency=" + dependency.getPluginId()
                        + ", version=" + dependency.getVersion());
            }
        }
        return DbException.getUnsupportedException("Configured plugin dependency cycle: plugin=" + plugin.getId());
    }

    private static void validateUniquePluginVersions(ArrayList<H2Plugin> plugins) {
        HashSet<String> keys = new HashSet<>();
        for (H2Plugin plugin : plugins) {
            String key = pluginKey(plugin);
            if (!keys.add(key)) {
                throw DbException.getUnsupportedException("Duplicate configured plugin version: plugin="
                        + plugin.getId() + ", version=" + plugin.getVersion());
            }
        }
    }

    private static boolean registeredContains(HashSet<String> registered, String pluginId, String versionRange) {
        for (String key : registered) {
            int separator = key.indexOf('\u0000');
            if (separator > 0 && key.substring(0, separator).equals(pluginId)
                    && PluginVersionRange.matches(key.substring(separator + 1), versionRange)) {
                return true;
            }
        }
        return false;
    }

    private static boolean discoveredContains(ArrayList<H2Plugin> plugins, String pluginId, String versionRange) {
        for (H2Plugin plugin : plugins) {
            if (plugin.getId().equals(pluginId) && PluginVersionRange.matches(plugin.getVersion(), versionRange)) {
                return true;
            }
        }
        return false;
    }

    private static String pluginKey(H2Plugin plugin) {
        return plugin.getId() + '\u0000' + plugin.getVersion();
    }

    private static void validatePluginDescriptor(H2Plugin plugin, String className) {
        if (plugin == null) {
            throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class=" + className
                    + ", reason=plugin is null");
        }
        String pluginId = requireNonBlank(plugin.getId(), "plugin id", className);
        requireNonBlank(plugin.getVersion(), "plugin version", className);
        Iterable<? extends PluginProvider> providers = plugin.getProviders();
        if (providers == null) {
            throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class=" + className
                    + ", plugin=" + pluginId + ", reason=providers are null");
        }
        boolean hasProvider = false;
        for (PluginProvider provider : providers) {
            if (provider == null) {
                throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class="
                        + className + ", plugin=" + pluginId + ", reason=provider is null");
            }
            requireNonBlank(provider.getType(), "provider type", className);
            requireNonBlank(provider.getId(), "provider id", className);
            hasProvider = true;
        }
        if (!hasProvider) {
            throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class=" + className
                    + ", plugin=" + pluginId + ", reason=no providers");
        }
        Iterable<PluginDependency> dependencies = plugin.getDependencies();
        if (dependencies == null) {
            throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class=" + className
                    + ", plugin=" + pluginId + ", reason=dependencies are null");
        }
        for (PluginDependency dependency : dependencies) {
            if (dependency == null) {
                throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class=" + className
                        + ", plugin=" + pluginId + ", reason=dependency is null");
            }
            requireNonBlank(dependency.getPluginId(), "dependency plugin id", className);
            requireNonBlank(dependency.getVersion(), "dependency version", className);
        }
    }

    private static String requireNonBlank(String value, String name, String className) {
        if (value == null || value.trim().isEmpty()) {
            throw DbException.getUnsupportedException("Configured plugin descriptor is invalid: class=" + className
                    + ", reason=" + name + " is empty");
        }
        return value;
    }

    static boolean isVersionInRange(String version, String range) {
        return PluginVersionRange.matches(version, range);
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
