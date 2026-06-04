/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

/**
 * Workload mode for the standalone long-running stress test application.
 */
public enum LongRunMode {
    MVSTORE,
    SQL,
    MIXED;

    static LongRunMode parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return MVSTORE;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase();
        for (LongRunMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown longrun mode: " + value);
    }
}
