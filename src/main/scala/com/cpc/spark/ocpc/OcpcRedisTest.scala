package com.cpc.spark.ocpc

import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.apache.spark.sql.functions._
import userprofile.Userprofile.UserProfile
import org.apache.spark.sql.functions.rand



object OcpcRedisTest {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // calculate time period for historical data
    val randSeed = args(0).toInt
    val dataset = spark.table("test.uid_userporfile_ctr_cvr").orderBy(rand(randSeed)).limit(20)
    for (row <- dataset.collect()) {
      val key = row.get(0).toString
      val ctrCnt = row.getLong(1)
      val cvrCnt = row.getLong(2)
//      val data = row.get(3).toString
      val kValue = key + "_UPDATA"
      println(s"$key, $ctrCnt, $cvrCnt")
      testPbRedis(kValue)
    }

  }


  def testPbRedis(key: String): Unit = {
    println("testPbRedis function: " + key)
    val conf = ConfigFactory.load()
    val redis = new RedisClient(conf.getString("redis.host"), conf.getInt("redis.port"))
    val buffer = redis.get[Array[Byte]](key).orNull
    var user: UserProfile.Builder = null
    if (buffer != null) {
      user = UserProfile.parseFrom(buffer).toBuilder
//      val u = user.build()
      println(user.getAge)
      println(user.getCtrcnt)
      println(user.getCvrcnt)
      //      redis.setex(key, 3600 * 24 * 7, user.build().toByteArray)
    }
    redis.disconnect
  }
}


