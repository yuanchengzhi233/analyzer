package com.cpc.spark.ocpc

import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}


object OcpcHourlyReportV2 {
  def main(args: Array[String]): Unit = {
    /*
    新版报表程序
    1. 从ocpc_unionlog拉取ocpc广告记录
    2. 采用数据关联方式获取转化数据
    3. 统计分ideaid级别相关数据
    4. 统计分conversion_goal级别相关数据
    5. 存储到hdfs
    6. 存储到mysql
     */
    val spark = SparkSession
      .builder()
      .appName("OcpcHourlyReport")
      .enableHiveSupport()
      .getOrCreate()

    val date = args(0).toString
    val hour = args(1).toString

    // 拉取点击、消费、转化等基础数据
    val rawData = getBaseData(date, hour, spark)

    // 分ideaid和conversion_goal统计转化成本
    val data = preprocessData(rawData, date, hour, spark)
    data.write.mode("overwrite").saveAsTable("test.check_report_data20190125")

    // 输出结果表
//    val result = saveDataToHdfs(data, date, hour, spark)
  }

  def preprocessData(rawData: DataFrame, date: String, hour: String, spark: SparkSession) = {
    /*
    //    ideaid  int     NULL
    //    userid  int     NULL
    //    conversion_goal string  NULL
    //    step2_percent   double  NULL
    //    cpa_given       double  NULL
    //    cpa_real        double  NULL
    //    pcvr    double  NULL
    //    ctr     double  NULL
    //    click_cvr       double  NULL
    //    show_cvr        double  NULL
    //    price   double  NULL
    //    show_cnt        bigint  NULL
    //    ctr_cnt bigint  NULL
    //    cvr_cnt bigint  NULL
    //    avg_k   double  NULL
    //    recent_k        double  NULL
    "searchid", "ideaid", "userid", "isclick", "isshow", "price", "cpagiven", "bid", "kvalue", "conversion_goal", "ocpc_step", "iscvr1", "iscvr2", "iscvr3", "iscvr"
     */
    rawData.createOrReplaceTempView("raw_data")


    val sqlRequest =
      s"""
         |SELECT
         |  ideaid,
         |  userid,
         |  conversion_goal,
         |  sum(case when ocpc_step=2 then 1 else 0 end) * 1.0 / count(1) as step2_percent,
         |  sum(case when isclick=1 then cpagiven else 0 end) * 1.0 / sum(isclick) as cpa_given,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(iscvr) as cpa_real,
         |  sum(case when isclick=1 then exp_cvr else 0 end) * 1.0 / sum(isclick) as pcvr,
         |  sum(isclick) * 1.0 / sum(isshow) as ctr,
         |  sum(iscvr) * 1.0 / sum(isclick) as click_cvr,
         |  sum(iscvr) * 1.0 / sum(isshow) as show_cvr,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(isclick) as acp,
         |  sum(isshow) as show_cnt,
         |  sum(isclick) as ctr_cnt,
         |  sum(iscvr) as cvr_cnt,
         |  sum(case when isclick=1 then kvalue else 0 end) * 1.0 / sum(isclick) as avg_k,
         |  sum(case when isclick=1 and hr='$hour' then kvalue else 0 end) * 1.0 / sum(case when hr='$hour' then isclick else 0 end) as recent_k
         |FROM
         |  raw_data
         |GROUP BY ideaid, userid, conversion_goal
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark.sql(sqlRequest)

    resultDF
  }

  def getBaseData(date: String, hour: String, spark: SparkSession) = {
    /**
      * 重新计算抽取全天截止当前时间的数据日志
      */

    // 抽取基础数据：所有跑ocpc的广告主
    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  ideaid,
         |  userid,
         |  isclick,
         |  isshow,
         |  price,
         |  exp_cvr,
         |  exp_ctr,
         |  cast(ocpc_log_dict['cpagiven'] as double) as cpagiven,
         |  cast(ocpc_log_dict['dynamicbid'] as double) as bid,
         |  cast(ocpc_log_dict['kvalue'] as double) as kvalue,
         |  cast(ocpc_log_dict['conversiongoal'] as int) as conversion_goal,
         |  cast(ocpc_log_dict['ocpcstep'] as int) as ocpc_step,
         |  hour as hr
         |FROM
         |  dl_cpc.ocpc_unionlog
         |WHERE
         |  `dt`='$date' and `hour` <= '$hour'
         |AND
         |  isshow=1
       """.stripMargin
    println(sqlRequest)
    val rawData = spark.sql(sqlRequest)


    // 关联转化表
    val selectCondition = s"`date`='$date'"
    // cvr1
    val cvr1Data = spark
      .table("dl_cpc.ml_cvr_feature_v1")
      .where(selectCondition)
      .filter(s"label2=1")
      .select("searchid")
      .withColumn("iscvr1", lit(1))
      .distinct()

    // cvr2
    val cvr2Data = spark
      .table("dl_cpc.ml_cvr_feature_v2")
      .where(selectCondition)
      .filter(s"label=1")
      .select("searchid")
      .withColumn("iscvr2", lit(1))
      .distinct()

    // cvr3
    val cvr3Data = spark
      .table("dl_cpc.site_form_unionlog")
      .where(selectCondition)
      .select("searchid")
      .withColumn("iscvr3", lit(1))
      .distinct()

    // 数据关联
    val resultDF = rawData
      .join(cvr1Data, Seq("searchid"), "left_outer")
      .join(cvr2Data, Seq("searchid"), "left_outer")
      .join(cvr3Data, Seq("searchid"), "left_outer")
      .withColumn("iscvr", when(col("conversion_goal") === 1, col("iscvr1")).otherwise(when(col("conversion_goal") === 2, col("iscvr2")).otherwise(col("iscvr3"))))
      .select("searchid", "ideaid", "userid", "isclick", "isshow", "price", "cpagiven", "bid", "kvalue", "conversion_goal", "ocpc_step", "iscvr1", "iscvr2", "iscvr3", "iscvr")

    resultDF.show(10)

    resultDF

  }


}