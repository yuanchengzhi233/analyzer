package com.cpc.spark.qukan.userprofile


import java.sql.Timestamp

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession
import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits._
import com.typesafe.config.ConfigFactory
import userprofile.Userprofile.{InterestItem, QttProfile, UserProfile}

/**
  * Created by roydong on 12/07/2018.
  */
object TeacherStudents {

  def main(args: Array[String]): Unit = {

    val date = args(0)
    val n = 20
    val spark = SparkSession
      .builder()
      .appName("teacher students " + date)
      .enableHiveSupport().getOrCreate()

    val sql =
      """
        |select device_code, member_id, nickname, wx_nickname, teacher_id, update_time
        | from gobblin.qukan_p_member_info where day = "%s"
      """.stripMargin.format(date)

    val users = spark.sql(sql).rdd
      .map {
        r =>
          val devid = r.getAs[String]("device_code")
          val mid = r.getAs[Long]("member_id")
          val nickname = r.getAs[String]("nickname")
          val wxname = r.getAs[String]("wx_nickname")
          val tmid = r.getAs[Long]("teacher_id")
          val uptime = r.getAs[Timestamp]("update_time").getTime

          (devid, mid, nickname, wxname, tmid, uptime)
      }
      .filter(_._1.length > 0)
      .cache()

    users.take(10).foreach(println)

    val mid = users.map(x => (x._2, x))

    val ts = users
      .map {
        x =>
          (x._5, Seq(x))
      }
      .reduceByKey(_ ++ _)
      .map {
        x =>
          (x._1, x._2.sortBy(v => -v._6).take(n))
      }
      .join(mid)
      .map {
        x =>
          val students = x._2._1
          val teacher = x._2._2
          (teacher, students)
      }

    ts.take(10).foreach(println)

    val sum = ts
      .mapPartitions {
        p =>
          var n = 0
          var n1 = 0
          val conf = ConfigFactory.load()
          val redis = new RedisClient(conf.getString("redis.host"), conf.getInt("redis.port"))
          p.foreach {
            x =>
              val t = x._1

              val key = x._1 + "_UPDATA"
              val buffer = redis.get[Array[Byte]](key).orNull
              if (buffer != null) {
                val user = UserProfile.parseFrom(buffer).toBuilder
                val qtt = user.getQttProfile.toBuilder
                n = n + 1

                val teacher = qtt.getTeacher.toBuilder
                teacher.setDevid(t._1)
                teacher.setMemberId(t._2)
                teacher.setNickname(t._3)
                teacher.setWxNickname(t._4)
                qtt.setTeacher(teacher)

                qtt.clearStudents()
                x._2.foreach {
                  v =>
                    val s = QttProfile.newBuilder()
                    s.setDevid(v._1)
                    s.setMemberId(v._2)
                    s.setNickname(v._3)
                    s.setWxNickname(v._4)
                    qtt.addStudents(s)
                }

                user.setQttProfile(qtt)
                //redis.setex(key, 3600 * 24 * 7, user.build().toByteArray)
              }
          }
          Seq(n).iterator
      }

    println("update", sum.sum)
  }
}
