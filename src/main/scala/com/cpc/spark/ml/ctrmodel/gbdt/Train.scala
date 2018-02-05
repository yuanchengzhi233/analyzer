package com.cpc.spark.ml.ctrmodel.gbdt

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import ml.dmlc.xgboost4j.scala.spark.XGBoostEstimator
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.linalg.{Vector, VectorUDT, Vectors}
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler, VectorIndexer}
import org.apache.spark.ml.regression.{GBTRegressionModel, GBTRegressor}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD

import scala.util.Random

/**
  * Created by roydong on 31/01/2018.
  */
object Train {

  Logger.getRootLogger.setLevel(Level.WARN)

  val cols = Seq(
    "sex", "age", "os", "isp", "network", "city",
    "mediaid_", "adslotid_", "phone_level", "adclass",
    "pagenum", "bookid_", "adtype", "adslot_type", "planid",
    "unitid", "ideaid", "user_req_ad_num", "user_req_num"
  )

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder()
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .appName("xgboost")
      .enableHiveSupport()
      .getOrCreate()

    var pathSep = Seq[String]()
    val cal = Calendar.getInstance()
    for (n <- 1 to 5) {
      val date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime)
      val hour = new SimpleDateFormat("HH").format(cal.getTime)
      pathSep = pathSep :+ date
      cal.add(Calendar.DATE, -1)
    }

    val path = "/user/cpc/lrmodel/ctrdata_v1/{%s}/*".format(pathSep.mkString(","))
    println(path)
    val data = spark.read.parquet(path)

    val totalNum = data.count().toDouble
    val pnum = data.filter(x => x.getAs[Int]("label") > 0).count().toDouble
    val rate = (pnum * 10 / totalNum * 1000).toInt
    println(pnum, totalNum, rate)
    val tmp = data.filter(x => x.getAs[Int]("label") > 0 || Random.nextInt(1000) < rate)
    val sample = getLimitedData(2e7, tmp)

    val Array(train, test) = sample.randomSplit(Array(0.9, 0.1), 123L)

    val mi = new StringIndexer()
      .setInputCol("media_appsid")
      .setOutputCol("mediaid_")
      .setHandleInvalid("skip")

    val si = new StringIndexer()
      .setInputCol("adslotid")
      .setOutputCol("adslotid_")
      .setHandleInvalid("skip")

    val bi = new StringIndexer()
      .setInputCol("bookid")
      .setOutputCol("bookid_")
      .setHandleInvalid("skip")

    val vectorAssembler = new VectorAssembler()
      .setInputCols(cols.toArray)
      .setOutputCol("features")

    val vi = new VectorIndexer()
      .setInputCol("f1")
      .setOutputCol("features")
      .setMaxCategories(10000)

    /*
    // Train a GBT model.
    val gbt = new GBTRegressor()
      .setLabelCol("label")
      .setFeaturesCol("f2")
      .setMaxBins(10000)
      .setMaxIter(10)
      */

    val xgb = new XGBoostEstimator(Map[String, Any]("num_rounds" -> 10))

    // Chain indexer and GBT in a Pipeline.
    val pipeline = new Pipeline()
      .setStages(Array(mi, si, bi, vectorAssembler, xgb))

    val model = pipeline.fit(train)

    val predictions = model.transform(test)

    val evaluator = new RegressionEvaluator()
      .setLabelCol("label")
      .setPredictionCol("prediction")
      .setMetricName("rmse")
    val rmse = evaluator.evaluate(predictions)





    println("Root Mean Squared Error (RMSE) on test data = " + rmse)
  }

  //限制总的样本数
  def getLimitedData(limitedNum: Double, ulog: DataFrame): DataFrame = {
    var rate = 1d
    val num = ulog.count().toDouble

    if (num > limitedNum){
      rate = limitedNum / num
    }

    ulog.randomSplit(Array(rate, 1 - rate), new Date().getTime)(0).coalesce(1000)
  }

  def getLrTestLog(lrTestResults: RDD[(Double, Double)]): Unit = {
    val testSum = lrTestResults.count()
    if (testSum < 0) {
      throw new Exception("must run lr test first or test results is empty")
    }
    var test0 = 0
    var test1 = 0
    lrTestResults
      .map {
        x =>
          var label = 0
          if (x._2 > 0.01) {
            label = 1
          }
          (label, 1)
      }
      .reduceByKey((x, y) => x + y)
      .toLocalIterator
      .foreach {
        x =>
          if (x._1 == 1) {
            test1 = x._2
          } else {
            test0 = x._2
          }
      }

    var log = "predict distribution %s %d(1) %d(0)\n".format(testSum, test1, test0)
    lrTestResults
      .map {
        x =>
          val v = (x._1 * 100).toInt / 5
          ((v, x._2.toInt), 1)
      }
      .reduceByKey((x, y) => x + y)
      .map {
        x =>
          val key = x._1._1
          val label = x._1._2
          if (label == 0) {
            (key, (x._2, 0))
          } else {
            (key, (0, x._2))
          }
      }
      .reduceByKey((x, y) => (x._1 + y._1, x._2 + y._2))
      .sortByKey(false)
      .toLocalIterator
      .foreach {
        x =>
          val sum = x._2
          log = log + "%.2f %d %.4f %.4f %d %.4f %.4f %.4f\n".format(x._1.toDouble * 0.05,
            sum._2, sum._2.toDouble / test1.toDouble, sum._2.toDouble / testSum.toDouble,
            sum._1, sum._1.toDouble / test0.toDouble, sum._1.toDouble / testSum.toDouble,
            sum._2.toDouble / (sum._1 + sum._2).toDouble)
      }

    println(log)
  }
}



