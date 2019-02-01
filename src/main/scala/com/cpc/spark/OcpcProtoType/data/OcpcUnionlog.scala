package com.cpc.spark.OcpcProtoType.data

import com.cpc.spark.udfs.Udfs_wj.udfStringToMap
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object OcpcUnionlog {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val hour = args(1).toString

    val data = getBaseUnionlog(date, hour, spark)

    data
      .repartition(100).write.mode("overwrite").saveAsTable("test.ocpc_base_unionlog")
//      .repartition(100).write.mode("overwrite").insertInto("dl_cpc.filtered_union_log_hourly")
    println("successfully save data into table: dl_cpc.filtered_union_log_hourly")
  }

  def getBaseUnionlog(date: String, hour: String, spark: SparkSession) = {
    var selectWhere = s"(`date`='$date' and hour = '$hour')"

    // 拿到基础数据
    var sqlRequest =
      s"""
         |select
         |    searchid,
         |    timestamp,
         |    network,
         |    exptags,
         |    media_type,
         |    media_appsid,
         |    adslotid,
         |    adslot_type,
         |    adtype,
         |    adsrc,
         |    interaction,
         |    bid,
         |    price,
         |    ideaid,
         |    unitid,
         |    planid,
         |    country,
         |    province,
         |    city,
         |    uid,
         |    ua,
         |    os,
         |    sex,
         |    age,
         |    isshow,
         |    isclick,
         |    duration,
         |    userid,
         |    ext_int['is_ocpc'] as is_ocpc,
         |    ext_string['ocpc_log'] as ocpc_log,
         |    ext_string['user_city'] as user_city,
         |    ext['city_level'].int_value as city_level,
         |    ext['adclass'].int_value as adclass
         |from dl_cpc.cpc_union_log
         |where $selectWhere
         |and (isshow>0 or isclick>0)
      """.stripMargin
    println(sqlRequest)
    val rawData = spark
      .sql(sqlRequest)


    val resultDF = rawData
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))

    resultDF.printSchema()

    resultDF
  }



}



