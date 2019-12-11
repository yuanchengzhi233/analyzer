package com.cpc.spark.oCPX.basedata

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object OcpcConversionHourly {
  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val hour = args(1).toString

    val cv1 = getLabel1(date, hour, spark)
    val cv2 = getLabel2(date, hour, spark)
    val cv3 = getLabel3(date, hour, spark)
    val cv4 = getLabel4(date, hour, spark)
    val cv5 = getLabel5(date, hour, spark)
    val cvWZ = getLabelWZ(date, hour, spark)

    val result = cv1.union(cv2).union(cv3).union(cv4).union(cv5).union(cvWZ)
    result
      .repartition(10).write.mode("overwrite").insertInto("test.ocpc_label_cvr_hourly")
//      .repartition(10).write.mode("overwrite").insertInto("dl_cpc.ocpc_label_cvr_hourly")
    println("successfully save data into table: dl_cpc.ocpc_unit_label_cvr_hourly")
  }

  def getLabel5(date: String, hour: String, spark: SparkSession) = {
    val sqlRequest =
      s"""
         |select
         |    searchid,
         |    1 as label
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'api_app_register')
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark
      .sql(sqlRequest)
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("cvr_goal", lit("cvr5"))
      .distinct()

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }


  def getLabelWZ(date: String, hour: String, spark: SparkSession) = {
    var selectCondition = s"`date`='$date' and hour = '$hour'"

    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  label2 as label
         |FROM
         |  dl_cpc.ml_cvr_feature_v1
         |WHERE
         |  $selectCondition
         |AND
         |  label2=1
         |AND
         |  label_type in (1, 2, 3)
         |GROUP BY searchid, label2
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark
      .sql(sqlRequest)
      .select("searchid", "label")
      .distinct()
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("cvr_goal", lit("wz"))

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }

  def getLabel4(date: String, hour: String, spark: SparkSession) = {
    var selectCondition = s"`date`='$date' and hour = '$hour'"

    val sqlRequest1 =
      s"""
         |select
         |    searchid,
         |    1 as label
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    (array_contains(conversion_target, 'sdk_site_wz')
         |OR
         |    array_contains(conversion_target, 'sdk_banner_wz')
         |OR
         |    array_contains(conversion_target, 'sdk_popupwindow_wz'))
       """.stripMargin
    println(sqlRequest1)
    val data1 = spark.sql(sqlRequest1)

    val sqlRequest2 =
      s"""
         |select
         |    distinct searchid,
         |    1 as label
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'js_active_copywx')
       """.stripMargin
    println(sqlRequest2)
    val data2 = spark.sql(sqlRequest2)



    val resultDF = data1
      .union(data2)
      .select("searchid", "label")
      .distinct()
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("cvr_goal", lit("cvr4"))

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }

  def getLabel3(date: String, hour: String, spark: SparkSession) = {
    var selectCondition = s"`date`='$date' and hour = '$hour'"
    // js加粉
    val sqlRequest4 =
      s"""
         |select
         |    distinct searchid
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'js_active_js_form')
       """.stripMargin
    println(sqlRequest4)
    val data4 = spark.sql(sqlRequest4)

    // 鲸鱼建站
    val sqlRequest5 =
      s"""
         |select
         |    distinct searchid
         |from
         |    dl_cpc.cpc_conversion
         |where
         |    day = '$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'site_form')
       """.stripMargin
    println(sqlRequest5)
    val data5 = spark.sql(sqlRequest5)

    val sqlRequest6 =
      s"""
         |select
         |    distinct searchid,
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'api_ldy_sform')
       """.stripMargin
    println(sqlRequest6)
    val data6 = spark.sql(sqlRequest6)

    val resultDF = data4
      .union(data5)
      .union(data6)
      .distinct()
      .withColumn("label", lit(1))
      .select("searchid", "label")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("cvr_goal", lit("cvr3"))

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }

  def getLabel2(date: String, hour: String, spark: SparkSession) = {
    val sqlRequest =
      s"""
         |select
         |    searchid,
         |    1 as label
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'api')
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark
      .sql(sqlRequest)
      .select("searchid", "label")
      .distinct()
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("cvr_goal", lit("cvr2"))

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }

  def getLabel1(date: String, hour: String, spark: SparkSession) = {
    val sqlRequest =
      s"""
         |select
         |    searchid,
         |    1 as label
         |from
         |     dl_cpc.cpc_conversion
         |where
         |    day='$date'
         |and
         |    `hour` = '$hour'
         |and
         |    array_contains(conversion_target, 'sdk_app_install')
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark
        .sql(sqlRequest)
        .select("searchid", "label")
        .distinct()
        .withColumn("date", lit(date))
        .withColumn("hour", lit(hour))
        .withColumn("cvr_goal", lit("cvr1"))

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }
}
