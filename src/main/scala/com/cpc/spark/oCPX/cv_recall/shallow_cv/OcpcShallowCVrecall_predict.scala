package com.cpc.spark.oCPX.cv_recall.shallow_cv

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.oCPX.OcpcTools.getTimeRangeSqlDate
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object OcpcShallowCVrecall_predict {
  def main(args: Array[String]): Unit = {
    // 计算日期周期
    val date = args(0).toString
    println("parameters:")
    println(s"date=$date")

    // spark app name
    val spark = SparkSession.builder().appName(s"OcpcShallowCVrecall_predict: $date").enableHiveSupport().getOrCreate()

  }

  def cvRecallPredict(date: String, hourInt: Int, spark: SparkSession) = {
    val cvData = calculateCV(date, hourInt, spark)

    var data = calculateRecallValue(cvData, 1, hourInt, spark)

    for (startHour <- 2 to 24) {
      val singleData = calculateRecallValue(cvData, startHour, hourInt, spark)
      data = data.union(singleData)
    }

    data
  }

  def calculateRecallValue(baseData: DataFrame, startHour: Int, hourInt: Int, spark: SparkSession) = {
    val endHour = startHour + hourInt
    val data = baseData.filter(s"click_hour_diff >= $startHour and click_hour_diff < $endHour")

    val totalCV = data
      .groupBy("unitid", "userid", "conversion_goal")
      .agg(sum(col("cv")).alias("total_cv"))
      .select("unitid", "userid", "conversion_goal", "total_cv")

    val clickCV = data
      .filter(s"cv_hour_diff >= $startHour and cv_hour_diff < $endHour")
      .groupBy("unitid", "userid", "conversion_goal")
      .agg(sum(col("cv")).alias("cv"))
      .select("unitid", "userid", "conversion_goal", "cv")

    val resultData = totalCV
      .join(clickCV, Seq("unitid", "userid", "conversion_goal"), "inner")
      .select("unitid", "userid", "conversion_goal", "total_cv", "cv")
      .withColumn("hour_diff", lit(startHour))

    resultData
  }

  def calculateCV(date: String, hourInt: Int, spark: SparkSession) = {
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")
    val newDate = date + " " + "00"
    val today = dateConverter.parse(newDate)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.HOUR, -hourInt)
    val yesterday = calendar.getTime
    val tmpDate = dateConverter.format(yesterday)
    val tmpDateValue = tmpDate.split(" ")
    val date1 = tmpDateValue(0)
    val hour1 = tmpDateValue(1)
    val selectCondition = getTimeRangeSqlDate(date1, hour1, date, "23")

    val sqlRequest1 =
      s"""
         |SELECT
         |    searchid,
         |    unitid,
         |    userid,
         |    conversion_goal,
         |    conversion_from,
         |    date as click_date,
         |    hour as click_hour
         |FROM
         |    dl_cpc.ocpc_base_unionlog
         |WHERE
         |    $selectCondition
         |AND
         |    is_ocpc = 1
         |AND
         |    conversion_goal in (2, 5)
         |AND
         |    isclick = 1
         |""".stripMargin
    println(sqlRequest1)
    val clickData = spark.sql(sqlRequest1)

    val sqlRequest2 =
      s"""
         |SELECT
         |    searchid,
         |    conversion_goal,
         |    conversion_from,
         |    date,
         |    hour,
         |    1 as iscvr,
         |    row_number() over(partition by searchid, conversion_goal, conversion_from order by date, hour) as seq
         |FROM
         |    dl_cpc.ocpc_cvr_log_hourly
         |WHERE
         |    date >= '$date1'
         |""".stripMargin
    println(sqlRequest2)
    val cvData = spark
      .sql(sqlRequest2)
      .filter(s"seq = 1")
      .withColumn("cv_date", col("date"))
      .withColumn("cv_hour", col("hour"))
      .select("searchid", "conversion_goal", "conversion_from", "cv_date", "cv_hour")

    val baseData = clickData
      .join(cvData, Seq("searchid", "conversion_goal", "conversion_from"), "inner")
      .select("searchid", "unitid", "userid", "conversion_goal", "conversion_from", "click_date", "click_hour", "cv_date", "cv_hour")
      .withColumn("click_hour_diff", udfCalculateHourDiff(date1, hour1)(col("click_date"), col("click_hour")))
      .withColumn("cv_hour_diff", udfCalculateHourDiff(date1, hour1)(col("cv_date"), col("cv_hour")))

    baseData.createOrReplaceTempView("base_data")

    val sqlRequest3 =
      s"""
         |SELECT
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  click_hour_diff,
         |  cv_hour_diff,
         |  count(distinct searchid) as cv
         |FROM
         |  base_data
         |GROUP BY unitid, userid, conversion_goal, click_hour_diff, cv_hour_diff
         |""".stripMargin
    println(sqlRequest3)
    val data = spark.sql(sqlRequest3).cache()

    data.show(10)

    data
  }

  def udfCalculateHourDiff(date: String, hour: String) = udf((date1: String, hour1: String) => {
    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")

    val nowTime = dateConverter.parse(date1 + " " + hour1)
    val ocpcTime = dateConverter.parse(date + " " + hour)
    val hourDiff = (nowTime.getTime() - ocpcTime.getTime()) / (1000 * 60 * 60)

    hourDiff
  })


}