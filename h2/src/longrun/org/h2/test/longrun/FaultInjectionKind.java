/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

/**
 * File-level corruption types supported by the longrun fault injector.
 */
public enum FaultInjectionKind {

    TRUNCATE,
    BIT_FLIP,
    ZERO_RANGE,
    RANDOM_RANGE,
    PARTIAL_PAGE;

    public static FaultInjectionKind parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("fault.kinds");
        }
        String text = value.trim().replace('-', '_').toUpperCase();
        return FaultInjectionKind.valueOf(text);
    }
}
