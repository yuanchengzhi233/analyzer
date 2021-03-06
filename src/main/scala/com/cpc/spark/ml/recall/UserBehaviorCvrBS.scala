package com.cpc.spark.ml.recall

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.expr

object UserBehaviorCvrBS {
  Logger.getRootLogger.setLevel(Level.WARN)
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .enableHiveSupport()
      .getOrCreate()

    val date = args(0)

    import spark.implicits._

    //cvr data
    val cvr_sql =
      s"""
         |select a.*,
         |      row_number() over(partition by uid,cvr_ideaid order by timestamp desc) rn
         |from
         |  (select searchid, uid, ideaid as cvr_ideaid, timestamp,
         |      adclass as cvr_adclass,
         |      userid       as cvr_userid,
         |      planid       as cvr_planid,
         |      adtype       as cvr_adtype,
         |      interaction  as cvr_interaction,
         |      city         as cvr_city,
         |      adslot_id     as cvr_adslotid,
         |      phone_level as cvr_phone_level,
         |      brand_title as cvr_brand_title
         |  from dl_cpc.cpc_basedata_union_events
         |  where day='$date'
         |  and isclick = 1 and ideaid > 0
         |  and media_appsid in ("80000001", "80000002", "80000006", "800000062", "80000064", "80000066","80000141")
         |  and uid not like "%.%"
         |  and uid not like "%000000%"
         |  and length(uid) in (14, 15, 36)
         |  ) a
         |left join
         |  (select searchid, ideaid, label2 from dl_cpc.ml_cvr_feature_v1
         |  WHERE `date` = '$date' and label2 = 1
         |  ) b on a.searchid = b.searchid and a.cvr_ideaid=b.ideaid
         |  left join
         |  (select searchid, ideaid, label from dl_cpc.ml_cvr_feature_v2
         |  WHERE `date` = '$date' and label = 1
         |  ) c on a.searchid = c.searchid and a.cvr_ideaid=c.ideaid
         |  where label2=1 or label=1
      """.stripMargin

    println(cvr_sql)

    val cvr_data = spark.sql(cvr_sql).where("rn = 1").select($"uid",
        $"cvr_ideaid",
        $"cvr_adclass",
        $"cvr_userid",
        $"cvr_planid",
        $"cvr_adtype",
        $"cvr_interaction",
        $"cvr_city",
        $"cvr_adslotid",
        $"cvr_phone_level",
        $"cvr_brand_title",
        expr("row_number() over (partition by uid order by timestamp desc)").alias("rn")).
      where("rn <= 5000").persist()

    println(s"cvr data count $date : " + cvr_data.count())

    //click data
    val click_sql =
      s"""
         |select uid, ideaid as click_ideaid, timestamp,
         |      adclass as click_adclass,
         |      userid       as click_userid,
         |      planid       as click_planid,
         |      adtype       as click_adtype,
         |      interaction  as click_interaction,
         |      city         as click_city,
         |      adslot_id     as click_adslotid,
         |      phone_level  as click_phone_level,
         |      brand_title  as click_brand_title,
         |      row_number() over(partition by uid,ideaid order by timestamp desc) rn
         |from dl_cpc.cpc_basedata_union_events
         |where day='$date'
         |  and isclick = 1 and ideaid > 0
         |  and media_appsid in ("80000001", "80000002", "80000006", "800000062", "80000064", "80000066","80000141")
         |  and uid not like "%.%"
         |  and uid not like "%000000%"
         |  and length(uid) in (14, 15, 36)
      """.stripMargin

    println(click_sql)

    val click_data = spark.sql(click_sql).where("rn = 1")
      .select($"uid",
        $"click_ideaid",
        $"click_adclass",
        $"click_userid",
        $"click_planid",
        $"click_adtype",
        $"click_interaction",
        $"click_city",
        $"click_adslotid",
        $"click_phone_level",
        $"click_brand_title",
        expr("row_number() over (partition by uid order by timestamp desc)").alias("rn")).
      where("rn <= 5000").persist()

    println(s"click data count $date : " + click_data.count())

    //show data
    val show_sql =
      s"""
         |select uid, ideaid as show_ideaid, timestamp,
         |      adclass as show_adclass ,
         |      row_number() over(partition by uid,ideaid order by timestamp desc) rn
         |from dl_cpc.cpc_basedata_union_events
         |where day='$date'
         |  and isshow = 1 and ideaid > 0
         |  and media_appsid in ("80000001", "80000002", "80000006", "800000062", "80000064", "80000066","80000141")
         |  and uid not like "%.%"
         |  and uid not like "%000000%"
         |  and length(uid) in (14, 15, 36)
      """.stripMargin

    println(show_sql)

    spark.sql(show_sql)
      .select($"uid",
        $"show_ideaid",
        $"show_adclass",
        expr("row_number() over (partition by uid order by timestamp desc)").alias("rn"))
      .where("rn <= 5000")
      .coalesce(50)
      .join(click_data, Seq("uid", "rn"), "left")
      .join(cvr_data, Seq("uid", "rn"), "left")
      .write.mode("overwrite")
      .parquet(s"hdfs://emr-cluster/warehouse/dl_cpc.db/cpc_user_behaviors_recall_cvr/load_date=$date")

    spark.sql(
      s"""
         |alter table dl_cpc.cpc_user_behaviors_recall_cvr add partition(load_date='$date')
         |location 'hdfs://emr-cluster/warehouse/dl_cpc.db/cpc_user_behaviors_recall_cvr/load_date=$date'
      """.stripMargin)

    //汇总三天数据


  }
}
