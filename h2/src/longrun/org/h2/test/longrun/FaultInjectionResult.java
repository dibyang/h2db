/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;

/**
 * Result of one longrun file corruption injection attempt.
 */
public final class FaultInjectionResult {

    private final long eventId;
    private final FaultInjectionKind kind;
    private final String status;
    private final String message;
    private final File file;
    private final long offset;
    private final long length;
    private final long beforeSize;
    private final long afterSize;

    public FaultInjectionResult(long eventId, FaultInjectionKind kind, String status, String message, File file,
            long offset, long length, long beforeSize, long afterSize) {
        this.eventId = eventId;
        this.kind = kind;
        this.status = status;
        this.message = message == null ? "" : message.replace(',', ';');
        this.file = file;
        this.offset = offset;
        this.length = length;
        this.beforeSize = beforeSize;
        this.afterSize = afterSize;
    }

    public long getEventId() {
        return eventId;
    }

    public FaultInjectionKind getKind() {
        return kind;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public File getFile() {
        return file;
    }

    public long getOffset() {
        return offset;
    }

    public long getLength() {
        return length;
    }

    public long getBeforeSize() {
        return beforeSize;
    }

    public long getAfterSize() {
        return afterSize;
    }

    public boolean isUnexpected() {
        return status != null && status.startsWith("UNEXPECTED");
    }
}
