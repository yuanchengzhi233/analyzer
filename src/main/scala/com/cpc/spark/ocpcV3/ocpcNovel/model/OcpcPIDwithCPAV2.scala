package com.cpc.spark.ocpcV3.ocpcNovel.model

import java.text.SimpleDateFormat
import java.util.Calendar
import com.cpc.spark.ocpcV3.ocpcNovel.model.OcpcRegressionV2.wz_discount
import com.cpc.spark.common.Utils.getTimeRangeSql
import com.cpc.spark.ocpc.OcpcUtils._
import com.cpc.spark.udfs.Udfs_wj._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.IntegerType


object OcpcPIDwithCPAV2 {
  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession.builder().appName("OcpcPIDwithCPA").enableHiveSupport().getOrCreate()

    val date = args(0).toString
    val hour = args(1).toString

    val result = calculateKv2(date, hour, spark)
    val tableName = "dl_cpc.ocpc_novel_k_value_table_v2"
    result
      .repartition(10).write.mode("overwrite").insertInto(tableName)
    println(s"successfully save data into table: $tableName")

    val prevk = spark.table("dl_cpc.ocpcv3_novel_pb_v2_once")
    prevk.write.mode("overwrite").saveAsTable("dl_cpc.ocpcv3_novel_pb_v2_once_middle")


  }

  /*******************************************************************************/
  def calculateKv2(date: String, hour: String, spark: SparkSession) :DataFrame = {
    /**
      * 计算新版k值
      * 基于前24个小时的平均k值和那段时间的cpa_ratio，按照更加详细的分段函数对k值进行计算
      */

    val baseData = getBaseTable(date, hour, spark)
    println("################ baseData #################")
    baseData.show(10)
    val historyData = getHistoryData(date, hour, 24, spark)
    println("################# historyData ####################")
    historyData.show(10)
    val avgK = getAvgK(baseData, historyData, date, hour, spark)
    println("################# avgK table #####################")
    avgK.show(10)
    val cpaRatio = getCPAratio(baseData, historyData, date, hour, spark)
    println("################# cpaRatio table #######################")
    cpaRatio.show(10)
    val newK = updateKv2(baseData, avgK, cpaRatio, date, hour, spark)
    println("################# final result ####################")
    newK.show(10)
    newK
  }

  def getBaseTable(endDate: String, hour: String, spark: SparkSession) :DataFrame ={
    // 计算日期周期
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val date = dateConverter.parse(endDate)
    val calendar = Calendar.getInstance
    calendar.setTime(date)
    calendar.add(Calendar.DATE, -7)
    val dt = calendar.getTime
    val startDate = dateConverter.format(dt)
    val selectCondition = getTimeRangeSql(startDate, hour, endDate, hour)

    // 累积计算最近一周数据
    val sqlRequest =
      s"""
         |SELECT
         |  unitid,
         |  adclass
         |FROM
         |  dl_cpc.ocpcv3_ctr_data_hourly
         |WHERE $selectCondition
         |  and media_appsid in ("80001098", "80001292")
       """.stripMargin
    println(sqlRequest)
    val baseData = spark
      .sql(sqlRequest)
      .withColumn("new_adclass", col("adclass")/1000)
      .withColumn("new_adclass", col("new_adclass").cast(IntegerType))
      .select("unitid", "new_adclass")
      .distinct()

//    baseData.write.mode("overwrite").saveAsTable("test.wy01")
    baseData

  }

  def getHistoryData(date: String, hour: String, hourCnt: Int, spark: SparkSession) :DataFrame ={

    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")
    val newDate = date + " " + hour
    val today = dateConverter.parse(newDate)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.HOUR, -hourCnt)
    val yesterday = calendar.getTime
    val tmpDate = dateConverter.format(yesterday)
    val tmpDateValue = tmpDate.split(" ")
    val date1 = tmpDateValue(0)
    val hour1 = tmpDateValue(1)
    val selectCondition = getTimeRangeSql2(date1, hour1, date, hour)

    val sqlRequest =
      s"""
         |SELECT
         |  searchid,
         |  unitid,
         |  adclass,
         |  isshow,
         |  isclick,
         |  price,
         |  ocpc_log,
         |  hour
         |FROM
         |  dl_cpc.ocpcv3_unionlog_label_hourly
         |WHERE
         |  $selectCondition
         |and
         |media_appsid in ('80001098', '80001292')
       """.stripMargin
    println(sqlRequest)
    val resultDF = spark.sql(sqlRequest)
