package com.cpc.spark.qukan.interest

import org.apache.spark.ml.classification.LogisticRegressionModel
import org.apache.spark.ml.linalg.Vectors
import com.typesafe.config.ConfigFactory
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.sql.{Row, SparkSession}
import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._

import scala.util.control._
import ml.dmlc.xgboost4j.scala.spark._

import scala.collection.mutable

/**
  * Created by roydong on 11/06/2018.
  */
object PredictAge {

  val words_fnum = 41e4
  val leaf_num = 64
  val round_num = 50
  def main(args: Array[String]): Unit = {
    val days = args(0).toInt
    val spark = SparkSession.builder()
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryoserializer.buffer.max", "2047MB")
      .appName("age model".format())
      .enableHiveSupport()
      .getOrCreate()
    import spark.implicits._

    val sample = spark.read.parquet("/user/cpc/qtt-age-sample/p1").rdd
      .map {
        r =>
          val did = r.getAs[String]("did")
          val brand = r.getAs[Int]("brand")
          val apps = r.getAs[Seq[Row]]("apps")

          val size = 1002
          var els = Seq[(Int, Double)]()
          if (apps != null && apps.length > 0) {
            apps.foreach {
              app =>
                els = els :+ (app.getInt(1), 1d)
            }

          }
          if (brand != null) {
            els = els :+ (1000, brand * 1d)
          }
          if (apps != null) {
            els = els :+ (1001, brand * 1d)
          }
          val vec = Vectors.sparse(size.toInt, els)
          (did, vec)
      }
      .toDF("did", "features")

    println(sample.count())
    sample.rdd.take(10).foreach(println)
    val lr = LogisticRegressionModel.load("/user/cpc/qtt-age-lrmodel/%s".format(days))
    val xgb = XGBoostModel.load("/user/cpc/qtt-age-xgmodel/%s".format(days))
    val xg_result = xgb.transformLeaf(sample)
    val lr_input = xg_result.rdd.map {
      r =>
        val did = r.getAs[String]("did")
        val leaf = r.getAs[mutable.WrappedArray[Float]](2).toArray
        var n_els = Seq[(Int, Double)]()

        for (i <- 0 to round_num - 1) {
          n_els = n_els :+ ((i * leaf_num + leaf(i)).toInt, 1d)
        }
        var vec = Vectors.sparse((leaf_num * round_num), n_els)
        (did, vec)
    }.toDF("did", "features")
    val result = lr.transform(lr_input)
    val f = args(1).toDouble
    val m = args(2).toDouble
    val conf = ConfigFactory.load()
    var st : String = ""
    val bst = spark.sparkContext.broadcast(st)
    val predict = result.rdd.map {
      r =>
        val did = r.getAs[String]("did")
        val score = r.getAs[Vector]("probability").toArray
        (did, score(1))
    }.filter(x => x._2 > m)
    predict.take(10).foreach(println)
    val sum =  predict.repartition(500)
      .mapPartitions {
        p =>
          var bbst = bst.value
          var insert = 0
          var revert = 0
          var both_taged = 0
          var total = 0
          var count_224 = 0
          var count_225 = 0
          val redis = new RedisClient(conf.getString("redis.host"), conf.getInt("redis.port"))
          val loop = new Breaks
          p.foreach {
            r =>
              total += 1
              val key = r._1 + "_UPDATA"
              val buffer = redis.get[Array[Byte]](key).getOrElse(null)
              if (buffer != null) {
                var age = 0
                if (r._2 > m) {
                  age = 225
                  count_225 += 1
                } else if (r._2 < f){
                  age = 224
                  count_224 += 1
                }
                val user = UserProfile.parseFrom(buffer).toBuilder
                val in = InterestItem.newBuilder()
                  .setTag(age)
                  .setScore(100)
                var has = false
                var conflict = false
                var age_224 = false
                var age_225 = false

                loop.breakable {
                  var idx = 0
                  while (idx < user.getInterestedWordsCount) {
                    val w = user.getInterestedWords(idx)
                    if (w.getTag == 224 || w.getTag == 225) {
                      if (w.getTag == 224) age_224 = true
                      if (w.getTag == 225) age_225 = true
                      has = true
                      if (w.getTag != in.getTag) {
                        conflict = true
                      }
                      user.removeInterestedWords(idx)
                    } else {
                      idx += 1
                    }
                    if (idx == user.getInterestedWordsCount) {
                      loop.break()
                    }
                  }
                }
                if (in.getTag != 0) {
                  user.addInterestedWords(in)
                  if (!has) {
                    insert += 1
                  } else if (age_224 && age_225) {
                    both_taged += 1
                  } else if (conflict) {
                    revert += 1
                  }
                  //redis.setex(key, 3600 * 24 * 7, user.build().toByteArray)
                }
              }
          }
          bbst = bbst + "%d ".format(insert) + "%d ".format(revert) + "%d ".format(both_taged) + "%d ".format(total)
          Seq((0, insert), (1, revert), (2, both_taged), (3, total), (4, count_224), (5, count_225)).iterator
      }
    println(st)
    //统计数据
    var n = 0
    var n1 = 0
    var n2 = 0
    var n3 = 0
    var n4 = 0
    var n5 = 0
    sum.reduceByKey((x, y) => x + y)
      .take(6)
      .foreach {
        x =>
          if (x._1 == 0) {
            n = x._2
          } else if (x._1 == 1) {
            n1 = x._2
          } else if (x._1 == 2) {
            n2 = x._2
          } else if (x._1 == 3){
            n3 = x._2
          } else if (x._1 == 4){
            n4 = x._2
          } else {
            n5 = x._2
          }
      }
    println("total: %s, insert: %s, revert %s, both_taged %s count_224: %s count_225 %s".format(n3, n, n1, n2, n4, n5))
  }
}
