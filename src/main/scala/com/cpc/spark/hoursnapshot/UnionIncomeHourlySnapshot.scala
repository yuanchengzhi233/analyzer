package com.cpc.spark.hoursnapshot

import org.apache.spark.sql.types.{FloatType, LongType}
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * 从MySQL的union数据库的income表中，获得income表中每小时增量的快照存在hive表中；
  * MySQL中的表  点击10s, 请求、展示80s更新一次；
  * 增量快照：本小时的点击量-上一个小时的点击量；其它同理；
  *
  */
object UnionIncomeHourlySnapshot {
  def main(args: Array[String]): Unit = {
    //参数小于1个
    if (args.length < 1) {
      System.err.println(
        s"""
           |usage: UnionIncomeHourlySnapshot table date hour
         """.stripMargin
      )
      System.exit(1)
    }

    //hive表名
    val hiveTable = args(0)
    val mysqlTable = args(1)
    val datee = args(2)
    val hour = args(3)

    //获得SparkSession
    val spark = SparkSession
      .builder()
      .appName("get income snapshot date = %s".format(datee))
      .enableHiveSupport()
      .getOrCreate()
    import spark.implicits._

    //定义url, user, psssword, driver, table
    val url = "jdbc:mysql://rr-2ze8n4bxmg3snxf7e.mysql.rds.aliyuncs.com:3306/union?useUnicode=true&characterEncoding=utf-8"
    val user = "rd"
    val passwd = "rdv587@123"
    val driver = "com.mysql.jdbc.Driver"
    val table = "(select * from %s where date='%s') as tmp".format(mysqlTable, datee)

    //从mysql获得最新charge
    val mysqlCharge_tmp = spark.read.format("jdbc")
      .option("url", url)
      .option("driver", driver)
      .option("user", user)
      .option("password", passwd)
      .option("dbtable", table)
      .load()
      .repartition(10)

    val mysqlCharge = mysqlCharge_tmp.select(
      mysqlCharge_tmp.col("media_id"),
      mysqlCharge_tmp.col("channel_id"),
      mysqlCharge_tmp.col("adslot_id"),
      mysqlCharge_tmp.col("date"),
      mysqlCharge_tmp.col("data_type"),
      mysqlCharge_tmp.col("request"),
      mysqlCharge_tmp.col("served_request"),
      mysqlCharge_tmp.col("impression"),
      mysqlCharge_tmp.col("click"),
      mysqlCharge_tmp.col("impression2"),
      mysqlCharge_tmp.col("click2"),
      mysqlCharge_tmp.col("imp_media_income"),
      mysqlCharge_tmp.col("imp_channel_income"),
      mysqlCharge_tmp.col("click_media_income"),
      mysqlCharge_tmp.col("click_channel_income"),
      mysqlCharge_tmp.col("media_income"),
      mysqlCharge_tmp.col("channel_income"),
      mysqlCharge_tmp.col("create_time"),
      mysqlCharge_tmp.col("modified_time"),
      mysqlCharge_tmp.col("media_income2"),
      mysqlCharge_tmp.col("rate").cast(FloatType),
      mysqlCharge_tmp.col("has_push").cast(LongType),
      mysqlCharge_tmp.col("settlement_type").cast(LongType),
      mysqlCharge_tmp.col("click2_media_income"),
      mysqlCharge_tmp.col("is_show").cast(LongType),
      mysqlCharge_tmp.col("coupon"),
      mysqlCharge_tmp.col("bd_rate").cast(LongType),
      mysqlCharge_tmp.col("bd_income").cast(LongType),
      mysqlCharge_tmp.col("settlement_income").cast(LongType),
      mysqlCharge_tmp.col("settlement_click").cast(LongType),
      mysqlCharge_tmp.col("settlement_impression").cast(LongType),
      mysqlCharge_tmp.col("allocated_income").cast(LongType),
      mysqlCharge_tmp.col("hd_click").cast(LongType),
      mysqlCharge_tmp.col("hd_impression").cast(LongType))

    println("mysql schema" + mysqlCharge.printSchema())
    mysqlCharge.take(1).foreach(x => println("##### mysqlCharge:" + x))

    //从hive中获得当日charge数据
    val hiveCharge = spark.sql(
      s"""
         |select *
         |from dl_cpc.$hiveTable
         |where thedate='$datee'
      """.stripMargin)

    println("hiveCharge schema" + hiveCharge.printSchema())
    hiveCharge.take(1).foreach(x => println("##### hiveCharge:" + x))

    /**
      * 如果hive没数据，mysql数据直接写入hive，否则计算增量在写入hive
      */
    if (hiveCharge.count() == 0) {
      println("##############")
      if (mysqlCharge.take(1).length > 0) {
        mysqlCharge
          .write
          .mode(SaveMode.Overwrite)
          .parquet("/warehouse/dl_cpc.db/%s/thedate=%s/thehour=%s".format(hiveTable, datee, hour))
        println("###### mysqlCharge write hive successfully")
      } else {
        println("###### mysqlCharge为空")
      }
    } else {

      //分组累加当日每小时的请求数，填充数，广告激励数，展示数，点击数，请求费用数，消费现金，消费优惠券
      val hiveCharge2 = hiveCharge.groupBy("media_id", "channel_id", "adslot_id", "date", "data_type")
        .sum("request", "served_request", "impression", "click", "impression2", "click2", "imp_media_income", "imp_channel_income",
          "click_media_income", "click_channel_income", "media_income", "channel_income", "media_income2", "click2_media_income",
          "coupon", "bd_income", "settlement_income", "settlement_click", "settlement_impression", "allocated_income", "hd_click", "hd_impression")
        .toDF("media_id", "channel_id", "adslot_id", "date", "data_type", "sum_request", "sum_served_request", "sum_impression",
          "sum_click", "sum_impression2", "sum_click2", "sum_imp_media_income", "sum_imp_channel_income", "sum_click_media_income",
          "sum_click_channel_income", "sum_media_income", "sum_channel_income", "sum_media_income2", "sum_click2_media_income",
          "sum_coupon", "sum_bd_income", "sum_settlement_income", "sum_settlement_click", "sum_settlement_impression", "sum_allocated_income",
          "sum_hd_click", "sum_hd_impression")

      println("hiveCharge2 schema" + hiveCharge2.printSchema())
      hiveCharge2.take(1).foreach(x => println("##### hiveCharge2:" + x))

      /**
        *  1.进行left outer join;
        *  2.用0填充null
        */

      val joinCharge = mysqlCharge
        .join(hiveCharge2, Seq("media_id", "channel_id", "adslot_id", "date", "data_type"), "left_outer")
        .na.fill(0, Seq("sum_request", "sum_served_request", "sum_impression", "sum_click", "sum_impression2", "sum_click2",
        "sum_imp_media_income", "sum_imp_channel_income", "sum_click_media_income", "sum_click_channel_income", "sum_media_income",
        "sum_channel_income", "sum_media_income2", "sum_click2_media_income", "sum_coupon", "sum_bd_income", "sum_settlement_income",
        "sum_settlement_click", "sum_settlement_impression", "sum_allocated_income", "sum_hd_click", "sum_hd_impression"))

      /**
        * 计算增量
        */
      val joinCharge2 = joinCharge
        .selectExpr(
          "media_id",
          "channel_id",
          "adslot_id",
          "date",
          "data_type",
          "request - sum_request",
          "served_request - sum_served_request",
          "impression - sum_impression",
          "click - sum_click",
          "impression2 - sum_impression2",
          "click2 - sum_click2",
          "imp_media_income - sum_imp_media_income",
          "imp_channel_income - sum_imp_channel_income",
          "click_media_income - sum_click_media_income",
          "click_channel_income - sum_click_channel_income",
          "media_income - sum_media_income",
          "channel_income - sum_channel_income",
          "create_time",
          "modified_time",
          "media_income2 - sum_media_income2",
          "rate",
          "has_push",
          "settlement_type",
          "click2_media_income - sum_click2_media_income",
          "is_show",
          "coupon - sum_coupon",
          "bd_rate",
          "bd_income - sum_bd_income",
          "settlement_income - sum_settlement_income",
          "settlement_click - sum_settlement_click",
          "settlement_impression - sum_settlement_impression",
          "allocated_income - sum_allocated_income",
          "hd_click - sum_hd_click",
          "hd_impression - sum_hd_impression"
        )
        .toDF("media_id", "channel_id", "adslot_id", "date", "data_type", "request", "served_request", "impression",
          "click", "impression2", "click2", "imp_media_income", "imp_channel_income", "click_media_income", "click_channel_income",
          "media_income", "channel_income", "create_time", "modified_time", "media_income2", "rate", "has_push", "settlement_type",
          "click2_media_income", "is_show", "coupon", "bd_rate", "bd_income", "settlement_income", "settlement_click", "settlement_impression",
          "allocated_income", "hd_click", "hd_impression")
        .cache()

      println("joinCharge2 schema" + joinCharge2.printSchema())
      joinCharge2.take(1).foreach(x => println("##### joinCharge2:" + x))

      if (joinCharge2.take(1).length > 0) {
        joinCharge2
          .write
          .mode(SaveMode.Overwrite)
          .parquet("/warehouse/dl_cpc.db/%s/thedate=%s/thehour=%s".format(hiveTable, datee, hour))

        joinCharge2.unpersist()
        println("###### joinCharge write hive successfully")
      } else {
        println("###### joinCharge为空")
      }

    }


    spark.sql(
      """
        |ALTER TABLE dl_cpc.%s add if not exists PARTITION(`thedate` = "%s", `thehour` = "%s")
        | LOCATION  '/warehouse/dl_cpc.db/%s/thedate=%s/thehour=%s'
      """.stripMargin.format(hiveTable, datee, hour, hiveTable, datee, hour))

    println("~~~~~~write metadata to hive income successfully")


  }
}
