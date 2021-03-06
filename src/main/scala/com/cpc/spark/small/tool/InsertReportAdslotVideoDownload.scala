package com.cpc.spark.small.tool

import java.sql.DriverManager
import java.util.Properties

import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * Created by wanli on 2018/11/21.
  */
object InsertReportAdslotVideoDownload {

  case class Info(
                   mediaId: String = "",
                   adslotId: String = "",
                   adslotType: Int = 0,
                   req: Long = 0,
                   isfill: Long = 0,
                   isshow: Long = 0,
                   isclick: Long = 0,
                   traceType: String = "",
                   traceOp1: String = "",
                   total: Long = 0,
                   date: String = "",
                   data_type: String = "") {
  }

  var mariaReport2dbUrl = ""
  val mariaReport2dbProp = new Properties()

  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)

    val argDay = args(0).toString

    val conf = ConfigFactory.load()
    mariaReport2dbUrl = conf.getString("mariadb.report2_write.url")
    mariaReport2dbProp.put("user", conf.getString("mariadb.report2_write.user"))
    mariaReport2dbProp.put("password", conf.getString("mariadb.report2_write.password"))
    mariaReport2dbProp.put("driver", conf.getString("mariadb.report2_write.driver"))

    val ctx = SparkSession
      .builder()
      .config("spark.debug.maxToStringFields", "2000")
      .appName("InsertReportAdslotVideoDownload is run day is %s".format(argDay))
      .enableHiveSupport()
      .getOrCreate()

    println("InsertReportAdslotVideoDownload is run day is %s".format(argDay))

    /**
      * 获取sdk下载流量信息
      */

    //获取sdk下载流量信息
    val unionLogDataByZQDownSdk = ctx
      .sql(
        """
          |SELECT media_appsid,adslot_id,adslot_type,isfill,isshow,isclick
          |FROM dl_cpc.cpc_basedata_union_events cul
          |WHERE cul.isfill>0 AND cul.interaction=2 AND cul.adtype in(8,10) AND adsrc=1
          |AND cul.day="%s" AND usertype=2 AND client_type IN("NATIVESDK","JSSDK")
        """.stripMargin.format(argDay))
      .rdd
      .map {
        x =>
          val mediaId = x.getAs[String](0)
          val adslotId = x.getAs[String](1)
          val adslotType = x.getAs[Int](2)
          val isfill = x.getAs[Int](3).toLong
          val isshow = x.getAs[Int](4).toLong
          val isclick = x.getAs[Int](5).toLong
          val req = 1.toLong
          ((adslotId), (Info(mediaId, adslotId, adslotType, req, isfill, isshow, isclick)))
      }
      .reduceByKey {
        (a, b) =>
          (Info(a.mediaId, a.adslotId, a.adslotType, a.req + b.req, a.isfill + b.isfill, a.isshow + b.isshow, a.isclick + b.isclick))
      }
      .map(_._2)
      .repartition(50)
      .cache()
    println("unionLogDataByZQDownSdk count is", unionLogDataByZQDownSdk.count())

    //获取zqsdk下载流量信息
    val jsTraceDataByZQDownSdk = ctx
      .sql(
        """
          |SELECT DISTINCT cutl.searchid,cul.media_appsid,cul.adslot_id,cul.adslot_type,cutl.trace_op1,cutl.trace_op1
          |FROM dl_cpc.cpc_basedata_trace_event cutl
          |INNER JOIN dl_cpc.cpc_basedata_union_events cul ON cul.searchid=cutl.searchid
          |WHERE
          |cul.isclick>0 AND cul.interaction=2 AND cul.adtype in(8,10) AND adsrc=1
          |AND cul.day="%s" AND cutl.day="%s" AND usertype=2 AND cul.client_type IN("NATIVESDK","JSSDK")
        """.stripMargin.format(argDay, argDay))
      .rdd
      .map {
        x =>
          val mediaId = x.getAs[String](1)
          val adslotId = x.getAs[String](2)
          val adslotType = x.getAs[Int](3)
          var traceType = x.getAs[String](4)
          var traceOp1 = x.getAs[String](5).replaceAll("[\\ud800\\udc00-\\udbff\\udfff\\ud800-\\udfff]", "*")
          var total = 1.toLong

          ((adslotId, traceType, traceOp1), (Info(mediaId, adslotId, adslotType, 0, 0, 0, 0, traceType, traceOp1, total)))
      }
      .reduceByKey {
        (a, b) =>
          (Info(a.mediaId, a.adslotId, a.adslotType, 0, 0, 0, 0, a.traceType, a.traceOp1, a.total + b.total))
      }
      .filter(_._1._2.length < 200)
      .map(_._2)
      .repartition(50)
      .cache()
    println("jsTraceDataByZQDownSdk count is", jsTraceDataByZQDownSdk.count())


    var downZQSdkReq = unionLogDataByZQDownSdk
      .map {
        x =>
          (x.mediaId, x.adslotId, x.adslotType, "request", x.traceOp1, x.req, argDay, "ZhengQi")
      }

    var downZQSdkFill = unionLogDataByZQDownSdk
      .map {
        x =>
          (x.mediaId, x.adslotId, x.adslotType, "served_request", x.traceOp1, x.isfill, argDay, "ZhengQi")
      }

    var downZQSdkShow = unionLogDataByZQDownSdk
      .map {
        x =>
          (x.mediaId, x.adslotId, x.adslotType, "impression", x.traceOp1, x.isshow, argDay, "ZhengQi")
      }

    var downZQSdkClick = unionLogDataByZQDownSdk
      .map {
        x =>
          (x.mediaId, x.adslotId, x.adslotType, "click", x.traceOp1, x.isclick, argDay, "ZhengQi")
      }

    var downZQSdkApkdown = jsTraceDataByZQDownSdk
      .map {
        x =>
          (x.mediaId, x.adslotId, x.adslotType, "apkdown", x.traceOp1, x.total, argDay, "ZhengQi")
      }

    var allData = downZQSdkFill.union(downZQSdkShow).union(downZQSdkClick).union(downZQSdkApkdown)

    val allDataFrame =
      ctx.createDataFrame(allData)
        .toDF("media_id", "adslot_id", "adslot_type", "target_type", "trace_op1", "target_value", "date", "data_type")

    allDataFrame.show(200)

    clearReportData(argDay)

    allDataFrame
      .write
      .mode(SaveMode.Append)
      .jdbc(mariaReport2dbUrl, "report2.report_adslot_video_download", mariaReport2dbProp)
    println("InsertReportAdslotVideoDownload_done")


    //---------------------------------------
  }


  def clearReportData(date: String): Unit = {
    try {
      Class.forName(mariaReport2dbProp.getProperty("driver"))
      val conn = DriverManager.getConnection(
        mariaReport2dbUrl,
        mariaReport2dbProp.getProperty("user"),
        mariaReport2dbProp.getProperty("password"))
      val stmt = conn.createStatement()
      val sql =
        """
          |delete from report2.report_adslot_video_download where `date` = "%s"
        """.stripMargin.format(date)
      stmt.executeUpdate(sql);
    } catch {
      case e: Exception => println("exception caught: " + e);
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
}
