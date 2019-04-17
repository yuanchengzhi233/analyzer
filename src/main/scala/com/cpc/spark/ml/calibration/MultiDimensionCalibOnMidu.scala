package com.cpc.spark.ml.calibration

import java.io.{File, FileOutputStream, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import com.cpc.spark.common.Utils
import com.cpc.spark.ml.calibration.HourlyCalibration._
import com.typesafe.config.ConfigFactory
import mlmodel.mlmodel.{CalibrationConfig, IRModel, PostCalibrations}
import org.apache.spark.SparkContext
import org.apache.spark.mllib.regression.IsotonicRegression
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import com.cpc.spark.ml.common.{Utils => MUtils}
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.sql.functions._


object MultiDimensionCalibOnMidu {

  val localDir = "/home/cpc/scheduled_job/hourly_calibration/"
  val destDir = "/home/work/mlcpp/calibration/"
  val MAX_BIN_COUNT = 10
  val MIN_BIN_SIZE = 10000

  def main(args: Array[String]): Unit = {

    // parse and process input
    val endDate = args(0)
    val endHour = args(1)
    val hourRange = args(2).toInt
    val softMode = args(3).toInt
    val calimodelname ="novel-ctr-dnn-rawid-v7-postcali"


    val endTime = LocalDateTime.parse(s"$endDate-$endHour", DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"))
    val startTime = endTime.minusHours(Math.max(hourRange - 1, 0))

    val startDate = startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val startHour = startTime.format(DateTimeFormatter.ofPattern("HH"))

    println(s"endDate=$endDate")
    println(s"endHour=$endHour")
    println(s"hourRange=$hourRange")
    println(s"startDate=$startDate")
    println(s"startHour=$startHour")
    println(s"softMode=$softMode")

    // build spark session
    val session = Utils.buildSparkSession("hourlyCalibration")

    val timeRangeSql = Utils.getTimeRangeSql_2(startDate, startHour, endDate, endHour)

    // get union log
    val sql = s"""
                 |select isclick, cast(raw_ctr as bigint) as ectr, show_timestamp, ctr_model_name, adslot_id, ideaid,
                 |case when user_req_ad_num = 1 then '1'
                 |  when user_req_ad_num = 2 then '2'
                 |  when user_req_ad_num in (3,4) then '4'
                 |  when user_req_ad_num in (5,6,7) then '7'
                 |  else '8' end as user_req_ad_num
                 | from dl_cpc.cpc_novel_union_events
                 | where $timeRangeSql
                 | and media_appsid in ('80001098', '80001292') and isshow = 1
                 | and ctr_model_name in ('novel-ctr-dnn-rawid-v7-cali','novel-ctr-dnn-rawid-v7-postcali')
                 | and ideaid > 0 and adsrc = 1 AND userid > 0
                 | AND (charge_type IS NULL OR charge_type = 1)
       """.stripMargin
    println(s"sql:\n$sql")
    val log = session.sql(sql)

    val group1 = log.groupBy("ideaid","user_req_ad_num","adslot_id").count().withColumn("count1",col("count"))
        .withColumn("group1",concat_ws("_",col("ideaid"),col("user_req_ad_num"),col("adslot_id")))
        .select("ideaid","user_req_ad_num","adslot_id","group1","count1")
    val data2 = log.join(group1,Seq("ideaid","user_req_ad_num","adslot_id"),"left")
      .filter("count1<100000")
    val group2 = data2.groupBy("ideaid","user_req_ad_num").count().withColumn("count2",col("count"))
      .withColumn("group2",concat_ws("_",col("ideaid"),col("user_req_ad_num")))
      .select("ideaid","user_req_ad_num","group2","count2")
    val group3 = data2.join(group2,Seq("ideaid","user_req_ad_num"),"left")
      .filter("count2<100000")
      .groupBy("ideaid").count().withColumn("count3",col("count"))
      .withColumn("group3",col("ideaid"))
      .select("ideaid","group3","count3")

    val keygroup = group1.join(group2,Seq("ideaid","user_req_ad_num"),"left").join(group3,Seq("ideaid"),"left")
        .withColumn("group",when(col("count1") < 100000,col("group2")).otherwise(col("group1")))
        .withColumn("group",when(col("count2") < 100000,col("group3")).otherwise(col("group")))
        .select("user_req_ad_num","adslot_id","ideaid","group","count3").distinct()
    keygroup.write.mode("overwrite").saveAsTable("test.calikey")
    keygroup.show(20)

      val data = log.join(keygroup,Seq("user_req_ad_num","adslot_id","ideaid"),"left")
        .select("user_req_ad_num","adslot_id","ideaid","isclick","ectr","show_timestamp","ctr_model_name","group","count3")
        .filter("count3>10000")
//  //
//      data.write.mode("overwrite").saveAsTable("test.wy01")
//
//      unionLogToConfig2(data.rdd, session, softMode, calimodelname)
  }


  def unionLogToConfig2(log: RDD[Row], session: SparkSession, softMode: Int, calimodelname: String, saveToLocal: Boolean = true,
                       minBinSize: Int = MIN_BIN_SIZE, maxBinCount : Int = MAX_BIN_COUNT, minBinCount: Int = 1): List[CalibrationConfig] = {
    val irTrainer = new IsotonicRegression()
    import session.implicits._
    val sc = session.sparkContext
    var auc = Seq[(Double,Double)]()
    var calimap = scala.collection.mutable.Map[String,CalibrationConfig]()
    val result = log.map( x => {
      var isClick = 0d
      if (x.get(3) != null) {
        isClick = x.getInt(3).toDouble
      }
      val ectr = x.getLong(4).toDouble / 1e6d
      val model = x.getString(6)
      val group = x.getString(7)
      val key = calimodelname + "_" + group
      (key, (ectr, isClick))
    }).groupByKey()
      .mapValues(
        x =>
          (binIterable(x, minBinSize, maxBinCount), Utils.sampleFixed(x, 100000))
//        x => {
//          val l = x.toList
//          val l1 = l.take(l.length - l.length/3)
//          val l2 = l.takeRight(l.length/3)
//          (binIterable(l1, minBinSize, maxBinCount), Utils.sampleFixed(l1.toIterable, 100000),Utils.sampleFixed(l2.toIterable, 100000))
//        }
          )
      .toLocalIterator
      .map {
        x =>
          val modelName: String = x._1
          val bins = x._2._1
          val samples = x._2._2
          val size = bins._2
          val positiveSize = bins._3
//          计算testauc
//          val test = x._2._3
//          val ScoreAndLabel = sc.parallelize(test)
//          val metrics = new BinaryClassificationMetrics(ScoreAndLabel)
//          val aucROC = metrics.areaUnderROC
          println(s"model: $modelName has data of size $size, of positive number of $positiveSize")
          println(s"bin size: ${bins._1.size}")
          if (size < minBinSize) {
            println("bin size too small, don't output the calibration")
            CalibrationConfig()
          } else {
            val irFullModel = irTrainer.setIsotonic(true).run(sc.parallelize(bins._1))
            val irModel = IRModel(
              boundaries = irFullModel.boundaries,
              predictions = irFullModel.predictions
            )
            println(s"bin size: ${irFullModel.boundaries.length}")
            println(s"calibration result (ectr/ctr) (before, after): ${computeCalibration(samples, irModel)}")
//            println(s"test (ectr/ctr) (before, after): ${computeCalibration(test, irModel)}")
//            val caliauc = getauccali(test, sc, irModel)
//            println(s"test auc(before, after): $aucROC,$caliauc")
//            auc = auc :+ (aucROC,caliauc)
            val config = CalibrationConfig(
              name = modelName,
              ir = Option(irModel)
            )
            calimap += ((modelName,config))
            config
          }
      }.toList
    val califile = PostCalibrations(calimap.toMap)
//    auc.toDF.write.mode("overwrite").saveAsTable("test.caliauc")
    if (saveToLocal) {
      val model = "novel-ctr-dnn-rawid-v7-cali"
      val localPath = saveProtoToLocal(model, califile)
      saveFlatTextFileForDebug(model, califile)
      if (softMode == 0) {
        val conf = ConfigFactory.load()
        println(MUtils.updateMlcppOnlineData(localPath, destDir + s"post-calibration-$model.mlm", conf))
        println(MUtils.updateMlcppModelData(localPath, newDestDir + s"post-calibration-$model.mlm", conf))
      }
    }
    return result
  }

  // input: (<ectr, click>)
  // output: original ectr/ctr, calibrated ectr/ctr
  def computeCalibration(samples: Array[(Double, Double)], irModel: IRModel): (Double, Double) = {
    var imp = 0.0
    var click = 0.0
    var ectr = 0.0
    var calibrated = 0.0
    samples.foreach(x => {
      imp += 1
      click += x._2
      ectr += x._1
      calibrated += computeCalibration(x._1, irModel)
    })
    return (ectr / click, calibrated / click)
  }

  def getauccali(samples: Array[(Double, Double)],sc: SparkContext, irModel: IRModel): Double = {
    //val data=Array(Double, Double)
    val dataListBuffer = scala.collection.mutable.ListBuffer[(Double,Double)]()
    samples.foreach(x => {
      val calibrated = computeCalibration(x._1, irModel)
      val label = x._2
      val t = (calibrated,label)
      dataListBuffer += t
    })
    val data = dataListBuffer.toArray
    val ScoreAndLabel = sc.parallelize(data)
    val metrics = new BinaryClassificationMetrics(ScoreAndLabel)
    val aucROC = metrics.areaUnderROC
    return aucROC
  }

  def binarySearch(num: Double, boundaries: Seq[Double]): Int = {
    if (num < boundaries(0)) {
      return 0
    }
    if (num >= boundaries.last) {
      return boundaries.size
    }
    val mid = boundaries.size / 2
    if (num < boundaries(mid)) {
      return binarySearch(num, boundaries.slice(0, mid))
    } else {
      return binarySearch(num, boundaries.slice(mid, boundaries.size)) + mid
    }
  }

  def computeCalibration(prob: Double, irModel: IRModel): Double = {
    if (prob <= 0) {
      return 0.0
    }
    var index = binarySearch(prob, irModel.boundaries)
    if (index == 0) {
      return Math.max(0.0, irModel.predictions(0) * (prob - irModel.boundaries(0)))
    }
    if (index == irModel.boundaries.size) {
      index = index - 1
    }
    return Math.max(0.0, Math.min(1.0, irModel.predictions(index-1) +
      (irModel.predictions(index) - irModel.predictions(index-1))
        * (prob - irModel.boundaries(index-1))
        / (irModel.boundaries(index) - irModel.boundaries(index-1))))
  }

  def saveProtoToLocal(modelName: String, config: PostCalibrations): String = {
    val filename = s"post-calibration-$modelName.mlm"
    val localPath = localDir + filename
    val outFile = new File(localPath)
    outFile.getParentFile.mkdirs()
    config.writeTo(new FileOutputStream(localPath))
    return localPath
  }

  def saveFlatTextFileForDebug(modelName: String, config: PostCalibrations): Unit = {
    val filename = s"post-calibration-flat-$modelName.txt"
    val localPath = localDir + filename
    val outFile = new File(localPath)
    outFile.getParentFile.mkdirs()
    new PrintWriter(localPath) { write(config.toString); close() }
  }

  // input: Seq<(<ectr, click>)
  // return: (Seq(<ctr, ectr, weight>), total count)
  def binIterable(data: Iterable[(Double, Double)], minBinSize: Int, maxBinCount: Int)
  : (Seq[(Double, Double, Double)], Double, Double) = {
    val dataList = data.toList
    val totalSize = dataList.size
    val binNumber = Math.min(Math.max(1, totalSize / minBinSize), maxBinCount)
    val binSize = totalSize / binNumber
    var bins = Seq[(Double, Double, Double)]()
    var allClickSum = 0d
    var clickSum = 0d
    var showSum = 0d
    var eCtrSum = 0d
    var n = 0
    dataList.sorted.foreach {
      x =>
        var ectr = 0.0
        if (x._1 > 0) {
          ectr = x._1
        }
        eCtrSum = eCtrSum + ectr
        if (x._2 > 1e-6) {
          clickSum = clickSum + 1
          allClickSum = allClickSum + 1
        }
        showSum = showSum + 1
        if (showSum >= binSize) {
          val ctr = clickSum / showSum
          bins = bins :+ (ctr, eCtrSum / showSum, 1d)
          n = n + 1
          clickSum = 0d
          showSum = 0d
          eCtrSum = 0d
        }
    }
    return (bins, totalSize, allClickSum)
  }
}