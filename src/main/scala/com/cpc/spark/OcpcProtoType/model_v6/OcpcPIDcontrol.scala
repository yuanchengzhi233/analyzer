package com.cpc.spark.OcpcProtoType.model_v6

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.OcpcProtoType.OcpcTools._
import com.cpc.spark.OcpcProtoType.model_v6.OcpcSmoothFactor.OcpcSmoothFactorMain
//import com.cpc.spark.ocpc.OcpcUtils.getTimeRangeSql2
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

object OcpcPIDcontrol {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val media = args(3).toString
    val sampleHour = args(4).toInt
    val minCV = args(5).toInt
    val kp = args(6).toDouble
    val ki = args(7).toDouble
    val kd = args(8).toDouble
    val expTag = args(9).toString
    val hourInt = args(10).toInt
    val isHidden = 0


    println("parameters:")
    println(s"date=$date, hour=$hour, media=$media, version=$version, sampleHour=$sampleHour, minCV=$minCV, kp=$kp, ki=$ki, kd=$kd, exptag=$expTag")

    val baseData = getBaseData(media, sampleHour, date, hour, spark)
    val errorData = calculateError(baseData, date, hour, spark)
    val prevError = getPrevData(sampleHour, expTag, isHidden, version, date, hour, spark)

    val data = errorData
      .join(prevError, Seq("unitid", "conversion_goal"), "left_outer")
      .select("unitid", "conversion_goal", "current_error", "prev_error", "last_error", "online_cali", "prev_cali", "cv")
      .na.fill(1.0, Seq("prev_cali"))
      .na.fill(0.0, Seq("prev_error", "last_error"))

    val pidData = calculatePID(data, kp, ki, kd, minCV, date, hour, spark)

    val pidResult = pidData
      .select("unitid", "current_error", "prev_error", "last_error", "kp", "ki", "kd", "increment_value", "current_calivalue", "conversion_goal")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("is_hidden", lit(isHidden))
      .withColumn("exp_tag", lit(expTag))
      .withColumn("version", lit(version))
      .cache()

    pidResult
//      .repartition(5).write.mode("overwrite").insertInto("test.ocpc_pid_cali_data_hourly")
      .repartition(5).write.mode("overwrite").insertInto("dl_cpc.ocpc_pid_cali_data_hourly")

    val cvrData = OcpcSmoothFactorMain(date, hour, version, media, hourInt, spark)
    val otherData = cvrData
      .select("identifier", "conversion_goal", "click", "cv", "pre_cvr", "total_price", "total_bid", "hour_cnt")
      .withColumn("jfb", col("total_price") * 1.0 / col("total_bid"))
      .withColumn("post_cvr", col("cv") * 1.0 / col("click"))
      .selectExpr("cast(identifier as int) unitid", "conversion_goal", "jfb", "post_cvr")
      .cache()
    otherData.show(10)

//    identifier,
//    conversion_goal,
//    is_hidden,
//    exp_tag,
//    1.0 / pcoc as cali_value,
//    1.0 / jfb as jfb_factor,
//    post_cvr,
//    high_bid_factor,
//    low_bid_factor,
//    cpagiven

    val result = otherData
      .join(pidResult, Seq("unitid", "conversion_goal"), "left_outer")
      .selectExpr("cast(unitid as string) identifier", "conversion_goal", "jfb", "post_cvr", "current_calivalue")
      .na.fill(1.0, Seq("current_calivalue"))
      .withColumn("pcoc", udfGetCountDown()(col("current_calivalue")))
      .withColumn("high_bid_factor", lit(1.0))
      .withColumn("low_bid_factor", lit(1.0))
      .withColumn("cpagiven", lit(1.0))
      .select("identifier", "conversion_goal", "pcoc", "jfb", "post_cvr", "high_bid_factor", "low_bid_factor", "cpagiven")
      .withColumn("is_hidden", lit(isHidden))
      .withColumn("exp_tag", lit(expTag))
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(version))
      .select("identifier", "pcoc", "jfb", "post_cvr", "high_bid_factor", "low_bid_factor", "cpagiven", "is_hidden", "exp_tag", "conversion_goal", "date", "hour", "version")
      .repartition(5)
