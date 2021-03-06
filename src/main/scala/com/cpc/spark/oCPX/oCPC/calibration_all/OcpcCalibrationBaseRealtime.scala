package com.cpc.spark.oCPX.oCPC.calibration_all

import com.cpc.spark.oCPX.OcpcTools._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}


object OcpcCalibrationBaseRealtime {
  def main(args: Array[String]): Unit = {
    /*
    动态计算alpha平滑系数
    1. 基于原始pcoc，计算预测cvr的量纲系数
    2. 二分搜索查找到合适的平滑系数
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    val date = args(0).toString
    val hour = args(1).toString
    val hourInt = args(2).toInt
    val isDelay = args(3).toInt
    println("parameters:")
    println(s"date=$date, hour=$hour, hourInt:$hourInt")

    var result = OcpcCalibrationBaseRealtimeMain(date, hour, hourInt, spark)
    if (isDelay == 1) {
      result = OcpcCalibrationBaseRealtimeDelayMain(date, hour, hourInt, spark)
    }

    result
      .repartition(10).write.mode("overwrite").saveAsTable("test.check_base_factor20190731a")
  }

  def OcpcCalibrationBaseRealtimeDelayMain(date: String, hour: String, hourInt: Int, spark: SparkSession) = {
    val baseDataRaw = getRealtimeDataDelay(hourInt, date, hour, spark)
    val baseData = baseDataRaw
      .selectExpr("cast(unitid as string) identifier", "conversion_goal", "media", "isclick", "iscvr", "exp_cvr", "date", "hour")

    // 计算结果
    val result = calculateParameter(baseData, spark)

    val resultDF = result
      .select("identifier", "conversion_goal", "media", "click", "cv", "total_pre_cvr", "date", "hour")


    resultDF
  }

  def OcpcCalibrationBaseRealtimeMain(date: String, hour: String, hourInt: Int, spark: SparkSession) = {
    val baseDataRaw = getRealtimeData(hourInt, date, hour, spark)
    val baseData = baseDataRaw
      .selectExpr("cast(unitid as string) identifier", "conversion_goal", "media", "isclick", "iscvr", "exp_cvr", "date", "hour")

    // 计算结果
    val result = calculateParameter(baseData, spark)

    val resultDF = result
      .select("identifier", "conversion_goal", "media", "click", "cv", "total_pre_cvr", "date", "hour")


    resultDF
  }


  def calculateParameter(rawData: DataFrame, spark: SparkSession) = {
    val data  =rawData
      .filter(s"isclick=1")
      .groupBy("identifier", "conversion_goal", "media", "date", "hour")
      .agg(
        sum(col("isclick")).alias("click"),
        sum(col("iscvr")).alias("cv"),
        sum(col("exp_cvr")).alias("total_pre_cvr")
      )
      .select("identifier", "conversion_goal", "media", "click", "cv", "total_pre_cvr", "date", "hour")

    data
  }

}