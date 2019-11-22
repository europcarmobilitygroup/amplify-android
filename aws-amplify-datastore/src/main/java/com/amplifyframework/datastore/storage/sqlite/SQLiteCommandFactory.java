/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.datastore.storage.sqlite;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.text.TextUtils;
import androidx.annotation.NonNull;

import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.ModelField;
import com.amplifyframework.core.model.ModelIndex;
import com.amplifyframework.core.model.ModelSchema;
import com.amplifyframework.core.model.ModelSchemaRegistry;
import com.amplifyframework.core.model.types.JavaFieldType;
import com.amplifyframework.core.model.types.SqliteDataType;
import com.amplifyframework.core.model.types.internal.TypeConverter;

import java.util.Iterator;
import java.util.List;

/**
 * A factory that produces the SQLite commands for a given
 * {@link Model} and {@link ModelSchema}.
 */
final class SQLiteCommandFactory implements SQLCommandFactory {

    // the singleton instance.
    private static SQLiteCommandFactory singletonInstance;

    // Delimiter used in the SQLite commands.
    private static final String SQLITE_COMMAND_DELIMITER = " ";

    private SQLiteCommandFactory() {
    }

    /**
     * Retrieves the singleton instance of the SQLiteCommandFactory.
     * @return the singleton instance of the SQLiteCommandFactory.
     */
    public static synchronized SQLiteCommandFactory getInstance() {
        if (singletonInstance == null) {
            singletonInstance = new SQLiteCommandFactory();
        }
        return singletonInstance;
    }

    /**
     * Generates the CREATE TABLE SQL command from the {@link ModelSchema}.
     *
     * @param modelSchema the schema of a {@link Model}
     *                    for which a CREATE TABLE SQL command needs to be generated.
     * @return the CREATE TABLE SQL command
     */
    @Override
    public SqlCommand createTableFor(@NonNull ModelSchema modelSchema) {
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE TABLE IF NOT EXISTS ")
            .append(modelSchema.getName())
            .append(SQLITE_COMMAND_DELIMITER);
        if (modelSchema.getFields() == null || modelSchema.getFields().isEmpty()) {
            return new SqlCommand(modelSchema.getName(), stringBuilder.toString());
        }

        stringBuilder.append("(");
        appendColumns(stringBuilder, modelSchema);
        if (!modelSchema.getForeignKeys().isEmpty()) {
            stringBuilder.append("," + SQLITE_COMMAND_DELIMITER);
            appendForeignKeys(stringBuilder, modelSchema);
        }
        stringBuilder.append(");");

        final String createSqlStatement = stringBuilder.toString();
        return new SqlCommand(modelSchema.getName(), createSqlStatement);
    }

    /**
     * Generates the CREATE INDEX SQL command from the {@link ModelSchema}.
     *
     * @param modelSchema the schema of a {@link Model}
     *                    for which a CREATE INDEX SQL command needs to be generated.
     * @return the CREATE INDEX SQL command
     */
    @Override
    public SqlCommand createIndexFor(@NonNull ModelSchema modelSchema) {
        final ModelIndex modelIndex = modelSchema.getModelIndex();
        if (modelIndex == null ||
            TextUtils.isEmpty(modelIndex.getIndexName()) ||
            modelIndex.getIndexFieldNames() == null ||
            modelIndex.getIndexFieldNames().isEmpty()) {
            return null;
        }

        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE INDEX IF NOT EXISTS ")
            .append(modelIndex.getIndexName()).append(" ON ")
            .append(modelSchema.getName())
            .append(SQLITE_COMMAND_DELIMITER);

        stringBuilder.append("(");
        Iterator<String> iterator = modelIndex.getIndexFieldNames().iterator();
        while (iterator.hasNext()) {
            final String indexColumnName = iterator.next();
            stringBuilder.append(indexColumnName);
            if (iterator.hasNext()) {
                stringBuilder.append("," + SQLITE_COMMAND_DELIMITER);
            }
        }
        stringBuilder.append(");");
        return new SqlCommand(modelSchema.getName(), stringBuilder.toString());
    }

