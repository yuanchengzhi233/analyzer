package com.cpc.spark.OcpcProtoType.report_novel

//import com.cpc.spark.ocpcV3.utils
//import com.typesafe.config.ConfigFactory
//import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.cpc.spark.OcpcProtoType.report.OcpcHourlyAucReport._
import org.apache.log4j.{Level, Logger}

//import scala.collection.mutable

object OcpcHourlyAucReportV3 {
  def main(args: Array[String]): Unit = {
    // 计算日期周期
    Logger.getRootLogger.setLevel(Level.WARN)
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val media = args(3).toString
    println("parameters:")
    println(s"date=$date, hour=$hour, version=$version, media=$media")

    // spark app name
    val spark = SparkSession.builder().appName(s"OcpcHourlyAucReport: $date, $hour").enableHiveSupport().getOrCreate()

    var isHidden = 0
    if (version == "novel_v2") {
      isHidden = 0
    } else {
      isHidden = 1
    }

    val rawData = getOcpcLog(media, date, hour, spark).filter(s"is_hidden = $isHidden")

    // 详情表数据
    val versionUnit = version + "_unitid"
    val unitData1 = calculateByUnitid(rawData, date, hour, spark)
    val unitData2 = calculateAUCbyUnitid(rawData, date, hour, spark)
    val unitData = unitData1
      .join(unitData2, Seq("unitid", "userid", "conversion_goal"), "left_outer")
      .withColumn("identifier", col("unitid"))
      .selectExpr("cast(identifier as string) identifier", "userid", "conversion_goal", "pre_cvr", "cast(post_cvr as double) post_cvr", "q_factor", "cpagiven", "cast(cpareal as double) cpareal", "cast(acp as double) acp", "acb", "auc")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(versionUnit))

//    unitData.write.mode("overwrite").saveAsTable("test.ocpc_detail_report_hourly20190226")
    unitData
      .repartition(2).write.mode("overwrite").insertInto("dl_cpc.ocpc_auc_report_detail_hourly")


    // 详情表数据
    val versionUserid = version + "_userid"
    val userData1 = calculateByUserid(rawData, date, hour, spark)
    val userData2 = calculateAUCbyUserid(rawData, date, hour, spark)
    val userData = userData1
      .join(userData2, Seq("userid", "conversion_goal"), "left_outer")
      .withColumn("identifier", col("userid"))
      .selectExpr("cast(identifier as string) identifier", "userid", "conversion_goal", "pre_cvr", "cast(post_cvr as double) post_cvr", "q_factor", "cpagiven", "cast(cpareal as double) cpareal", "cast(acp as double) acp", "acb", "auc")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(versionUserid))

//    userData.write.mode("overwrite").saveAsTable("test.ocpc_detail_report_hourly20190226")
    userData
      .repartition(2).write.mode("overwrite").insertInto("dl_cpc.ocpc_auc_report_detail_hourly")

    // 汇总表数据
    val conversionData1 = calculateByConversionGoal(rawData, date, hour, spark)
    val conversionData2 = calculateAUCbyConversionGoal(rawData, date, hour, spark)
    val conversionData = conversionData1
      .join(conversionData2, Seq("conversion_goal"), "left_outer")
      .select("conversion_goal", "pre_cvr", "post_cvr", "q_factor", "cpagiven", "cpareal", "acp", "acb", "auc")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(version))

//    conversionData.write.mode("overwrite").saveAsTable("test.ocpc_summary_report_hourly20190226")
    conversionData
      .repartition(1).write.mode("overwrite").insertInto("dl_cpc.ocpc_auc_report_summary_hourly")


  }

}