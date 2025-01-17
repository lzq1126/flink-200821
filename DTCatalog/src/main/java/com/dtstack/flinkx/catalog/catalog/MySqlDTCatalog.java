/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.catalog.jdbc.catalog;

import com.dtstack.flinkx.catalog.jdbc.dialect.DTDialectTypeMapper;
import com.dtstack.flinkx.catalog.jdbc.dialect.MySqlTypeMapper;
import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.ArrayHandler;
import org.apache.commons.dbutils.handlers.ColumnListHandler;
import org.apache.commons.dbutils.handlers.MapListHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.annotation.Internal;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.catalog.*;
import org.apache.flink.table.catalog.exceptions.*;
import org.apache.flink.table.descriptors.DescriptorProperties;
import org.apache.flink.table.descriptors.Schema;
import org.apache.flink.table.types.DataType;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.ThrowingConsumer;
import org.apache.flink.util.function.ThrowingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.dtstack.flinkx.catalog.jdbc.table.JdbcConnectorOptions.*;
import static org.apache.flink.table.factories.FactoryUtil.CONNECTOR;

/** Catalog for MySQL. */
@Internal
public class MySqlDTCatalog extends AbstractDTCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlDTCatalog.class);

    private final DTDialectTypeMapper dialectTypeMapper;

    private static final Set<String> builtinDatabases =
            new HashSet<String>() {
                {
                    add("information_schema");
                    add("mysql");
                    add("performance_schema");
                    add("sys");
                }
            };


    public MySqlDTCatalog(
            String catalogName,
            String defaultDatabase,
            String username,
            String pwd,
            String baseUrl,
            String projectId,
            String tenantId) {
        super(catalogName, defaultDatabase, username, pwd, baseUrl, projectId, tenantId);

        String driverVersion =
                Preconditions.checkNotNull(getDriverVersion(), "Driver version must not be null.");
        String databaseVersion =
                Preconditions.checkNotNull(
                        getDatabaseVersion(), "Database version must not be null.");
        LOG.info("Driver version: {}, database version: {}", driverVersion, databaseVersion);
        this.dialectTypeMapper = new MySqlTypeMapper(databaseVersion, driverVersion);
    }

    @Override
    public void open() throws CatalogException {
        // Step 1: 加载数据库驱动
        DbUtils.loadDriver("com.mysql.cj.jdbc.Driver");
        // Step 2: 获取数据库连接对象
        try {
            connection = DriverManager.getConnection(defaultUrl, username, pwd);
            queryRunner = new QueryRunner();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        // Step 3: 创建DbUtils核心工具类对象
    }

    @Override
    public void close() throws CatalogException {
        super.close();
        DbUtils.closeQuietly(connection);
    }

    @Override
    public List<String> listDatabases() throws CatalogException {
        String defaultDatabase = getDefaultDatabase();
        String catalogName = getName();
        String projectId = getProjectId();
        String tenantId = getTenantId();
        String sql =
                String.format(
                        "select database_name from database_info where catalog_name = '%s' and project_id = '%s' and tenant_id = '%s';", catalogName, projectId, tenantId);
        List<Object> resultList;
        try {
            resultList = (List<Object>) queryRunner.query(connection, sql, new ColumnListHandler());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        List<String> reuslt = resultList.stream().map(String::valueOf).collect(Collectors.toList());
        return reuslt;
    }

    @Override
    public void createDatabase(String name, CatalogDatabase database, boolean ignoreIfExists)
            throws DatabaseAlreadyExistException, CatalogException {
        // 先查询是否存在数据，如果已经存在了直接抛异常，表示无法建表。
        if (databaseExists(name)) {
            if (ignoreIfExists) {
                return;
            }
            throw new DatabaseAlreadyExistException(getName(), name);
        }
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String catalogName = getName();
        String projectId = getProjectId();
        String tenantId = getTenantId();

        String sql =
                String.format(
                        "INSERT INTO database_info (catalog_name, database_name, catalog_type, project_id, tenant_id) VALUES ('%s', '%s', '%s', '%s', '%s')", catalogName, name, "mysql", projectId, tenantId);
        try {
            // 如果要返回第一个主键，需要传入 connection.
            queryRunner.insert(connection, sql, new ScalarHandler<>());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void dropDatabase(String name, boolean ignoreIfNotExists, boolean cascade)
            throws DatabaseNotExistException, DatabaseNotEmptyException, CatalogException {
        if (!databaseExists(name)) {
            if (ignoreIfNotExists) {
                return;
            }
            throw new DatabaseNotExistException(getName(), name);
        }
        if (cascade) {
            // 三张表做事物型删除
            ThrowingRunnable batch =
                    () -> {
                        List<String> tableList = null;
                        tableList = listTables(name);
                        // TODO fori tableList
                        tableList.forEach(
                                tableName -> {
                                    dropTable(new ObjectPath(name, tableName));
                                });
                        deleteDatabase(name);
                    };
            executeBatchInTransaction(batch);
        } else {
            // TODO
            throw new DatabaseNotEmptyException(getName(), name);
        }
    }
    //编写一个泛型方法对异常进行包装
    static <E extends Exception> void doThrow(Exception e) throws E {
        throw (E)e;
    }
    private void deleteDatabase(String name) {
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String catalogName = getName();
        String projectId = getProjectId();
        String tenantId = getTenantId();
        String sql =
                String.format(
                        "delete from database_info  where catalog_name = '%s' and database_name = '%s' and project_id = '%s' and tenant_id = '%s'", catalogName, name, projectId, tenantId);
        try {
            // 如果要返回第一个主键，需要传入 connection.
            queryRunner.execute(connection, sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CatalogDatabase getDatabase(String databaseName)
            throws DatabaseNotExistException, CatalogException {

        Preconditions.checkState(
                !org.apache.flink.util.StringUtils.isNullOrWhitespaceOnly(databaseName),
                "Database name must not be blank.");
        if (listDatabases().contains(databaseName)) {
            return new CatalogDatabaseImpl(Collections.emptyMap(), null);
        } else {
            throw new DatabaseNotExistException(getName(), databaseName);
        }
    }

    // ------ tables ------

    @Override
    public void createTable(ObjectPath tablePath, CatalogBaseTable table, boolean ignoreIfExists)
            throws TableAlreadyExistException, DatabaseNotExistException, CatalogException {
        String databaseName = tablePath.getDatabaseName();
        // 数据库不存在，报错。
        if (!databaseExists(databaseName)) {
            if (ignoreIfExists) {
                return;
            }
            throw new DatabaseNotExistException(getName(), databaseName);
        }
        // 表如果已经存在，报错。
        if (tableExists(tablePath)) {
            if (ignoreIfExists) {
                return;
            }
            throw new TableAlreadyExistException(getName(), tablePath);
        }
        Map<String, String> properties = new HashMap<>();
        // 元数据存储
        Map<String, String> options = table.getOptions();

        DescriptorProperties tableSchemaProps = new DescriptorProperties(true);
        tableSchemaProps.putTableSchema(Schema.SCHEMA, table.getSchema());
        Map<String, String> stringStringMap = tableSchemaProps.asMap();

        properties.putAll(options);
        properties.putAll(stringStringMap);
        // 两张表做事物型插入
        ThrowingRunnable batch =
                () -> {
                    String tableId;
                    try {
                        tableId = insertTableInfo(tablePath);
                        insertProperties(properties, tableId);
                    } catch (TableAlreadyExistException e) {
                        throw new RuntimeException(e);
                    }
                };
        executeBatchInTransaction(batch);
    }

    @Override
    public void dropTable(ObjectPath tablePath, boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        // 表如果不存在，报错。
        if (!tableExists(tablePath)) {
            if (ignoreIfNotExists) {
                return;
            }
            throw new TableNotExistException(getName(), tablePath);
        }
        // 两张表做事物型删除
        ThrowingRunnable batch = () -> dropTable(tablePath);
        executeBatchInTransaction(batch);
    }

    private void dropTable(ObjectPath tablePath) {
        deleteProperties(tablePath);
        deleteTableInfo(tablePath);
    }

    @Override
    public void renameTable(ObjectPath tablePath, String newTableName, boolean ignoreIfNotExists)
            throws TableNotExistException, TableAlreadyExistException, CatalogException {
        // 表如果不存在，报错。
        if (!tableExists(tablePath)) {
            if (ignoreIfNotExists) {
                return;
            }
            throw new TableNotExistException(getName(), tablePath);
        }

        throw new UnsupportedOperationException();
        // TODO update 语句
    }

    @Override
    public void alterTable(
            ObjectPath tablePath, CatalogBaseTable newTable, boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        // 表如果不存在，报错。
        if (!tableExists(tablePath)) {
            if (ignoreIfNotExists) {
                return;
            }
            throw new TableNotExistException(getName(), tablePath);
        }
        // TODO update 语句
        throw new UnsupportedOperationException();
    }

    private void executeBatchInTransaction(ThrowingRunnable batch) {
        try {
            connection.setAutoCommit(false);
            batch.run();
            connection.commit();
        } catch (Throwable e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
            throw new RuntimeException(e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void insertProperties(Map<String, String> properties, String tableId) {
        String defaultDatabase = getDefaultDatabase();
        String sql =
                String.format(
                        "INSERT INTO `properties_info` (table_id, `key`, `value`) VALUES ('%s' , ?, ?);", tableId);
        Object[][] params = getMapKeyValue(properties);
        try {
            queryRunner.batch(connection, sql, params);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteProperties(ObjectPath tablePath) {
        String tableId = getTableId(tablePath);
        String defaultDatabase = getDefaultDatabase();
        String sql =
                String.format(
                        "delete from `properties_info` where table_id = '%s'", tableId);
        try {
            queryRunner.execute(connection, sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object[][] getMapKeyValue(Map map) {
        Object[][] object = null;
        if ((map != null) && (!map.isEmpty())) {
            int size = map.size();
            object = new Object[size][2];
            Iterator iterator = map.entrySet().iterator();
            for (int i = 0; i < size; i++) {
                Map.Entry entry = (Map.Entry) iterator.next();
                Object key = entry.getKey();
                Object value = entry.getValue();
                object[i][0] = key;
                object[i][1] = value;
            }
        }
        return object;
    }

    private String insertTableInfo(ObjectPath tablePath) throws TableAlreadyExistException {
        // 先查询是否存在数据，如果已经存在了直接抛异常，表示无法建表。
        if (tableExists(tablePath)) {
            throw new TableAlreadyExistException(getName(), tablePath);
        }
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String databaseName = tablePath.getDatabaseName();
        String tableName = tablePath.getObjectName();
        String databaseId = getdatabaseId(databaseName);
        String projectId = getProjectId();
        String tenantId = getTenantId();
        String sql =
                String.format(
                        "INSERT INTO `table_info` (catalog_id, database_name, table_name, project_id, tenant_id)  VALUES ('%s', '%s', '%s', '%s', '%s')", databaseId, databaseName, tableName, projectId, tenantId);
        Object tableId;
        try {
            // 如果要返回第一个主键，需要传入 connection.
            tableId = queryRunner.insert(connection, sql, new ScalarHandler<>());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return String.valueOf(tableId);
    }

    private void deleteTableInfo(ObjectPath tablePath) {
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String databaseName = tablePath.getDatabaseName();
        String tableName = tablePath.getObjectName();
        String databaseId = getdatabaseId(databaseName);
        String projectId = getProjectId();
        String tenantId = getTenantId();
        String sql =
                String.format(
                        "delete from table_info where database_id = '%s' and table_name = '%s' and project_id = '%s' and tenant_id = '%s'", databaseId, databaseName, tableName, projectId, tenantId);
        try {
            queryRunner.execute(connection, sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String getdatabaseId(String databaseName) {
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String catalogName = getName();
        String sql =
                String.format(
                        "select id from database_info where catalog_name = '%s' and database_name = '%s'", catalogName, databaseName);
        Object[] databaseInfo;
        try {
            databaseInfo = queryRunner.query(connection, sql, new ArrayHandler());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (databaseInfo == null) {
            throw new CatalogException(
                    String.format(
                            "Catalog : %s, database : %s is not exist.",
                            catalogName, databaseName));
        }
        return String.valueOf(databaseInfo[0]);
    }

    private String getTableId(ObjectPath tablePath) {
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String catalogName = getName();
        String databaseName = tablePath.getDatabaseName();
        String databaseId = getdatabaseId(databaseName);
        String tableName = tablePath.getObjectName();

        String sql =
                String.format(
                        "select * from table_info where database_id = '%s' and table_name = '%s'", databaseId, databaseName, tableName);
        Object[] databaseInfo;
        try {
            databaseInfo = queryRunner.query(connection, sql, new ArrayHandler());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (databaseInfo == null || databaseInfo.length == 0) {
            throw new CatalogException(
                    String.format(
                            "Catalog : %s, database : %s is not exist.",
                            catalogName, databaseName));
        }
        return String.valueOf(databaseInfo[0]);
    }

    public Map<String, String> getTableProperties(ObjectPath tablePath) {
        // 元数据存储
        String defaultDatabase = getDefaultDatabase();
        String catalogName = getName();
        String databaseName = tablePath.getDatabaseName();
        String tableId = getTableId(tablePath);
        String sql =
                String.format(
                        "select * from properties_info where table_id = '%s'", tableId);
        HashMap<String, String> result = new HashMap<>();
        List<Map<String, Object>> propertiesInfo;
        try {
            propertiesInfo = queryRunner.query(connection, sql, new MapListHandler());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (propertiesInfo == null || propertiesInfo.size() == 0) {
            throw new CatalogException(
                    String.format(
                            "Catalog : %s, database : %s is not exist.",
                            catalogName, databaseName));
        }
        propertiesInfo.stream()
                .map(
                        row -> {
                            Object key = row.get("key");
                            Object value = row.get("value");
                            result.put(String.valueOf(key), String.valueOf(value));
                            return value;
                        })
                .collect(Collectors.toList());
        return result;
    }

    @Override
    public List<String> listTables(String databaseName)
            throws DatabaseNotExistException, CatalogException {
        Preconditions.checkState(
                StringUtils.isNotBlank(databaseName), "Database name must not be blank.");
        if (!databaseExists(databaseName)) {
            throw new DatabaseNotExistException(getName(), databaseName);
        }
        String defaultDatabase = getDefaultDatabase();
        String databaseId = getdatabaseId(databaseName);
        String projectId = getProjectId();
        String tenantId = getTenantId();
        String sql =
                String.format(
                        "select table_name from table_info where database_id = '%s' and project_id = '%s' and tenant_id = '%s';", databaseId, databaseName, projectId, tenantId);
        List<Object> resultList;
        try {
            resultList = (List<Object>) queryRunner.query(connection, sql, new ColumnListHandler());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        List<String> reuslt = resultList.stream().map(String::valueOf).collect(Collectors.toList());
        return reuslt;
    }

    @Override
    public CatalogBaseTable getTable(ObjectPath tablePath)
            throws TableNotExistException, CatalogException {
        if (!tableExists(tablePath)) {
            throw new TableNotExistException(getName(), tablePath);
        }
        Map<String, String> properties = getTableProperties(tablePath);
        TableSchema tableSchema;
        DescriptorProperties tableSchemaProps = new DescriptorProperties(true);
        tableSchemaProps.putProperties(properties);
        tableSchema =
                tableSchemaProps
                        .getOptionalTableSchema(Schema.SCHEMA)
                        .orElseGet(
                                () ->
                                        tableSchemaProps
                                                .getOptionalTableSchema("generic.table.schema")
                                                .orElseThrow(
                                                        () ->
                                                                new CatalogException(
                                                                        "Failed to get table schema from properties for generic table "
                                                                                + tablePath)));
        List<String> partitionKeys = tableSchemaProps.getPartitionKeys();
        // remove the schema from properties
        properties = CatalogTableImpl.removeRedundant(properties, tableSchema, partitionKeys);
        return new CatalogTableImpl(tableSchema, properties, "");
    }

    @Override
    public boolean tableExists(ObjectPath tablePath) throws CatalogException {
        String defaultDatabase = getDefaultDatabase();

        String databaseName = tablePath.getDatabaseName();
        String tableName = tablePath.getObjectName();
        String databaseId = getdatabaseId(databaseName);
        String sql =
                String.format(
                        "select * from table_info where database_id = '%s' and table_name = '%s'", databaseId, databaseName, tableName);
        Object[] databaseInfo;
        try {
            databaseInfo = queryRunner.query(connection, sql, new ArrayHandler());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        if (databaseInfo == null || databaseInfo.length == 0) {
            return false;
        }
        return true;
    }

    private String getDatabaseVersion() {
        try (Connection conn = DriverManager.getConnection(defaultUrl, username, pwd)) {
            return conn.getMetaData().getDatabaseProductVersion();
        } catch (Exception e) {
            throw new CatalogException(
                    String.format("Failed in getting MySQL version by %s.", defaultUrl), e);
        }
    }

    private String getDriverVersion() {
        try (Connection conn = DriverManager.getConnection(defaultUrl, username, pwd)) {
            String driverVersion = conn.getMetaData().getDriverVersion();
            Pattern regexp = Pattern.compile("\\d+?\\.\\d+?\\.\\d+");
            Matcher matcher = regexp.matcher(driverVersion);
            return matcher.find() ? matcher.group(0) : null;
        } catch (Exception e) {
            throw new CatalogException(
                    String.format("Failed in getting MySQL driver version by %s.", defaultUrl), e);
        }
    }

    /** Converts MySQL type to Flink {@link DataType}. */
    @Override
    protected DataType fromJDBCType(ObjectPath tablePath, ResultSetMetaData metadata, int colIndex)
            throws SQLException {
        return dialectTypeMapper.mapping(tablePath, metadata, colIndex);
    }

    @Override
    protected String getTableName(ObjectPath tablePath) {
        return tablePath.getObjectName();
    }

    @Override
    protected String getSchemaName(ObjectPath tablePath) {
        return tablePath.getDatabaseName();
    }

    @Override
    protected String getSchemaTableName(ObjectPath tablePath) {
        return tablePath.getObjectName();
    }
}
