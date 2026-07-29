/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import org.h2.api.BoundParameterBatchView;
import org.h2.api.BoundParameterView;
import org.h2.api.DmlExecutionContext;
import org.h2.api.DmlExecutionPlan;
import org.h2.api.DmlExecutionProvider;
import org.h2.api.DmlPrepareContext;
import org.h2.api.ErrorCode;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.Trigger;
import org.h2.command.Command;
import org.h2.command.CommandInterface;
import org.h2.command.query.Query;
import org.h2.engine.DbObject;
import org.h2.engine.PluginRegistry.RegisteredProvider;
import org.h2.engine.Right;
import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.expression.ValueExpression;
import org.h2.expression.condition.Comparison;
import org.h2.expression.condition.ConditionAndOr;
import org.h2.index.Index;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVPrimaryIndex;
import org.h2.mvstore.db.MVStoreTableEngineProvider;
import org.h2.result.ResultInterface;
import org.h2.result.ResultTarget;
import org.h2.result.Row;
import org.h2.table.Column;
import org.h2.table.DataChangeDeltaTable;
import org.h2.table.DataChangeDeltaTable.ResultOption;
import org.h2.table.Table;
import org.h2.table.TableBase;
import org.h2.util.HasSQL;
import org.h2.value.Value;

/**
 * This class represents the statement
 * INSERT
 */
public final class Insert extends CommandWithValues implements ResultTarget {

    private Table table;
    private Column[] columns;
    private Query query;
    private long rowNumber;
    private boolean insertFromSelect;

    private Boolean overridingSystem;

    /**
     * For MySQL-style INSERT ... ON DUPLICATE KEY UPDATE ....
     */
    private HashMap<Column, Expression> duplicateKeyAssignmentMap;

    private Value[] onDuplicateKeyRow;

    /**
     * For MySQL-style INSERT IGNORE and PostgreSQL-style ON CONFLICT DO
     * NOTHING.
     */
    private boolean ignore;

    private ResultTarget deltaChangeCollector;

    private ResultOption deltaChangeCollectionMode;

    private DmlExecutionPlan dmlExecutionPlan = DmlExecutionPlan.NONE;
    private DmlExecutionProvider dmlExecutionProvider;
    private boolean generatedKeysRequested;

    public Insert(SessionLocal session) {
        super(session);
    }

    @Override
    public void setCommand(Command command) {
        super.setCommand(command);
        if (query != null) {
            query.setCommand(command);
        }
    }

    @Override
    public Table getTable() {
        return table;
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    /**
     * Sets MySQL-style INSERT IGNORE mode or PostgreSQL-style ON CONFLICT
     * DO NOTHING.
     *
     * @param ignore ignore duplicates
     */
    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public void setOverridingSystem(Boolean overridingSystem) {
        this.overridingSystem = overridingSystem;
    }

    /**
     * Keep a collection of the columns to pass to update if a duplicate key
     * happens, for MySQL-style INSERT ... ON DUPLICATE KEY UPDATE ....
     *
     * @param column the column
     * @param expression the expression
     */
    public void addAssignmentForDuplicate(Column column, Expression expression) {
        if (duplicateKeyAssignmentMap == null) {
            duplicateKeyAssignmentMap = new HashMap<>();
        }
        if (duplicateKeyAssignmentMap.putIfAbsent(column, expression) != null) {
            throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1, column.getName());
        }
    }

    @Override
    public long update(ResultTarget deltaChangeCollector, ResultOption deltaChangeCollectionMode) {
        this.deltaChangeCollector = deltaChangeCollector;
        this.deltaChangeCollectionMode = deltaChangeCollectionMode;
        try {
            return insertRows();
        } finally {
            this.deltaChangeCollector = null;
            this.deltaChangeCollectionMode = null;
        }
    }

