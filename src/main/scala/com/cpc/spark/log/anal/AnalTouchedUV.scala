package com.cpc.spark.log.anal

import java.util.Calendar

import com.cpc.spark.log.parser.{LogParser, UnionLog}
import com.redis.RedisClient
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.util.Random

/**
  * Created by Roy on 2017/5/18.
  *
  */
object AnalTouchedUV {

  /*统计维度
  地域
  性别   暂时通过百分比  1 => 50% 2 => 50%  0 => 100%
  年龄   暂时通过百分比  0 => 100%   1 - 6  => 20%
  人群分类
  操作系统
  网络环境
  投放时间
   */
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println(
        s"""
           |Usage: GetUserProfile <day_before> <int>
           |
        """.stripMargin)
      System.exit(1)
    }

    Logger.getRootLogger.setLevel(Level.WARN)
    val dayBefore = args(0).toInt
    val cal = Calendar.getInstance()
    cal.add(Calendar.DATE, -dayBefore)
    val date = LogParser.dateFormat.format(cal.getTime)
    val conf = ConfigFactory.load()
    val redis = new RedisClient(conf.getString("touched_uv.redis.host"), conf.getInt("touched_uv.redis.port"))

    val ctx = SparkSession.builder()
      .appName("anal ad touched query amount[%s]".format(date))
      .enableHiveSupport()
      .getOrCreate()
    import ctx.implicits._

    val log = ctx.sql("select * from dl_cpc.cpc_union_log where `date` = \"%s\"".format(date)).as[UnionLog]
    val ret = log.rdd
      .map {
        x =>
          val rndSex = Random.nextInt(2) + 1  //随机性别
          val rndAge = Random.nextInt(6) + 1 //随机年龄
          var lvl = 0
          if (x.coin < 10) {
            lvl = 1
          } else if (x.coin < 1000) {
            lvl = 2
          } else if (x.coin < 10000) {
            lvl = 3
          } else {
            lvl = 4
          }
          val cond = AnalCond(
            province = x.province,
            sex = rndSex,
            age = rndAge,
            coin_level = lvl,
            os = x.os,
            network = x.network,
            sum = 1,
            uid = x.uid,
            date = date
          )
          (cond.keyuid, cond)
      }
      .reduceByKey((x, y) => x)
      .map {
        x =>
          val v = x._2
          (v.key, v)
      }
      .reduceByKey((x, y) => x.sum(y))
      .map(_._2)
      .cache()

    println("count", ret.count())
    val ret1 = ret
      .flatMap(x => Seq(x, x.copy(sex = 0)))
      .flatMap(x => Seq(x, x.copy(age = 0)))
      .flatMap {
        x =>
          if (x.province > 0) {
            Seq(x, x.copy(province = 0))
          } else {
            Seq()
          }
      }
      .flatMap {
        x =>
          if (x.os > 0) {
            Seq(x, x.copy(os = 0))
          } else {
            Seq()
          }
      }
      .flatMap {
        x =>
          if (x.network > 0) {
            Seq(x, x.copy(network = 0))
          } else {
            Seq()
          }
      }
      .flatMap {
        x =>
          if (x.coin_level == 1) {
            Seq(x, x.copy(coin_level = 0), x.copy(coin_level = 2), x.copy(coin_level = 3), x.copy(coin_level = 4))
          } else if (x.coin_level == 2) {
            Seq(x, x.copy(coin_level = 0), x.copy(coin_level = 3), x.copy(coin_level = 4))
          } else if (x.coin_level == 3) {
            Seq(x, x.copy(coin_level = 0), x.copy(coin_level = 4))
          } else {
            Seq(x, x.copy(coin_level = 0))
          }
      }
      .map(x => (x.key, x))
      .reduceByKey((x, y) => x.sum(y))
      .map(_._2)
      .cache()

    ret.unpersist()
    println("ret1", ret1.count())
    ret1.toDF()
      .write
      .mode(SaveMode.Append)
      .partitionBy("date")
      .saveAsTable("dl_cpc.ad_touched_uv")

    ret1.toLocalIterator
      .foreach {
        x =>
          redis.set(x.key + "_TOUCHEDUV", x.sum)
      }

    ret1.unpersist()
    ctx.stop()
  }
}

case class AnalCond(
                   province: Int = 0,

                   //暂时按照100分比例来算
                   sex: Int = 0,
                   age: Int = 0,

                   //coin_level 注意保证和bs一致
                   //用户积分级别. 0默认全选 1第一档用户，积分在0-10分。 2第二档用户，积分在0-1000分。 3第三档用户，积分在0-10000分。4全选
                   coin_level: Int = 0,
                   os: Int = 0,
                   network: Int = 0,
                   sum: Int = 0,
                   uid: String = "",
                   date: String = ""

               ) {


  val key = "%d-%d-%d-%d-%d-%d".format(province, sex, age, coin_level, os, network)

  val keyuid = "%d-%d-%d-%d-%d-%d-%s".format(province, sex, age, coin_level, os, network, uid)

  def sum(k: AnalCond): AnalCond = {
    copy(sum = sum + k.sum)
  }
}



