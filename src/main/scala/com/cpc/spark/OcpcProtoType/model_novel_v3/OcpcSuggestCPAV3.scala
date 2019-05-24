package com.cpc.spark.OcpcProtoType.model_novel_v3

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.OcpcProtoType.suggest_cpa_v1.OcpcSuggestCPA._
import com.cpc.spark.udfs.Udfs_wj.udfStringToMap
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.IntegerType

object OcpcSuggestCPAV3 {
  def main(args: Array[String]): Unit = {
    /*
    新版推荐cpa程序：
    unitid, userid, adclass, original_conversion, conversion_goal, show, click, cvrcnt, cost, post_ctr, acp, acb, jfb, cpa, pcvr, post_cvr, pcoc, cal_bid, auc, kvalue, industry, is_recommend, ocpc_flag, usertype, pcoc1, pcoc2

    主要源表：dl_cpc.ocpc_base_unionlog, dl_cpc.ocpc_label_cvr_hourly

    数据构成分为以下部分:
    1. 基础数据部分：unitid, userid, adclass, original_conversion, conversion_goal, show, click, cvrcnt, cost, post_ctr, acp, acb, jfb, cpa, pcvr, post_cvr, pcoc, industry, usertype
    2. ocpc部分：kvalue
    3. 模型部分：auc
    4. 实时查询：ocpc_flag
    5. 历史推荐cpa数据：pcoc1, pcoc2
    6.
     */
    // 计算日期周期
    Logger.getRootLogger.setLevel(Level.WARN)
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val media = args(3).toString
    val hourInt = args(4).toInt


    val spark = SparkSession
      .builder()
      .appName(s"ocpc suggest cpa v2: $date, $hour")
      .enableHiveSupport().getOrCreate()

    //根据dl_cpc.dw_unitid_detail定义转化目标
    val baseData = getBaseData(date, hour, spark)

//    // 实时查询ocpc标记（从mysql抽取）,如广告主是ocpc广告，取广告主给定cpa
//    val ocpcFlag = getOcpcFlag(spark)
//
//    // ocpc部分：kvalue
//    val kvalue = getKvalue(version, conversionGoal, date, hour, spark)
//
//    // 模型部分
//    val aucData = getAucData(version, conversionGoal, date, hour, spark)
//
//
//    // 历史推荐cpa的pcoc数据
//    val prevData = getPrevSuggestData(version, conversionGoal, date, hour, spark)
//
//    // 数据组装
//    val result = assemblyData(baseData, kvalue, aucData, ocpcFlag, prevData, conversionGoal, spark)
//
//    val resultDF = result
//      .withColumn("cv_goal", lit(conversionGoal))
//      .withColumn("date", lit(date))
//      .withColumn("hour", lit(hour))
//      .withColumn("version", lit(version))
//
//    resultDF.show(10)
//
//    resultDF
//      .repartition(10).write.mode("overwrite").insertInto("dl_cpc.ocpc_suggest_cpa_recommend_hourly_v2")
//    println("successfully save data into table: dl_cpc.ocpc_suggest_cpa_recommend_hourly_v2")
  }

  def getBaseData(date: String, hour: String, spark: SparkSession) = {
    /*
    抽取基础表，只包括前一天趣头条上有记录的unitid和对应adclass
     */
    // 计算日期周期
    val sdf = new SimpleDateFormat("yyyy-MM-dd")
    val end_date = sdf.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(end_date)
    calendar.add(Calendar.DATE, -1)
    val start_date = calendar.getTime
    val date1 = sdf.format(start_date)
    calendar.add(Calendar.DATE, -2)
    val start_date2 = calendar.getTime
    val date2 = sdf.format(start_date2)
    val selectCondition = s"`date` between '$date2' and '$date1'"
    val selectCondition2 = s"day between '$date2' and '$date1'"

    // 点击数据
    val sqlRequest1 =
      s"""
         |SELECT
         |    searchid,
         |    unitid,
         |    adclass,
         |    price,
         |    bid,
         |    ocpc_log
         |FROM
         |    dl_cpc.ocpc_base_unionlog
         |WHERE $selectCondition
         |  AND adslot_type in (1,2,3)
         |  AND adsrc = 1
         |  and isclick = 1
         |  AND (charge_type is null or charge_type = 1)
       """.stripMargin
    println(sqlRequest1)
    val ctrData = spark.sql(sqlRequest1).withColumn("ocpc_log_dict", udfStringToMap()(col("ocpc_log")))

    // 转化
    val sqlRequest2 =
      s"""
         |select distinct a.searchid,
         |        a.conversion_target as unit_target,
         |        b.conversion_target[0] as real_target
         |from dl_cpc.dm_conversions_for_model a
         |join dl_cpc.dw_unitid_detail b
         |    on a.unitid=b.unitid and a.day = b.day
         |    and b.$selectCondition2
         |where a.$selectCondition2
       """.stripMargin
    println(sqlRequest2)
    val cvrData = spark.sql(sqlRequest2)
      .withColumn("iscvr",matchcvr(col("unit_target"),col("real_target")))
      .filter("iscvr = 1")

    // 数据关联
    val data = ctrData
      .join(cvrData, Seq("searchid"), "left_outer")

    data.createOrReplaceTempView("base_data")
    val sqlRequest =
      s"""
         |SELECT
         |    searchid,
         |    unitid,
         |    adclass,
         |    price,
         |    bid as original_bid,
         |    ocpc_log,
         |    iscvr,
         |    (case when length(ocpc_log) > 0 then cast(ocpc_log_dict['dynamicbid'] as double)
         |          else cast(bid as double) end) as real_bid
         |FROM
         |    base_data
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark.sql(sqlRequest)
      .withColumn("new_adclass", (col("adclass")/1000).cast(IntegerType))
      .groupBy("unitid", "new_adclass")
      .agg(
        sum(col("price")).alias("cost"),
        sum(col("iscvr")).alias("cvrcnt"),
        avg(col("real_bid")).alias("qtt_avgbid"))
      .withColumn("qttcpa",col("cost")/col("iscvr"))

    resultDF.show(10)
    resultDF
  }

  def matchcvr = udf {
    (unit_target: Array[String],real_target:String) => {
      var flag = 0
      if (unit_target contains(real_target))  {
        1
      }
      flag
    }
  }


  def getOcpcFlag(spark: SparkSession) = {
    val url = "jdbc:mysql://rr-2zehhy0xn8833n2u5.mysql.rds.aliyuncs.com:3306/adv?useUnicode=true&characterEncoding=utf-8"
    val user = "adv_live_read"
    val passwd = "seJzIPUc7xU"
    val driver = "com.mysql.jdbc.Driver"
    val table = "(select id, user_id, ideas, bid, ocpc_bid, ocpc_bid_update_time, cast(conversion_goal as char) as conversion_goal, status, is_ocpc from adv.unit where is_ocpc=1 and ideas is not null) as tmp"

    val data = spark.read.format("jdbc")
      .option("url", url)
      .option("driver", driver)
      .option("user", user)
      .option("password", passwd)
      .option("dbtable", table)
      .load()

    val base = data
      .withColumn("unitid", col("id"))
      .withColumn("userid", col("user_id"))
      .selectExpr("unitid", "is_ocpc", "cast(conversion_goal as int) conversion_goal")

    base.createOrReplaceTempView("base_data")
    val sqlRequest =
      s"""
         |SELECT
         |  unitid,
         |  is_ocpc
         |FROM
         |  base_data
         |WHERE
         |  conversion_goal = $conversionGoal
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark.sql(sqlRequest)

    resultDF.show(10)
    resultDF
  }
}
