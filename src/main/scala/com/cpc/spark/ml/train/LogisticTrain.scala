package com.cpc.spark.ml.train

import org.apache.log4j.{Level, Logger}
import org.apache.spark.mllib.feature.Normalizer
import org.apache.spark.mllib.classification.LogisticRegressionWithLBFGS
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.util.Random

/**
  * Created by Roy on 2017/5/15.
  */
object LogisticTrain {

  def main(args: Array[String]): Unit = {
    Logger.getRootLogger().setLevel(Level.WARN)
    val ctx = SparkSession.builder()
      .appName("cpc training logistic model")
      .getOrCreate()
    val sc = ctx.sparkContext
    val parsedData = MLUtils.loadLibSVMFile(sc, args(0))
    val nor = new Normalizer()
    val splits = parsedData
      //random pick 1/20 negative sample
      .filter(x => x.label > 0.01 || Random.nextInt(20) == 1)
      .map(x => new LabeledPoint(x.label, nor.transform(x.features)))
      .randomSplit(Array(0.99,0.01), seed = 1314159L)

    val training = splits(0).cache()
    val test = splits(1)

    println("sample count", training.count())
    training
      .map {
        x =>
          var label = 0
          if (x.label > 0.01) {
            label = 1
          }
          (label, 1)
      }
      .reduceByKey((x, y) => x + y)
      .toLocalIterator
      .foreach(println)

    println("training ...")
    val model = new LogisticRegressionWithLBFGS()
      .setNumClasses(2)
      .run(training)
    training.unpersist()
    println("done")

    println("testing...")
    model.clearThreshold()
    val predictionAndLabels = test.map {
      case LabeledPoint(label, features) =>
        val prediction = model.predict(features)
        (prediction, label)
    }.cache()
    println("done")

    println("predict distribution", predictionAndLabels.count())
    predictionAndLabels
      .map {
        x =>
          ("%.1f-%.0f".format(x._1, x._2), 1)
      }
      .reduceByKey((x, y) => x + y)
      .toLocalIterator
      .foreach {
        x =>
          println(x)
      }

    ctx.createDataFrame(predictionAndLabels)
      .write
      .mode(SaveMode.Overwrite)
      .text("/user/cpc/test_result/v1")

    predictionAndLabels.unpersist()
    println("save model")
    model.save(sc, "/user/cpc/model/v1/")
    sc.stop()
  }
}
