package com.cpc.spark.OcpcProtoType.model_v6

import com.cpc.spark.OcpcProtoType.model_v6.OcpcSmoothFactor.OcpcSmoothFactorMain
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}


object OcpcCalculateCalibration {
  def main(args: Array[String]): Unit = {
    /*
    校准策略：
    主校准 + 备用校准：
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    // 计算日期周期
    // bash: 2019-01-02 12 1 qtt_demo qtt
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val media = args(3).toString
    val minCV = args(4).toInt

    // 主校准回溯时间长度
    val hourInt1 = args(5).toInt
    // 备用校准回溯时间长度
    val hourInt2 = args(6).toInt
    // 兜底校准时长
    val hourInt3 = args(7).toInt


    println("parameters:")
    println(s"date=$date, hour=$hour, version=$version, media=$media, hourInt1=$hourInt1, hourInt2=$hourInt2, hourInt3=$hourInt3")
    // 抽取媒体id

    val result = OcpcCalculateCalibrationMain(date, hour, version, media, minCV, hourInt1, hourInt2, hourInt3, spark)
    result
      .repartition(10).write.mode("overwrite").saveAsTable("test.check_ocpc_calibration20190702a")

  }

  def OcpcCalculateCalibrationMain(date: String, hour: String, version: String, media: String, minCV: Int, hourInt1: Int, hourInt2: Int, hourInt3: Int, spark: SparkSession) = {
    // 抽取媒体id
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias." + media + ".media_selection"
    val mediaSelection = conf.getString(conf_key)

    val data1 = OcpcSmoothFactorMain(date, hour, version, media, hourInt1, spark).cache()
    data1.show(10)
    val data2 = OcpcSmoothFactorMain(date, hour, version, media, hourInt2, spark).cache()
    data2.show(10)
    val data3 = OcpcSmoothFactorMain(date, hour, version, media, hourInt3, spark).cache()
    data3.show(10)

    val calibration1 = calculateCalibrationValue(data1, data2, minCV, spark)
    val calibrationNew = data3
      .withColumn("post_cvr", col("cv") * 1.0 / col("click"))
      .withColumn("pcoc_new", col("pre_cvr") * 1.0 / col("post_cvr"))
      .withColumn("jfb_new", col("total_price") * 1.0 / col("total_bid"))
      .withColumn("post_cvr_new", col("post_cvr"))
      .select("identifier", "conversion_goal", "pcoc_new", "jfb_new", "post_cvr_new")

    val calibration = calibrationNew
      .join(calibration1, Seq("identifier", "conversion_goal"), "left_outer")
      .select("identifier", "conversion_goal", "pcoc_new", "jfb_new", "post_cvr_new", "pcoc3", "jfb3", "post_cvr3")
      .withColumn("post_cvr", when(col("post_cvr3").isNotNull, col("post_cvr3")).otherwise(col("post_cvr_new")))
      .withColumn("pcoc", when(col("pcoc3").isNotNull, col("pcoc3")).otherwise(col("pcoc_new")))
      .withColumn("jfb", when(col("jfb3").isNotNull, col("jfb3")).otherwise(col("jfb_new")))
      .cache()

    calibration.show(10)
    data1.unpersist()
    data2.unpersist()
    data3.unpersist()

    val resultDF = calibration
      .select("identifier", "conversion_goal", "pcoc", "jfb", "post_cvr")

    resultDF

  }

  def calculateCalibrationValue(dataRaw1: DataFrame, dataRaw2: DataFrame, minCV: Int, spark: SparkSession) = {
    /*
    "identifier", "click", "cv", "pre_cvr", "total_price", "total_bid"
     */

    // 主校准模型
    val data1 = dataRaw1
      .filter(s"cv >= $minCV")
      .withColumn("post_cvr", col("cv") * 1.0 / col("click"))
      .withColumn("pcoc1", col("pre_cvr") * 1.0 / col("post_cvr"))
      .withColumn("jfb1", col("total_price") * 1.0 / col("total_bid"))
      .withColumn("post_cvr1", col("post_cvr"))
      .withColumn("flag", lit(1))
      .select("identifier", "conversion_goal", "pcoc1", "jfb1", "post_cvr1", "flag")

    // 备用校准模型
    val data2 = dataRaw2
      .filter(s"cv >= $minCV")
      .withColumn("post_cvr", col("cv") * 1.0 / col("click"))
      .withColumn("pcoc2", col("pre_cvr") * 1.0 / col("post_cvr"))
      .withColumn("jfb2", col("total_price") * 1.0 / col("total_bid"))
      .withColumn("post_cvr2", col("post_cvr"))
      .select("identifier", "conversion_goal", "pcoc2", "jfb2", "post_cvr2")

    // 数据表关联
    val data = data2
      .join(data1, Seq("identifier", "conversion_goal"), "left_outer")
      .na.fill(0, Seq("flag"))
      .withColumn("pcoc3", when(col("flag") === 1, col("pcoc1")).otherwise(col("pcoc2")))
      .withColumn("jfb3", when(col("flag") === 1, col("jfb1")).otherwise(col("jfb2")))
      .withColumn("post_cvr3", when(col("flag") === 1, col("post_cvr1")).otherwise(col("post_cvr2")))

    data.show()

    data

  }



}


