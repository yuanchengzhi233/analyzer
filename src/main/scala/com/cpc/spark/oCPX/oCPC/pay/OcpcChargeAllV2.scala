package com.cpc.spark.oCPX.oCPC.pay

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.oCPX.OcpcTools.{mapMediaName, udfConcatStringInt, udfDetermineIndustry}
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

@deprecated
object OcpcChargeAllV2 {
  def main(args: Array[String]): Unit = {
    /*
    按照七天周期计算赔付数据
    1. 计算当天所有单元的点击、消费、转化、平均cpagiven、平均cpareal、赔付金额
    2. 获取这批单元在赔付周期中的起始时间
    3. 如果当前为周期第一天，则重新落表，否则，叠加上前一天的历史数据
    4. 数据落表，需包括周期编号，是否周期第一天
     */
    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val version = args(1).toString
    val dayCnt = args(2).toInt

    // 计算当天数据
    val baseData = getBaseData(date, spark)
    baseData
      .repartition(10)
      .write.mode("overwrite").saveAsTable("test.ocpc_pay_data20191010a")

    // 判断这批单元的赔付周期
    // 根据日期，获得一张表
    // 标记每个单元，当前已经进行过几轮赔付，标记是否需要继续赔付，当前赔付周期的起始日期, ocpc_charge_time
    val paySchedule = getPaySchedule(date, dayCnt, spark)
    paySchedule
      .repartition(10)
      .write.mode("overwrite").saveAsTable("test.ocpc_pay_data20191010b")

    // 如果当前为周期第一天，则重新落表，否则，叠加上前一天的历史数
    // 获取前一天数据
    val prevData = getPrevData(date, spark)
    // 根据date是否等于pay_date来判断是否重新落表
    val finalPayData = updatePay(prevData, paySchedule, baseData, date, spark)

    // 数据落表
    // 赔付周期控制表
    val finalPaySchedule = paySchedule
      .withColumn("update_flag", when(col("pay_date") > date, 0).otherwise(1))
      .withColumn("pay_cnt", when(col("update_flag") === 1 && col("flag") === 1, col("pay_cnt") + 1).otherwise(col("pay_cnt")))
      .withColumn("pay_date", when(col("update_flag") === 1 && col("flag") === 1, col("update_date")).otherwise(col("pay_date")))
      .select("unitid", "pay_cnt", "pay_date", "ocpc_charge_time", "update_flag", "update_date", "flag")

    finalPaySchedule
//      .select("unitid", "pay_cnt", "pay_date", "ocpc_charge_time")
      .withColumn("date", lit(date))
      .repartition(1)
      .write.mode("overwrite").saveAsTable("test.ocpc_pay_data20191010c")

    // 赔付数据落表
    finalPayData
      .withColumn("date", lit(date))
      .repartition(1)
      .write.mode("overwrite").saveAsTable("test.ocpc_pay_data20191010d")

  }

  def updatePay(prevData: DataFrame, paySchedule: DataFrame, baseData: DataFrame, date: String, spark: SparkSession) = {
    val flagData1 = paySchedule
      .filter("flag = 1")
      .filter(s"pay_date > date")
      .select("unitid")
      .distinct()

    val flagData2 = paySchedule
      .filter(s"flag = 1")
      .select("unitid")
      .distinct()

    val prevResult = prevData.join(flagData1, Seq("unitid"), "inner")
    val baseResult = baseData.join(flagData2, Seq("unitid"), "inner")
    val rawData = prevResult.union(baseResult)
    rawData.createOrReplaceTempView("raw_data")

    val sqlRequest1 =
      s"""
         |SELECT
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  sum(click) as click,
         |  sum(cv) as cv,
         |  sum(cost) as cost,
         |  sum(cpagiven * click) * 1.0 / sum(click) as cpagiven
         |FROM
         |  raw_data
         |GROUP BY unitid, userid, conversion_goal
         |""".stripMargin
    println(sqlRequest1)
    val data = spark
      .sql(sqlRequest1)
      .na.fill(0, Seq("cv", "click", "cost", "cpagiven"))

    data
  }

  def getPrevData(date: String, spark: SparkSession) = {
    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val today = dateConverter.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -1)
    val yesterday = calendar.getTime
    val date1 = dateConverter.format(yesterday)

    val sqlRequest =
      s"""
         |SELECT
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  click,
         |  cv,
         |  cost,
         |  cpagiven,
         |  pay_cnt,
         |  pay_date
         |FROM
         |  test.ocpc_pay_data_daily_v2
         |WHERE
         |  `date` = '$date1'
         |""".stripMargin
    println(sqlRequest)
    val result = spark.sql(sqlRequest)

