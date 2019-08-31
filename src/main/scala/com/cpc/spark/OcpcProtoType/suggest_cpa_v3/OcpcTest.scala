package com.cpc.spark.OcpcProtoType.suggest_cpa_v3

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.OcpcProtoType.OcpcTools._
import com.cpc.spark.OcpcProtoType.suggest_cpa_v3.OcpcCalculateAUC.OcpcCalculateAUCmain
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.cpc.spark.OcpcProtoType.suggest_cpa_v3.OcpcSuggestCPA.getUnitidList


object OcpcTest{
  def main(args: Array[String]): Unit = {
    // 计算日期周期
    Logger.getRootLogger.setLevel(Level.WARN)
    val date = args(0).toString
    val hour = args(1).toString


    val spark = SparkSession
      .builder()
      .appName(s"ocpc suggest cpa v2: $date, $hour")
      .enableHiveSupport().getOrCreate()
    println("parameters:")
    println(s"date=$date, hour=$hour")

    val data = getUnitidList(date, hour, spark)
    val totalCnt = data.count()
    data
      .repartition(10).write.mode("overwrite").saveAsTable("test.check_ocpc_data20190831a")
    val cnt = totalCnt.toFloat / 10
    val randomData = data
      .orderBy(rand())
      .limit(cnt.toInt)
    randomData
      .repartition(10)
      .write.mode("overwrite").saveAsTable("test.check_ocpc_data20190831b")

  }


}
