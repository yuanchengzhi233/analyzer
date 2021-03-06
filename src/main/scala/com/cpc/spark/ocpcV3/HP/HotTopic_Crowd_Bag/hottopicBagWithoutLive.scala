package com.cpc.spark.ocpcV3.HP.HotTopic_Crowd_Bag

import com.cpc.spark.qukan.userprofile.SetUserProfileTag.SetUserProfileTagInHiveDaily
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import java.text.SimpleDateFormat
import java.util.Calendar

object hottopicBagWithoutLive {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("hottopicBagWithoutLive").enableHiveSupport().getOrCreate()

    val date = args(0).toString
    val days = args(1).toInt

    val liveApp = getLiveApp(spark) // appName

    val pkgAppMap = getPkgAppMap(spark, date) // comb, appName, pkg, count

    val liveComb = liveApp.join(pkgAppMap, Seq("appName")).select("appName", "comb") //appName, comb

    liveComb.write.mode("overwrite").saveAsTable("test.liveComb_sjq")

    val uidWithoutLive = getUidWithoutLive(spark, date, days - 1, liveComb) // uid, tag, date

//    create table if not exists dl_cpc.hottopic_crowd_bag_collection_sjq
//    (
//      uid STRING COMMENT '用户id'
//    )
//    comment '段子人群包汇总'
//    partitioned by (tag Int, `date` string);

//    uidWithoutLive.write.mode("overwrite").saveAsTable("test.uidWithLive_sjq")
    uidWithoutLive.repartition(10).write.mode("overwrite").insertInto("dl_cpc.hottopic_crowd_bag_collection_sjq")

    update(spark, date)

  }

  def getLiveApp(spark: SparkSession) = {
    import spark.implicits._
    val live = List("火山小视频", "映客", "花椒直播", "石榴直播", "斗鱼直播", "水多直播", "丁香直播", "盒子直播", "深入直播", "一直播", "番茄直播", "快手美女秀", "香蕉直播", "妖娆直播", "小宝贝直播", "易直播", "猫咪视频直播", "快猫直播", "蜜秀直播",
      "快狐直播", "么么直播", "直播吧", "辣舞直播", "大秀直播", "樱桃直播", "浴火直播", "诱火", "嗨秀秀场", "哇塞直播", "小蛮腰直播", "蜜聊直播", "蜜疯直播", "棉花糖", "陌秀直播", "NOW直播", "夜嗨直播", "蜜兔直播", "花间娱乐美女视频直播交友", "水滴直播",
      "要播直播", "伊人直播", "NN直播", "红人直播", "Z直播", "比心直播", "来疯直播", "酷咪直播", "九秀美女直播")
    val lb = scala.collection.mutable.ListBuffer[LiveApp]()
    for (app <- live) {
      lb += LiveApp(app)
    }
    lb.distinct.toDF("appName")
  }

  def getPkgAppMap(spark: SparkSession, date: String) = {
    import spark.implicits._
    val sql1 =
      s"""
         |select
         | concat_ws(',', app_name) as pkgs1
         |from dl_cpc.cpc_user_installed_apps a
         |where load_date = '$date'
       """.stripMargin

    val pkgs = spark.sql(sql1)
    val result = pkgs.rdd
      .map(x => x.getAs[String]("pkgs1"))
      .flatMap(x => x.split(","))
      .map(x => (x, 1))
      .reduceByKey((x, y) => x + y)
      .map(x => (x._1, x._1.split("-"), x._2))
      .map(x =>
        if (x._2.length > 1) {
          AppPkgMap(x._1, x._2(0), x._2(1), x._3)
        } else {
          AppPkgMap(x._1, x._2(0), "", x._3)
        }
      ).toDF()
    result
  }

  def getUidWithoutLive(spark: SparkSession, date: String, days: Int, LiveComb: DataFrame) = {
    import spark.implicits._
    val sdf = new SimpleDateFormat("yyyy-MM-dd")
    val calendar = Calendar.getInstance()
    val today = sdf.parse(date)
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -days)
    val firstDay = calendar.getTime
    val date0 = sdf.format(firstDay)

    val sql1 =
      s"""
         | select
         |    t1.uid,
         |    t2.pkgs1
         |from (
         |        select
         |          `date` as dt, uid
         |         from dl_cpc.cpc_hot_topic_union_log b
         |        where `date` between '$date0' and '$date'
         |          and isshow = 1
         |          and ext['antispam'].int_value = 0  --反作弊标记：1作弊，0未作弊
         |          and userid > 0 -- 广告主id
         |          and adsrc = 1  -- cpc广告（我们这边的广告，非外部广告）
         |          and media_appsid = '80002819'
         |        group by `date`, uid
         |       ) t1
         | join (
         |       select
         |        load_date,
         |        uid,
         |        concat_ws( ',', app_name ) as pkgs1
         |       from dl_cpc.cpc_user_installed_apps
         |      where load_date between '$date0' and '$date'
         |      group by load_date, uid, app_name
         |     ) t2
         | on t1.dt = t2.load_date and t1.uid = t2.uid
         | group by t1.uid, t2.pkgs1
       """.stripMargin
    println(sql1)

    val df = spark.sql(sql1)
    val result = df.rdd
      .map(x => (x.getAs[String]("uid"), x.getAs[String]("pkgs1").split(",")))
      .flatMap(x => {
        val uid = x._1
        val pkgs = x._2
        val lb = scala.collection.mutable.ListBuffer[UidComb]()
        for (comb <- pkgs) {
          lb += UidComb(uid, comb)
        }
        lb.distinct
      }).toDF()
      .join(LiveComb, Seq("comb"), "left")
      .withColumn("flag", when(col("appName").isNotNull, lit(1) ).otherwise(lit(0)))
      .groupBy("uid")
      .agg(
        sum("flag").alias("sum_flag")
      )
      .select("uid", "sum_flag")
      .withColumn("date", lit(date))
      .withColumn("tag", lit(324))
