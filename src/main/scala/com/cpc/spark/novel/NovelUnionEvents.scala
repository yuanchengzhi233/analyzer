package com.cpc.spark.novel

import java.util.Properties

import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * @author WangYao
  * @date 2019/03/06
  */
object NovelUnionEvents {
    def main(args: Array[String]): Unit = {
        Logger.getRootLogger.setLevel(Level.WARN)
        val date = args(0)
        val hour = args(1)
        val spark = SparkSession.builder()
          .appName(s"NovelUnionLog date = $date and hour = $hour")
          .enableHiveSupport()
          .getOrCreate()
        val sql =
            s"""
               |select *
               |from dl_cpc.cpc_basedata_union_events
               |where media_appsid in ('80001098','80001292','80001011','80001539','80002480')
               |      and day= '$date' and hour = '$hour'
             """.stripMargin

        println(sql)

        spark.sql(sql).toDF
          .write
          .partitionBy("day", "hour", "minute")
          .mode(SaveMode.Append) // 修改为Append
          .parquet(
            s"""
               |hdfs://emr-cluster/warehouse/dl_cpc.db/cpc_novel_union_events/
         """.stripMargin.trim)

        var i = 0
        while (i <60){
            var minute = (i/10).toString
            minute=minute + i%10
            println(minute)
            spark.sql(
                s"""
                   |ALTER TABLE dl_cpc.cpc_novel_union_events
                   | add if not exists PARTITION(`day` = "$date", `hour` = "$hour", `minute`="$minute")
                   | LOCATION 'hdfs://emr-cluster/warehouse/dl_cpc.db/cpc_novel_union_events/day=$date/hour=$hour/minute=$minute'
          """.stripMargin.trim)
            i=i+1
        }
        println(" -- write cpc_novel_union_events to hive successfully -- ")
    }
}
