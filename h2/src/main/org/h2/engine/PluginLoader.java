/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

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
        if (pluginClasses == null || pluginClasses.trim().isEmpty()) {
            return;
        }
        String[] classNames = StringUtils.arraySplit(pluginClasses, ',', true);
        for (String className : classNames) {
            if (className.isEmpty()) {
                continue;
            }
            H2Plugin plugin = newPlugin(className);
            validatePlugin(registry, plugin, className);
            registry.registerPlugin(plugin, PluginSource.CONFIGURED_CLASS);
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
        for (H2Plugin plugin : plugins) {
            validatePlugin(registry, plugin, plugin.getClass().getName());
            registry.registerPlugin(plugin, source);
            count++;
        }
        return count;
    }

    private static void validatePlugin(PluginRegistry registry, H2Plugin plugin, String className) {
        String range = plugin.getH2VersionRange();
        if (range != null && !range.isEmpty() && !"*".equals(range) && !Constants.VERSION.equals(range)) {
            throw DbException.getUnsupportedException("Configured plugin class " + className
                    + " does not support this H2 version: required=" + range + ", actual=" + Constants.VERSION);
        }
        for (PluginDependency dependency : plugin.getDependencies()) {
            if (!registry.hasPlugin(dependency.getPluginId())) {
                throw DbException.getUnsupportedException("Configured plugin dependency is missing: plugin="
                        + plugin.getId() + ", dependency=" + dependency.getPluginId()
                        + ", version=" + dependency.getVersion());
            }
        }
        PluginSecurity.validateProviderTypes(plugin);
    }

    private static H2Plugin newPlugin(String className) {
        try {
            Object plugin = JdbcUtils.loadUserClass(className).getDeclaredConstructor().newInstance();
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
