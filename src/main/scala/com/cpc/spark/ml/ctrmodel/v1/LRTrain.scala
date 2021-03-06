package com.cpc.spark.ml.ctrmodel.v1

import java.text.SimpleDateFormat
import java.util
import java.util.{Calendar, Date}

import com.cpc.spark.common.Utils
import com.cpc.spark.ml.common.{Utils => MUtils}
import com.cpc.spark.ml.train.LRIRModel
import com.cpc.spark.qukan.parser.HdfsParser
import com.typesafe.config.ConfigFactory
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.mutable
import mlserver.mlserver._
import org.apache.log4j.{Level, Logger}

import scala.collection.mutable.WrappedArray
import scala.util.Random

/**
  * Created by zhaolei on 22/12/2017.
  */


object LRTrain {

  private var days = 7
  private var dayBefore = 7
  private var trainLog = Seq[String]()

  private val model: LRIRModel = new LRIRModel

  def main(args: Array[String]): Unit = {
    if (args.length > 0) {
      dayBefore = args(0).toInt
      days = args(1).toInt
    }

    Logger.getRootLogger.setLevel(Level.WARN)

    val spark: SparkSession = model.initSpark("cpc lr model")

    initFeatureDict(spark)

    //按分区取数据
/*    var date = ""
    val cal = Calendar.getInstance()
    cal.add(Calendar.DATE, -dayBefore)
    var pathSep = Seq[String]()
    for (n <- 1 to days) {
      date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime)
      pathSep = pathSep :+ date
      cal.add(Calendar.DATE, 1)
    }

    val inpath = "/gobblin/source/lechuan/qukan/extend_report/{%s}".format(pathSep.mkString(","))

    val uidApp = getUserAppInstalled(spark, inpath)
    val ids = getTopApp(uidApp, 1000)
    dictStr.update("appid",ids)
    val userAppIdx = getUserAppIdx(spark, uidApp, ids)
*/

    val userAppIdx = getUserApp(spark, dayBefore, days)

    val ulog = getData(spark)
    val ulogData = getLeftJoinData(ulog, userAppIdx).cache()


    /*
    //qtt-all-parser3
    model.clearResult()
    val qttAll = ulogData.filter(x => (x.getAs[String]("media_appsid") == "80000001" || x.getAs[String]("media_appsid") == "80000002") && (x.getAs[Int]("adslot_type") == 1 || x.getAs[Int]("adslot_type") == 2)).cache()
    train(spark, "parser3", "qtt-all-parser3", qttAll, "qtt-all-parser3.lrm")

    //qtt-all-parser2
    model.clearResult()
    train(spark, "parser2", "qtt-all-parser2", qttAll, "qtt-all-parser2.lrm")

    //qtt-list-parser3
    model.clearResult()
    val qttList = qttAll.filter(x =>x.getAs[Int]("adslot_type") == 1)
    train(spark, "parser3", "qtt-list-parser3", qttList, "qtt-list-parser3.lrm")

    //qtt-content-parser3
    model.clearResult()
    val qttContent = qttAll.filter(x =>x.getAs[Int]("adslot_type") == 2)
    train(spark, "parser3", "qtt-content-parser3", qttContent, "qtt-content-parser3.lrm")

    //external-all-parser2
    model.clearResult()
    val externalAll = ulogData.filter(x => (x.getAs[String]("media_appsid") != "80000001" && x.getAs[String]("media_appsid") != "80000002") && (x.getAs[Int]("adslot_type") == 1 || x.getAs[Int]("adslot_type") == 2))
    train(spark, "parser2", "external-all-parser2", externalAll, "external-all-parser2.lrm")
    */

    //all-interact-parser2
    model.clearResult()
    val allInteract = ulogData.filter(x => x.getAs[Int]("adslot_type") == 3)
    train(spark, "parser2", "all-interact-parser2", allInteract, "all-interact-parser2.lrm")


    //cvr按20天取数据
    days = 20
    dayBefore = 20
    initFeatureDict(spark)

    //按分区取数据
    val cvr_userAppIdx = getUserApp(spark, dayBefore, days)

    //cvr-parser2
    model.clearResult()
    val cvrlog = getCvrData(spark).filter(x => (x.getAs[String]("media_appsid") == "80000001" || x.getAs[String]("media_appsid") == "80000002") && (x.getAs[Int]("adslot_type") == 1 || x.getAs[Int]("adslot_type") == 2))
    val cvrlogData = getLeftJoinData(cvrlog, cvr_userAppIdx).cache()
    train(spark, "parser2", "cvr-parser2", cvrlogData, "cvr-parser2.lrm")

    //cvr-parser3
    model.clearResult()
    train(spark, "parser3", "cvr-parser3", cvrlogData, "cvr-parser3.lrm")


    Utils.sendMail(trainLog.mkString("\n"), "TrainLog", Seq("rd@aiclk.com"))
    ulogData.unpersist()
    cvrlog.unpersist()
  }

