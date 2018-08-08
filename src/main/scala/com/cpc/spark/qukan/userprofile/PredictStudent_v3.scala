package com.cpc.spark.qukan.userprofile

import org.apache.spark.sql.SparkSession

/**
  * 使用金晋锋预测数据推到redis，tag：241
  * created time: 2018-08-08
  *
  * @author zhj
  * @version 1.0
  *
  */
object PredictStudent_v3 {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits._
    val tag = 241

    val devices = spark.sql(
      """
        |select device
        |from rpt_qukan.jb_predict_student
        |where predict > 0
      """.stripMargin)
      .map { x =>
        (x.getAs[String]("device"), tag, true)
      }

    SetUserProfileTag.setUserProfileTag(devices.rdd)
  }
}
