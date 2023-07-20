/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.jdbc.meta;

import org.h2.engine.Constants;
import org.h2.result.ResultInterface;
import org.h2.result.SimpleResult;
import org.h2.value.TypeInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Base implementation of database meta information.
 */
abstract class DatabaseMetaLocalBase extends DatabaseMeta {
    // SQL:92 reserved words from 'ANSI X3.135-1992, January 4, 1993'
    protected static final String[] SQL92_KEYWORDS = new String[] { "ABSOLUTE", "ACTION", "ADD", "ALL", "ALLOCATE", "ALTER", "AND", "ANY", "ARE", "AS", "ASC",
        "ASSERTION", "AT", "AUTHORIZATION", "AVG", "BEGIN", "BETWEEN", "BIT", "BIT_LENGTH", "BOTH", "BY", "CASCADE", "CASCADED", "CASE", "CAST", "CATALOG",
        "CHAR", "CHARACTER", "CHARACTER_LENGTH", "CHAR_LENGTH", "CHECK", "CLOSE", "COALESCE", "COLLATE", "COLLATION", "COLUMN", "COMMIT", "CONNECT",
        "CONNECTION", "CONSTRAINT", "CONSTRAINTS", "CONTINUE", "CONVERT", "CORRESPONDING", "COUNT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE",
        "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURSOR", "DATE", "DAY", "DEALLOCATE", "DEC", "DECIMAL", "DECLARE", "DEFAULT", "DEFERRABLE",
        "DEFERRED", "DELETE", "DESC", "DESCRIBE", "DESCRIPTOR", "DIAGNOSTICS", "DISCONNECT", "DISTINCT", "DOMAIN", "DOUBLE", "DROP", "ELSE", "END",
        "END-EXEC", "ESCAPE", "EXCEPT", "EXCEPTION", "EXEC", "EXECUTE", "EXISTS", "EXTERNAL", "EXTRACT", "FALSE", "FETCH", "FIRST", "FLOAT", "FOR",
        "FOREIGN", "FOUND", "FROM", "FULL", "GET", "GLOBAL", "GO", "GOTO", "GRANT", "GROUP", "HAVING", "HOUR", "IDENTITY", "IMMEDIATE", "IN", "INDICATOR",
        "INITIALLY", "INNER", "INPUT", "INSENSITIVE", "INSERT", "INT", "INTEGER", "INTERSECT", "INTERVAL", "INTO", "IS", "ISOLATION", "JOIN", "KEY",
        "LANGUAGE", "LAST", "LEADING", "LEFT", "LEVEL", "LIKE", "LOCAL", "LOWER", "MATCH", "MAX", "MIN", "MINUTE", "MODULE", "MONTH", "NAMES", "NATIONAL",
        "NATURAL", "NCHAR", "NEXT", "NO", "NOT", "NULL", "NULLIF", "NUMERIC", "OCTET_LENGTH", "OF", "ON", "ONLY", "OPEN", "OPTION", "OR", "ORDER", "OUTER",
        "OUTPUT", "OVERLAPS", "PAD", "PARTIAL", "POSITION", "PRECISION", "PREPARE", "PRESERVE", "PRIMARY", "PRIOR", "PRIVILEGES", "PROCEDURE", "PUBLIC",
        "READ", "REAL", "REFERENCES", "RELATIVE", "RESTRICT", "REVOKE", "RIGHT", "ROLLBACK", "ROWS", "SCHEMA", "SCROLL", "SECOND", "SECTION", "SELECT",
        "SESSION", "SESSION_USER", "SET", "SIZE", "SMALLINT", "SOME", "SPACE", "SQL", "SQLCODE", "SQLERROR", "SQLSTATE", "SUBSTRING", "SUM", "SYSTEM_USER",
        "TABLE", "TEMPORARY", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TO", "TRAILING", "TRANSACTION", "TRANSLATE", "TRANSLATION",
        "TRIM", "TRUE", "UNION", "UNIQUE", "UNKNOWN", "UPDATE", "UPPER", "USAGE", "USER", "USING", "VALUE", "VALUES", "VARCHAR", "VARYING", "VIEW", "WHEN",
        "WHENEVER", "WHERE", "WITH", "WORK", "WRITE", "YEAR", "ZONE" };