    result
  }

  def getPaySchedule(date: String, dayCnt: Int, spark: SparkSession) = {
    // 获取老版的单元周期数据
    val prevData = spark
      .table("dl_cpc.ocpc_pay_cnt_daily")
      .where(s"`date` = '2019-10-09'")

    // 抽取媒体id，获取当天的数据
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias.total.media_selection"
    val mediaSelection = conf.getString(conf_key)

    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  timestamp,
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  adslot_type,
         |  media_appsid,
         |  adclass,
         |  cast(ocpc_log_dict['IsHiddenOcpc'] as int) as is_hidden
         |FROM
         |  dl_cpc.ocpc_filter_unionlog
         |WHERE
         |  `date` = '$date'
         |AND
         |  $mediaSelection
         |AND
         |  is_ocpc = 1
         |AND
         |  isclick = 1
       """.stripMargin
    println(sqlRequest)
    val rawData1 = spark
      .sql(sqlRequest)
      .filter(s"is_hidden = 0")

    val rawData2 = mapMediaName(rawData1, spark)

    val rawData = rawData2
      .filter(s"media in ('qtt', 'hottopic', 'novel')")
      .withColumn("industry", udfDetermineIndustry()(col("adslot_type"), col("adclass")))
      .filter(s"industry in ('feedapp', 'elds')")
      .select("searchid", "unitid", "timestamp")

    rawData.createOrReplaceTempView("raw_data")

    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  unitid,
         |  timestamp,
         |  from_unixtime(timestamp,'YYYY-MM-dd HH:mm:ss') as ocpc_charge_time,
         |  row_number() over(partition by unitid order by timestamp) as seq
         |FROM
         |  raw_data
       """.stripMargin
    println(sqlRequest2)

    val firstRecord = spark
      .sql(sqlRequest2)
      .filter(s"seq = 1")
      .select("unitid", "ocpc_charge_time")
      .distinct()

    val ocpcChargeDate = date + " 00:00:00"

    // 数据union
    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val today = dateConverter.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.DATE, +dayCnt)
    val tomorrow = calendar.getTime
    val nextPayDate = dateConverter.format(tomorrow)

    val data = prevData
      .join(firstRecord, Seq("unitid"), "outer")
      .select("unitid", "pay_cnt", "pay_date", "ocpc_charge_time")
      .na.fill(0, Seq("pay_cnt"))
      .na.fill(date, Seq("pay_date"))
      .filter(s"pay_date <= '$date'")
      .withColumn("pay_cnt", when(col("pay_cnt") < 5, col("pay_cnt") + 1).otherwise(col("pay_cnt")))
      .withColumn("flag", when(col("pay_cnt") < 5, 1).otherwise(0))
      .na.fill(ocpcChargeDate, Seq("ocpc_charge_time"))
      .withColumn("ocpc_charge_time", udfSetOcpcChargeTime(ocpcChargeDate)(col("pay_cnt"), col("ocpc_charge_time")))


    data.printSchema()
    data
  }


  def udfSetOcpcChargeTime(ocpcChargeDate: String) = udf((prevPayCnt: Int, ocpcChargeTime: String) => {
    val result = prevPayCnt match {
      case 1 => ocpcChargeTime
      case _ => ocpcChargeDate
    }
    result
  })


  def getBaseData(date: String, spark: SparkSession) = {
    // 抽取媒体id
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias.total.media_selection"
    val mediaSelection = conf.getString(conf_key)

    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  isclick,
         |  price,
         |  (case
         |      when (cast(adclass as string) like '134%' or cast(adclass as string) like '107%') then "elds"
         |      when (adslot_type<>7 and cast(adclass as string) like '100%') then "feedapp"
         |      when (adslot_type=7 and cast(adclass as string) like '100%') then "yysc"
         |      when adclass in (110110100, 125100100) then "wzcp"
         |      else "others"
         |  end) as industry,
         |  cast(ocpc_log_dict['cpagiven'] as double) as cpagiven,
         |  cast(ocpc_log_dict['IsHiddenOcpc'] as int) as is_hidden
         |FROM
         |  dl_cpc.ocpc_filter_unionlog
         |WHERE
         |  `date` = '$date'
         |AND
         |  $mediaSelection
         |AND
         |  is_ocpc = 1
         |AND
         |  isclick = 1
       """.stripMargin
    println(sqlRequest)
    val clickData = spark
      .sql(sqlRequest)
      .withColumn("cvr_goal", udfConcatStringInt("cvr")(col("conversion_goal")))
      .filter(s"is_hidden = 0")

    // 抽取cv数据
    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  label as iscvr,
         |  cvr_goal
         |FROM
         |  dl_cpc.ocpc_label_cvr_hourly
         |WHERE
         |  `date` >= '$date'
       """.stripMargin
    println(sqlRequest2)
    val cvData = spark.sql(sqlRequest2).distinct()

    // 数据关联
    val result = clickData
      .join(cvData, Seq("searchid", "cvr_goal"), "left_outer")
      .na.fill(0, Seq("iscvr"))
    result.createOrReplaceTempView("base_table")

    val sqlRequest3 =
      s"""
         |SELECT
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  sum(isclick) as click,
         |  sum(iscvr) as cv,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 as cost,
         |  sum(case when isclick=1 then cpagiven else 0 end) * 1.0 / sum(isclick) as cpagiven
         |FROM
         |  base_table
         |GROUP BY unitid, userid, conversion_goal
         |""".stripMargin
    println(sqlRequest3)
    val resultDF = spark.sql(sqlRequest3)

    resultDF
  }



}