//    result.write.mode("overwrite").saveAsTable("test.result_sjq")
    val result1 = result.filter("sum_flag = 0").select("uid", "tag", "date")

    result1
  }

  def update(spark: SparkSession, date: String): Unit ={
    import spark.implicits._
    val sdf = new SimpleDateFormat("yyyy-MM-dd")
    val calendar = Calendar.getInstance
    val today = sdf.parse(date)
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -1)
    val yesterday = calendar.getTime()
    calendar.add(Calendar.DATE, -6)
    val startday = calendar.getTime()
    val date0 = sdf.format(yesterday)
    val date_1 = sdf.format(startday)

    val sqlRequest =
      s"""
         |select
         |  coalesce(t1.uid, t2.uid) as uid,
         |  tag0,
         |  tag1
         |from
         |  (
         |    select
         |      uid,
         |      1 as tag1
         |    from
         |      dl_cpc.hottopic_crowd_bag_collection_sjq
         |    where
         |      `date` = '$date'
         |	  and tag = 324
         |	  group by uid, 1
         |  ) t1 full
         |  outer join (
         |    select
         |      uid,
         |      1 as tag0
         |    from
         |      dl_cpc.hottopic_crowd_bag_collection_sjq
         |    where
         |      `date` between '$date_1' and '$date0'
         |	  and tag = 324
         |   group by uid, 1
         |  ) t2 on t1.uid = t2.uid
         """.stripMargin

    println(sqlRequest)
    val df = spark.sql(sqlRequest)
      .withColumn("id", lit(324))
      .withColumn("io", when(col("tag1").isNotNull, lit(true)).otherwise(lit(false)))
      .select("uid", "tag0", "tag1", "id", "io")

    //    df.write.mode("overwrite").saveAsTable("test.putOrDrop_sjq")

    val rdd1 = df.select("uid", "id", "io").rdd.map(x => (x.getAs[String]("uid"), x.getAs[Int]("id"), x.getAs[Boolean]("io")))
    val result = SetUserProfileTagInHiveDaily(rdd1)

  }

  case class LiveApp(var appName: String)

  case class AppPkgMap(var comb: String, var pkg: String, appName: String, var count: Int)

  case class UidComb(var uid: String, var comb: String)

}