    /**
     * Generates the INSERT INTO command in a raw string representation and a compiled
     * prepared statement that can be bound later with inputs.
     *
     * @param tableName   name of the table
     * @param modelSchema schema of the model
     * @return the SQL command that encapsulates the INSERT INTO command
     */
    @Override
    public SqlCommand insertFor(@NonNull String tableName,
                                @NonNull ModelSchema modelSchema,
                                @NonNull SQLiteDatabase writableDatabaseConnectionHandle) {
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("INSERT INTO ");
        stringBuilder.append(tableName);
        stringBuilder.append(" (");
        final List<ModelField> fields = modelSchema.getSortedFields();
        final Iterator<ModelField> fieldsIterator = fields.iterator();
        while (fieldsIterator.hasNext()) {
            final String fieldName = fieldsIterator.next().getName();
            stringBuilder.append(fieldName);
            if (fieldsIterator.hasNext()) {
                stringBuilder.append(", ");
            } else {
                stringBuilder.append(")");
            }
        }
        stringBuilder.append(" VALUES ");
        stringBuilder.append("(");
        for (int i = 0; i < fields.size(); i++) {
            if (i == fields.size() - 1) {
                stringBuilder.append("?");
            } else {
                stringBuilder.append("?, ");
            }
        }
        stringBuilder.append(")");
        final String preparedInsertStatement = stringBuilder.toString();
        final SQLiteStatement compiledInsertStatement =
                writableDatabaseConnectionHandle.compileStatement(preparedInsertStatement);
        return new SqlCommand(tableName, preparedInsertStatement, compiledInsertStatement);
    }

    // Utility method to append columns in CREATE TABLE
    private void appendColumns(StringBuilder stringBuilder, ModelSchema modelSchema) {
        final Iterator<ModelField> fieldsIterator = modelSchema.getSortedFields().iterator();
        while (fieldsIterator.hasNext()) {
            final ModelField field = fieldsIterator.next();
            final String fieldName = field.getName();

            final SqliteDataType sqliteDataType;
            if (field.isModel()) {
                sqliteDataType = TypeConverter.getSqlTypeForJavaType(JavaFieldType.MODEL.stringValue());
            } else if (field.isEnum()) {
                sqliteDataType = TypeConverter.getSqlTypeForJavaType(JavaFieldType.ENUM.stringValue());
            } else {
                sqliteDataType = TypeConverter.getSqlTypeForGraphQLType(field.getTargetType());
            }

            stringBuilder.append(fieldName)
                    .append(SQLITE_COMMAND_DELIMITER)
                    .append(sqliteDataType.getSqliteDataType());

            if (field.isPrimaryKey()) {
                stringBuilder.append(SQLITE_COMMAND_DELIMITER + "PRIMARY KEY");
            }

            if (field.isRequired()) {
                stringBuilder.append(SQLITE_COMMAND_DELIMITER + "NOT NULL");
            }

            if (fieldsIterator.hasNext()) {
                stringBuilder.append("," + SQLITE_COMMAND_DELIMITER);
            }
        }
    }

    // Utility method to append foreign key references in CREATE TABLE
    private void appendForeignKeys(StringBuilder stringBuilder, ModelSchema modelSchema) {
        final Iterator<ModelField> foreignKeyIterator = modelSchema.getForeignKeys().iterator();
        while (foreignKeyIterator.hasNext()) {
            final ModelField foreignKey = foreignKeyIterator.next();

            String connectionName = foreignKey.getName();
            String connectionTarget = foreignKey.belongsTo();
            String connectionId = ModelSchemaRegistry.singleton()
                    .getModelSchemaForModelClass(connectionTarget)
                    .getPrimaryKey()
                    .getName();

            stringBuilder
                .append("FOREIGN KEY" + SQLITE_COMMAND_DELIMITER)
                .append("(")
                .append(connectionName)
                .append(")")
                .append(SQLITE_COMMAND_DELIMITER + "REFERENCES" + SQLITE_COMMAND_DELIMITER)
                .append(connectionTarget).append("(").append(connectionId).append(")");

            if (foreignKeyIterator.hasNext()) {
                stringBuilder.append("," + SQLITE_COMMAND_DELIMITER);
            }
        }
    }
}
