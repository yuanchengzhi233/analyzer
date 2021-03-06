package com.cpc.spark.OcpcProtoType.suggest_cpa_v3

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.OcpcProtoType.OcpcTools._
import com.cpc.spark.OcpcProtoType.suggest_cpa_v3.OcpcCalculateAUC.OcpcCalculateAUCmain
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}


object OcpcSuggestCPA {
  def main(args: Array[String]): Unit = {
    /*
    新版推荐cpa程序：
    unitid, userid, adclass, original_conversion, conversion_goal, show, click, cvrcnt, cost, post_ctr, acp, acb, jfb, cpa, pcvr, post_cvr, pcoc, cal_bid, auc, kvalue, industry, is_recommend, ocpc_flag, usertype, pcoc1, pcoc2

    主要源表：dl_cpc.ocpc_base_unionlog, dl_cpc.ocpc_label_cvr_hourly

    数据构成分为以下部分:
    1. 基础数据部分：unitid, userid, adclass, original_conversion, conversion_goal, show, click, cvrcnt, cost, post_ctr, acp, acb, jfb, cpa, pcvr, post_cvr, pcoc, industry, usertype
    2. ocpc部分：kvalue
    3. 模型部分：auc
    4. 实时查询：ocpc_flag
    5. 历史推荐cpa数据：pcoc1, pcoc2
    6.
     */
    // 计算日期周期
    Logger.getRootLogger.setLevel(Level.WARN)
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val hourInt = args(3).toInt


    val spark = SparkSession
      .builder()
      .appName(s"ocpc suggest cpa v2: $date, $hour")
      .enableHiveSupport().getOrCreate()
    println("parameters:")
    println(s"date=$date, hour=$hour, version=$version")


    // 按照转化目标抽取基础数据表
    val baseLog = getBaseLog(hourInt, date, hour, spark)

    // 统计数据
    val baseData = calculateLog(baseLog, date, hour, spark).repartition(10).cache()
    baseData.show(10)

    // ocpc校准部分
    val kvalue = getKvalue(baseLog, baseData, date, hour, spark).repartition(10).cache()
    kvalue.show(10)

    // 模型部分
    val aucData = OcpcCalculateAUCmain(date, hour, version, hourInt, spark).repartition(10).cache()
    aucData.show(10)

    // 获取ocpc_status
    val ocpcStatusRaw = getConversionGoal(date, hour, spark)
    val ocpcStatus = ocpcStatusRaw
      .select("unitid", "userid", "ocpc_status")
      .distinct()


    // 数据组装
    val result = assemblyData(baseData, kvalue, aucData, ocpcStatus, spark)

    val resultDF = result
      .select("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type", "show", "click", "cvrcnt", "cost", "post_ctr", "acp", "acb", "jfb", "cpa", "pre_cvr", "post_cvr", "pcoc", "cal_bid", "auc", "is_recommend", "ocpc_status")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))
      .withColumn("version", lit(version))

    resultDF
