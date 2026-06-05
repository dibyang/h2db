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

/**
 * Resolves custom JDBC URL prefixes before database open.
 */
public final class JdbcUrlPrefixResolver {

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
        PluginLoader.loadServiceLoaderPlugins(registry, true);
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