//      .write.mode("overwrite").insertInto("test.ocpc_param_calibration_hourly_v2")
      .write.mode("overwrite").insertInto("dl_cpc.ocpc_param_calibration_hourly_v2")



    println("successfully save data into hive")


  }

  def udfGetCountDown() = udf((value: Double) => {
    val result = 1.0 / value
    result
  })

  def calculatePID(baseData: DataFrame, kp: Double, ki: Double, kd: Double, minCV: Int, date: String, hour: String, spark: SparkSession) = {
    val result = baseData
      .na.fill(0, Seq("current_error", "prev_error", "last_error"))
      .withColumn("increment_value", udfCalculatePID(kp, ki, kd)(col("current_error"), col("prev_error"), col("last_error")))
      .withColumn("kp", lit(kp))
      .withColumn("ki", lit(ki))
      .withColumn("kd", lit(kd))
      .withColumn("current_calivalue", udfUpdateCali(minCV)(col("increment_value"), col("online_cali"), col("cv")))
      .select("unitid", "conversion_goal", "current_error", "prev_error", "last_error", "kp", "ki", "kd", "increment_value", "current_calivalue", "cv", "online_cali")
    
    result
  }

  def udfUpdateCali(minCV: Int) = udf((increment: Double, prevCali: Double, cv: Int) => {
    var currentCali = prevCali + increment
    if (currentCali < 0.2) {
      currentCali = 0.2
    }
    if (currentCali > 2.0) {
      currentCali = 2.0
    }
    if (cv < minCV) {
      currentCali = prevCali
    }
    currentCali
  })

  def udfCalculatePID(kp: Double, ki: Double, kd: Double) = udf((currentError: Double, prevError: Double, lastError: Double) => {
    var result = kp * (currentError - prevError) + ki * currentError + kd * (currentError - 2.0 * prevError + lastError)
    result
  })

  def getPrevData(hourInt: Int, expTag: String, isHidden: Int, version: String, date: String, hour: String, spark: SparkSession) = {
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

    // 抽取数据
    val sqlRequest1 =
      s"""
         |SELECT
         |  unitid,
         |  conversion_goal,
         |  current_error as error1,
         |  prev_error as error2,
         |  current_calivalue as prev_cali
         |FROM
         |  dl_cpc.ocpc_pid_cali_data_hourly
         |WHERE
         |  `date` = '$date1'
         |AND
         |  `hour` = '$hour1'
         |AND
         |  version = '$version'
         |AND
         |  exp_tag = '$expTag'
         |AND
         |  is_hidden = $isHidden
       """.stripMargin
    println(sqlRequest1)
    val data = spark
        .sql(sqlRequest1)
        .withColumn("prev_error", col("error1"))
        .withColumn("last_error", col("error2"))

    data
  }

  def calculateError(baseData: DataFrame, date: String, hour: String, spark: SparkSession) = {
    baseData.createOrReplaceTempView("base_data")
    val sqlRequest1 =
      s"""
         |SELECT
         |  unitid,
         |  conversion_goal,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(iscvr) as cpareal,
         |  sum(case when isclick=1 then cpagiven else 0 end) * 1.0 / sum(isclick) as cpagiven,
         |  sum(case when isclick=1 then cali_value else 0 end) * 1.0 / sum(isclick) as online_cali,
         |  sum(iscvr) as cv
         |FROM
         |  base_data
         |GROUP BY unitid, conversion_goal
       """.stripMargin
    val data = spark.sql(sqlRequest1)

    val currentError = data
      .select("unitid", "conversion_goal", "cpareal", "cpagiven", "cv", "online_cali")
      .withColumn("current_error", udfCalculateError()(col("cpagiven"), col("cpareal")))

    currentError
  }

  def udfCalculateError() = udf((cpagiven: Double, cpareal: Double) => {
    val result = 1.0 - cpareal / cpagiven
    result
  })


  def getBaseData(media: String, sampleHour: Int, date: String, hour: String, spark: SparkSession) = {
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
    calendar.add(Calendar.HOUR, -sampleHour)
    val yesterday = calendar.getTime
    val tmpDate = dateConverter.format(yesterday)
    val tmpDateValue = tmpDate.split(" ")
    val date1 = tmpDateValue(0)
    val hour1 = tmpDateValue(1)
    val selectCondition = getTimeRangeSqlDate(date1, hour1, date, hour)

    // 点击数据
    val sqlRequest1 =
      s"""
         |SELECT
         |  searchid,
         |  unitid,
         |  adclass,
         |  isshow,
         |  isclick,
         |  price,
         |  cast(ocpc_log_dict['cpagiven'] as double) as cpagiven,
         |  cast(ocpc_log_dict['IsHiddenOcpc'] as int) as is_hidden,
         |  cast(ocpc_log_dict['cvrCalFactor'] as double) as cali_value,
         |  conversion_goal,
         |  hour
         |FROM
         |  dl_cpc.ocpc_filter_unionlog
         |WHERE
         |  $selectCondition
         |AND
         |  $mediaSelection
         |AND
         |  is_ocpc = 1
         |AND
         |  conversion_goal > 0
       """.stripMargin
    println(sqlRequest1)
    val clickData = spark
      .sql(sqlRequest1)
      .withColumn("cvr_goal", udfConcatStringInt("cvr")(col("conversion_goal")))

    // 转化数据
    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  label as iscvr,
         |  cvr_goal
         |FROM
         |  dl_cpc.ocpc_label_cvr_hourly
         |WHERE
         |  `date` >= '$date1'
       """.stripMargin
    println(sqlRequest2)
    val cvData = spark.sql(sqlRequest2)

    // 数据关联
    val data = clickData
      .join(cvData, Seq("searchid", "cvr_goal"), "left_outer")
      .na.fill(0, Seq("iscvr"))

    data
  }
}

