package com.cpc.spark.ecpc.v1

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.ocpc.OcpcUtils.getTimeRangeSql2
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

object eCPCforElds {
  def main(args: Array[String]): Unit = {
    /*
    计算新版的cvr平滑策略：
    1. 抽取基础数据
    2. 计算该维度下pcoc与计费比、后验cvr等等指标
    3. 计算该维度下根据给定highBidFactor计算出的lowBidFactor
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val media = args(3).toString
    val hourInt = args(4).toInt
    val highBidFactor = args(5).toDouble

    println("parameters:")
    println(s"date=$date, hour=$hour, version:$version, media:$media, hourInt:$hourInt")

    // 抽取基础数据
    val rawData = getBaseData(media, hourInt, date, hour, spark)
    val baseData = rawData
      .withColumn("adtype", udfAdtypeMap()(col("adtype")))
      .withColumn("slottype", udfSlottypeMap()(col("slottype")))
      .select("searchid", "adclass", "adtype", "slottype", "slotid", "bid", "price", "exp_cvr", "isshow", "isclick", "iscvr")

    // 计算各维度下的pcoc、jfb以及后验cvr等指标
    val data1 = calculateData1(baseData, date, hour, spark)

    // 计算该维度下根据给定highBidFactor计算出的lowBidFactor
    val baseData2 = baseData
      .join(data1, Seq("adclass", "adtype", "slottype", "slotid"), "inner")

    val data2 = calculateData2(baseData2, highBidFactor, date, hour, spark)


  }

  def udfSlottypeMap() = udf((slottype: Int) => {
    /*
    tag name log as
    adslottype 列表页 1 1
    adslottype 详情页 2 2
    adslottype 互动页 3 3
    adslottype 开屏 4 4
    adslottype 横幅 5 14
    adslottype 视频 6 12
    adslottype 激励 7 9
     */
    val result = slottype match {
      case 1 => 1
      case 2 => 2
      case 3 => 3
      case 4 => 4
      case 5 => 14
      case 6 => 12
      case 7 => 9
      case _ => 0
    }
    result
  })

  def udfAdtypeMap() = udf((adtype: Int) => {
    /*
    adtype 文本 1 1
    adtype 大图 2 2
    adtype 图文 3 5
    adtype 组图 4 8
    adtype 互动 5 9
    adtype 开屏 6 10
    adtype 横幅 7 11
    adtype 横版视频 8 4
    adtype 激励 9 12
    adtype 竖版视频 10 13
     */
    val result = adtype match {
      case 1 => 1
      case 2 => 2
      case 3 => 5
      case 4 => 8
      case 5 => 9
      case 6 => 10
      case 7 => 11
      case 8 => 4
      case 9 => 12
      case 10 => 13
      case _ => 0
    }
    result
  })

  def calculateData2(baseData: DataFrame, highBidFactor: Double, date: String, hour: String, spark: SparkSession) = {
    /*
    int64 adclass = 1;
    int64 adtype = 2;
    int64 slottype = 3;
    string slotid = 4;
    double post_cvr = 5;
    double calCvrFactor = 6;
    double highBidFactor = 7;
    double lowBidFactor = 8;
     */
    baseData.createOrReplaceTempView("base_data")
    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  adclass,
         |  adtype,
         |  slottype,
         |  slotid,
         |  bid,
         |  price,
         |  exp_cvr,
         |  isclick,
         |  isshow,
         |  exp_cvr * 1.0 / (jfb * pcoc) as pcvr,
         |  post_cvr
         |FROM
         |  base_data
       """.stripMargin
    println(sqlRequest)
    val rawData = spark
      .sql(sqlRequest)
      .withColumn("pcvr_group", when(col("pcvr") >= col("post_cvr"), "high").otherwise("low"))

    rawData.createOrReplaceTempView("raw_data")
    val sqlRequest1 =
      s"""
         |SELECT
         |  adclass,
         |  adtype,
         |  slottype,
         |  slotid,
         |  sum(isclick) as click,
         |  sum(case when isclick=1 then pcvr else 0 end) * 1.0 / sum(isclick) as pre_cvr
         |FROM
         |  raw_data
         |GROUP BY adclass, adtype, slottype, slotid
       """.stripMargin
    println(sqlRequest1)
    val data1 = spark
      .sql(sqlRequest1)
      .withColumn("calc_total", col("pre_cvr") * col("click"))
      .select("adclass", "adtype", "slottype", "slotid")

    val sqlRequest2 =
      s"""
         |SELECT
         |  adclass,
         |  adtype,
         |  slottype,
         |  slotid,
         |  sum(isclick) as click,
         |  sum(case when isclick=1 then pcvr else 0 end) * 1.0 / sum(isclick) as pre_cvr
         |FROM
         |  raw_data
         |WHERE
         |  pcvr_group = "high"
         |GROUP BY unitid, ideaid, slotid, slottype, adtype
       """.stripMargin
    println(sqlRequest2)
    val data2 = spark
      .sql(sqlRequest2)
      .withColumn("calc_high", col("pre_cvr") * col("click") * highBidFactor)
      .select("unitid", "ideaid", "slotid", "slottype", "adtype", "calc_high")

    val sqlRequest3 =
      s"""
         |SELECT
         |  unitid,
         |  ideaid,
         |  slotid,
         |  slottype,
         |  adtype,
         |  sum(isclick) as click,
         |  sum(case when isclick=1 then pcvr else 0 end) * 1.0 / sum(isclick) as pre_cvr
         |FROM
         |  raw_data
         |WHERE
         |  pcvr_group = "low"
         |GROUP BY unitid, ideaid, slotid, slottype, adtype
       """.stripMargin
    println(sqlRequest3)
    val data3 = spark
      .sql(sqlRequest3)
      .withColumn("calc_low", col("pre_cvr") * col("click"))
      .select("unitid", "ideaid", "slotid", "slottype", "adtype", "calc_low")

    val data = data1
      .join(data2, Seq("unitid", "ideaid", "slotid", "slottype", "adtype"), "inner")
      .join(data3, Seq("unitid", "ideaid", "slotid", "slottype", "adtype"), "inner")
      .select("unitid", "ideaid", "slotid", "slottype", "adtype", "calc_total", "calc_high", "calc_low")

    data.createOrReplaceTempView("data")
    val sqlRequestFinal =
      s"""
         |SELECT
         |  unitid,
         |  ideaid,
         |  slotid,
         |  slottype,
         |  adtype,
         |  calc_total,
         |  calc_high,
         |  calc_low,
         |  (calc_total - calc_high) * 1.0 / calc_low as low_bid_factor
         |FROM
         |  data
       """.stripMargin
    println(sqlRequestFinal)
    val dataFinal = spark
      .sql(sqlRequestFinal)
      .withColumn("low_bid_factor", when(col("low_bid_factor") <= 0.3, 0.3).otherwise(col("low_bid_factor")))

    dataFinal
  }

  def calculateData1(baseData: DataFrame, date: String, hour: String, spark: SparkSession) = {
    /*
    int64 adclass = 1;
    int64 adtype = 2;
    int64 slottype = 3;
    string slotid = 4;
    double post_cvr = 5;
    double calCvrFactor = 6;
    double highBidFactor = 7;
    double lowBidFactor = 8;
     */
    baseData.createOrReplaceTempView("base_data")

    val sqlRequest =
      s"""
         |SELECT
         |  adclass,
         |  adtype,
         |  slottype,
         |  slotid,
         |  sum(iscvr) * 1.0 / sum(isclick) as post_cvr,
         |  sum(case when isclick=1 then exp_cvr else 0 end) * 1.0 / sum(isclick) as pre_cvr,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(isclick) as acp,
         |  sum(case when isclick=1 then bid else 0 end) * 1.0 / sum(isclick) as acb,
         |  sum(isclick) as click
         |FROM
         |  base_data
         |GROUP BY adclass, adtype, slottype, slotid
       """.stripMargin
    println(sqlRequest)
    val data = spark
      .sql(sqlRequest)
      .withColumn("pcoc", col("pre_cvr") * 1.0 / col("post_cvr"))
      .withColumn("jfb", col("acp") * 1.0 / col("acb"))
      .select("adclass", "adtype", "slottype", "slotid", "post_cvr", "pre_cvr", "acp", "acb", "pcoc", "jfb", "click")

    data
  }

  def getBaseData(media: String, hourInt: Int, date: String, hour: String, spark: SparkSession) = {
    // 抽取媒体id
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias." + media + ".media_selection"
    val mediaSelection = conf.getString(conf_key)

    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")
    val newDate = date + " " + hour
    val today = dateConverter.parse(newDate)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.HOUR, -hourInt)
    val yesterday = calendar.getTime
    val tmpDate = dateConverter.format(yesterday)
    val tmpDateValue = tmpDate.split(" ")
    val date1 = tmpDateValue(0)
    val hour1 = tmpDateValue(1)
    val selectCondition = getTimeRangeSql2(date1, hour1, date, hour)

    val sqlRequest1 =
      s"""
         |SELECT
         |  searchid,
         |  adclass,
         |  adtype,
         |  adslot_type as slottype,
         |  adslotid as slotid,
         |  bid_discounted_by_ad_slot as bid,
         |  price,
         |  exp_cvr,
         |  isshow,
         |  isclick
         |FROM
         |  dl_cpc.ocpc_base_unionlog
         |WHERE
         |  $selectCondition
         |AND
         |  $mediaSelection
         |AND
         |  price <= bid_discounted_by_ad_slot
         |AND
         |  is_ocpc = 0
       """.stripMargin
    println(sqlRequest1)
    val clickData = spark.sql(sqlRequest1)

    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  label as iscvr
         |FROM
         |  dl_cpc.ocpc_label_cvr_hourly
         |WHERE
         |  `date` >= '$date1'
         |AND
         |  cvr_goal = 'cvr3'
       """.stripMargin
    val cvrData = spark.sql(sqlRequest2)

    val data = clickData
      .join(cvrData, Seq("searchid"), "left_outer")
      .na.fill(0, Seq("iscvr"))

    data
  }


}
