/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

/**
 * Minimal plugin version range matcher.
 */
final class PluginVersionRange {

    private PluginVersionRange() {
    }

    static boolean matches(String version, String range) {
        if (range == null || range.isEmpty() || "*".equals(range) || version.equals(range)) {
            return true;
        }
        if (range.length() < 3 || (range.charAt(0) != '[' && range.charAt(0) != '(')) {
            return false;
        }
        char end = range.charAt(range.length() - 1);
        if (end != ']' && end != ')') {
            return false;
        }
        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            return false;
        }
        String min = body.substring(0, comma).trim();
        String max = body.substring(comma + 1).trim();
        boolean minOk = min.isEmpty() || compare(version, min) > 0
                || range.charAt(0) == '[' && compare(version, min) == 0;
        boolean maxOk = max.isEmpty() || compare(version, max) < 0
                || end == ']' && compare(version, max) == 0;
        return minOk && maxOk;
    }

    static int compare(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            int l = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int r = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                break;
            }
            result = result * 10 + ch - '0';
        }
        return result;
    }
}
