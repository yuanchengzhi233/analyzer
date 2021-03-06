package com.cpc.spark.qukan.interest

import java.io.{FileWriter, PrintWriter}
import java.sql.{DriverManager, ResultSet}
import java.text.SimpleDateFormat
import java.util.{Calendar, Properties}

import com.cpc.spark.common.Utils
import com.cpc.spark.log.parser.{ExtValue, TraceLog, UnionLog}
import com.cpc.spark.ml.train.LRIRModel
import com.cpc.spark.qukan.parser.HdfsParser
import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.corpus.tag.Nature
import com.typesafe.config.ConfigFactory
import org.apache.spark.mllib.clustering.{KMeans, KMeansModel}
import org.apache.spark.mllib.feature.{Word2Vec, Word2VecModel}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession, Row}

import scala.io.Source
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.Random
import com.redis.serialization.Parse.Implicits._
import com.redis.RedisClient
import com.cpc.spark.qukan.parser.HdfsParser
import userprofile.Userprofile.{InterestItem, UserProfile}
import scala.util.control._

object CheckUserApp {
  def main(args: Array[String]): Unit = {
    val days  = args(0).toInt
    val spark = SparkSession.builder()
      .appName("check user app")
      .enableHiveSupport()
      .getOrCreate()

    val sample = spark.read.parquet("/user/cpc/qtt-age-sample/%s".format(days)).rdd
      .map {
        r =>
          val did = r.getAs[String]("did")
          val apps = r.getAs[Seq[Row]]("apps")
          val birth = r.getAs[Int]("birth")
          if (apps != null) {
            (did, apps.length, birth, apps)
          } else {
            null
          }

      }
      .filter(_ != null)
    val young = sample.filter(x => x._3 < 22).count()
    println(young)
    println(sample.filter(x => x._3 < 22 && x._2 < 10).count())
    println(sample.filter(x => x._3 >= 22).count())
    println(sample.filter(x => x._3 >= 22 && x._2 < 10).count())

    sample.filter(x => x._3 < 22 && x._2 < 10).take(10).foreach(println)
    println("#####")
    println(sample.filter(x => x._3 < 22 && x._2 > 15).count())
    sample.filter(x => x._3 < 22 && x._2 > 15).take(10).foreach(println)

    sample.filter(x => x._3 < 22).flatMap {
      r =>
        r._4.map{
          x =>
            (x.getAs[String](0), 1)
        }
    }.reduceByKey(_+_)
      .map {
        r =>
          (r._1, 1d * r._2 / young)
      }
      .sortBy(_._2, false)
      .take(50).foreach(println)

    val all_sample = spark.read.parquet("/user/cpc/qtt-age-sample/p1").rdd.map {
      r =>
        val apps = r.getAs[Seq[Row]]("apps")
        if (apps != null) {
          if (apps.length > 5){
            (1)
          } else {
            (0)
          }
        } else {
          null
        }
    }.filter(_ != null)

    println(all_sample.filter(_ == 1).count())
    println(all_sample.filter(_ == 0).count())


  }
}
