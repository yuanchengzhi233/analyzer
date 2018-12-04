package com.cpc.spark.ocpcV3.ocpcNovel.report

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object OcpcHourlyReport {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val hour = args(1).toString

    val result = getHourlyReport(date, hour, spark)
    result.show(10)
  }

  def getHourlyReport(date: String, hour: String, spark: SparkSession) = {
    // 获得基础数据
    val selectCondition = s"`date`='$date' and `hour`<='$hour'"
    val sqlRequest1 =
      s"""
         |SELECT
         |    searchid,
         |    unitid,
         |    userid,
         |    price,
         |    ocpc_log_dict['kvalue'] as kvalue,
         |    ocpc_log_dict['cpahistory'] as cpahistory,
         |    ocpc_log_dict['cpagiven'] as cpagiven,
         |    ocpc_log_dict['dynamicbid'] as bid,
         |    ocpc_log_dict['ocpcstep'] as ocpc_step,
         |    ocpc_log_dict['conversiongoal'] as conversion_goal,
         |    isshow,
         |    isclick,
         |    hour
         |FROM
         |    dl_cpc.ocpcv3_unionlog_label_hourly
         |WHERE
         |    $selectCondition
       """.stripMargin
    println(sqlRequest1)
    val rawData = spark.sql(sqlRequest1)

    val sqlRequest2 =
      s"""
         |SELECT
         |    searchid,
         |    label2 as iscvr1
         |FROM
         |    dl_cpc.ml_cvr_feature_v1
         |WHERE
         |    $selectCondition
         |AND
         |    label2=1
       """.stripMargin
    println(sqlRequest2)
    val cvr1Data = spark.sql(sqlRequest2).distinct()

    val sqlRequest3 =
      s"""
         |SELECT
         |    searchid,
         |    label as iscvr2
         |FROM
         |    dl_cpc.ml_cvr_feature_v2
         |WHERE
         |    $selectCondition
         |AND
         |    label=1
       """.stripMargin
    println(sqlRequest3)
    val cvr2Data = spark.sql(sqlRequest3).distinct()

    // 关联数据
    val data = rawData
      .join(cvr1Data, Seq("searchid"), "left_outer")
      .join(cvr2Data, Seq("searchid"), "left_outer")
    data.createOrReplaceTempView("data_table")

    // 计算指标
    val sqlRequest4 =
      s"""
         |SELECT
         |    unitid,
         |    userid,
         |    conversion_goal,
         |    sum(case when ocpc_step==2 then isclick else 0 end) * 1.0 / sum(isclick) as step2_percent,
         |    SUM(case when isclick==1 then cpagiven else 0 end) * 1.0 / sum(isclick) as cpa_given,
         |    SUM(case when isclick==1 then price else 0 end) as cost,
         |    SUM(isshow) as show_cnt,
         |    SUM(isclick) as ctr_cnt,
         |    SUM(iscvr1) as cvr1_cnt,
         |    SUM(iscvr2) as cvr2_cnt,
         |    sum(case when isclick=1 then kvalue else 0 end) * 1.0 / sum(isclick) as avg_k,
         |    SUM(case when isclick=1 and `hour`='$hour' then kvalue else 0 end) * 1.0 / sum(case when `hour`='$hour' then isclick else 0 end) as recent_k
         |FROM
         |    data_table
         |GROUP BY unitid, userid, conversion_goal
       """.stripMargin
    println(sqlRequest4)
    val result = spark
      .sql(sqlRequest4)
      .withColumn("cvr_cnt", when(col("conversion_goal")===1, col("cvr1_cnt")).otherwise(col("cvr2_cnt")))
      .withColumn("cpa_real", col("cost") * 1.0 / col("cvr_cnt"))
    result.show(10)

    val resultDF = result.select("unitid", "userid", "conversion_goal", "step2_percent", "cpa_given", "cpa_real", "show_cnt", "ctr_cnt", "cvr_cnt", "avg_k", "recent_k", "cost")

    resultDF
  }

}