    private long insertRows() {
        Long fastPathRowCount = executeSingleRowDmlFastPathIfEligible();
        if (fastPathRowCount != null) {
            return fastPathRowCount.longValue();
        }
        session.getUser().checkTableRight(table, Right.INSERT);
        setCurrentRowNumber(0);
        table.fire(session, Trigger.INSERT, true);
        rowNumber = 0;
        int listSize = valuesExpressionList.size();
        if (listSize > 0) {
            int columnLen = columns.length;
            for (int x = 0; x < listSize; x++) {
                Row newRow = table.getTemplateRow();
                Expression[] expr = valuesExpressionList.get(x);
                setCurrentRowNumber(x + 1);
                for (int i = 0; i < columnLen; i++) {
                    Column c = columns[i];
                    int index = c.getColumnId();
                    Expression e = expr[i];
                    if (e != ValueExpression.DEFAULT) {
                        try {
                            newRow.setValue(index, e.getValue(session));
                        } catch (DbException ex) {
                            throw setRow(ex, x, getSimpleSQL(expr));
                        }
                    }
                }
                rowNumber++;
                table.convertInsertRow(session, newRow, overridingSystem);
                if (deltaChangeCollectionMode == ResultOption.NEW) {
                    deltaChangeCollector.addRow(newRow.getValueList().clone());
                }
                if (!table.fireBeforeRow(session, null, newRow)) {
                    table.lock(session, Table.WRITE_LOCK);
                    try {
                        table.addRow(session, newRow);
                    } catch (DbException de) {
                        if (handleOnDuplicate(de, null)) {
                            // MySQL returns 2 for updated row
                            // TODO: detect no-op change
                            rowNumber++;
                        } else {
                            // INSERT IGNORE case
                            rowNumber--;
                        }
                        continue;
                    }
                    DataChangeDeltaTable.collectInsertedFinalRow(session, table, deltaChangeCollector,
                            deltaChangeCollectionMode, newRow);
                    table.fireAfterRow(session, null, newRow, false);
                } else {
                    DataChangeDeltaTable.collectInsertedFinalRow(session, table, deltaChangeCollector,
                            deltaChangeCollectionMode, newRow);
                }
            }
        } else {
            table.lock(session, Table.WRITE_LOCK);
            if (insertFromSelect) {
                query.query(0, this);
            } else {
                ResultInterface rows = query.query(0);
                while (rows.next()) {
                    Value[] r = rows.currentRow();
                    try {
                        addRow(r);
                    } catch (DbException de) {
                        if (handleOnDuplicate(de, r)) {
                            // MySQL returns 2 for updated row
                            // TODO: detect no-op change
                            rowNumber++;
                        } else {
                            // INSERT IGNORE case
                            rowNumber--;
                        }
                    }
                }
                rows.close();
            }
        }
        table.fire(session, Trigger.INSERT, false);
        return rowNumber;
    }

    @Override
    public void addRow(Value... values) {
        Row newRow = table.getTemplateRow();
        setCurrentRowNumber(++rowNumber);
        for (int j = 0, len = columns.length; j < len; j++) {
            newRow.setValue(columns[j].getColumnId(), values[j]);
        }
        table.convertInsertRow(session, newRow, overridingSystem);
        if (deltaChangeCollectionMode == ResultOption.NEW) {
            deltaChangeCollector.addRow(newRow.getValueList().clone());
        }
        if (!table.fireBeforeRow(session, null, newRow)) {
            table.addRow(session, newRow);
            DataChangeDeltaTable.collectInsertedFinalRow(session, table, deltaChangeCollector,
                    deltaChangeCollectionMode, newRow);
            table.fireAfterRow(session, null, newRow, false);
        } else {
            DataChangeDeltaTable.collectInsertedFinalRow(session, table, deltaChangeCollector,
                    deltaChangeCollectionMode, newRow);
        }
    }

    @Override
    public long getRowCount() {
        // This method is not used in this class
        return rowNumber;
    }

    @Override
    public void limitsWereApplied() {
        // Nothing to do
    }

    @Override
    public String getPlanSQL(int sqlFlags) {
        StringBuilder builder = new StringBuilder("INSERT INTO ");
        table.getSQL(builder, sqlFlags).append('(');
        Column.writeColumns(builder, columns, sqlFlags);
        builder.append(")\n");
        if (insertFromSelect) {
            builder.append("DIRECT ");
        }
        if (!valuesExpressionList.isEmpty()) {
            builder.append("VALUES ");
            int row = 0;
            if (valuesExpressionList.size() > 1) {
                builder.append('\n');
            }
            for (Expression[] expr : valuesExpressionList) {
                if (row++ > 0) {
                    builder.append(",\n");
                }
                Expression.writeExpressions(builder.append('('), expr, sqlFlags).append(')');
            }
        } else {
            builder.append(query.getPlanSQL(sqlFlags));
        }
        return builder.toString();
    }

