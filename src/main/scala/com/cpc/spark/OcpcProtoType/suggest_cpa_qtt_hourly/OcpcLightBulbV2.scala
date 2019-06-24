package com.cpc.spark.OcpcProtoType.suggest_cpa_qtt_hourly

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import com.cpc.spark.OcpcProtoType.suggest_cpa_v2.OcpcLightBulbV2._
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}

object OcpcLightBulbV2{
  def main(args: Array[String]): Unit = {
    /*
    通过向redis中存储suggest cpa来控制灯泡的亮灯逻辑
    1. 抽取recommendation数据表
    2. mappartition打开redis，并存储数据
     */
    // 计算日期周期
    Logger.getRootLogger.setLevel(Level.WARN)
//    2019-02-02 10 qtt_demo qtt
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val media = args(3).toString


    val spark = SparkSession
      .builder()
      .appName(s"OcpcLightBulbV2: $date, $hour, $version, $media")
      .enableHiveSupport().getOrCreate()

    // todo 修改表名
//    val tableName = "dl_cpc.ocpc_light_control_version"
    val tableName = "test.ocpc_qtt_light_control_v2"
//    val tableName = "test.ocpc_qtt_light_control_version20190415"
    println("parameters:")
    println(s"date=$date, hour=$hour, version=$version, tableName=$tableName")


    // 抽取数据
    val cpcData = getRecommendationAd(version, date, hour, spark)
//    cpcData.write.mode("overwrite").saveAsTable("test.check_ocpc_light_control_20190511a")
    val ocpcData = getOcpcRecord(media, version, date, hour, spark)
//    ocpcData.write.mode("overwrite").saveAsTable("test.check_ocpc_light_control_20190511b")
    val confData = getConfCPA(media, version, date, hour, spark)
    val cvUnit = getCPAgiven(date, hour, spark)


    val data = cpcData
        .join(ocpcData, Seq("unitid", "conversion_goal"), "outer")
        .join(confData, Seq("unitid", "conversion_goal"), "outer")
        .select("unitid", "conversion_goal", "cpa1", "cpa2", "cpa3")
        .na.fill(-1, Seq("cpa1", "cpa2", "cpa3"))
        .withColumn("cpa", udfSelectCPA()(col("cpa1"), col("cpa2"), col("cpa3")))
        .na.fill(-1, Seq("cpa1", "cpa2", "cpa3", "cpa"))
//    data.write.mode("overwrite").saveAsTable("test.check_ocpc_light_control_20190511c")

    data.show(10)

    val resultDF = data
        .join(cvUnit, Seq("unitid", "conversion_goal"), "inner")
        .select("unitid", "conversion_goal", "cpa")
        .selectExpr("cast(unitid as string) unitid", "conversion_goal", "cpa")
        .withColumn("date", lit(date))
        .withColumn("hour", lit(hour))
        .withColumn("version", lit(version))
        .cache()

    resultDF.show(10)

    resultDF
//      .repartition(5).write.mode("overwrite").saveAsTable("test.ocpc_light_control_hourly")
      .repartition(5).write.mode("overwrite").insertInto("dl_cpc.ocpc_light_control_hourly")

    resultDF
      .select("unitid", "conversion_goal", "cpa", "date", "version")
//      .repartition(5).write.mode("overwrite").saveAsTable("test.ocpc_qtt_light_control_version20190415")
      .repartition(5).write.mode("overwrite").insertInto("dl_cpc.ocpc_light_control_version")

    resultDF
      .repartition(5).write.mode("overwrite").saveAsTable(tableName)
  }

}
