/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Provider for JDBC URL prefixes handled by the H2 driver.
 * <p>
 * Implementations are loaded before a database is opened, so they must not
 * depend on a database-local plugin registry. They should only perform a
 * deterministic URL mapping to a regular {@code jdbc:h2:} URL.
 */
public interface JdbcUrlPrefixProvider extends PluginProvider {

    /**
     * Provider type.
     */
    String TYPE = "jdbc_url_prefix";

    /**
     * Returns the JDBC URL prefix handled by this provider.
     *
     * @return JDBC URL prefix, for example {@code jdbc:adb:}
     */
    String getUrlPrefix();

    /**
     * Checks whether this provider accepts the specified URL.
     *
     * @param url JDBC URL
     * @return whether this provider can map the URL
     */
    default boolean acceptsURL(String url) {
        return url != null && url.startsWith(getUrlPrefix());
    }

    /**
     * Maps a custom JDBC URL to a regular H2 URL.
     *
     * @param url custom JDBC URL
     * @return regular {@code jdbc:h2:} URL
     */
    String toH2Url(String url);
}
