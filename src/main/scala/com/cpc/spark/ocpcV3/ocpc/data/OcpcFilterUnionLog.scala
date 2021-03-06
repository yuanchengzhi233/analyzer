package com.cpc.spark.ocpcV3.ocpc.data

import com.cpc.spark.udfs.Udfs_wj.udfStringToMap
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object OcpcFilterUnionLog {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val hour = args(1).toString

    val data = getUnionlog(date, hour, spark)

//    data
//      .write.mode("overwrite").saveAsTable("test.filtered_union_log_hourly")

    data
      .repartition(100).write.mode("overwrite").insertInto("dl_cpc.filtered_union_log_hourly")
    println("successfully save data into table: dl_cpc.filtered_union_log_hourly")

    // 按需求增加需要进行抽取的数据表
    // bid
    val bidData = getBidUnionlog(data, date, hour, spark)
    bidData
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
//      .write.mode("overwrite").saveAsTable("test.filtered_union_log_bid_hourly")
      .repartition(50).write.mode("overwrite").insertInto("dl_cpc.filtered_union_log_bid_hourly")

    // 增加可供ab对比实验的数据表
    val abData = getAbUnionlog(data, date, hour, spark)
    abData
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
//      .write.mode("overwrite").saveAsTable("test.filtered_union_log_exptag_hourly")
      .repartition(50).write.mode("overwrite").insertInto("dl_cpc.filtered_union_log_exptag_hourly")
  }

  def getAbUnionlog(rawData: DataFrame, date: String, hour: String, spark: SparkSession) = {
    var selectWhere = s"(`date`='$date' and hour = '$hour')"

    // 拿到基础数据
    var sqlRequest =
      s"""
         |SELECT
         |    searchid,
         |    ideaid,
         |    unitid,
         |    planid,
         |    userid,
         |    uid,
         |    adslotid,
         |    adslot_type,
         |    adtype,
         |    adsrc,
         |    exptags,
         |    media_type,
         |    media_appsid,
         |    adclass,
         |    ocpc_log,
         |    exp_ctr,
         |    exp_cvr,
         |    bid as original_bid,
         |    price,
         |    isshow,
         |    isclick
         |FROM
         |    dl_cpc.ocpc_base_unionlog
         |WHERE
         |    $selectWhere
         |and (isshow>0 or isclick>0)
      """.stripMargin
    println(sqlRequest)
    val resultDF = spark.sql(sqlRequest).withColumn("ocpc_log_dict", udfStringToMap()(col("ocpc_log")))
    resultDF
  }

  def getBidUnionlog(rawData: DataFrame, date: String, hour: String, spark: SparkSession) = {
    val data = rawData.withColumn("ocpc_log_dict", udfStringToMap()(col("ocpc_log")))

    data.createOrReplaceTempView("base_data")
    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  ideaid,
         |  unitid,
         |  planid,
         |  userid,
         |  uid,
         |  adslotid,
         |  adslot_type,
         |  adtype,
         |  adsrc,
         |  exptags,
         |  media_type,
         |  media_appsid,
         |  bid as original_bid,
         |  (case when length(ocpc_log)>0 then cast(ocpc_log_dict['dynamicbid'] as int)
         |        else bid end) as bid,
         |  ocpc_log
         |FROM
         |  base_data
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark.sql(sqlRequest)

    resultDF
  }


  def getUnionlog(date: String, hour: String, spark: SparkSession) = {
    var selectWhere = s"(day ='$date' and hour = '$hour')"

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
         |    adslot_id as adslotid,
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
         |    0 as duration,
         |    userid,
         |    is_ocpc,
         |    ocpc_log,
         |    user_city,
         |    city_level,
         |    adclass
         |from dl_cpc.cpc_basedata_union_events
         |where $selectWhere
         |and (isshow>0 or isclick>0)
         |and (charge_type IS NULL OR charge_type = 1)
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