//    resultDF.write.mode("overwrite").saveAsTable("test.wy02")
    resultDF
  }


  def getAvgK(baseData: DataFrame, historyData: DataFrame, date: String, hour: String, spark: SparkSession) :DataFrame ={
    /**
      * 计算修正前的k基准值
      * case1：前24个小时有isclick=1的数据，统计这批数据的k均值作为基准值
      * case2：前24个小时没有isclick=1的数据，将前一个小时的数据作为基准值
      */

    historyData
      .withColumn("ocpc_log_dict", udfStringToMap()(col("ocpc_log")))
      .createOrReplaceTempView("raw_table")

    val sqlRequest2 =
      s"""
         |SELECT
         |  searchid,
         |  unitid,
         |  adclass,
         |  isshow,
         |  isclick,
         |  ocpc_log,
         |  ocpc_log_dict['kvalue'] as kvalue,
         |  hour
         |FROM
         |  raw_table
       """.stripMargin
    println(sqlRequest2)
    val rawData = spark.sql(sqlRequest2)

    // case1
    val case1 = rawData
      .filter("isclick=1")
      .withColumn("new_adclass", col("adclass")/1000)
      .withColumn("new_adclass", col("new_adclass").cast(IntegerType))
      .groupBy("unitid", "new_adclass")
      .agg(avg(col("kvalue")).alias("kvalue1"))
      .select("unitid", "new_adclass", "kvalue1")

//    case1.write.mode("overwrite").saveAsTable("test.wy_case1")

    // case2
    // table name: dl_cpc.ocpcv3_novel_pb_hourly
//    ocpcv3_novel_pb_v2_hourly
    val case2 = spark
      .table("dl_cpc.ocpcv3_novel_pb_v2_once")
      .withColumn("kvalue2", col("kvalue"))
      .select("unitid", "kvalue2")
      .distinct()

    // 优先case1，然后case2
    val resultDF = baseData
      .join(case1, Seq("unitid", "new_adclass"), "left_outer")
      .select("unitid", "new_adclass", "kvalue1")
      .join(case2, Seq("unitid"), "left_outer")
      .select("unitid", "new_adclass", "kvalue1", "kvalue2")
      .withColumn("kvalue", when(col("kvalue1").isNull, col("kvalue2")).otherwise(col("kvalue1")))

    resultDF.show(10)
//    resultDF.write.mode("overwrite").saveAsTable("test.wy03")
    resultDF

  }

  def getCPAratio(baseData: DataFrame, historyData: DataFrame, date: String, hour: String, spark: SparkSession) :DataFrame ={
    /**
      * 计算前24个小时每个广告创意的cpa_given/cpa_real的比值
      * case1：hourly_ctr_cnt<10，可能出价过低，需要提高k值，所以比值应该大于1
      * case2：hourly_ctr_cnt>=10但是没有cvr_cnt，可能出价过高，需要降低k值，所以比值应该小于1
      * case3：hourly_ctr_cnt>=10且有cvr_cnt，按照定义计算比值即可
      */

    // 获得cpa_given
    // TODO 表名
    val tableName = "dl_cpc.ocpcv3_novel_cpa_history_hourly_v2"
    val cpaGiven = spark
      .table(tableName)
      .where(s"`date`='$date' and `hour`='$hour'")
      .withColumn("cpa_given", col("cpa_history"))
      .select("unitid", "new_adclass", "cpa_given", "conversion_goal")

    val cvr1Data=getCvr1HistoryData(date, hour, 24, spark)
      .withColumn("new_adclass", col("adclass")/1000)
      .withColumn("new_adclass", col("new_adclass").cast(IntegerType))
      .groupBy("unitid", "new_adclass")
      .agg(sum(col("cvr1cnt")).alias("cvr1cnt"))
    val cvr2Data=getCvr2HistoryData(date, hour, 24, spark)
      .withColumn("new_adclass", col("adclass")/1000)
      .withColumn("new_adclass", col("new_adclass").cast(IntegerType))
      .groupBy("unitid", "new_adclass")
      .agg(sum(col("cvr2cnt")).alias("cvr2cnt"))
    // 按ideaid统计每一个广告创意的数据
    val rawData = historyData
      .withColumn("cost",
        when(col("isclick")===1,col("price")).otherwise(0))
      .withColumn("new_adclass", col("adclass")/1000)
      .withColumn("new_adclass", col("new_adclass").cast(IntegerType))
      .groupBy("unitid", "new_adclass")
      .agg(
        sum(col("cost")).alias("total_cost"),
        sum(col("isclick")).alias("ctr_cnt"))
      .select("unitid", "new_adclass", "total_cost", "ctr_cnt")
      .join(cvr1Data,Seq("unitid", "new_adclass"), "left_outer")
      .join(cvr2Data,Seq("unitid", "new_adclass"), "left_outer")
      .select("unitid", "new_adclass", "total_cost", "ctr_cnt", "cvr1cnt", "cvr2cnt")

    // 计算cpa_ratio
    val joinData = baseData
      .join(cpaGiven, Seq("unitid", "new_adclass"), "left_outer")
      .select("unitid", "new_adclass", "cpa_given", "conversion_goal")
      .join(rawData, Seq("unitid", "new_adclass"), "left_outer")
      .withColumn("cvr_cnt", when(col("conversion_goal")===2, col("cvr2cnt")).otherwise(col("cvr1cnt")))
      .select("unitid", "new_adclass", "cpa_given", "conversion_goal", "total_cost", "ctr_cnt", "cvr_cnt")
      .filter("cpa_given is not null")

    joinData.createOrReplaceTempView("join_table")
//    joinData.write.mode("overwrite").saveAsTable("test.wy_join")

    val sqlRequest =
      s"""
         |SELECT
         |  unitid,
         |  new_adclass,
         |  conversion_goal,
         |  cpa_given,
         |  total_cost,
         |  ctr_cnt,
         |  cvr_cnt,
         |  (case when total_cost is null or total_cost = 0 then 1.0
         |        when (cvr_cnt = 0 or cvr_cnt is null) and total_cost > 50000 then 0.8
         |        when (cvr_cnt = 0 or cvr_cnt is null) and total_cost <= 50000 then 1.0
         |        else cpa_given * cvr_cnt * 1.0 / total_cost end) as cpa_ratio
         |FROM
         |  join_table
       """.stripMargin
    println(sqlRequest)
    val cpaRatio = spark.sql(sqlRequest)
//    cpaRatio.write.mode("overwrite").saveAsTable("test.wy04")
    cpaRatio

  }

  def updateKv2(baseData: DataFrame, kValue: DataFrame, cpaRatio: DataFrame, date: String, hour: String, spark: SparkSession) :DataFrame ={
    /**
      * 根据新的K基准值和cpa_ratio来在分段函数中重新定义k值
      * case1：0.9 <= cpa_ratio <= 1.1，k基准值
      * case2：0.8 <= cpa_ratio < 0.9，k / 1.1
      * case2：1.1 < cpa_ratio <= 1.2，k * 1.1
      * case3：0.6 <= cpa_ratio < 0.8，k / 1.2
      * case3：1.2 < cpa_ratio <= 1.4，k * 1.2
      * case4：0.4 <= cpa_ratio < 0.6，k / 1.4
      * case5：1.4 < cpa_ratio <= 1.6，k * 1.4
      * case6：cpa_ratio < 0.4，k / 1.6
      * case7：cpa_ratio > 1.6，k * 1.6
      *
      * 上下限依然是0.2 到1.2
      */

    // 关联得到基础表
    val rawData = baseData
      .join(kValue, Seq("unitid", "new_adclass"), "left_outer")
      .select("unitid", "new_adclass", "kvalue")
      .join(cpaRatio, Seq("unitid", "new_adclass"), "left_outer")
      .select("unitid", "new_adclass", "kvalue", "cpa_ratio", "conversion_goal")
      .withColumn("ratio_tag", udfSetRatioCase()(col("cpa_ratio")))
      .withColumn("ratio_tag",when(col("new_adclass")===110110,udfSetRatioCase()(col("cpa_ratio") * wz_discount))
        .otherwise(col("ratio_tag")))
      .withColumn("updated_k", udfUpdateK()(col("ratio_tag"), col("kvalue")))

//    rawData.write.mode("overwrite").saveAsTable("test.wy05")
    val resultDF = rawData
      .select("unitid", "new_adclass", "updated_k", "conversion_goal")
      .withColumn("k_value", col("updated_k"))
      .select("unitid", "new_adclass", "k_value", "updated_k", "conversion_goal")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))

    resultDF
  }


}