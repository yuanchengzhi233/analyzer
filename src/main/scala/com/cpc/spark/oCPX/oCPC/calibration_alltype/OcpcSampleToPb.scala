package com.cpc.spark.oCPX.oCPC.calibration_alltype

import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.OcpcProtoType.OcpcTools.getTimeRangeSqlDate
import com.cpc.spark.oCPX.OcpcTools.udfAdslotTypeMapAs
import com.cpc.spark.oCPX.oCPC.calibration_alltype.udfs.udfGenerateId
import com.typesafe.config.ConfigFactory
import ocpcParams.ocpcParams.{OcpcParamsList, SingleItem}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable.ListBuffer


object OcpcSampleToPb {
  def main(args: Array[String]): Unit = {
    /*
    pb文件格式：
    string identifier = 1;
    int32 conversiongoal = 2;
    double kvalue = 3;
    double cpagiven = 4;
    int64 cvrcnt = 5;
    对于明投广告，cpagiven=1， cvrcnt使用ocpc广告记录进行关联，k需要进行计算

    将文件从dl_cpc.ocpc_pcoc_jfb_hourly表中抽出，存入pb文件，需要过滤条件：
    kvalue>0
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    // bash: 2019-01-02 12 qtt_demo 1
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val fileName = args(3).toString
    println("parameters:")
    println(s"date=$date, hour=$hour, version:$version, fileName:$fileName")

    val data = getCalibrationData(date, hour, version, spark)

    val adtype15List = getAdtype15(date, hour, 48, version, spark)
    val resultDF = data
      .join(adtype15List, Seq("unitid", "conversion_goal", "exp_tag"), "left_outer")
      .na.fill(1.0, Seq("ratio"))
      .withColumn("jfb_factor_old", col("jfb_factor"))
      .withColumn("jfb_factor", col("jfb_factor_old") *  col("ratio"))

    resultDF
      .select("identifier", "conversion_goal", "is_hidden", "exp_tag", "cali_value", "jfb_factor", "post_cvr", "high_bid_factor", "low_bid_factor", "cpa_suggest", "smooth_factor", "cpagiven")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(version))
      .repartition(5)
//      .write.mode("overwrite").insertInto("test.ocpc_param_pb_data_hourly_alltype")
      .write.mode("overwrite").insertInto("dl_cpc.ocpc_param_pb_data_hourly_alltype")

  }

  def getCalibrationData(date: String, hour: String, version: String, spark: SparkSession) = {
    val sqlRequest1 =
      s"""
         |SELECT
         |  identifier,
         |  conversion_goal,
         |  is_hidden,
         |  exp_tag,
         |  cvr_factor as cali_value,
         |  jfb_factor,
         |  post_cvr,
         |  smooth_factor,
         |  high_bid_factor,
         |  low_bid_factor,
         |  cpagiven,
         |  cast(split(identifier, '&')[0] as int) as unitid
         |FROM
         |  dl_cpc.ocpc_pb_data_hourly_alltype
         |WHERE
         |  `date` = '$date'
         |AND
         |  `hour` = '$hour'
         |AND
         |  version = '$version'
       """.stripMargin
    println(sqlRequest1)
    val data1 = spark.sql(sqlRequest1).cache()
    data1.show(10)

    val sqlRequest2 =
      s"""
         |SELECT
         |  unitid,
         |  conversion_goal,
         |  avg(cpa_suggest) as cpa_suggest
         |FROM
         |  dl_cpc.ocpc_history_suggest_cpa_version
         |WHERE
         |  version = 'ocpcv1'
         |GROUP BY unitid, conversion_goal
       """.stripMargin
    println(sqlRequest2)
    val data2 = spark.sql(sqlRequest2).cache()
    data2.show(10)

    val data = data1
      .join(data2, Seq("unitid", "conversion_goal"), "left_outer")
      .select("identifier", "conversion_goal", "is_hidden", "exp_tag", "cali_value", "jfb_factor", "post_cvr", "high_bid_factor", "low_bid_factor", "cpa_suggest", "smooth_factor", "cpagiven", "unitid")
      .withColumn("cali_value", udfCheckCali(0.1, 5.0)(col("cali_value")))
      .na.fill(1.0, Seq("high_bid_factor", "low_bid_factor", "cpagiven"))
      .na.fill(0.0, Seq("cali_value", "jfb_factor", "post_cvr", "cpa_suggest", "smooth_factor"))

    data.show(10)

    data
  }

  def udfCheckCali(minCali: Double, maxCali: Double) = udf((caliValue: Double) => {
    var result = caliValue
    if (result < minCali) {
      result = minCali
    }
    if (result > maxCali) {
      result = maxCali
    }
    result
  })


  def getAdtype15(date: String, hour: String, hourInt: Int, version: String, spark: SparkSession) = {
    // 抽取媒体id
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias.total.media_selection"
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
    val selectCondition = getTimeRangeSqlDate(date1, hour1, date, hour)

    val sqlRequest =
      s"""
         |SELECT
         |  unitid,
         |  conversion_goal,
         |  adtype
         |FROM
         |  dl_cpc.ocpc_base_unionlog
         |WHERE
         |  $selectCondition
         |AND
         |  $mediaSelection
         |AND
         |  isclick = 1
         |AND
         |  is_ocpc = 1
         |AND
         |  conversion_goal > 0
         |AND
         |  adtype = 15
       """.stripMargin
    println(sqlRequest)
    val data1 = spark
      .sql(sqlRequest)
      .distinct()

    val data2 = getAdtype15Factor(version, spark)

    val data = data1
      .join(data2, Seq("conversion_goal"), "inner")
      .select("unitid", "conversion_goal", "exp_tag", "adtype", "ratio")
      .cache()


    data.show(10)
    data

  }

  def getAdtype15Factor(version: String, spark: SparkSession) = {
    // 从配置文件读取数据
    val conf = ConfigFactory.load("ocpc")
    val confPath = conf.getString("exp_tag.adtype15")
    val rawData = spark.read.format("json").json(confPath)
    val data = rawData
      .filter(s"version = '$version'")
      .select("exp_tag", "conversion_goal", "ratio")
      .distinct()

    println("adtype15 config:")
    data.show(10)

    data

  }

}