    //dib.yang#增加sql关键字
    public String getSQLKeywords4SQL92() {
        Set<String> keywords = new HashSet<>();
        for (String keyword : SQL92_KEYWORDS) {
            keywords.add(keyword);
        }
        keywords.add("CURRENT_CATALOG");
        keywords.add("CURRENT_SCHEMA");
        keywords.add("GROUPS");
        keywords.add("IF");
        keywords.add("ILIKE");
        keywords.add("INTERSECTS");
        keywords.add("KEY");
        keywords.add("LIMIT");
        keywords.add("MINUS");
        keywords.add("OFFSET");
        keywords.add("QUALIFY");
        keywords.add("REGEXP");
        keywords.add("ROWNUM");
        keywords.add("SYSDATE");
        keywords.add("SYSTIME");
        keywords.add("SYSTIMESTAMP");
        keywords.add("TODAY");
        keywords.add("TOP");
        keywords.add("USER");
        keywords.add("VALUE");
        keywords.add("_ROWID_");
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String keyword : keywords) {
            builder.append(keyword);
            if (!first) {
                builder.append(",");
            } else {
                first = false;
            }

        }
        return builder.toString();
    }

    @Override
    public final String getDatabaseProductVersion() {
        return Constants.FULL_VERSION;
    }

    @Override
    public final ResultInterface getVersionColumns(String catalog, String schema, String table) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("SCOPE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_SIZE", TypeInfo.TYPE_INTEGER);
        result.addColumn("BUFFER_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("DECIMAL_DIGITS", TypeInfo.TYPE_SMALLINT);
        result.addColumn("PSEUDO_COLUMN", TypeInfo.TYPE_SMALLINT);
        return result;
    }

    @Override
    public final ResultInterface getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TYPE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("CLASS_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("BASE_TYPE", TypeInfo.TYPE_SMALLINT);
        return result;
    }

    @Override
    public final ResultInterface getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TYPE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SUPERTYPE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SUPERTYPE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SUPERTYPE_NAME", TypeInfo.TYPE_VARCHAR);
        return result;
    }

    @Override
    public final ResultInterface getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SUPERTABLE_NAME", TypeInfo.TYPE_VARCHAR);
        return result;
    }

    @Override
    public final ResultInterface getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TYPE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("ATTR_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("ATTR_TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("ATTR_SIZE", TypeInfo.TYPE_INTEGER);
        result.addColumn("DECIMAL_DIGITS", TypeInfo.TYPE_INTEGER);
        result.addColumn("NUM_PREC_RADIX", TypeInfo.TYPE_INTEGER);
        result.addColumn("NULLABLE", TypeInfo.TYPE_INTEGER);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("ATTR_DEF", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SQL_DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("SQL_DATETIME_SUB", TypeInfo.TYPE_INTEGER);
        result.addColumn("CHAR_OCTET_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("ORDINAL_POSITION", TypeInfo.TYPE_INTEGER);
        result.addColumn("IS_NULLABLE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SCOPE_CATALOG", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SCOPE_SCHEMA", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SCOPE_TABLE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SOURCE_DATA_TYPE", TypeInfo.TYPE_SMALLINT);
        return result;
    }

    @Override
    public final int getDatabaseMajorVersion() {
        return Constants.VERSION_MAJOR;
    }

    @Override
    public final int getDatabaseMinorVersion() {
        return Constants.VERSION_MINOR;
    }

    @Override
    public final ResultInterface getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("FUNCTION_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FUNCTION_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FUNCTION_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FUNCTION_TYPE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("SPECIFIC_NAME", TypeInfo.TYPE_VARCHAR);
        return result;
    }

    @Override
    public final ResultInterface getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
            String columnNamePattern) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("FUNCTION_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FUNCTION_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FUNCTION_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_TYPE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("PRECISION", TypeInfo.TYPE_INTEGER);
        result.addColumn("LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("SCALE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("RADIX", TypeInfo.TYPE_SMALLINT);
        result.addColumn("NULLABLE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("CHAR_OCTET_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("ORDINAL_POSITION", TypeInfo.TYPE_INTEGER);
        result.addColumn("IS_NULLABLE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SPECIFIC_NAME", TypeInfo.TYPE_VARCHAR);
        return result;
    }

    final SimpleResult getPseudoColumnsResult() {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("COLUMN_SIZE", TypeInfo.TYPE_INTEGER);
        result.addColumn("DECIMAL_DIGITS", TypeInfo.TYPE_INTEGER);
        result.addColumn("NUM_PREC_RADIX", TypeInfo.TYPE_INTEGER);
        result.addColumn("COLUMN_USAGE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("CHAR_OCTET_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("IS_NULLABLE", TypeInfo.TYPE_VARCHAR);
        return result;
    }

    abstract void checkClosed();

}
