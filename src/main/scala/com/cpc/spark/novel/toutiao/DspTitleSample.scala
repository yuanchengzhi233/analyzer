package com.cpc.spark.novel.toutiao

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SQLContext

object DspTitleSample {
  def main(args: Array[String]): Unit = {
    val date = args(0)
    val spark = SparkSession.builder()
      .appName(s"midu_sample")
      .enableHiveSupport()
      .getOrCreate()
    //穿山甲直投米读
    val sql =
      s"""
         |select title,concat_ws(' ',collect_set(ButtonText)) ButtonText,concat_ws(' ',collect_set(description)) as description
         |  from
         |  (select title,description,ButtonText,row_number() over(partition by title order by description) rk
         |      from
         |      (select distinct title,description,ButtonText from dl_cpc.cpc_midu_toutiao_log
         |       where `date` > date_sub('$date', 30)
         |       ) a
         |   ) b
         |   where rk<10
         |group by title
             """.stripMargin
    println(sql)
    val data = spark.sql(sql)
    data.coalesce(1).write.mode("overwrite").format("com.databricks.spark.csv")
      .option("header", "true").save("/home/cpc/dsp_title/midu_toutiao_sample.csv")
//    data.select("title","buttontext","description").save("/home/cpc/dsp_title/midu_toutiao_sample.csv","com.databricks.spark.csv")
//    data.repartition(1).write.mode("overwrite").saveAsTable("dl_cpc.midu_toutiao_sample")

//    //穿山甲dsp
//    val sql2 =
//      s"""
//         |SELECT
//         |distinct
//         |adid, title
//         |FROM dl_cpc.slim_union_log
//         |WHERE dt> date_sub('$date', 7) and adid != '' and adsrc = 22
//         |and media_appsid in ("80001098", "80001292")
//         |and title != ''
//             """.stripMargin
//    println(sql2)
//    val data2 = spark.sql(sql2)
//    data2.repartition(1).write.mode("overwrite").format("com.databricks.spark.csv")
//      .option("delimiter","\001").save("hdfs://emr-cluster/home/cpc/dsp_title/dsp_toutiao_sample.csv")
//    data2.repartition(1).write.mode("overwrite").saveAsTable("dl_cpc.midu_toutiao_sample2")
//
//    //title label
//    val sql3 =
//      s"""
//         |select
//         |  title,
//         |  case when cate_1='美容化妆' then '0'
//         |  when cate_2='游戏类'  then '1'
//         |  when cate_2='社交网络' then '2'
//         |  when cate_2='网上购物' then '3'
//         |  when cate_1='二类电商' then '4'
//         |  when cate_1='成人用品' then '5'
//         |  when cate_2='生活服务' then '6'
//         |  when cate_2='短视频' then '7'
//         |  else '8' end as label
//         |from dl_cpc.midu_toutiao_title
//             """.stripMargin
//    println(sql3)
//    val data3 = spark.sql(sql3)
//    data3.repartition(1).write.mode("overwrite").format("com.databricks.spark.csv")
//      .option("delimiter","\001").save("hdfs://emr-cluster/home/cpc/dsp_title/title_label.csv")
  }

  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit)
  {
    val p = new java.io.PrintWriter(f);
    p.write("asin,")
    p.write("rating_avg\n")
    try { op(p) }
    finally { p.close() }
  }
}
