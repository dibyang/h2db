/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import org.h2.value.Value;

/**
 * Read-only view over bound parameters for one execution row.
 */
public interface BoundParameterView {

    /**
     * Get parameter count.
     *
     * @return parameter count
     */
    int size();

    /**
     * Get a bound value by zero-based index.
     *
     * @param index zero-based parameter index
     * @return bound value
     */
    Value getValue(int index);
}
