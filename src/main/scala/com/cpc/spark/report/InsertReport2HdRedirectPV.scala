package com.cpc.spark.report

import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.{Calendar, Properties}

import com.cpc.spark.log.parser.{CfgLog, LogParser}
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * 每5min统计每个adslotid 的pv数
  * 统计逻辑： 根据adslotid分组求sum(1)
  *
  * 输入: hive, dl_cpc.logparsed_cpc_cfg_minute
  * 输出：mysql, report2.report_hd_redirect_pv_minute
  *
  */
@deprecated
object InsertReport2HdRedirectPV {
  var report2Url = ""
  val report2Prop = new Properties()

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println(
        s"""
           |Usage: day <date>
        """.stripMargin)
      System.exit(1)
    }
    val argDay = args(0).toString
    val argHour = args(1).toString
    val argMinute = args(2).toString
    val eminute = args(3).toString
    Logger.getRootLogger.setLevel(Level.WARN)

    val conf = ConfigFactory.load()
    report2Url = conf.getString("mariadb.report2_write.url")
    report2Prop.put("user", conf.getString("mariadb.report2_write.user"))
    report2Prop.put("password", conf.getString("mariadb.report2_write.password"))
    report2Prop.put("driver", conf.getString("mariadb.report2_write.driver"))

    val spark = SparkSession.builder()
      .appName("InsertReport2HdRedirectPVLog date " + argDay + " ,hour " + argHour + " ,minute " + argMinute)
      .enableHiveSupport()
      .getOrCreate()

    val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    val sql =
      s"""
         |select raw
         |from dl_cpc.cpc_basedata_cfg_log
         |where day='$argDay' and hour='$argHour' and (minute between '$argMinute' and '$eminute')
       """.stripMargin
    println("sql: " + sql, "count1: " + spark.sql(sql).count())

    val cfgLog = spark.sql(sql).repartition(1000)
      .rdd
      .map { x =>
        val raw = x.getAs[String]("raw")
        LogParser.parseCfgLog_v2(raw)
      }
      .filter(x => x.log_type == "/hdjump" || x.log_type == "/reqhd")

    //    var cfgLog = spark.read
    //      .parquet("/warehouse/dl_cpc.db/logparsed_cpc_cfg_minute/%s/%s/%s".format(argDay, argHour, argMinute))
    //      .as[CfgLog2]
    //      .rdd
    //      .filter(x => x.log_type == "/hdjump" || x.log_type == "/reqhd")

    println("count2: " + cfgLog.count())
    cfgLog.take(1).foreach(x => println(x))


    val startDate = getTimeStampByDate(argDay, argHour, argMinute) / 1000
    val middleDate = getTimeStampByDate(argDay, argHour, (argMinute.toInt + 5).toString) / 1000
    val endDate = getTimeStampByDate(argDay, argHour, (argMinute.toInt + 10).toString) / 1000

    val createTime1 = dateFormat.format(startDate * 1000)
    val createTime2 = dateFormat.format(middleDate * 1000)

    println("startDate:" + startDate + "  middleDate:" + middleDate + "  endDate:" + endDate)
    println("createTime1:" + createTime1 + "  createTime2:" + createTime2)

    //获得theminute分区的前5min数据
    val cfgLog1 = cfgLog
      .filter(_ != "")
      .filter(x => x.search_timestamp >= startDate && x.search_timestamp <= middleDate)

    //获得theminute分区的后5min数据
    val cfgLog2 = cfgLog
      .filter(_ != "")
      .filter(x => x.search_timestamp > middleDate && x.search_timestamp <= endDate)

    //前5min cfg计算pv数写入mysql
    writeToMysql(spark, cfgLog1, argDay, createTime1)
    //后5min cfg计算pv数写入mysql
    writeToMysql(spark, cfgLog2, argDay, createTime2)

    spark.stop()

  }

  /**
    * 每5min统计每个adslotid 的pv数
    *
    * @param spark
    * @param cfgLog cfgRDD
    * @param argDay
    */
  def writeToMysql(spark: SparkSession, cfgLog: RDD[CfgLog], argDay: String, createTime: String): Unit = {

    var toResult = cfgLog
      .map(x => (x.aid, 1))
      .reduceByKey((x, y) => x + y)
      .map {
        case (adslotId, count) =>
          (adslotId, argDay, count, createTime)
      }

    println("count:" + toResult.count())

    val insertDataFrame = spark.createDataFrame(toResult).toDF("adslot_id", "date", "pv", "create_time")

    insertDataFrame.show(10)

    //删除当前日期和create_time的数据，防止多次写入
    clearReportHourData("report_hd_redirect_pv_minute", argDay, createTime)

    insertDataFrame
      .write
      .mode(SaveMode.Append)
      .jdbc(report2Url, "report2.report_hd_redirect_pv_minute", report2Prop)

    println("~~~~~~write to mysql successfully")

  }

  def clearReportHourData(tbl: String, date: String, createTime: String): Unit = {
    try {
      Class.forName(report2Prop.getProperty("driver"))
      val conn = DriverManager.getConnection(
        report2Url,
        report2Prop.getProperty("user"),
        report2Prop.getProperty("password"))
      val stmt = conn.createStatement()
      val sql =
        """
          |delete from report2.%s where `date` = "%s" AND create_time="%s"
        """.stripMargin.format(tbl, date, createTime)
      stmt.executeUpdate(sql);
    } catch {
      case e: Exception => println("exception caught: " + e)
    }
  }


  /**
    * 根据日期获取时间戳
    *
    * @param date
    * @param hour
    * @param minute
    * @return
    */
  def getTimeStampByDate(date: String, hour: String, minute: String): Long = {
    val cal = Calendar.getInstance()

    cal.set(Calendar.YEAR, date.split("-")(0).toInt)
    cal.set(Calendar.MONTH, date.split("-")(1).toInt - 1)
    cal.set(Calendar.DAY_OF_MONTH, date.split("-")(2).toInt)
    cal.set(Calendar.HOUR_OF_DAY, hour.toInt)
    cal.set(Calendar.MINUTE, minute.toInt)
    cal.set(Calendar.SECOND, 0)

    cal.getTime.getTime
  }


  case class CfgLog2(
                      aid: String = "",
                      search_timestamp: Int = 0,
                      log_type: String = "", // req/tpl/hdjump
                      request_url: String = "",
                      resp_body: String = "",
                      redirect_url: String = "",
                      template_conf: String = "",
                      adslot_conf: String = "",
                      date: String = "",
                      hour: String = "",
                      ip: String = "",
                      ua: String = ""
                    )

}