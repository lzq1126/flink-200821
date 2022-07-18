package com.lzq.catalog;

import cn.hutool.core.util.ReflectUtil;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.TableFactoryService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * @author wujuan
 * @version 1.0
 * @date 2022/4/7 21:44 星期四
 * @email wujuan@dtstack.com
 * @company www.dtstack.com
 */
public class HiveCatalogDemo {

    EnvironmentSettings settings;
    StreamExecutionEnvironment env;
    StreamTableEnvironment tableEnv;
    StatementSet statementSet;

    @Rule
    public final EnvironmentVariables environmentVariables
            = new EnvironmentVariables();


    @Before
    public void initStreamEnv() {
        settings = EnvironmentSettings
                .newInstance()
                .inStreamingMode()
                .useBlinkPlanner()
                .build();
        //构建环境信息
        env = StreamExecutionEnvironment.getExecutionEnvironment();
        tableEnv = StreamTableEnvironment.create(env, settings);
        statementSet = tableEnv.createStatementSet();
        env.setParallelism(1);
        tableEnv.getConfig().getConfiguration().setString(PipelineOptions.NAME, "wujuan_job");
        System.out.println("初始化 Flink 环境成功!");

        environmentVariables.set("HADOOP_HOME", "");
        //environmentVariables.set("HADOOP_CONF_DIR", "");
        //environmentVariables.set("YARN_CONF_DIR", "");

        environmentVariables.set("HADOOP_CONF_DIR", "/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/target/classes/hiveconf/conf");
        environmentVariables.set("YARN_CONF_DIR", "/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/target/classes/hiveconf/conf");
        environmentVariables.set("HADOOP_USER_NAME", "root");

    }


