/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.util.Properties;
import org.h2.mvstore.MVStoreOnlineReclamationResult;

/**
 * Common lifecycle for long-running workload implementations.
 */
public interface LongRunWorkload extends AutoCloseable {

    String getJdbcUrl();

    void step() throws Exception;

    void commit() throws Exception;

    void verify() throws Exception;

    void reopenAndVerify() throws Exception;

    MVStoreOnlineReclamationResult runReclamation();

    default FaultInjectionResult runFaultInjection(long eventId) throws Exception {
        return null;
    }

    default void collectReportProperties(Properties properties) {
        // Optional workload-specific report properties.
    }

    @Override
    void close() throws Exception;
}
