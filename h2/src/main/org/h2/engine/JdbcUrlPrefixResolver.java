/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.util.Map;

import org.h2.api.JdbcUrlPrefixProvider;
import org.h2.api.PluginProvider;
import org.h2.message.DbException;
import org.h2.util.Utils;

/**
 * Resolves custom JDBC URL prefixes before database open.
 */
public final class JdbcUrlPrefixResolver {

    private static final String DRIVER_PLUGIN_CLASSES = "h2.driverPluginClasses";
    private static final String DRIVER_PLUGIN_PATHS = "h2.driverPluginPaths";
    private static final String DRIVER_PLUGIN_SERVICE_LOADER = "h2.driverPluginServiceLoader";
    static final String PLUGIN_CLASSES = "h2.pluginClasses";
    static final String PLUGIN_PATHS = "h2.pluginPaths";
    static final String PLUGIN_SERVICE_LOADER = "h2.pluginServiceLoader";

    private JdbcUrlPrefixResolver() {
    }

    /**
     * Checks whether a custom URL can be mapped by a driver-level plugin.
     *
     * @param url JDBC URL
     * @return whether the URL is supported
     */
    public static boolean acceptsURL(String url) {
        return mapToH2Url(url) != null;
    }

    /**
     * Maps a custom JDBC URL to a regular H2 URL.
     *
     * @param url JDBC URL
     * @return mapped H2 URL, or {@code null} when no provider accepts it
     */
    public static String mapToH2Url(String url) {
        if (url == null || url.startsWith(Constants.START_URL) || !url.startsWith("jdbc:")) {
            return null;
        }
        PluginRegistry registry = new PluginRegistry();
        PluginLoader.loadConfiguredPlugins(registry, Utils.getProperty(PLUGIN_CLASSES, null),
                Utils.getProperty(PLUGIN_PATHS, null));
        PluginLoader.loadConfiguredPlugins(registry, Utils.getProperty(DRIVER_PLUGIN_CLASSES, null),
                Utils.getProperty(DRIVER_PLUGIN_PATHS, null));
        boolean serviceLoader = Utils.getProperty(PLUGIN_SERVICE_LOADER, false)
                || Utils.getProperty(DRIVER_PLUGIN_SERVICE_LOADER, false);
        PluginLoader.loadServiceLoaderPlugins(registry, serviceLoader);
        for (Map.Entry<String, PluginRegistry.RegisteredProvider> entry
                : registry.getProviders(JdbcUrlPrefixProvider.TYPE).entrySet()) {
            PluginProvider provider = entry.getValue().getProvider();
            if (provider instanceof JdbcUrlPrefixProvider) {
                JdbcUrlPrefixProvider urlProvider = (JdbcUrlPrefixProvider) provider;
                if (urlProvider.acceptsURL(url)) {
                    String h2Url = urlProvider.toH2Url(url);
                    if (h2Url == null || !h2Url.startsWith(Constants.START_URL)) {
                        throw DbException.getUnsupportedException("JDBC URL prefix provider returned invalid H2 URL: "
                                + "provider=" + urlProvider.getId() + ", url=" + h2Url);
                    }
                    return h2Url;
                }
            }
        }
        return null;
    }
}
