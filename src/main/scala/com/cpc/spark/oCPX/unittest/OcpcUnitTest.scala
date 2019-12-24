package com.cpc.spark.oCPX.unittest


import com.cpc.spark.oCPX.oCPC.report.OcpcHourlyReportV2.{calculateData, getBaseData}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}


object OcpcUnitTest {
  /*
  新增部分媒体id采用暗投
   */
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    val date = args(0).toString
    val hour = args(1).toString

    println("parameters:")
    println(s"date=$date, hour=$hour")

    val rawData = getBaseData(date, hour, spark)
    val stage3DataRaw = rawData.filter(s"deep_ocpc_step = 2")
    val stage3Data = calculateData(stage3DataRaw, spark)


    stage3Data
      .write.mode("overwrite").saveAsTable("test.check_ocpc_exp_data20191224a")


  }

}