    @Override
    void doPrepare() {
        if (columns == null) {
            if (!valuesExpressionList.isEmpty() && valuesExpressionList.get(0).length == 0) {
                // special case where table is used as a sequence
                columns = new Column[0];
            } else {
                columns = table.getColumns();
            }
        }
        if (!valuesExpressionList.isEmpty()) {
            for (Expression[] expr : valuesExpressionList) {
                if (expr.length != columns.length) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
                for (int i = 0, len = expr.length; i < len; i++) {
                    Expression e = expr[i];
                    if (e != null) {
                        e = e.optimize(session);
                        if (e instanceof Parameter) {
                            Parameter p = (Parameter) e;
                            p.setColumn(columns[i]);
                        }
                        expr[i] = e;
                    }
                }
            }
        } else {
            query.prepare();
            if (query.getColumnCount() != columns.length) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
        prepareDmlFastPathPlan();
    }

    /**
     * P2 only discovers an eligible plugin DML plan. Execution intentionally
     * stays on the native INSERT path until parameter and transaction views are
     * added in later phases.
     */
    private void prepareDmlFastPathPlan() {
        dmlExecutionPlan = DmlExecutionPlan.NONE;
        dmlExecutionProvider = null;
        if (valuesExpressionList.isEmpty()) {
            return;
        }
        DmlPrepareContext context = new InsertDmlPrepareContext();
        ArrayList<RegisteredProvider> candidates =
                new ArrayList<>(session.getDatabase().getPluginRegistry()
                        .getProviders(DmlExecutionProvider.TYPE).values());
        candidates.sort(Comparator.comparing(
                candidate -> candidate.getProvider().getId()));
        String matchedProviderId = null;
        DmlExecutionProvider matchedProvider = null;
        DmlExecutionPlan matchedPlan = DmlExecutionPlan.NONE;
        for (RegisteredProvider registered : candidates) {
            PluginProvider provider = registered.getProvider();
            if (provider instanceof DmlExecutionProvider
                    && provider.supports(
                            PluginCapability.DML_INSERT_VALUES_FAST_PATH)) {
                DmlExecutionPlan plan =
                        ((DmlExecutionProvider) provider).prepareDml(context);
                if (plan != null && plan.isSupported()) {
                    if (matchedProvider != null) {
                        throw new IllegalStateException(
                                "Ambiguous DML fast path providers for table "
                                        + table.getName() + ": "
                                        + matchedProviderId + ", "
                                        + provider.getId());
                    }
                    matchedProviderId = provider.getId();
                    matchedProvider = (DmlExecutionProvider) provider;
                    matchedPlan = plan;
                }
            }
        }
        if (matchedProvider != null) {
            dmlExecutionProvider = matchedProvider;
            dmlExecutionPlan = matchedPlan;
            session.getTrace().info(
                    "DML fast path candidate matched: provider={0}, table={1}",
                    matchedProviderId, table.getName());
        }
    }

    /**
     * Exposed for tests and later execution phases.
     *
     * @return true if a DML fast-path provider accepted this INSERT plan
     */
    public boolean hasDmlExecutionPlan() {
        return dmlExecutionPlan.isSupported();
    }

    /**
     * Mark whether the current execution was entered from JDBC generated-keys
     * handling. V1 fast path falls back whenever generated keys were requested,
     * even when H2 later discovers that there are no key columns to collect.
     *
     * @param generatedKeysRequested true when generated keys were requested
     */
    public void setGeneratedKeysRequested(boolean generatedKeysRequested) {
        this.generatedKeysRequested = generatedKeysRequested;
    }

    /**
     * Execute the first guarded DML fast-path shape.
     * <p>
     * P3 deliberately accepts only the shape that can be represented by a
     * simple bound-parameter view without changing existing INSERT semantics:
     * one VALUES row, all table columns in physical order, no generated keys,
     * no row triggers or constraints, no IGNORE / ON DUPLICATE behavior, and no
     * DEFAULT expressions. Broader SQL semantics are added by later phases and
     * stay on the native path for now.
     *
     * @return row count, or {@code null} when native INSERT execution should be
     *         used
     */
    private Long executeSingleRowDmlFastPathIfEligible() {
        if (!isSingleRowDmlFastPathEligible()) {
            return null;
        }
        session.getUser().checkTableRight(table, Right.INSERT);
        setCurrentRowNumber(0);
        table.fire(session, Trigger.INSERT, true);
        boolean completed = false;
        try {
            table.lock(session, Table.WRITE_LOCK);
            rowNumber = dmlExecutionPlan.execute(new SingleRowDmlExecutionContext());
            completed = true;
            return Long.valueOf(rowNumber);
        } finally {
            if (completed) {
                table.fire(session, Trigger.INSERT, false);
            }
        }
    }

    private boolean isSingleRowDmlFastPathEligible() {
        return isCommonDmlFastPathEligible() && areCurrentParametersCompatible();
    }

    private boolean isCommonDmlFastPathEligible() {
        if (dmlExecutionProvider == null || !dmlExecutionPlan.isSupported()
                || !dmlExecutionProvider.supports(PluginCapability.PARAMETERS_BOUND_VIEW)) {
            return false;
        }
        if (valuesExpressionList.size() != 1 || query != null || insertFromSelect || ignore || generatedKeysRequested
                || duplicateKeyAssignmentMap != null || deltaChangeCollector != null || overridingSystem != null
                || table.fireRow()) {
            return false;
        }
        Column[] tableColumns = table.getColumns();
        if (columns.length != tableColumns.length || parameters == null || parameters.isEmpty()) {
            return false;
        }
        for (int i = 0; i < columns.length; i++) {
            if (columns[i] != tableColumns[i]) {
                return false;
            }
        }
        Expression[] row = valuesExpressionList.get(0);
        if (row.length != columns.length || row.length != parameters.size()) {
            return false;
        }
        for (int i = 0; i < row.length; i++) {
            if (!(row[i] instanceof Parameter) || row[i] != parameters.get(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean areCurrentParametersCompatible() {
        Expression[] row = valuesExpressionList.get(0);
        for (int i = 0; i < row.length; i++) {
            Parameter parameter = parameters.get(i);
            if (row[i] != parameter || !parameter.isValueSet()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getType() {
        return CommandInterface.INSERT;
    }

    @Override
    public String getStatementName() {
        return "INSERT";
    }

    public void setInsertFromSelect(boolean value) {
        this.insertFromSelect = value;
    }

    @Override
    public boolean isCacheable() {
        return duplicateKeyAssignmentMap == null;
    }

    /**
     * @param de duplicate key exception
     * @param currentRow current row values (optional)
     * @return {@code true} if row was updated, {@code false} if row was ignored
     */
    private boolean handleOnDuplicate(DbException de, Value[] currentRow) {
        if (de.getErrorCode() != ErrorCode.DUPLICATE_KEY_1) {
            throw de;
        }
        if (duplicateKeyAssignmentMap == null) {
            if (ignore) {
                return false;
            }
            throw de;
        }

        int columnCount = columns.length;
        Expression[] row = (currentRow == null) ? valuesExpressionList.get((int) getCurrentRowNumber() - 1)
                : new Expression[columnCount];
        onDuplicateKeyRow = new Value[table.getColumns().length];
        for (int i = 0; i < columnCount; i++) {
            Value value;
            if (currentRow != null) {
                value = currentRow[i];
                row[i] = ValueExpression.get(value);
            } else {
                value = row[i].getValue(session);
            }
            onDuplicateKeyRow[columns[i].getColumnId()] = value;
        }

        StringBuilder builder = new StringBuilder("UPDATE ");
        table.getSQL(builder, HasSQL.DEFAULT_SQL_FLAGS).append(" SET ");
        boolean f = false;
        for (Entry<Column, Expression> entry : duplicateKeyAssignmentMap.entrySet()) {
            if (f) {
                builder.append(", ");
            }
            f = true;
            entry.getKey().getSQL(builder, HasSQL.DEFAULT_SQL_FLAGS).append('=');
            entry.getValue().getUnenclosedSQL(builder, HasSQL.DEFAULT_SQL_FLAGS);
        }
        builder.append(" WHERE ");
        Index foundIndex = (Index) de.getSource();
        if (foundIndex == null) {
            throw DbException.getUnsupportedException(
                    "Unable to apply ON DUPLICATE KEY UPDATE, no index found!");
        }
        prepareUpdateCondition(foundIndex, row).getUnenclosedSQL(builder, HasSQL.DEFAULT_SQL_FLAGS);
        String sql = builder.toString();
        Update command = (Update) session.prepare(sql);
        command.setOnDuplicateKeyInsert(this);
        for (Parameter param : command.getParameters()) {
            Parameter insertParam = parameters.get(param.getIndex());
            param.setValue(insertParam.getValue(session));
        }
        boolean result = command.update() > 0;
        onDuplicateKeyRow = null;
        return result;
    }

    private Expression prepareUpdateCondition(Index foundIndex, Expression[] row) {
        // MVPrimaryIndex is playing fast and loose with it's implementation of
        // the Index interface.
        // It returns all of the columns in the table when we call
        // getIndexColumns() or getColumns().
        // Don't have time right now to fix that, so just special-case it.
        // PageDataIndex has the same problem.
        final Column[] indexedColumns;
        if (foundIndex instanceof MVPrimaryIndex) {
            MVPrimaryIndex foundMV = (MVPrimaryIndex) foundIndex;
            indexedColumns = new Column[] { foundMV.getIndexColumns()[foundMV
                    .getMainIndexColumn()].column };
        } else {
            indexedColumns = foundIndex.getColumns();
        }

        Expression condition = null;
        for (Column column : indexedColumns) {
            ExpressionColumn expr = new ExpressionColumn(getDatabase(),
                    table.getSchema().getName(), table.getName(), column.getName());
            for (int i = 0; i < columns.length; i++) {
                if (expr.getColumnName(session, i).equals(columns[i].getName())) {
                    if (condition == null) {
                        condition = new Comparison(Comparison.EQUAL, expr, row[i], false);
                    } else {
                        condition = new ConditionAndOr(ConditionAndOr.AND, condition,
                                new Comparison(Comparison.EQUAL, expr, row[i], false));
                    }
                    break;
                }
            }
        }
        return condition;
    }

    /**
     * Get the value to use for the specified column in case of a duplicate key.
     *
     * @param columnIndex the column index
     * @return the value
     */
    public Value getOnDuplicateKeyValue(int columnIndex) {
        return onDuplicateKeyRow[columnIndex];
    }

    @Override
    public void collectDependencies(HashSet<DbObject> dependencies) {
        ExpressionVisitor visitor = ExpressionVisitor.getDependenciesVisitor(dependencies);
        if (!valuesExpressionList.isEmpty()) {
            for (Expression[] expr : valuesExpressionList) {
                for (Expression e : expr) {
                    e.isEverything(visitor);
                }
            }
        } else {
            query.isEverything(visitor);
        }
    }

    private final class InsertDmlPrepareContext implements DmlPrepareContext {

        @Override
        public String getStatementType() {
            return DmlPrepareContext.INSERT_VALUES;
        }

        @Override
        public String getTableName() {
            return table.getName();
        }

        @Override
        public String getTableEngineProviderId() {
            if (!(table instanceof TableBase)) {
                return null;
            }
            String tableEngine = ((TableBase) table).getTableEngine();
            return tableEngine != null ? tableEngine
                    : MVStoreTableEngineProvider.ID;
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public boolean isBatchCapable() {
            return parameters != null && !parameters.isEmpty();
        }

        @Override
        public boolean requestsGeneratedKeys() {
            // Generated-key requests are only known at execute time in the
            // current command flow; later phases must re-check and fallback.
            return false;
        }
    }

    private final class SingleRowDmlExecutionContext implements DmlExecutionContext {

        private final BoundParameterView parameters = new SingleRowBoundParameterView();

        @Override
        public Object getSession() {
            return session;
        }

        @Override
        public Object getTable() {
            return table;
        }

        @Override
        public BoundParameterView getParameters() {
            return parameters;
        }

        @Override
        public BoundParameterBatchView getBatchParameters() {
            return EmptyBoundParameterBatchView.INSTANCE;
        }

        @Override
        public boolean isBatch() {
            return false;
        }

        @Override
        public boolean isAutoCommit() {
            return session.getAutoCommit();
        }
    }

    private final class SingleRowBoundParameterView implements BoundParameterView {

        @Override
        public int size() {
            return parameters.size();
        }

        @Override
        public Value getValue(int index) {
            return parameters.get(index).getValue(session);
        }
    }

    private static final class EmptyBoundParameterBatchView implements BoundParameterBatchView {

        private static final EmptyBoundParameterBatchView INSTANCE = new EmptyBoundParameterBatchView();

        @Override
        public int size() {
            return 0;
        }

        @Override
        public BoundParameterView get(int rowIndex) {
            throw new IndexOutOfBoundsException("No batch parameter row: " + rowIndex);
        }
    }

}
