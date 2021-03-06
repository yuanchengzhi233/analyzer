package com.cpc.spark.oCPX.oCPC.calibration_all

import com.cpc.spark.oCPX.oCPC.calibration_all.OcpcCalibrationBase._
import com.cpc.spark.oCPX.oCPC.calibration_all.OcpcJFBfactor._
import com.cpc.spark.oCPX.oCPC.calibration_all.OcpcSmoothfactor._
import com.cpc.spark.oCPX.oCPC.calibration_all.OcpcCVRfactorRealtime._
import com.cpc.spark.oCPX.oCPC.calibration_all.OcpcBIDfactor._
import com.cpc.spark.oCPX.oCPC.calibration_all.OcpcCalibrationBaseRealtime._
import org.apache.spark.sql.functions._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SparkSession}


object OcpcGetPb {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val expTag = args(3).toString
    val bidFactorHourInt = args(4).toInt

    // 主校准回溯时间长度
    val hourInt1 = args(5).toInt
    // 备用校准回溯时间长度
    val hourInt2 = args(6).toInt
    // 兜底校准时长
    val hourInt3 = args(7).toInt

    println("parameters:")
    println(s"date=$date, hour=$hour, version:$version, expTag:$expTag, hourInt1:$hourInt1, hourInt2:$hourInt2, hourInt3:$hourInt3")

    // 计算jfb_factor,cvr_factor,post_cvr
    val dataRaw = OcpcCalibrationBaseMain(date, hour, hourInt3, spark).cache()
    dataRaw.show(10)

    dataRaw
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912a")


    val jfbDataRaw = OcpcJFBfactorMain(date, hour, version, expTag, dataRaw, hourInt1, hourInt2, hourInt3, spark)
    val jfbData = jfbDataRaw
      .withColumn("jfb_factor", lit(1.0) / col("jfb"))
      .select("identifier", "conversion_goal", "exp_tag", "jfb_factor")
      .cache()
    jfbData.show(10)
    jfbData
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912b")

    val dataRawOnlySmooth = OcpcCalibrationBaseMainOnlySmooth(date, hour, hourInt3, spark).cache()
    dataRawOnlySmooth.show(10)
    dataRawOnlySmooth
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912c")

    val smoothDataRaw = OcpcSmoothfactorMain(date, hour, version, expTag, dataRawOnlySmooth, hourInt1, hourInt2, hourInt3, spark)
    val smoothData = smoothDataRaw
      .withColumn("post_cvr", col("cvr"))
      .select("identifier", "conversion_goal", "exp_tag", "post_cvr", "smooth_factor")
      .cache()
    smoothData.show(10)
    smoothData
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912d")

    val dataRawRealtime = OcpcCalibrationBaseRealtimeMain(date, hour, hourInt3, spark).cache()
    dataRawRealtime
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912e")

    val pcocDataRaw = OcpcCVRfactorMain(date, hour, version, expTag, dataRawRealtime, hourInt1, hourInt2, hourInt3, spark)
    val pcocData = pcocDataRaw
      .withColumn("cvr_factor", lit(1.0) / col("pcoc"))
      .select("identifier", "conversion_goal", "exp_tag", "cvr_factor")
      .cache()
    pcocData.show(10)
    pcocData
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912f")

    val bidFactorDataRaw = OcpcBIDfactorMain(date, hour, version, expTag, bidFactorHourInt, spark)
    val bidFactorData = bidFactorDataRaw
      .select("identifier", "conversion_goal", "exp_tag", "high_bid_factor", "low_bid_factor")
      .cache()
    bidFactorData.show(10)
    bidFactorData
      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_exp_data20190912h")

    val data = assemblyData(jfbData, smoothData, pcocData, bidFactorData, spark).cache()
    data.show(10)

    dataRaw.unpersist()
    dataRawOnlySmooth.unpersist()
    dataRawRealtime.unpersist()

    // 明投单元
    val result = data
      .withColumn("cpagiven", lit(1.0))
      .withColumn("is_hidden", lit(0))
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(version))
      .select("identifier", "conversion_goal", "jfb_factor", "post_cvr", "smooth_factor", "cvr_factor", "high_bid_factor", "low_bid_factor", "cpagiven", "date", "hour", "exp_tag", "is_hidden", "version")

    val resultDF = result
      .select("identifier", "conversion_goal", "jfb_factor", "post_cvr", "smooth_factor", "cvr_factor", "high_bid_factor", "low_bid_factor", "cpagiven", "date", "hour", "exp_tag", "is_hidden", "version")

    resultDF
      .repartition(1)
      .write.mode("overwrite").insertInto("test.ocpc_pb_data_hourly_exp_alltype")
//      .write.mode("overwrite").insertInto("dl_cpc.ocpc_pb_data_hourly_exp_alltype")


  }


  def assemblyData(jfbData: DataFrame, smoothData: DataFrame, pcocData: DataFrame, bidFactorData: DataFrame, spark: SparkSession) = {
    // 组装数据
    val data = jfbData
      .join(pcocData, Seq("identifier", "conversion_goal", "exp_tag"), "outer")
      .join(smoothData, Seq("identifier", "conversion_goal", "exp_tag"), "outer")
      .join(bidFactorData, Seq("identifier", "conversion_goal", "exp_tag"), "left_outer")
      .select("identifier", "conversion_goal", "exp_tag", "jfb_factor", "post_cvr", "smooth_factor", "cvr_factor", "high_bid_factor", "low_bid_factor")
      .na.fill(1.0, Seq("jfb_factor", "cvr_factor", "high_bid_factor", "low_bid_factor"))
      .na.fill(0.0, Seq("post_cvr", "smooth_factor"))

    data


  }


}


