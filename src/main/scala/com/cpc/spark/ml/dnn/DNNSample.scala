package com.cpc.spark.ml.dnn

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.mutable
import scala.collection.mutable.WrappedArray
import scala.util.Random
import com.cpc.spark.common.Murmur3Hash


object DNNSample {

  private var trainLog = Seq[String]()

  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession.builder()
      .appName("dnn sample")
      .enableHiveSupport()
      .getOrCreate()

    val date = args(0)
    val tdate = args(1)

    val userAppIdx1 = getUidApp(spark, date)
    val train = getSample(spark, userAppIdx1, date)

    val userAppIdx2 = getUidApp(spark, tdate)
    val test = getSample(spark, userAppIdx2, tdate).randomSplit(Array(0.97, 0.03), 123L)(1)

    val clickiNum = train.filter {
      x =>
        val label = x.getAs[Seq[Int]]("label")
        label(0) == 1
    }.count()
    println(train.count(), clickiNum)

    val resampled = train.filter{
      x =>
        val label = x.getAs[Seq[Int]]("label")
        label(0) == 1 || Random.nextInt(1000) < 100
    }

    resampled.repartition(50)
      .write
      .mode("overwrite")
      .format("tfrecords")
      .option("recordType", "Example")
      .save("/user/cpc/dw/dnntrain-" + date)
    println("train size", resampled.count())

    test.repartition(50)
      .write
      .mode("overwrite")
      .format("tfrecords")
      .option("recordType", "Example")
      .save("/user/cpc/dw/dnntest-" + tdate)
    test.take(10).foreach(println)
    println("test size", test.count())
  }

  def getSample(spark: SparkSession, userAppIdx: DataFrame, date: String): DataFrame = {
    import spark.implicits._
    val ulog = spark.sql(
      s"""
         |select *
         |from dl_cpc.ml_ctr_feature_v1
         |where date='$date'
      """.stripMargin)
      .filter { x =>
        val ideaid = x.getAs[Int]("ideaid")
        val slottype = x.getAs[Int]("adslot_type")
        val mediaid = x.getAs[String]("media_appsid").toInt
        val uid = x.getAs[String]("uid")
        val isip = uid.contains(".") || uid.contains("000000")
        ideaid > 0 && slottype == 1 && Seq(80000001, 80000002).contains(mediaid) && !isip
      }
      .join(userAppIdx, Seq("uid"), "leftouter")
      .rdd
      .map { row =>
        val ret = getVectorParser(row)
        val raw = ret._1
        val uid = ret._2
        val apps = ret._3
        var label = Seq(0, 1)
        if (row.getAs[Int]("label") > 0) {
          label = Seq(1, 0)
        }

        var hashed = Seq[Long]()
        for (i <- raw.indices) {
          hashed = hashed :+ Murmur3Hash.stringHash64("%s:%d".format(fnames(i), raw(i)), 0)
        }
        hashed = hashed :+ Murmur3Hash.stringHash64("uid:%s".format(uid), 0)

        var idx0 = Seq[Long]()
        var idx1 = Seq[Long]()
        var idx2 = Seq[Long]()
        var id_arr = Seq[Long]()
        for (i <- apps.indices) {
          val id = Murmur3Hash.stringHash64("app:%s".format(apps(i)), 0)
          idx0 = idx0 :+ 0L
          idx1 = idx1 :+ 0L
          idx2 = idx2 :+ i.toLong
          id_arr = id_arr :+ id
        }

        (label, hashed, idx0, idx1, idx2, id_arr)
      }
      .zipWithUniqueId()
      .map(x => (x._2, x._1._1, x._1._2, x._1._3, x._1._4, x._1._5, x._1._6))
      .toDF("sample_idx", "label", "dense", "idx0", "idx1", "idx2", "id_arr")
      .repartition(1000)
    ulog
  }

  def getPathSeq(days: Int): mutable.Map[String, Seq[String]] = {
    var date = ""
    var hour = ""
    val cal = Calendar.getInstance()
    cal.add(Calendar.HOUR, -(days * 24 + 2))
    val pathSep = mutable.Map[String, Seq[String]]()

    for (n <- 1 to days * 24) {
      date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime)
      hour = new SimpleDateFormat("HH").format(cal.getTime)
      pathSep.update(date, (pathSep.getOrElse(date, Seq[String]()) :+ hour))
      cal.add(Calendar.HOUR, 1)
    }

    pathSep
  }

  def getUidApp(spark: SparkSession, date: String): DataFrame = {
    import spark.implicits._
    spark.sql(
      """
        |select * from dl_cpc.cpc_user_installed_apps where `date` = "%s"
      """.stripMargin.format(date)).rdd
      .map(x => (x.getAs[String]("uid"), x.getAs[Seq[String]]("pkgs")))
      .reduceByKey(_ ++ _)
      .map(x => (x._1, x._2.distinct))
      .toDF("uid", "pkgs")
  }


  def getData(spark: SparkSession, dataVersion: String, pathSep: mutable.Map[String, Seq[String]]): DataFrame = {

    var path = Seq[String]()
    pathSep.map {
      x =>
        path = path :+ "/user/cpc/lrmodel/%s/%s/{%s}".format(dataVersion, x._1, x._2.mkString(","))
    }

    path.foreach {
      x =>
        println(x)
    }

    spark.read.parquet(path: _*)
  }

  val fnames = Seq(
    "hour", "sex", "age", "os", "net", "pl", "adtype", "city", "mediaid",
    "adslotid", "adclass", "planid", "unitid", "ideaid"
  )

  def getVectorParser(x: Row): (Seq[Int], String, Seq[String]) = {
    val cal = Calendar.getInstance()
    cal.setTimeInMillis(x.getAs[Int]("timestamp") * 1000L)
    val week = cal.get(Calendar.DAY_OF_WEEK) //1 to 7
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    var raw = Seq[Int]()

    raw = raw :+ hour

    val sex = x.getAs[Int]("sex")
    raw = raw :+ sex

    //age
    val age = x.getAs[Int]("age")
    raw = raw :+ age

    //os 96 - 97 (2)
    val os = x.getAs[Int]("os")
    raw = raw :+ os

    //net
    val net = x.getAs[Int]("network")
    raw = raw :+ net

    val pl = x.getAs[Int]("phone_level")
    raw = raw :+ pl

    val at = x.getAs[Int]("adtype")
    raw = raw :+ at

    val cityid = x.getAs[Int]("city")
    raw = raw :+ cityid

    val mediaid = x.getAs[String]("media_appsid").toInt
    raw = raw :+ mediaid

    val slotid = x.getAs[String]("adslotid").toInt
    raw = raw :+ slotid

    val ac = x.getAs[Int]("adclass")
    raw = raw :+ ac

    val planid = x.getAs[Int]("planid")
    raw = raw :+ planid

    val unitid = x.getAs[Int]("unitid")
    raw = raw :+ unitid

    val ideaid = x.getAs[Int]("ideaid")
    raw = raw :+ ideaid

    val apps = x.getAs[Seq[String]]("pkgs")
    val uid = x.getAs[String]("uid")

    if (apps != null && apps.length > 0) {
      (raw, uid, apps.slice(0, 500))
    } else {
      (raw, uid, Seq(""))
    }
  }
}




