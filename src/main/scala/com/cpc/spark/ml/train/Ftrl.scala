package com.cpc.spark.ml.train

import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ListBuffer
import com.alibaba.fastjson.{JSON, JSONObject}
import mlmodel.mlmodel.Dict
import org.apache.spark.sql.SparkSession

import scala.collection.mutable


class Ftrl(size: Int) {

  var alpha: Double = 0.01
  var beta: Double = 1.0
  var L1: Double = 0.0
  var L2: Double = 0.0
  var n, z, w = new Array[Double](size)
  var dict: Dict = Dict()


  def toJsonString(): String = {
    val json = new JSONObject()
    json.put("alpha", alpha)
    json.put("beta", beta)
    json.put("L1", L1)
    json.put("L2", L2)
    json.put("n", n.mkString(" "))
    json.put("z", z.mkString(" "))
    json.put("w", w.mkString(" "))
    json.put("dict_advertiser", mapToJson(dict.advertiserid))
    json.put("dict_plan", mapToJson(dict.planid))
    json.put("dict_idea", mapToJson(dict.ideaid))
    json.toJSONString()
  }

  def fromJsonString(jsonString: String): Unit = {
    val json = JSON.parseObject(jsonString)
    w = json.getString("w").split(" ").map(_.toDouble)
    n = json.getString("n").split(" ").map(_.toDouble)
    z = json.getString("z").split(" ").map(_.toDouble)
    alpha = json.getDoubleValue("alpha")
    beta = json.getDoubleValue("beta")
    L1 = json.getDoubleValue("L1")
    L2 = json.getDoubleValue("L2")
    dict = Dict(
      planid = jsonToIntMap(json.getJSONObject("dict_plan")),
      advertiserid = jsonToIntMap(json.getJSONObject("dict_advertiser")),
      ideaid = jsonToIntMap(json.getJSONObject("dict_idea"))
    )
  }

  def mapToJson[T, U](map: Map[T, U]): JSONObject = {
    val json = new JSONObject()
    map.foreach( x => json.put(x._1.toString, x._2))
    return json
  }

  def jsonToIntMap(json:JSONObject): Map[Int, Int] = {
    val map = mutable.Map[Int, Int]()
    val keyIt = json.keySet().iterator()
    while (keyIt.hasNext) {
      val key = keyIt.next()
      map.put(key.toInt, json.getIntValue(key))
    }
    return map.toMap
  }

  def print() = {
    println("n:")
    println(n.mkString(" "))
    println("z:")
    println(z.mkString(" "))
    println("w:")
    println(w.mkString(" "))
    println("alpha:")
    println(alpha)
    println("beta:")
    println(beta)
    println("L1:")
    println(L1)
    println("L2:")
    println(L2)
    println("dict_advertiser (top 5):")
    println(dict.advertiserid.toArray.slice(0, Math.min(5, dict.advertiserid.size)))
    println("dict_idea (top 5):")
    println(dict.ideaid.toArray.slice(0, Math.min(5, dict.ideaid.size)))

  }

  def predict(x: Array[Int]): Double = {
    var wTx = 0.0

    x foreach { x =>
      val sign = if (z(x) < 0) -1.0 else 1.0

      if (sign * z(x) <= L1)
        w(x) = 0.0
      else
        w(x) = (sign * L1 - z(x)) / ((beta + math.sqrt(n(x))) / alpha + L2)

      wTx = wTx + w(x)
    }
    return 1.0 / (1.0 + math.exp(-wTx))
  }

  def update(x: Array[Int], p: Double, y: Double): Unit = {
    val g = p - y

    x foreach { x =>
      val sigma = (math.sqrt(n(x) + g * g) - math.sqrt(n(x))) / alpha
      z(x) = z(x) + g - sigma * w(x)
      n(x) = n(x) + g * g
    }

  }

  def predictAndAuc(session: SparkSession, instances: Array[LabeledPoint]): Double = {
    val listBuffer = new ListBuffer[(Double, Double)]
    for (instance <- instances) {
      val x = instance.features.toSparse.indices
      val pre = predict(x)
      listBuffer.append((pre, instance.label))
    }
    val metrics = new BinaryClassificationMetrics(session.sparkContext.parallelize(listBuffer))
    val auc = metrics.areaUnderROC()
    return auc
  }

  def train(spark:  SparkSession, data: RDD[LabeledPoint]): Unit = {
    var posCount = 0
    val res = data.collect()
    val beforeAUC = predictAndAuc(spark, res)
    println(s"before training auc: $beforeAUC")
    for (p <- res) {
      val x = p.features.toSparse.indices
      val pre = predict(x)
      update(x, pre, p.label)
      if (p.label > 0) {
        posCount += 1
      }
    }
    println(s"posCount=$posCount, totalCount=${res.size}")
    val afterAUC = predictAndAuc(spark, res)
    println(s"after training auc=$afterAUC")
  }
}
