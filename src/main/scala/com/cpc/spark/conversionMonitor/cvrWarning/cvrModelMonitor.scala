package com.cpc.spark.conversionMonitor.cvrWarning

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.conversionMonitor.monitorJiafen.sendMail
import com.github.jurajburian.mailer.{Content, Mailer, Message, SessionFactory, SmtpAddress}
import javax.mail.internet.InternetAddress
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.sys.process._

object cvrModelMonitor {
  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 计算日期周期
    val date = args(0).toString
    val modelName = args(1).toString
    val cvr_diff = args(2).toDouble

    // 清理ok文件
    s"hadoop fs -rm hdfs://emr-cluster/user/cpc/wangjun/okdir/conversion/new_cvrmodel/$modelName-$date.ok" !

    val dataToday = getData(date, modelName, spark)
    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val today = dateConverter.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -1)
    val yesterday = calendar.getTime
    val date1 = dateConverter.format(yesterday)
    val dataYesterday = getData(date1, modelName, spark)

    // 数据对比
    val cmpResult = cmpData(dataToday, dataYesterday, spark)
    val result = cmpResult
      .withColumn("date", lit(date))
      .select("cvr_yesterday", "cvr_today", "cvr_diff", "hour", "date", "model_name")

    result
      .repartition(1)
      .write.mode("overwrite").insertInto("dl_cpc.model_cvr_cmp_daily_v2")

    // 数据监控
    val filterResult = result.filter(s"cvr_diff > $cvr_diff")
    val cnt = filterResult.count()
    val totalCnt = result.count()
    filterResult.show(10)

    // email content
    val message = s"training set for $modelName, $date is incorrect!"
    val sub = "cvr model training dataset monitor is warning"
    var receiver = Seq[String]()
    receiver:+="wangjun02@qutoutiao.net"
    receiver:+="yanglei@qutoutiao.net"
    receiver:+="admodel@qutoutiao.net"
    receiver:+="wanlunjun@qutoutiao.net"
    receiver:+="wangfang03@qutoutiao.net"
    receiver:+="dongjinbao@qutoutiao.net"
    if (cnt > 0 || totalCnt != 24) {
      sendMail(message, sub, receiver)
    } else {
      //输出标记文件
      s"hadoop fs -touchz hdfs://emr-cluster/user/cpc/wangjun/okdir/conversion/new_cvrmodel/$modelName-$date.ok" !
    }

  }

  def cmpData(dataToday: DataFrame, dataYesterday: DataFrame, spark: SparkSession) = {
    val data0 = dataToday
      .withColumn("cvr_today", col("cvr"))
      .select("model_name", "cvr_today", "hour")

    val data1 = dataYesterday
      .withColumn("cvr_yesterday", col("cvr"))
      .select("model_name", "cvr_yesterday", "hour")

    val data = data1
      .join(data0, Seq("model_name", "hour"), "outer")
      .withColumn("cvr_diff", (col("cvr_today") - col("cvr_yesterday")) / col("cvr_yesterday"))
      .na.fill(1, Seq("cvr_diff"))
      .withColumn("cvr_diff", abs(col("cvr_diff")))
      .select("hour", "cvr_yesterday", "cvr_today", "cvr_diff", "model_name")


    data
  }

  def getData(date: String, modelName: String, spark: SparkSession) = {
    val sqlRequest =
      s"""
         |SELECT
         |  click,
         |  cv,
         |  cv * 1.0 / click as cvr,
         |  hour,
         |  model_name
         |FROM
         |  dl_cpc.model_cvr_data_daily
         |WHERE
         |  date = '$date'
         |AND
         |  model_name = '$modelName'
       """.stripMargin
    println(sqlRequest)
    val data = spark.sql(sqlRequest)
    data
  }

  def sendMail(txt: String, sub: String, to: Seq[String]): Boolean = {
    //val conf = ConfigFactory.load()
    val session = (SmtpAddress("smtp.exmail.qq.com", 25) :: SessionFactory())
      .session(Some("cpc_notify@aiclk.com" -> "nXBYjc8XVB6hCPv5"))
    val toAdd = to.map(new InternetAddress(_))
    val msg = Message(
      from = new InternetAddress("cpc_notify@aiclk.com"),
      subject = sub,
      content = Content().text(txt),
      to = toAdd)
    try {
      Mailer(session).send(msg)
      true
    } catch {
      case e: Exception =>
        println(e.getMessage)
        false
    }
  }


}