  def getUserApp(spark: SparkSession, dayBefore: Int, days: Int): DataFrame ={
    //按分区取数据
    var date = ""
    val cal = Calendar.getInstance()
    cal.add(Calendar.DATE, -dayBefore)
    var pathSep = Seq[String]()
    for (n <- 1 to days) {
      date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime)
      pathSep = pathSep :+ date
      cal.add(Calendar.DATE, 1)
    }

    val inpath = "/gobblin/source/lechuan/qukan/extend_report/{%s}".format(pathSep.mkString(","))

    val uidApp = getUserAppInstalled(spark, inpath)
    val ids = getTopApp(uidApp, 1000)
    dictStr.update("appid",ids)
    getUserAppIdx(spark, uidApp, ids)
  }


  def getTime(): String = {
    val now: Date = new Date()
    val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    dateFormat.format(now)
  }

  //用户及其App安装列表
  def getUserAppInstalled(spark : SparkSession, inpath : String): RDD[(String,List[String])] ={
    spark.read.orc(inpath).rdd
      .map(HdfsParser.parseInstallApp(_, x => true, null))
      .filter(x => x != null && x.pkgs.length > 0)
      .map(x => (x.devid, x.pkgs.map(_.name)))
      .reduceByKey(_ ++ _)
      .map(x => (x._1, x._2.distinct))
  }

  //安装列表中top k的App
  def getTopApp(uidApp : RDD[(String,List[String])], k : Int): Map[String,Int] ={
    var idx = 0
    val ids = mutable.Map[String,Int]()
    uidApp
      .flatMap(x => x._2.map((_,1)))
      .reduceByKey(_ + _)
      .sortBy(_._2,false)
      .toLocalIterator
      .take(k)
      .foreach{
        id =>
          idx += 1
          ids.update(id._1,idx)
      }
    ids.toMap
  }

  //用户安装列表对应的App idx
  def getUserAppIdx(spark: SparkSession, uidApp : RDD[(String,List[String])], ids : Map[String,Int]): DataFrame ={
    import spark.implicits._
    uidApp.map{
      x =>
        val k = x._1
        val v = x._2.map(p => (ids.getOrElse(p,0))).filter(_ > 0)
        (k,v)
    }.toDF("uid","appIdx")
  }

  //用户安装列表特征合并到原有特征
  def getLeftJoinData(data: DataFrame, userAppIdx: DataFrame): DataFrame ={
    data.join(userAppIdx,Seq("uid"),"leftouter")
  }

  def train(spark: SparkSession, parser: String, name: String, ulog: DataFrame, destfile: String): Unit = {
    trainLog :+= "\n------train log--------"
    trainLog :+= "name = %s".format(name)
    trainLog :+= "parser = %s".format(parser)
    trainLog :+= "destfile = %s".format(destfile)

    val num = ulog.count().toDouble
    println("sample num", num)
    trainLog :+= "total size %.0f".format(num)

    //最多2000w条测试数据
    var testRate = 0.1
    if (num * testRate > 2e7) {
      testRate = 2e7 / num
    }

    val Array(train, test) = ulog.randomSplit(Array(1 - testRate, testRate), new Date().getTime)
    ulog.unpersist()

    val tnum = train.count().toDouble
    val pnum = train.filter(_.getAs[Int]("label") > 0).count().toDouble
    val nnum = tnum - pnum

    //保证训练数据正负比例 1:9
    val rate = (pnum * 9 / nnum * 1000).toInt
    println("total positive negative", tnum, pnum, nnum, rate)
    trainLog :+= "train size total=%.0f positive=%.0f negative=%.0f scaleRate=%d/1000".format(tnum, pnum, nnum, rate)

    val sampleTrain = formatSample(spark, parser, train.filter(x => x.getAs[Int]("label") > 0 || Random.nextInt(1000) < rate))
    val sampleTest = formatSample(spark, parser, test)

    println(sampleTrain.take(5).foreach(x => println(x.features)))
    model.run(sampleTrain, 200, 1e-8)
    model.test(sampleTest)

    model.printLrTestLog()
    trainLog :+= model.getLrTestLog()


    val testNum = sampleTest.count().toDouble * 0.9
    val minBinSize = 1000d
    var binNum = 1000d
    if (testNum < minBinSize * binNum) {
      binNum = testNum / minBinSize
    }

    model.runIr(binNum.toInt, 0.9)
    trainLog :+= model.binsLog.mkString("\n")

    val date = new SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new Date().getTime)
    val lrfilepath = "/data/cpc/anal/model/lrmodel-%s-%s.lrm".format(name, date)
    model.saveHdfs("/user/cpc/lrmodel/lrmodeldata/%s".format(date))
    model.saveIrHdfs("/user/cpc/lrmodel/irmodeldata/%s".format(date))
    model.savePbPack(parser, lrfilepath, dict.toMap, dictStr.toMap)

    trainLog :+= "protobuf pack %s".format(lrfilepath)

    trainLog :+= "\n-------update server data------"
    if (destfile.length > 0) {
      trainLog :+= MUtils.updateOnlineData(lrfilepath, destfile, ConfigFactory.load())
    }
  }

  def formatSample(spark: SparkSession, parser: String, ulog: DataFrame): RDD[LabeledPoint] = {
    val BcDict = spark.sparkContext.broadcast(dict)

    ulog.rdd
      .mapPartitions {
        p =>
          dict = BcDict.value
          p.map {
            u =>
              val vec = parser match {
                case "parser1" =>
                  getVectorParser1(u)
                case "parser2" =>
                  getVectorParser2(u)
                case "parser3" =>
                  getVectorParser3(u)
              }
              LabeledPoint(u.getAs[Int]("label").toDouble, vec)
          }
      }
  }

  var dict = mutable.Map[String, Map[Int, Int]]()
  val dictNames = Seq(
    "mediaid",
    "planid",
    "unitid",
    "ideaid",
    "slotid",
    "adclass",
    "cityid"
  )
  var dictStr = mutable.Map[String, Map[String, Int]]()

  def initFeatureDict(spark: SparkSession): Unit = {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DATE, -dayBefore)
    var pathSeps = Seq[String]()
    for (d <- 1 to days) {
      val date = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime)
      pathSeps = pathSeps :+ date
      calendar.add(Calendar.DATE, 1)
    }
    trainLog :+= "\n------dict size------"
    for (name <- dictNames) {
      val pathTpl = "/user/cpc/lrmodel/feature_ids_v1/%s/{%s}"
      var n = 0
      val ids = mutable.Map[Int, Int]()
      spark.read
        .parquet(pathTpl.format(name, pathSeps.mkString(",")))
        .rdd
        .map(x => x.getInt(0))
        .distinct()
        .sortBy(x => x)
        .toLocalIterator
        .foreach {
          id =>
            n += 1
            ids.update(id, n)
        }
      dict.update(name, ids.toMap)
      println("dict", name, ids.size)
      trainLog :+= "%s=%d".format(name, ids.size)
    }
  }

  def getCvrData(spark: SparkSession): DataFrame = {
    trainLog :+= "\n-------get ulog data------"
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DATE, -dayBefore)
    var pathSeps = Seq[String]()
    for (d <- 1 to days) {
      val date = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime)
      pathSeps = pathSeps :+ date
      calendar.add(Calendar.DATE, 1)
    }

    val path = "/user/cpc/lrmodel/cvrdata_v2/{%s}".format(pathSeps.mkString(","))
    println(path)
    trainLog :+= path
    spark.read.parquet(path)
  }


  def getData(spark: SparkSession): DataFrame = {
    trainLog :+= "\n-------get ulog data------"
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DATE, -dayBefore)
    var pathSeps = Seq[String]()
    for (d <- 1 to days) {
      val date = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime)
      pathSeps = pathSeps :+ date
      calendar.add(Calendar.DATE, 1)
    }

    val path = "/user/cpc/lrmodel/ctrdata_v2/{%s}".format(pathSeps.mkString(","))
    println(path)
    trainLog :+= path
    spark.read.parquet(path).coalesce(2000)
  }

  /*
  def parseFeature(row: Row): Vector = {
    val (ad, m, slot, u, loc, n, d, t) = unionLogToObject(row)
    var svm = ""
    getVector(ad, m, slot, u, loc, n, d, t)
  }
  */

  def unionLogToObject(x: Row, seq: Seq[String]): (AdInfo, Media, AdSlot, User, Location, Network, Device, Long) = {
    val ad = AdInfo(
      ideaid = x.getAs[Int]("ideaid"),
      unitid = x.getAs[Int]("unitid"),
      planid = x.getAs[Int]("planid"),
      adtype = x.getAs[Int]("adtype"),
      _class = x.getAs[Int]("adclass"),
      showCount = x.getAs[Int]("user_req_ad_num")
    )
    val m = Media(
      mediaAppsid = x.getAs[String]("media_appsid").toInt
    )
    val slot = AdSlot(
      adslotid = x.getAs[String]("adslotid").toInt,
      adslotType = x.getAs[Int]("adslot_type"),
      pageNum = x.getAs[Int]("pagenum"),
      bookId = x.getAs[String]("bookid")
    )
    val u = User(
      sex = x.getAs[Int]("sex"),
      age = x.getAs[Int]("age"),
      installpkg = seq,
      reqCount = x.getAs[Int]("user_req_num")
    )
    val n = Network(
      network = x.getAs[Int]("network"),
      isp = x.getAs[Int]("isp")
    )
    val loc = Location(
      city = x.getAs[Int]("city")
    )
    val d = Device(
      os = x.getAs[Int]("os"),
      phoneLevel = x.getAs[Int]("phone_level")
    )
    (ad, m, slot, u, loc, n, d, x.getAs[Int]("timestamp") * 1000L)
  }


  def getVectorParser1(x: Row): Vector = {

    val cal = Calendar.getInstance()
    cal.setTimeInMillis(x.getAs[Int]("timestamp") * 1000L)
    val week = cal.get(Calendar.DAY_OF_WEEK)   //1 to 7
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    var els = Seq[(Int, Double)]()
    var i = 0

    els = els :+ (week + i - 1, 1d)
    i += 7

    //(24)
    els = els :+ (hour + i, 1d)
    i += 24

    //sex
    els = els :+ (x.getAs[Int]("sex") + i, 1d)
    i += 9

    //age
    els = els :+ (x.getAs[Int]("age") + i, 1d)
    i += 100

    //os 96 - 97 (2)
    els = els :+ (x.getAs[Int]("os") + i, 1d)
    i += 10

    //isp
    els = els :+ (x.getAs[Int]("isp") + i, 1d)
    i += 20

    //net
    els = els :+ (x.getAs[Int]("network") + i, 1d)
    i += 10

    els = els :+ (dict("cityid").getOrElse(x.getAs[Int]("city"), 0) + i, 1d)
    i += dict("cityid").size + 1

    //media id
    els = els :+ (dict("mediaid").getOrElse(x.getAs[String]("media_appsid").toInt, 0) + i, 1d)
    i += dict("mediaid").size + 1

    //ad slot id
    els = els :+ (dict("slotid").getOrElse(x.getAs[String]("adslotid").toInt, 0) + i, 1d)
    i += dict("slotid").size + 1

    //0 to 4
    els = els :+ (x.getAs[Int]("phone_level") + i, 1d)
    i += 10

    //pagenum
    var pnum = x.getAs[Int]("pagenum")
    if (pnum < 0 || pnum > 50) {
      pnum = 0
    }
    els = els :+ (pnum + i, 1d)
    i += 100

    //bookid
    var bid = 0
    try {
      bid = x.getAs[String]("bookid").toInt
    } catch {
      case e: Exception =>
    }
    if (bid < 0 || bid > 50) {
      bid = 0
    }
    els = els :+ (bid + i, 1d)
    i += 100

    //ad class
    val adcls = dict("adclass").getOrElse(x.getAs[Int]("adclass"), 0)
    els = els :+ (adcls + i, 1d)
    i += dict("adclass").size + 1

    //adtype
    els = els :+ (x.getAs[Int]("adslot_type") + i, 1d)
    i += 10

    //planid
    els = els :+ (dict("planid").getOrElse(x.getAs[Int]("planid"), 0) + i, 1d)
    i += dict("planid").size + 1

    //unitid
    els = els :+ (dict("unitid").getOrElse(x.getAs[Int]("unitid"), 0) + i, 1d)
    i += dict("unitid").size + 1

    //ideaid
    els = els :+ (dict("ideaid").getOrElse(x.getAs[Int]("ideaid"), 0) + i, 1d)
    i += dict("ideaid").size + 1

    try {
      Vectors.sparse(i, els)
    } catch {
      case e: Exception =>
        throw new Exception(els.toString + " " + i.toString + " " + e.getMessage)
        null
    }
  }

  def getVectorParser2(x: Row): Vector = {

    val cal = Calendar.getInstance()
    cal.setTimeInMillis(x.getAs[Int]("timestamp") * 1000L)
    val week = cal.get(Calendar.DAY_OF_WEEK)   //1 to 7
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    var els = Seq[(Int, Double)]()
    var i = 0

    els = els :+ (week + i - 1, 1d)
    i += 7

    //(24)
    els = els :+ (hour + i, 1d)
    i += 24

    //sex
    els = els :+ (x.getAs[Int]("sex") + i, 1d)
    i += 9

    //age
    els = els :+ (x.getAs[Int]("age") + i, 1d)
    i += 100

    //os 96 - 97 (2)
    els = els :+ (x.getAs[Int]("os") + i, 1d)
    i += 10

    //isp
    els = els :+ (x.getAs[Int]("isp") + i, 1d)
    i += 20

    //net
    els = els :+ (x.getAs[Int]("network") + i, 1d)
    i += 10

    els = els :+ (dict("cityid").getOrElse(x.getAs[Int]("city"), 0) + i, 1d)
    i += dict("cityid").size + 1

    //media id
    els = els :+ (dict("mediaid").getOrElse(x.getAs[String]("media_appsid").toInt, 0) + i, 1d)
    i += dict("mediaid").size + 1

    //ad slot id
    els = els :+ (dict("slotid").getOrElse(x.getAs[String]("adslotid").toInt, 0) + i, 1d)
    i += dict("slotid").size + 1

    //0 to 4
    els = els :+ (x.getAs[Int]("phone_level") + i, 1d)
    i += 10

    //pagenum
    var pnum = x.getAs[Int]("pagenum")
    if (pnum < 0 || pnum > 50) {
      pnum = 0
    }
    els = els :+ (pnum + i, 1d)
    i += 100

    //bookid
    var bid = 0
    try {
      bid = x.getAs[String]("bookid").toInt
    } catch {
      case e: Exception =>
    }
    if (bid < 0 || bid > 50) {
      bid = 0
    }
    els = els :+ (bid + i, 1d)
    i += 100

    //ad class
    val adcls = dict("adclass").getOrElse(x.getAs[Int]("adclass"), 0)
    els = els :+ (adcls + i, 1d)
    i += dict("adclass").size + 1

    //adtype
    els = els :+ (x.getAs[Int]("adtype") + i, 1d)
    i += 10

    //adslot_type
    els = els :+ (x.getAs[Int]("adslot_type") + i, 1d)
    i += 10

    //planid
    els = els :+ (dict("planid").getOrElse(x.getAs[Int]("planid"), 0) + i, 1d)
    i += dict("planid").size + 1

    //unitid
    els = els :+ (dict("unitid").getOrElse(x.getAs[Int]("unitid"), 0) + i, 1d)
    i += dict("unitid").size + 1

    //ideaid
    els = els :+ (dict("ideaid").getOrElse(x.getAs[Int]("ideaid"), 0) + i, 1d)
    i += dict("ideaid").size + 1

    //user_req_ad_num
    var uran_idx = 0
    val uran = x.getAs[Int]("user_req_ad_num")
    if (uran >= 1 && uran <= 10 ){
      uran_idx = uran
    }
    if (uran > 10){
      uran_idx = 11
    }
    els = els :+ (uran_idx + i, 1d)
    i += 12 + 1

    //user_req_num
    var urn_idx = 0
    val urn = x.getAs[Int]("user_req_num")
    if (urn >= 1 && urn <= 10){
      urn_idx = 1
    }else if(urn > 10 && urn <= 100){
      urn_idx = 2
    }else if(urn > 100 && urn <= 1000){
      urn_idx = 3
    }else if(urn > 1000){
      urn_idx = 4
    }
    els = els :+ (urn_idx + i, 1d)
    i += 5 + 1

    try {
      Vectors.sparse(i, els)
    } catch {
      case e: Exception =>
        throw new Exception(els.toString + " " + i.toString + " " + e.getMessage)
        null
    }
  }


  def getVectorParser3(x: Row): Vector = {

    val cal = Calendar.getInstance()
    cal.setTimeInMillis(x.getAs[Int]("timestamp") * 1000L)
    val week = cal.get(Calendar.DAY_OF_WEEK)   //1 to 7
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    var els = Seq[(Int, Double)]()
    var i = 0

    els = els :+ (week + i - 1, 1d)
    i += 7

    //(24)
    els = els :+ (hour + i, 1d)
    i += 24

    //sex
    els = els :+ (x.getAs[Int]("sex") + i, 1d)
    i += 9

    //age
    els = els :+ (x.getAs[Int]("age") + i, 1d)
    i += 100

    //os 96 - 97 (2)
    els = els :+ (x.getAs[Int]("os") + i, 1d)
    i += 10

    //isp
    els = els :+ (x.getAs[Int]("isp") + i, 1d)
    i += 20

    //net
    els = els :+ (x.getAs[Int]("network") + i, 1d)
    i += 10

    els = els :+ (dict("cityid").getOrElse(x.getAs[Int]("city"), 0) + i, 1d)
    i += dict("cityid").size + 1

    //media id
    els = els :+ (dict("mediaid").getOrElse(x.getAs[String]("media_appsid").toInt, 0) + i, 1d)
    i += dict("mediaid").size + 1

    //ad slot id
    els = els :+ (dict("slotid").getOrElse(x.getAs[String]("adslotid").toInt, 0) + i, 1d)
    i += dict("slotid").size + 1

    //0 to 4
    els = els :+ (x.getAs[Int]("phone_level") + i, 1d)
    i += 10

    //pagenum
    var pnum = x.getAs[Int]("pagenum")
    if (pnum < 0 || pnum > 50) {
      pnum = 0
    }
    els = els :+ (pnum + i, 1d)
    i += 100

    //bookid
    var bid = 0
    try {
      bid = x.getAs[String]("bookid").toInt
    } catch {
      case e: Exception =>
    }
    if (bid < 0 || bid > 50) {
      bid = 0
    }
    els = els :+ (bid + i, 1d)
    i += 100

    //ad class
    val adcls = dict("adclass").getOrElse(x.getAs[Int]("adclass"), 0)
    els = els :+ (adcls + i, 1d)
    i += dict("adclass").size + 1

    //adtype
    els = els :+ (x.getAs[Int]("adtype") + i, 1d)
    i += 10

    //adslot_type
    els = els :+ (x.getAs[Int]("adslot_type") + i, 1d)
    i += 10

    //planid
    els = els :+ (dict("planid").getOrElse(x.getAs[Int]("planid"), 0) + i, 1d)
    i += dict("planid").size + 1

    //unitid
    els = els :+ (dict("unitid").getOrElse(x.getAs[Int]("unitid"), 0) + i, 1d)
    i += dict("unitid").size + 1

    //ideaid
    els = els :+ (dict("ideaid").getOrElse(x.getAs[Int]("ideaid"), 0) + i, 1d)
    i += dict("ideaid").size + 1

    //user_req_ad_num
    var uran_idx = 0
    val uran = x.getAs[Int]("user_req_ad_num")
    if (uran >= 1 && uran <= 10 ){
      uran_idx = uran
    }
    if (uran > 10){
      uran_idx = 11
    }
    els = els :+ (uran_idx + i, 1d)
    i += 12 + 1

    //user_req_num
    var urn_idx = 0
    val urn = x.getAs[Int]("user_req_num")
    if (urn >= 1 && urn <= 10){
      urn_idx = 1
    }else if(urn > 10 && urn <= 100){
      urn_idx = 2
    }else if(urn > 100 && urn <= 1000){
      urn_idx = 3
    }else if(urn > 1000){
      urn_idx = 4
    }
    els = els :+ (urn_idx + i, 1d)
    i += 5 + 1

    //sex - age
    if (x.getAs[Int]("sex") > 0 && x.getAs[Int]("age") > 0){
      els = els :+ (6 * (x.getAs[Int]("sex") - 1) + x.getAs[Int]("age") + i, 1d)
    }
    i += 2 * 6 + 1

    //user installed app
    val appIdx = x.getAs[WrappedArray[Int]]("appIdx")
    if (appIdx != null){
      val inxList = appIdx.map(p => (p + i,1d))
      els = els ++ inxList
    }
    i += 1000 + 1

    try {
      Vectors.sparse(i, els)
    } catch {
      case e: Exception =>
        throw new Exception(els.toString + " " + i.toString + " " + e.getMessage)
        null
    }
  }
}
