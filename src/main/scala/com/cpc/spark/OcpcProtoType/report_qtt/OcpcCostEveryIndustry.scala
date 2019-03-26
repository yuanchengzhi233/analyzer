package com.cpc.spark.OcpcProtoType.report_qtt

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object OcpcCostEveryIndustry {
  def main(args: Array[String]): Unit = {
    val date = args(0).toString
    val spark = SparkSession.builder().appName("OcpcCostEveryIndustry").enableHiveSupport().getOrCreate()
    getOcpcCost(date, spark)
    println("--------success----------")
  }

  def getOcpcCost(date: String, spark: SparkSession): Unit ={
    val sql =
      s"""
        |select
        |    `date`,
        |    (case when (cast(adclass as string) like "134%" or cast(adclass as string) like "107%") then 'elds'
        |          when cast(adclass as string) like "100%" then 'app'
        |          when adclass in (110110100, 125100100) then 'wzcp'
        |          else 'others' end) as industry,
        |    sum(isclick) as click,
        |    sum(isshow) as show,
        |    sum(case when isclick = 1 and is_ocpc = 1 then price else 0 end) * 0.01 as ocpc_cost,
        |    sum(case when isclick = 1 then price else 0 end) * 0.01 as all_cost,
        |    round(sum(case when isclick = 1 and is_ocpc = 1 then price else 0 end)
        |      / sum(case when isclick = 1 then price else 0 end), 3) as ratio
        |from
        |    dl_cpc.ocpc_base_unionlog
        |where
        |    `date` = '$date'
        |and
        |    media_appsid  in ("80000001", "80000002")
        |and
        |    isshow = 1
        |and
        |    antispam = 0
        |and
        |    adsrc = 1
        |group by
        |    `date`,
        |    (case when (cast(adclass as string) like "134%" or cast(adclass as string) like "107%") then 'elds'
        |          when cast(adclass as string) like "100%" then 'app'
        |          when adclass in (110110100, 125100100) then 'wzcp'
        |          else 'others' end)
        |order by
        |    (case when (cast(adclass as string) like "134%" or cast(adclass as string) like "107%") then 'elds'
        |          when cast(adclass as string) like "100%" then 'app'
        |          when adclass in (110110100, 125100100) then 'wzcp'
        |          else 'others' end)
      """.stripMargin
    val dataDF = spark.sql(sql)
    dataDF
      .withColumn("date", lit(date))
      .withColumn("version", lit("qtt_demo"))
      .repartition(2)
      .write.mode("overwrite").insertInto("dl_cpc.ocpc_cost_every_industry_data")
  }
}
