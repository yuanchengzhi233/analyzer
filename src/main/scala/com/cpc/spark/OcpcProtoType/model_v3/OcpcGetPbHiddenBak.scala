package com.cpc.spark.OcpcProtoType.model_v3

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.ocpc.OcpcUtils.getTimeRangeSql2
//import com.cpc.spark.ocpcV3.ocpc.OcpcUtils.getTimeRangeSql2
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._


object OcpcGetPbHiddenBak {
  def main(args: Array[String]): Unit = {
    /*
    pb文件格式：
    string identifier = 1;
    int32 conversiongoal = 2;
    double kvalue = 3;
    double cpagiven = 4;
    int64 cvrcnt = 5;
    对于明投广告，cpagiven=1， cvrcnt使用ocpc广告记录进行关联，k需要进行计算，每个conversiongoal都需要进行计算
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    // 计算日期周期
    // bash: 2019-01-02 12 1 hottopic_test hottopic
    val date = args(0).toString
    val hour = args(1).toString
    val conversionGoal = args(2).toInt
    val version = args(3).toString
    val media = args(4).toString

    println("parameters:")
    println(s"date=$date, hour=$hour, conversionGoal=$conversionGoal, version=$version, media=$media")
    var mediaSelection = s"media_appsid in ('80000001', '80000002')"
    if (media == "qtt") {
      mediaSelection = s"media_appsid in ('80000001', '80000002')"
    } else if (media == "novel") {
      mediaSelection = s"media_appsid in ('80001098','80001292')"
    } else {
      mediaSelection = s"media_appsid = '80002819'"
    }

    // 明投：可以有重复identifier
    val result = getPbByConversion(mediaSelection, conversionGoal, version, date, hour, spark)
    val resultDF = result
        .withColumn("cpagiven", lit(1))
        .select("identifier", "cpagiven", "cvrcnt", "kvalue")
        .withColumn("conversion_goal", lit(conversionGoal))
        .withColumn("date", lit(date))
        .withColumn("hour", lit(hour))
        .withColumn("version", lit(version))

    resultDF
//      .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_pb_result_hourly_20190303")
      .repartition(10).write.mode("overwrite").insertInto("dl_cpc.ocpc_pb_result_hourly_v2")

  }

  def getPbByConversion(mediaSelection: String, conversionGoal: Int, version: String, date: String, hour: String, spark: SparkSession) = {
    /*
    计算步骤
    1. 获取base_data
    2. 按照conversiongoal, 计算cvrcnt，数据串联
    3. 计算k
     */
    val base = getBaseData(mediaSelection, conversionGoal, date, hour, spark)
    val kvalue = getKvalue(conversionGoal, version, date, hour, spark)

    val resultDF = base
      .withColumn("cvrcnt", lit(0))
      .join(kvalue, Seq("identifier", "conversion_goal"), "left_outer")
      .select("identifier", "conversion_goal", "cvrcnt", "kvalue")
      .na.fill(0, Seq("cvrcnt", "kvalue"))
      .withColumn("kvalue", when(col("kvalue") > 15.0, 15.0).otherwise(col("kvalue")))


    resultDF
  }

  def getKvalue(conversionGoal: Int, version: String, date: String, hour: String, spark: SparkSession) = {
    // 48小时数据
    val version1 = version + "_48"
    val sqlRequest1 =
      s"""
         |SELECT
         |  identifier,
         |  1.0 / jfb as kvalue1
         |FROM
         |  dl_cpc.ocpc_pcoc_jfb_hourly
         |WHERE
         |  `date` = '$date'
         |AND
         |  `hour` = '$hour'
         |AND
         |  version = '$version1'
         |AND
         |  conversion_goal = $conversionGoal
       """.stripMargin
    println(sqlRequest1)
    val data1 = spark.sql(sqlRequest1)

    // 72小时数据
    val version2 = version + "_72"
    val sqlRequest2 =
      s"""
         |SELECT
         |  identifier,
         |  1.0 / jfb as kvalue2
         |FROM
         |  dl_cpc.ocpc_pcoc_jfb_hourly
         |WHERE
         |  `date` = '$date'
         |AND
         |  `hour` = '$hour'
         |AND
         |  version = '$version2'
         |AND
         |  conversion_goal = $conversionGoal
       """.stripMargin
    println(sqlRequest2)
    val data2 = spark.sql(sqlRequest2)

    // 数据关联
    val data = data1
      .join(data2, Seq("identifier"), "outer")
      .na.fill(0.0, Seq("kvalue1", "kvalue2"))
      .select("identifier", "kvalue1", "kvalue2")
      .withColumn("kvalue", udfSelectK()(col("kvalue1"), col("kvalue2")))
      .select("identifier", "kvalue1", "kvalue2", "kvalue")

    val resultDF = data.select("identifier", "kvalue").filter(s"kvalue > 0")
    resultDF
  }

  def udfSelectK() = udf((kvalue1: Double, kvalue2: Double) => {
    var kvalue = 0.0
    if (kvalue1 == 0.0) {
      kvalue = kvalue2
    } else {
      kvalue = kvalue1
    }

    kvalue

  })

  def getBaseData(mediaSelection: String, conversionGoal: Int, date: String, hour: String, spark: SparkSession) = {
    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val today = dateConverter.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -7)
    val startdate = calendar.getTime
    val date1 = dateConverter.format(startdate)
    val selectCondition = getTimeRangeSql2(date1, hour, date, hour)

    val sqlRequest =
      s"""
         |SELECT
         |  cast(unitid as string) as identifier
         |FROM
         |  dl_cpc.ocpc_ctr_data_hourly
         |WHERE
         |  $selectCondition
         |AND
         |  $mediaSelection
       """.stripMargin

    println(sqlRequest)
    val resultDF = spark
      .sql(sqlRequest)
      .withColumn("conversion_goal", lit(conversionGoal))
      .distinct()

    resultDF
  }

}


