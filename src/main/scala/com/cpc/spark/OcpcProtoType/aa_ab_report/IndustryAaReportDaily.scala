package com.cpc.spark.OcpcProtoType.aa_ab_report

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object IndustryAaReportDaily {
  def main(args: Array[String]): Unit = {
    val date = args(0).toString
    val hour = args(1).toString
    val spark = SparkSession.builder().appName("IndustryAaReportDaily").enableHiveSupport().getOrCreate()
//    GetBaseData.getBaseData(date, hour, spark)
    println("-----has got base data--------")
    getIndexValue(date, hour, spark)
    println("-----has got index value--------")
  }

  // 获取指标值
  def getIndexValue(date: String, hour: String, spark: SparkSession): Unit ={
    // 得到分行业的总unit、user数，以及ocpc的unit、user、hidden_num数
    val numDataDF = getNumOfIndustry(date, hour, spark)
    // 明投和暗投的控制数等指标
    val controlNumDF = getControlNum(date, hour, spark)
    // 获得cv、click、show等指标值
    val cvEtcIndexDF = getCvEtcIndexValue(date, hour, spark)
    // 所有可投ocpc的总消费
    val ktOcpcAllCostDF = getKtOcpcAllCost(date, hour, spark)
    // 获取前一天的cv、post_cvr等数据
    val preDataDF = getPreData(date, spark)

    numDataDF.createOrReplaceTempView("unit_user_num_table")
    controlNumDF.createOrReplaceTempView("control_num_table")
    cvEtcIndexDF.createOrReplaceTempView("other_index_table")
    ktOcpcAllCostDF.createOrReplaceTempView("kt_ocpc_cost_table")
    preDataDF.createOrReplaceTempView("pre_data_table")

    // 统计分行业的所有指标值
    val sql1 =
      s"""
        |select
        |    a.industry,
        |    b.all_user_num,
        |    b.all_unit_num,
        |    b.ocpc_user_num,
        |    b.ocpc_unit_num,
        |    a.cv,
        |    a.click,
        |    a.show,
        |    a.post_cvr,
        |    a.cost,
        |    a.cpm,
        |    a.ocpc_cost,
        |    a.ocpc_cost_ratio,
        |    d.kt_ocpc_cost,
        |    round(a.ocpc_cost / d.kt_ocpc_cost , 4) as ocpc_in_kt_ratio,
        |    c.cpa_control_num,
        |    round(c.cpa_control_num * 1.0/ b.ocpc_unit_num, 4) as cpa_control_ratio,
        |    b.ocpc_hidden_num,
        |    a.ocpc_hidden_cost,
        |    a.ocpc_hidden_cost_ratio,
        |    c.hidden_control_num,
        |    round(c.hidden_control_num * 1.0 / b.ocpc_hidden_num, 4) as hidden_control_ratio,
        |    c.hit_line_num,
        |    round(c.hit_line_num * 1.0 / b.ocpc_hidden_num, 4) as hidden_hit_line_ratio,
        |    c.avg_hidden_cost,
        |    c.avg_hidden_budget,
        |    (case when c.avg_hidden_budget > 0 then round(c.avg_hidden_cost * 1.0 / c.avg_hidden_budget, 4)
        |          else null end) as hidden_budget_cost_ratio,
        |    e.pre_cv,
        |    (case when (e.pre_cv is null or e.pre_cv = 0) then 0
        |          else round(a.cv * 1.0 / e.pre_cv, 4) end) as cv_ring_ratio,
        |    e.pre_cost,
        |    (case when (e.pre_cost is null or e.pre_cost = 0) then 0
        |          else round(a.cost * 1.0 / e.pre_cost, 4) end) as cost_ring_ratio,
        |    e.pre_post_cvr,
        |    (case when (e.pre_post_cvr is null or e.pre_post_cvr = 0) then 0
        |          else round(a.post_cvr * 1.0 / e.pre_post_cvr, 4) end) as post_cvr_ring_ratio,
        |    e.pre_cpm,
        |    (case when (e.pre_cpm is null or e.pre_cpm = 0) then 0
        |          else round(a.cpm * 1.0 / e.pre_cpm, 4) end) as cpm_ring_ratio
        |from
        |    other_index_table a
        |left join
        |    unit_user_num_table b
        |on
        |    a.industry = b.industry
        |left join
        |    control_num_table c
        |on
        |    a.industry = c.industry
        |left join
        |    kt_ocpc_cost_table d
        |on
        |    a.industry = d.industry
        |left join
        |    pre_data_table e
        |on
        |    a.industry = e.industry
      """.stripMargin
    println("-------get index value sql1-------")
    spark.sql(sql1).createOrReplaceTempView("index_of_industry_table")

    // 统计整体的指标值
    // 首先统计整体的cpm
    val sql2 =
    s"""
       |select
       |    'all' as industry,
       |    cpm,
       |    post_cvr
       |from
       |    (select
       |        round(sum(case when isclick = 1 then price else 0 end) * 10.0 / sum(isshow), 4) as cpm,
       |        round(sum(iscvr) * 1.0 / sum(isclick), 4) as post_cvr
       |    from
       |        dl_cpc.ocpc_aa_ab_report_base_data
       |    where
       |        `date` = '$date'
       |    and
       |        hour = '$hour'
       |    and
       |        version = 'qtt_demo'
       |    and
       |        is_ocpc = 1) temp
      """.stripMargin
    println("--------get all index sql2--------")
    println(sql2)
    spark.sql(sql2).createOrReplaceTempView("all_cpm_post_cvr_table")

    // 然后统计其他的整体的指标
    val sql3 =
      s"""
         |select
         |    'all' as industry,
         |    sum(b.all_user_num) as all_user_num,
         |    sum(b.all_unit_num) as all_unit_num,
         |    sum(b.ocpc_user_num) as ocpc_user_num,
         |    sum(b.ocpc_unit_num) as ocpc_unit_num,
         |    sum(a.cv) as cv,
         |    sum(a.click) as click,
         |    sum(a.show) as show,
         |    sum(a.cost) as cost,
         |    sum(a.ocpc_cost) as ocpc_cost,
         |    (case when sum(a.cost) > 0 then round(sum(a.ocpc_cost) / sum(a.cost), 4)
         |          else 0 end)  as ocpc_cost_ratio,
         |    sum(d.kt_ocpc_cost) as kt_ocpc_cost,
         |    (case when sum(d.kt_ocpc_cost) > 0 then round(sum(a.ocpc_cost) / sum(d.kt_ocpc_cost) , 4)
         |          else 0 end) as ocpc_in_kt_ratio,
         |    sum(c.cpa_control_num) as cpa_control_num,
         |    (case when sum(b.ocpc_unit_num) > 0 then round(sum(c.cpa_control_num) * 1.0/ sum(b.ocpc_unit_num), 4)
         |          else 0 end) as cpa_control_ratio,
         |    sum(b.ocpc_hidden_num) as ocpc_hidden_num,
         |    sum(a.ocpc_hidden_cost) as ocpc_hidden_cost,
         |    (case when sum(a.ocpc_hidden_cost) > 0 then round(sum(a.ocpc_hidden_cost) / sum(a.ocpc_cost), 4)
         |          else 0 end) as ocpc_hidden_cost_ratio,
         |    sum(c.hidden_control_num) as hidden_control_num,
         |    (case when sum(b.ocpc_hidden_num) > 0 then round(sum(c.hidden_control_num) * 1.0 / sum(b.ocpc_hidden_num), 4)
         |          else 0 end) as hidden_control_ratio,
         |    sum(c.hit_line_num) as hit_line_num,
         |    (case when sum(b.ocpc_hidden_num) > 0 then round(sum(c.hit_line_num) * 1.0 / sum(b.ocpc_hidden_num), 4)
         |          else 0 end)  as hidden_hit_line_ratio,
         |    (case when sum(b.ocpc_hidden_num) > 0 then round(sum(c.all_hidden_cost) / sum(b.ocpc_hidden_num), 4)
         |          else 0 end) as avg_hidden_cost,
         |    (case when sum(b.ocpc_hidden_num) > 0 then round(sum(c.all_hidden_budget) / sum(b.ocpc_hidden_num), 4)
         |          else 0 end) as avg_hidden_budget
         |from
         |    other_index_table a
         |left join
         |    unit_user_num_table b
         |on
         |    a.industry = b.industry
         |left join
         |    control_num_table c
         |on
         |    a.industry = c.industry
         |left join
         |    kt_ocpc_cost_table d
         |on
         |    a.industry = d.industry
      """.stripMargin
    println("--------get all index sql3--------")
    println(sql3)
    spark.sql(sql3).createOrReplaceTempView("all_other_index_table")

    val sql4 =
      s"""
         |select
         |    temp1.industry,
         |    temp1.all_user_num,
         |    temp1.all_unit_num,
         |    temp1.ocpc_user_num,
         |    temp1.ocpc_unit_num,
         |    temp1.cv,
         |    temp1.click,
         |    temp1.show,
         |    temp2.post_cvr,
         |    temp1.cost,
         |    temp2.cpm,
         |    temp1.ocpc_cost,
         |    temp1.ocpc_cost_ratio,
         |    temp1.kt_ocpc_cost,
         |    temp1.ocpc_in_kt_ratio,
         |    temp1.cpa_control_num,
         |    temp1.cpa_control_ratio,
         |    temp1.ocpc_hidden_num,
         |    temp1.ocpc_hidden_cost,
         |    temp1.ocpc_hidden_cost_ratio,
         |    temp1.hidden_control_num,
         |    temp1.hidden_control_ratio,
         |    temp1.hit_line_num,
         |    temp1.hidden_hit_line_ratio,
         |    temp1.avg_hidden_cost,
         |    temp1.avg_hidden_budget,
         |    (case when temp1.avg_hidden_budget > 0 then round(temp1.avg_hidden_cost * 1.0 / temp1.avg_hidden_budget, 4)
         |          else null end) as hidden_budget_cost_ratio
         |from
         |    all_other_index_table temp1
         |left join
         |    all_cpm_post_cvr_table temp2
         |on
         |    temp1.industry = temp2.industry
      """.stripMargin
    println("--------get all index sql4--------")
    println(sql4)
    spark.sql(sql4).createOrReplaceTempView("index_of_all_table")

    // 整体和分行业的指标进行合并
    val sql5 =
      s"""
         |select
         |    a.*,
         |    b.pre_cv as pre_cv,
         |    (case when b.pre_cv > 0 then round(a.cv * 1.0 / b.pre_cv, 4)
         |          else 0 end) as cv_ring_ratio,
         |    b.pre_cost as pre_cost,
         |    (case when b.pre_cost > 0 then round(a.cost / b.pre_cost, 4)
         |          else 0 end) as cost_ring_ratio,
         |    b.pre_post_cvr as pre_post_cvr,
         |    (case when b.pre_post_cvr > 0 then round(a.post_cvr / b.pre_post_cvr, 4)
         |          else 0 end) as post_cvr_ring_ratio,
         |    b.pre_cpm as pre_cpm ,
         |    (case when b.pre_cpm > 0 then round(a.cpm / b.pre_cpm, 4)
         |          else 0 end) as cpm_ring_ratio
         |from
         |    index_of_all_table a
         |left join
         |    pre_data_table b
         |on
         |    a.industry = b.industry
         |
         |union
         |
         |select * from index_of_industry_table
      """.stripMargin
    println("--------get all index sql5--------")
    println(sql5)
    val dataDF = spark.sql(sql5)
    dataDF
      .withColumn("date", lit(date))
      .withColumn("version", lit("qtt_demo"))
      .repartition(400)
      .write.mode("overwrite").insertInto("dl_cpc.ocpc_industry_aa_report_daily")
  }

  // 统计分行业的总unit、user数，以及ocpc的unit、user、hidden_num数
  def getNumOfIndustry(date: String, hour: String, spark: SparkSession): DataFrame ={
    val sql1 =
      s"""
         |select
         |    industry,
         |    count(userid) as all_user_num
         |from
         |    (select
         |        industry,
         |        userid
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    group by
         |        industry,
         |        userid) temp
         |group by
         |    industry
      """.stripMargin
    println("-----get num sql1-----")
    println(sql1)
    spark.sql(sql1).createOrReplaceTempView("temp_table1")

    val sql2 =
      s"""
         |select
         |    industry,
         |    count(unitid) as all_unit_num
         |from
         |    (select
         |        industry,
         |        unitid
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    group by
         |        industry,
         |        unitid) temp
         |group by
         |    industry
      """.stripMargin
    println("-----get num sql2-----")
    println(sql2)
    spark.sql(sql2).createOrReplaceTempView("temp_table2")

    val sql3 =
      s"""
         |select
         |    industry,
         |    count(userid) as ocpc_user_num
         |from
         |    (select
         |        industry,
         |        userid
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    and
         |        is_ocpc = 1
         |    group by
         |        industry,
         |        userid) temp
         |group by
         |    industry
      """.stripMargin
    println("-----get num sql3-----")
    println(sql3)
    spark.sql(sql3).createOrReplaceTempView("temp_table3")

    val sql4 =
      s"""
         |select
         |    industry,
         |    count(unitid) as ocpc_unit_num
         |from
         |    (select
         |        industry,
         |        unitid
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    and
         |        is_ocpc = 1
         |    group by
         |        industry,
         |        unitid) temp
         |group by
         |    industry
      """.stripMargin
    println("-----get num sql4-----")
    println(sql4)
    spark.sql(sql4).createOrReplaceTempView("temp_table4")

    val sql5 =
      s"""
         |select
         |    industry,
         |    count(unitid) as ocpc_hidden_num
         |from
         |    (select
         |        industry,
         |        unitid
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    and
         |        is_ocpc = 1
         |    and
         |        is_hidden = 1
         |    group by
         |        industry,
         |        unitid) temp
         |group by
         |    industry
      """.stripMargin
    println("-----get num sql5-----")
    println(sql5)
    spark.sql(sql5).createOrReplaceTempView("temp_table5")

    val sql6 =
      s"""
         |select
         |    a.industry,
         |    a.all_user_num,
         |    b.all_unit_num,
         |    c.ocpc_user_num,
         |    d.ocpc_unit_num,
         |    e.ocpc_hidden_num
         |from
         |    temp_table1 a
         |left join
         |    temp_table2 b
         |on
         |    a.industry = b.industry
         |left join
         |    temp_table3 c
         |on
         |    a.industry = c.industry
         |left join
         |    temp_table4 d
         |on
         |    a.industry = d.industry
         |left join
         |    temp_table5 e
         |on
         |    a.industry = e.industry
      """.stripMargin
    println("-----get num sql6-----")
    println(sql6)
    val numDataDF = spark.sql(sql6)
    numDataDF
  }

  // 获取明投和暗投的控制数等指标
  def getControlNum(date: String, hour: String, spark: SparkSession): DataFrame ={
    // 统计明投控制数
    val sql1 =
      s"""
         |select
         |    industry,
         |    sum(case when cpa_real < cpa_given * 1.2 then 1 else 0 end) as cpa_control_num
         |from
         |    (select
         |        industry,
         |        unitid,
         |        userid,
         |        sum(case when isclick = 1 then cpa_given else 0 end) * 0.01 / sum(isclick) as cpa_given,
         |        sum(case when isclick = 1 then price else 0 end) * 0.01 / sum(iscvr) as cpa_real
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    and
         |        is_ocpc = 1
         |    group by
         |        industry,
         |        unitid,
         |        userid) temp
         |group by
         |    industry
      """.stripMargin
    println("--------get mingtou control num--------")
    println(sql1)
    spark.sql(sql1).createOrReplaceTempView("mingtou_control_num_table")

    // 统计暗投控制数
    val sql2 =
      s"""
         |select
         |    industry,
         |    sum(case when hidden_cpa_real < hidden_cpa_given * 1.2 then 1 else 0 end) as hidden_control_num,
         |    sum(case when hidden_cost > 0 and hidden_cost >= hidden_budget then 1 else 0 end) as hit_line_num,
         |    avg(hidden_cost) as avg_hidden_cost,
         |    avg(hidden_budget) as avg_hidden_budget,
         |    sum(hidden_cost) as all_hidden_cost,
         |    sum(hidden_budget) as all_hidden_budget
         |from
         |    (select
         |        industry,
         |        unitid,
         |        userid,
         |        sum(case when isclick = 1 then cpa_given else 0 end) * 0.01 / sum(isclick) as hidden_cpa_given,
         |        sum(case when isclick = 1 then price else 0 end) * 0.01 / sum(iscvr) as hidden_cpa_real,
         |        sum(case when isclick = 1 then price else 0 end) * 0.01 as hidden_cost,
         |        max(case when isclick = 1 then budget else 0 end) * 0.01 as hidden_budget
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    and
         |        is_ocpc = 1
         |    and
         |        is_hidden = 1
         |    group by
         |        industry,
         |        unitid,
         |        userid) temp
         |group by
         |    industry
      """.stripMargin
    println("--------get antou control num--------")
    println(sql2)
    spark.sql(sql2).createOrReplaceTempView("antou_control_num_table")

    // 合并明投、暗投的控制数
    val sql3 =
      s"""
         |select
         |    a.industry,
         |    a.cpa_control_num,
         |    b.hidden_control_num,
         |    b.hit_line_num,
         |    b.avg_hidden_cost,
         |    b.avg_hidden_budget,
         |    b.all_hidden_cost,
         |    b.all_hidden_budget
         |from
         |    mingtou_control_num_table a
         |left join
         |    antou_control_num_table b
         |on
         |    a.industry = b.industry
      """.stripMargin
    println("--------merge mingtou and antou control num--------")
    println(sql3)
    val controlNumDF = spark.sql(sql3)
    controlNumDF
  }

  // 获得cv、click、show等指标值
  def getCvEtcIndexValue(date: String, hour: String, spark: SparkSession): DataFrame ={
    val sql =
      s"""
         |select
         |    a.industry,
         |    a.cv,
         |    a.click,
         |    a.show,
         |    a.post_cvr,
         |    b.cost,
         |    a.cpm,
         |    a.ocpc_cost,
         |    round(a.ocpc_cost / b.cost, 4) as ocpc_cost_ratio,
         |    a.ocpc_hidden_cost,
         |    a.ocpc_hidden_cost_ratio
         |from
         |    (select
         |        industry,
         |        sum(iscvr) as cv,
         |        sum(isclick) as click,
         |        sum(isshow) as show,
         |        round(sum(iscvr) * 1.0 / sum(isclick), 4) as post_cvr,
         |        round(sum(case when isclick = 1 then price else 0 end) * 10.0 / sum(isshow), 4) as cpm,
         |        sum(case when isclick = 1 then price else 0 end) * 0.01 as ocpc_cost,
         |        round(sum(case when isclick = 1 then price else 0 end)
         |            / sum(case when isclick = 1 then price else 0 end), 4) as ocpc_cost_ratio,
         |        sum(case when isclick = 1 and is_hidden = 1 then price else 0 end) * 0.01 as ocpc_hidden_cost,
         |        round(sum(case when isclick = 1 and is_hidden = 1 then price else 0 end)
         |            / sum(case when isclick = 1 then price else 0 end), 4) as ocpc_hidden_cost_ratio
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    and
         |        is_ocpc = 1
         |    group by
         |        industry) a
         |left join
         |    (select
         |        industry,
         |        sum(case when isclick = 1 then price else 0 end) * 0.01 as cost
         |    from
         |        dl_cpc.ocpc_aa_ab_report_base_data
         |    where
         |        `date` = '$date'
         |    and
         |        hour = '$hour'
         |    and
         |        version = 'qtt_demo'
         |    group by
         |        industry) b
         |on
         |    a.industry = b.industry
      """.stripMargin
    println("--------get cv show click etc--------")
    println(sql)
    val cvEtcIndexDF = spark.sql(sql)
    cvEtcIndexDF
  }

  // 统计所有可投ocpc的总消费
  def getKtOcpcAllCost(date: String, hour: String, spark: SparkSession): DataFrame ={
    // 首先统计is_recommend = 1 和 is_ocpc等于1的unitid
    val sql1 =
      s"""
        |select
        |    unitid
        |from
        |    (select
        |        unitid
        |    from
        |        dl_cpc.ocpc_suggest_cpa_recommend_hourly
        |    where
        |        `date` = '$date'
        |    and
        |        hour = '06'
        |    and
        |        version = 'qtt_demo'
        |    and
        |        is_recommend = 1
        |    group by
        |        unitid
        |
        |    union
        |
        |    select
        |        unitid
        |    from
        |        dl_cpc.ocpc_aa_ab_report_base_data
        |    where
        |        `date` = '$date'
        |    and
        |        hour = 'all'
        |    and
        |        version = 'qtt_demo'
        |    and
        |        is_ocpc = 1) temp
        |group by
        |    unitid
      """.stripMargin
    println("------get kt ocpc all cost sql1-----")
    println(sql1)
    spark.sql(sql1).createOrReplaceTempView("all_unitid_table")

    // 统计每个Unit的总消费
    val sql2 =
      s"""
        |select
        |    industry,
        |    unitid,
        |    sum(case when isclick = 1 then price else 0 end) * 0.01 as cost
        |from
        |    dl_cpc.ocpc_aa_ab_report_base_data
        |where
        |    `date` = '$date'
        |and
        |    hour = '$hour'
        |and
        |    version = 'qtt_demo'
        |group by
        |    industry,
        |    unitid
      """.stripMargin
    println("------get kt ocpc all cost sql2-----")
    println(sql2)
    spark.sql(sql2).createOrReplaceTempView("unit_cost_table")

    // 分行业统计所有可投ocpc的总消费
    val sql3 =
      s"""
        |select
        |    b.industry,
        |    sum(b.cost) as kt_ocpc_cost
        |from
        |    all_unitid_table a
        |left join
        |    unit_cost_table b
        |on
        |    a.unitid = b.unitid
        |group by
        |    b.industry
      """.stripMargin
    println("------get kt ocpc all cost sql3-----")
    println(sql3)
    val ktOcpcAllCostDF = spark.sql(sql3)
    ktOcpcAllCostDF
  }

  // 获取昨天的cv、post_cvr等数据
  def getPreData(date: String, spark: SparkSession): DataFrame ={
    // 得到前一天的时间
    val sdf = new SimpleDateFormat("yyyy-MM-dd")
    val today = sdf.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -1)
    val yesterday = calendar.getTime
    val preDate = sdf.format(yesterday)

    val sql =
      s"""
        |select
        |    industry,
        |    cv as pre_cv,
        |    cost as pre_cost,
        |    post_cvr as pre_post_cvr,
        |    cpm as pre_cpm
        |from
        |    dl_cpc.ocpc_industry_aa_report_daily
        |where
        |    `date` = '$preDate'
        |and
        |    version = 'qtt_demo'
      """.stripMargin
    println("------get pre data sql-----")
    println(sql)
    val preDataDF = spark.sql(sql)
    preDataDF
  }
}