//      .repartition(10).write.mode("overwrite").insertInto("test.ocpc_recommend_units_hourly")
      .repartition(10).write.mode("overwrite").insertInto("dl_cpc.ocpc_recommend_units_hourly")
    println("successfully save data into table: dl_cpc.ocpc_recommend_units_hourly")
  }

  def assemblyData(baseData: DataFrame, kvalue: DataFrame, aucData: DataFrame, ocpcStatus: DataFrame, spark: SparkSession) = {
    /*
    assemlby the data together
     */
    val rawData = baseData
      .join(kvalue, Seq("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type"), "left_outer")
      .join(aucData, Seq("unitid", "conversion_goal", "media"), "left_outer")
      .join(ocpcStatus, Seq("unitid", "userid"), "left_outer")
      .select("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type", "show", "click", "cvrcnt", "cost", "post_ctr", "acp", "acb", "jfb", "cpa", "pre_cvr", "post_cvr", "pcoc", "cal_bid", "auc", "ocpc_status")

    // 从配置文件读取数据
    val conf = ConfigFactory.load("ocpc")
    val confPath = conf.getString("ocpc_all.light_control.ocpc_userid_threshold")
    val confRawData = spark.read.format("json").json(confPath)
    val confData = confRawData
      .select("userid", "min_cv", "min_auc")
      .distinct()
    confData.show(10)

    val resultDF = rawData
      .join(confData, Seq("userid"), "left_outer")
      .na.fill(-1, Seq("min_cv", "min_auc"))
      .withColumn("is_recommend", when(col("auc").isNotNull && col("cal_bid").isNotNull && col("cvrcnt").isNotNull, 1).otherwise(0))
      .withColumn("is_recommend", udfIsRecommendV2()(col("industry"), col("media"), col("conversion_goal"), col("cvrcnt"), col("auc"), col("is_recommend"), col("min_cv"), col("min_auc")))
      .na.fill(0, Seq("is_recommend"))
      .withColumn("is_recommend", when(col("industry") === "wzcp", 1).otherwise(col("is_recommend")))
      .select("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type", "show", "click", "cvrcnt", "cost", "post_ctr", "acp", "acb", "jfb", "cpa", "pre_cvr", "post_cvr", "pcoc", "cal_bid", "auc", "is_recommend", "ocpc_status")
      .cache()

    resultDF.show(10)

//    resultDF
//        .write.mode("overwrite").saveAsTable("test.check_suggest_cpa20191114b")

    resultDF

  }

  def udfIsRecommendV2() = udf((industry: String, media: String, conversionGoal: Int, cv: Long, auc: Double, isRecommend: Int, minCV: Int, minAuc: Double) => {
    var result = isRecommend
    if (isRecommend == 1) {
      result = (media, industry, conversionGoal) match {
        case ("qtt", "elds", _) | ("qtt", "feedapp", _) | ("novel", "elds", _) | ("novel", "feedapp", _) => {
          val cvThresh = {
            if (minCV < 0) {
              10
            } else {
              minCV
            }
          }
          val aucThreshold = {
            if (minAuc < 0) {
              0.6
            } else {
              minAuc
            }
          }

          if (cv >= cvThresh && auc >= aucThreshold) {
            1
          } else {
            0
          }
        }
        case (_, "wzcp", _) => 1
        case (_, "others", _) => {
          val cvThresh = {
            if (minCV < 0) {
              10
            } else {
              minCV
            }
          }
          val aucThreshold = {
            if (minAuc < 0) {
              0.5
            } else {
              minAuc
            }
          }

          if (cv >= cvThresh && auc >= 0.55) {
            1
          } else if (cv >= 60 && auc >= aucThreshold) {
            1
          } else {
            0
          }
        }
        case ("hottopic", "feedapp", 1) => {
          val cvThresh = {
            if (minCV < 0) {
              20
            } else {
              minCV
            }
          }
          val aucThreshold = {
            if (minAuc < 0) {
              0.6
            } else {
              minAuc
            }
          }

          if (cv >= cvThresh && auc >= aucThreshold) {
            1
          } else {
            0
          }
        }
        case (_, _, _) => {
          val cvThresh = {
            if (minCV < 0) {
              60
            } else {
              minCV
            }
          }
          val aucThreshold = {
            if (minAuc < 0) {
              0.6
            } else {
              minAuc
            }
          }

          if (cv >= cvThresh && auc >= aucThreshold) {
            1
          } else {
            0
          }
        }
      }
    }
    result
  })

  def udfIsRecommend() = udf((industry: String, media: String, conversion_goal: Int, cv: Long, auc: Double, isRecommend: Int) => {
    var result = isRecommend
    if (isRecommend == 1) {
      if ((media == "novel" || media == "qtt") && (industry == "elds" || industry == "feedapp")) {
        if (cv >= 10 && auc >= 0.6) {
          result = 1
        } else {
          result = 0
        }
      } else if (industry == "others") {
        if (cv >= 10 && auc >= 0.55) {
          result = 1
        } else if (cv >= 60 && auc >= 0.5) {
          result = 1
        } else {
          result = 0
        }
      } else if ((media == "hottopic") && (industry == "feedapp") && (conversion_goal == 1)) {
        if (cv >= 20 && auc >= 0.6) {
          result = 1
        } else {
          result = 0
        }
      } else {
        if (cv >= 60 && auc >= 0.6) {
          result = 1
        } else {
          result = 0
        }
      }
    } else {
      result = isRecommend
    }
    result
  })

  def getKvalue(baseData: DataFrame, baseStat: DataFrame, date: String, hour: String, spark: SparkSession) = {
    baseStat.createOrReplaceTempView("base_data")
    val sqlRequest =
      s"""
         |SELECT
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  media,
         |  adclass,
         |  industry,
         |  usertype,
         |  adslot_type,
         |  1.0 / pcoc as cali_value,
         |  1.0 / jfb as jfb_factor,
         |  post_cvr,
         |  cpa
         |FROM
         |  base_data
       """.stripMargin
    println(sqlRequest)
    val cvrData = spark.sql(sqlRequest)

    val data = baseData
      .join(cvrData, Seq("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type"), "inner")
      .withColumn("cali_pcvr", col("exp_cvr") * 0.5 * col("cali_value") + col("post_cvr") * 0.5)
      .select("searchid", "unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type", "cali_pcvr", "jfb_factor", "cpa")
      .withColumn("cal_bid", col("cali_pcvr") * col("cpa") * col("jfb_factor"))
      .groupBy("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type")
      .agg(
        avg(col("cal_bid")).alias("cal_bid")
      )
      .select("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type", "cal_bid")

    data
  }

  def calculateLog(rawData: DataFrame, date: String, hour: String, spark: SparkSession) = {
    // 数据统计: unitid, userid, adclass, original_conversion, conversion_goal, show, click, cvrcnt, cost, post_ctr, acp, acb, jfb, cpa, pcvr, post_cvr, pcoc, industry, usertype
    rawData.createOrReplaceTempView("raw_data")
    val sqlRequest1 =
      s"""
         |SELECT
         |  unitid,
         |  userid,
         |  conversion_goal,
         |  media,
         |  adclass,
         |  industry,
         |  usertype,
         |  adslot_type,
         |  sum(isshow) as show,
         |  sum(isclick) as click,
         |  sum(iscvr) as cvrcnt,
         |  sum(case when isclick=1 then price else 0 end) as cost,
         |  sum(isclick) * 1.0 / sum(isshow) as post_ctr,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(isclick) as acp,
         |  sum(case when isclick=1 then bid else 0 end) * 1.0 / sum(isclick) as acb,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(case when isclick=1 then bid else 0 end) as jfb,
         |  sum(case when isclick=1 then price else 0 end) * 1.0 / sum(iscvr) as cpa,
         |  sum(case when isclick=1 then exp_cvr else 0 end) * 1.0 / sum(isclick) as pre_cvr,
         |  sum(iscvr) * 1.0 / sum(isclick) as post_cvr
         |FROM
         |  raw_data
         |GROUP BY unitid, userid, conversion_goal, media, adclass, industry, usertype, adslot_type
       """.stripMargin
    println(sqlRequest1)
    val data = spark
      .sql(sqlRequest1)
      .withColumn("pcoc", col("pre_cvr") * 1.0 / col("post_cvr"))

    // 数据关联
    val resultDF = data
      .select("unitid", "userid", "conversion_goal", "media", "adclass", "industry", "usertype", "adslot_type", "show", "click", "cvrcnt", "cost", "post_ctr", "acp", "acb", "jfb", "cpa", "pre_cvr", "post_cvr", "pcoc")

    resultDF
  }

  def getBaseLog(hourCnt: Int, date: String, hour: String, spark: SparkSession) = {
    /*
    抽取基础数据用于后续计算与统计
    unitid, userid, adclass, original_conversion, conversion_goal, show, click, cvrcnt, cost, post_ctr, acp, acb, jfb, cpa, pcvr, post_cvr, pcoc, industry, usertype
     */
    // 媒体选择
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias.total.media_selection"
    val mediaSelection = conf.getString(conf_key)

    // 时间区间选择
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")
    val endDay = date + " " + hour
    val endDayTime = dateConverter.parse(endDay)
    val calendar = Calendar.getInstance
    calendar.setTime(endDayTime)
    calendar.add(Calendar.HOUR, -hourCnt)
    val startDateTime = calendar.getTime
    val startDateStr = dateConverter.format(startDateTime)
    val date1 = startDateStr.split(" ")(0)
    val hour1 = startDateStr.split(" ")(1)
    val timeSelection = getTimeRangeSqlDate(date1, hour1, date, hour)

    // 抽取点击数据: dl_cpc.ocpc_base_unionlog
    val sqlRequest1 =
      s"""
         |SELECT
         |    searchid,
         |    unitid,
         |    userid,
         |    adclass,
         |    isshow,
         |    isclick,
         |    price,
         |    bid_discounted_by_ad_slot as bid,
         |    (case
         |        when media_appsid in ('80000001', '80000002') then 'qtt'
         |        when media_appsid in ('80002819', '80004944', '80004948', '80004953') then 'hottopic'
         |        else 'novel'
         |    end) as media,
         |    (case
         |        when (cast(adclass as string) like '134%' or cast(adclass as string) like '107%') then "elds"
         |        when (adslot_type<>7 and cast(adclass as string) like '100%') then "feedapp"
         |        when (adslot_type=7 and cast(adclass as string) like '100%') then "yysc"
         |        when adclass in (110110100, 125100100) then "wzcp"
         |        else "others"
         |    end) as industry,
         |    usertype,
         |    exp_cvr,
         |    exp_ctr,
         |    conversion_goal,
         |    adslot_type
         |FROM
         |    dl_cpc.ocpc_base_unionlog
         |WHERE
         |    $timeSelection
         |AND
         |    $mediaSelection
         |AND
         |    conversion_goal > 0
         |AND
         |    is_ocpc = 1
       """.stripMargin
    println(sqlRequest1)
    val ctrData = spark
      .sql(sqlRequest1)
      .withColumn("cvr_goal", udfConcatStringInt("cvr")(col("conversion_goal")))

    // 抽取转化数据
    val sqlRequest2 =
      s"""
         |SELECT
         |    searchid,
         |    label as iscvr,
         |    cvr_goal
         |FROM
         |    dl_cpc.ocpc_label_cvr_hourly
         |WHERE
         |    $timeSelection
       """.stripMargin
    println(sqlRequest2)
    val cvrData = spark.sql(sqlRequest2)

    // 数据关联
    val data = ctrData
      .join(cvrData, Seq("searchid", "cvr_goal"), "left_outer")
    data
  }

}