    @Test
    public void StreamSQLDemo0(){

        String catalogName = "flink_hive_catalog";

        final Map<String, String> options = new HashMap<>();
        options.put("type", "hive");
        options.put("hive-conf-dir","/Users/lzq/Desktop/Projects/Flink/flink-1.12-catalog-demo/src/main/resources/hiveconf/conf");

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            ReflectUtil.invoke(classLoader,"addURL",new URL("file:///Users/lzq/Desktop/Projects/Flink/flink-1.12-catalog-demo/jar/flink-connector-hive_2.12-1.12.7.jar"));
            ReflectUtil.invoke(classLoader,"addURL",new URL("file:///Users/lzq/Desktop/Projects/Flink/flink-1.12-catalog-demo/jar/hive-exec-2.3.4.jar"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        CatalogFactory legacyFactory =
                TableFactoryService.find(CatalogFactory.class, options, classLoader);

        Catalog catalog = legacyFactory.createCatalog(catalogName, options);


        //Catalog actualCatalog =
        //        FactoryUtil.createCatalog(
        //                catalogName, options, null, Thread.currentThread().getContextClassLoader());


        tableEnv.registerCatalog(catalogName, catalog);

        //String useCatalog = "use CATALOG flink_hive_catalog";
        //tableEnv.executeSql(useCatalog);
        // -----------------------------------------------------------------

        String mysqlSource =
                ""
                        + "CREATE TABLE if not exists flink_hive_catalog.wujuan.t3 (\n"
                        + " id int,\n"
                        + " name string,\n"
                        + " age bigint,\n"
                        + " primary key (id) not enforced\n"
                        + ") with (\n"
                        + " 'connector' = 'jdbc',\n"
                        + " 'url' = 'jdbc:mysql://172.16.83.218:3306/wujuan?useSSL=false',\n"
                        + " 'table-name' = 't2',\n"
                        + " 'username' = 'drpeco',\n"
                        + " 'password' = 'DT@Stack#123'\n"
                        + ")";

        tableEnv.executeSql(mysqlSource);


        //String slelectMysql = "select * from flink_hive_catalog.wujuan.t3";
        //// 查询结构 mysql 数据源 t2 表
        //TableResult tableResult = tableEnv.executeSql(slelectMysql);
        //
        //tableResult.print();
    }

    @Test
    public void StreamSQLDemo1(){
   /*     String hadoop_home = System.getenv("HADOOP_HOME");
        tableEnv.getConfig().getConfiguration().setString(PipelineOptions.NAME, "wujuan_job");
        // -----------------------------------------------------------------
        //String hiveCatalog =
        //        "CREATE CATALOG flink_hive_catalog WITH (\n"
        //                + "    'type' = 'hive',\n"
        //                + "    'default-database' = 'wujuan',\n"
        //                + "    'hive-conf-dir' = '/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/src/main/resources/hiveconf/conf'\n"
        //                + ")";
        //tableEnv.executeSql(hiveCatalog);
        //
        //String useCatalog = "use CATALOG flink_hive_catalog";
        //tableEnv.executeSql(useCatalog);
        // -----------------------------------------------------------------
        String name = "flink_hive_catalog";
        String database = "wujuan";
        String confDir =
                "/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/src/main/resources/hiveconf/conf";
        // HiveConf hiveConf = new HiveConf(conf, HiveConf.class);

        Catalog catalog = new HiveCatalog(name, database, confDir);
        tableEnv.registerCatalog(name, catalog);

        //String useCatalog = "use CATALOG flink_hive_catalog";
        //tableEnv.executeSql(useCatalog);
        // -----------------------------------------------------------------

        String mysqlSource =
                ""
                        + "CREATE TABLE if not exists flink_hive_catalog.wujuan.t3 (\n"
                        + " id int,\n"
                        + " name string,\n"
                        + " age bigint,\n"
                        + " primary key (id) not enforced\n"
                        + ") with (\n"
                        + " 'connector' = 'jdbc',\n"
                        + " 'url' = 'jdbc:mysql://172.16.83.218:3306/wujuan?useSSL=false',\n"
                        + " 'table-name' = 't2',\n"
                        + " 'username' = 'drpeco',\n"
                        + " 'password' = 'DT@Stack#123'\n"
                        + ")";

        tableEnv.executeSql(mysqlSource);

        String slelectMysql = "select * from flink_hive_catalog.wujuan.t3";
        // 查询结构 mysql 数据源 t2 表
        TableResult tableResult = tableEnv.executeSql(slelectMysql);

        tableResult.print();*/
    }


    @Test
    public void StreamSQLDemo2(){


        String hiveCatalog = "CREATE CATALOG flink_hive_catalog WITH (\n" +
                "    'type' = 'hive',\n" +
                "    'default-database' = 'wujuan',\n" +
                "    'hive-conf-dir' = '/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/src/main/resources/hiveconf/conf'\n" +
                ")";
        tableEnv.executeSql(hiveCatalog);

        String useCatalog = "use CATALOG flink_hive_catalog";
        tableEnv.executeSql(useCatalog);

        String mysqlSource = "" +
                "CREATE TABLE if not exists kafka_source_city (\n" +
                "    id int,\n" +
                "    city string\n" +
                ") WITH (\n" +
                "      'connector' = 'kafka',\n" +
                "      'topic' = 'wujuan_city',\n" +
                "      'properties.bootstrap.servers' = '172.16.100.109:9092',\n" +
                "      'properties.group.id' = 'wujuan_consumer3',\n" +
                "      'format' = 'json',\n" +
                "      'scan.startup.mode' = 'earliest-offset'\n" +
                //"      'scan.startup.mode' = 'specific-offsets',\n" +
                //"      'scan.startup.specific-offsets' = 'partition:0,offset:0'\n" +
                "    )";

        tableEnv.executeSql(mysqlSource);

        String slelectMysql = "select * from kafka_source_city";
        // 查询结构 mysql 数据源 t2 表
        TableResult tableResult = tableEnv.executeSql(slelectMysql);

        tableResult.print();
    }

    //  kafka join mysql(维表)
    @Test
    public void StreamSQLDemo3(){

        // 0. 初始化 catalog
        String hiveCatalog = "CREATE CATALOG flink_hive_catalog WITH (\n" +
                "    'type' = 'hive',\n" +
                "    'default-database' = 'wujuan',\n" +
                "    'hive-conf-dir' = '/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/src/main/resources/hiveconf/conf'\n" +
                ")";
        tableEnv.executeSql(hiveCatalog);

        String useCatalog = "use CATALOG flink_hive_catalog";
        tableEnv.executeSql(useCatalog);

        // 1. 源表
        String kafkaSource = "" +
                "CREATE TABLE if not exists kafka_source_city (\n" +
                "    id int,\n" +
                "    city string\n" +
                ") WITH (\n" +
                "      'connector' = 'kafka',\n" +
                "      'topic' = 'wujuan_city',\n" +
                "      'properties.bootstrap.servers' = '172.16.100.109:9092',\n" +
                "      'properties.group.id' = 'wujuan_consumer',\n" +
                "      'format' = 'json',\n" +
                "      'scan.startup.mode' = 'earliest-offset'\n" +
                "    )";
        // earliest-offset、group-offsets 、latest-offset 、timestamp 、specific-offsets
        tableEnv.executeSql(kafkaSource);

        // 2. 维表
        String mysqlSource = "" +
                "CREATE TABLE if not exists t2 (\n" +
                " id int,\n" +
                " name string,\n" +
                " age bigint,\n" +
                " primary key (id) not enforced\n" +
                ") with (\n" +
                " 'connector' = 'jdbc',\n" +
                " 'url' = 'jdbc:mysql://172.16.83.218:3306/wujuan?useSSL=false',\n" +
                " 'table-name' = 't2',\n" +
                " 'username' = 'drpeco',\n" +
                " 'password' = 'DT@Stack#123'\n" +
                ")";

        tableEnv.executeSql(mysqlSource);

        // 3. 结果表
        String resultWujuan = "" +
                "create table if not exists wujuan_result (\n" +
                "    id int,\n" +
                "    city string,\n" +
                "    name string,\n" +
                "    age bigint\n" +
                ") WITH (\n" +
                "   'connector' = 'print' \n" +
                ")";
        tableEnv.executeSql(resultWujuan);


        // 4. 计算
        String result = "insert into wujuan_result select t1.id,t1.city, t2.name, t2.age from kafka_source_city t1 join t2 on t1.id = t2.id";
        // 查询结构 mysql 数据源 t2 表
        TableResult tableResult = tableEnv.executeSql(result);

        tableResult.print();
    }

    // 直接使用 hive catalog 去计算
    @Test
    public void StreamSQLDemo4(){
/*        String name            = "flink_hive_catalog";
        String defaultDatabase = "wujuan";
        String hiveConfDir     = "/Users/lzq/Desktop/Projects/Flink/flink-1.12-java-demo/src/main/resources/hiveconf/conf";

        HiveCatalog flinkHiveCatalog = new HiveCatalog(name, defaultDatabase, hiveConfDir);
        tableEnv.registerCatalog("flink_hive_catalog", flinkHiveCatalog);

        // set the HiveCatalog as the current catalog of the session
        tableEnv.useCatalog("flink_hive_catalog");

        // 4. 直接计算
        String result = "insert into wujuan_result select t1.id,t1.city, t2.name, t2.age from kafka_source_city t1 join t2 on t1.id = t2.id";
        // 查询结构 mysql 数据源 t2 表
        TableResult tableResult = tableEnv.executeSql(result);

        tableResult.print();*/
    }
}
