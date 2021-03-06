package com.cpc.spark.ocpcV3.HP

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.fpm.FPGrowth
import java.util.Date

object Lab2 {
  def main(args: Array[String]): Unit = {
    val date = args(0).toString
    val minSupport = args(1).toDouble
    val minConfidence = args(2).toDouble
    val numPartition = args(3).toInt
    val spark = SparkSession.builder().appName("Lab").enableHiveSupport().getOrCreate()
    import spark.implicits._

    val baseData0 = getBaseData(spark, date)
    val baseData = baseData0.rdd.map(x => x.getAs[String]("appNames").split(",")).persist()
    print("baseData has " + baseData.count() + " elements")

    val model = new FPGrowth().setMinSupport(minSupport).setNumPartitions(numPartition).run(baseData)
    println("note1")
    val freqItemsets = model.freqItemsets
    println("note2")
    val lb1 = scala.collection.mutable.ListBuffer[ItemsetFreq]()
    freqItemsets.collect.foreach(itemset => {
      val items = itemset.items //Array[String]
      val freq = itemset.freq //Long
      lb1 += ItemsetFreq(items.sorted.mkString("{", ",", "}"), freq)
      println( items.sorted.mkString("{", ",", "}")+" : "+ freq )
      print("#")
    })
    println("note3")
    lb1.toDF("itemset", "freq").write.mode("overwrite").saveAsTable("test.itemsetfreq_sjq")

    val associationRules = model.generateAssociationRules(minConfidence)
    println("note4")
    val lb2 = scala.collection.mutable.ListBuffer[AnteConsConf]()
    associationRules.collect.foreach(rule => {
      val ante = rule.antecedent //Array[String]
      val cons = rule.consequent //Array[String]
      val conf = rule.confidence //Double
      lb2 += AnteConsConf(ante.sorted.mkString("{", ",", "}"), cons.sorted.mkString("{", ",", "}"), conf)
      println( ante.sorted.mkString("{", ",", "}") + "=>" +cons.sorted.mkString("{", ",", "}") + " : " + conf )
      print("@")
    })
    println("note5")
    lb2.toDF("ante", "cons", "conf").write.mode("overwrite").saveAsTable("test.AnteConsConf_sjq")

  }

  def getBaseData(spark: SparkSession, date: String) = {
    val sqlRequest = s"select * from test.associationRule_base_data_sjq"
    val df = spark.sql(sqlRequest)
    df
  }

  case class ItemsetFreq(var itemset: String, var freq: Double)

  case class AnteConsConf(var ante: String, var cons: String, var conf: Double)

}








