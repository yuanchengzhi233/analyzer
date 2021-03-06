package com.cpc.spark.ocpcV3.ocpcQtt.data

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object OcpcLabelCvr3 {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val hour = args(1).toString

//    // TODO  如何判断究竟用哪一个表？
//    val result1 = getLabelFromAdv(date, hour, spark)
//    result1.write.mode("overwrite").saveAsTable("test.ocpcv3_cvr3_data_hourly")
//    println("successfully save data into table: dl_cpc.ocpcv3_cvr3_data_hourly")

    val result2 = getLabel(date, hour, spark)
    result2.write.mode("overwrite").saveAsTable("test.ocpcv3_qtt_cvr3_data_hourly")
//    result2.write.mode("overwrite").insertInto("dl_cpc.ocpcv3_qtt_cvr3_data_hourly")
    println("successfully save data into table: dl_cpc.ocpcv3_cvr3_data_hourly")
  }

  def getLabelFromAdv(date: String, hour: String, spark: SparkSession) = {
    var selectWhere = s"`date`='$date' and hour = '$hour'"

    var sqlRequest1 =
      s"""
         |select
         |    searchid,
         |    uid,
         |    ideaid,
         |    unitid,
         |    price,
         |    bid,
         |    userid,
         |    media_appsid,
         |    ext['adclass'].int_value as adclass,
         |    isclick,
         |    isshow
         |from dl_cpc.cpc_union_log
         |where $selectWhere
         |and isclick is not null
         |and media_appsid in ("80000001", "80000002")
         |and isshow = 1
         |and ext['antispam'].int_value = 0
         |and ideaid > 0
         |and adsrc = 1
         |and adslot_type in (1,2,3)
      """.stripMargin
    println(sqlRequest1)
    val rawData = spark.sql(sqlRequest1)
    rawData.createOrReplaceTempView("raw_table")

    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  1 as label
         |FROM
         |  dl_cpc.site_form_unionlog
         |WHERE
         |  where $selectWhere
       """.stripMargin
    println(sqlRequest2)
    val labelData = spark.sql(sqlRequest2).distinct()

    val resultDF = rawData
      .join(labelData, Seq("searchid"), "left_outer")
      .groupBy("ideaid", "adclass", "media_appsid")
      .agg(sum(col("label")).alias("cvr3_cnt"))
      .select("ideaid", "adclass", "media_appsid", "cvr3_cnt")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))

    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }

  def getLabel(date: String, hour: String, spark: SparkSession) = {
    var selectWhere = s"`date`='$date' and hour = '$hour'"

    var sqlRequest1 =
      s"""
         |select
         |    searchid,
         |    uid,
         |    ideaid,
         |    unitid,
         |    price,
         |    bid,
         |    userid,
         |    ext['adclass'].int_value as adclass,
         |    isclick,
         |    isshow
         |from dl_cpc.cpc_union_log
         |where $selectWhere
         |and isclick is not null
         |and media_appsid in ("80000001", "80000002")
         |and isshow = 1
         |and ext['antispam'].int_value = 0
         |and ideaid > 0
         |and adsrc = 1
         |and adslot_type in (1,2,3)
      """.stripMargin
    println(sqlRequest1)
    val rawData = spark.sql(sqlRequest1)
    rawData.createOrReplaceTempView("raw_table")

    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  label2 as label
         |FROM
         |  dl_cpc.ml_cvr_feature_v1
         |WHERE
         |  where $selectWhere
         |AND
         |  label2=1
         |AND
         |  label_type!=12
       """.stripMargin
    println(sqlRequest2)
    val labelData = spark.sql(sqlRequest2).distinct()

    val resultDF = rawData
      .join(labelData, Seq("searchid"), "left_outer")
      .groupBy("ideaid", "unitid", "adclass")
      .agg(sum(col("label")).alias("cvr3_cnt"))
      .select("ideaid", "unitid", "adclass", "cvr3_cnt")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))


    resultDF.show(10)
    resultDF.printSchema()

    resultDF
  }

}
