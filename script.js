/**
 * 用来生成批量插入脚本
 */

var fs = require("fs");
var DATA = fs.readFileSync('tables') + '';
var LINES = DATA.split('\r\n');
var SPLIT_SCHEMA_TABLE = ".";
var SQL = [];
//从数据库读取到的数据源配置文件
var SOURCE = {
    "mode": "SINGLE",
    "name": "",
    "namespace": "cmf2",
    "source": {
        "driver": "com.mysql.jdbc.Driver",
        "encode": "UTF8",
        "gmtCreate": new Date().getTime(),
        "gmtModified": new Date().getTime(),
        "id": 1,
        "name": "Source",
        "password": "wch123",
        "type": "MYSQL",
        "url": "jdbc:mysql://10.65.215.12:3306",
        "username": "root"
    }
}, TARGET = {
    "mode": "SINGLE",
    "name": "tt_inst_notify_log",
    "namespace": "cmf2",
    "source": {
        "driver": "com.mysql.jdbc.Driver",
        "encode": "UTF8",
        "gmtCreate": new Date().getTime(),
        "gmtModified": new Date().getTime(),
        "id": 2,
        "name": "Target",
        "password": "wch123",
        "type": "MYSQL",
        "url": "jdbc:mysql://10.65.215.37:3306",
        "username": "root"
    }
};
/**
 * 生成数据表
 */
function genDataMediaSql() {
    SQL = [];
    LINES.forEach(function (line) {
        line = line.trim();
        let cols = line.split(SPLIT_SCHEMA_TABLE);
        if (!cols || cols.length < 2) {
            return;
        }
        var schema = cols[0], table = cols[1];
        SOURCE.name = table;
        SOURCE.namespace = schema;
        TARGET.name = table;
        TARGET.namespace = schema;
        genTableSqlWithSource(SOURCE);
        genTableSqlWithSource(TARGET)
    });
    saveSql('data-media.sql');
}

function genTableSqlWithSource(source) {
    var sql = ["INSERT INTO DATA_MEDIA(name,namespace,properties,data_media_source_id,gmt_create) VALUES ("
        , '\'', source.name, '\',\'', source.namespace, '\',\'', JSON.stringify(source), '\',', source.source.id, ',', "NOW());"];
    SQL.push(sql.join(''));
}
//生成配对配置，与生成配置表的配置配合使用，记得修改start及count值
function genPairSql() {
    var pipeline = 1;
    SQL = [];
    //SELECT AUTO_INCREMENT FROM information_schema.`TABLES` WHERE TABLE_SCHEMA='otter' AND TABLE_NAME='data_media';
    var start = 1, count = LINES.length; //配置表的源id
    for (var i = 0; i < count; i++) {
        var sql = [
            'INSERT INTO `DATA_MEDIA_PAIR` (`PULLWEIGHT`, `PUSHWEIGHT`, `SOURCE_DATA_MEDIA_ID`, `TARGET_DATA_MEDIA_ID`, `PIPELINE_ID`, `COLUMN_PAIR_MODE`, `GMT_CREATE`, `GMT_MODIFIED`) values(NULL',
            "'5'",
            start++, start++
            , pipeline,"'INCLUDE','2018-01-30 18:23:54','2018-01-30 18:23:54');"
        ];
        SQL.push(sql.join(','))
    }
    //SQL.push("UPDATE DATA_MEDIA_PAIR SET resolver = (SELECT t.data FROM data_bak t WHERE t.name = 'resolver-null'),FILTER = (SELECT t.data FROM data_bak t WHERE t.name = 'filter-del');");
    saveSql('pair.sql');
}
/**
 * 生成清空表数据sql
 */
function truncateTable() {
    SQL = [];
    LINES.forEach(line => {
        line = line.trim();
        let cols = line.split(SPLIT_SCHEMA_TABLE);
        if (!cols || cols.length < 2) {
            return;
        }
        var schema = cols[0], table = cols[1];
        SQL.push('TRUNCATE TABLE ' + schema + '2.' + table + ';');
    });
    saveSql('truncate.sql');
}

function saveSql(filename = 'sql.sql') {
    fs.writeFile(filename, SQL.join("\r\n"), function (err) {
        if (err) {
            console.error(err);
        } else {
            console.log('写入成功:' + filename);
        }
    });
}



//genDataMediaSql();
genPairSql();
//truncateTable();


//target
//gop	t_transit_fund_change_detail
//tss	t_in_transit_fund_message

//UPDATE data_media_pair SET resolver = (SELECT t.data FROM data_bak t WHERE t.name = 'resolver-null'),FILTER = (SELECT t.data FROM data_bak t WHERE t.name = 'filter-del')
//filter {"blank":false,"extensionDataType":"SOURCE","notBlank":true,"sourceText":"package com.alibaba.otter.node.extend.processor;import com.alibaba.otter.shared.etl.extend.processor.EventProcessor;import com.alibaba.otter.shared.etl.model.EventData;import com.alibaba.otter.shared.etl.model.EventType;import org.slf4j.Logger;import org.slf4j.LoggerFactory;public class TestEventProcessor implements EventProcessor {    static Logger LOG = LoggerFactory.getLogger(TestEventProcessor.class);    @Override    public boolean process(EventData eventData) {        if (EventType.DELETE.equals(eventData.getEventType())) {            LOG.info(\"{}\", eventData);            return false;        }        return true;    }}","timestamp":1517307834203}
//resolver {"blank":true,"clazzPath":"","extensionDataType":"CLAZZ","notBlank":false,"timestamp":1517307834203}