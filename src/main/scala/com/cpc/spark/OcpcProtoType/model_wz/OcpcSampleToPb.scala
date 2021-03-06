

package com.cpc.spark.OcpcProtoType.model_wz

import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.common.Utils.getTimeRangeSql
import com.cpc.spark.ocpc.OcpcUtils.{getTimeRangeSql2, getTimeRangeSql3}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import ocpc.ocpc.{OcpcList, SingleRecord}

import scala.collection.mutable.ListBuffer


object OcpcSampleToPb {
  def main(args: Array[String]): Unit = {
    /*
    pb文件格式：
    string identifier = 1;
    int32 conversiongoal = 2;
    double kvalue = 3;
    double cpagiven = 4;
    int64 cvrcnt = 5;
    对于明投广告，cpagiven=1， cvrcnt使用ocpc广告记录进行关联，k需要进行计算

    将文件从dl_cpc.ocpc_pb_result_hourly_v2表中抽出，存入pb文件，需要过滤条件：
    kvalue>0
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // bash: 2019-01-02 12 qtt_demo 1
    val date = args(0).toString
    val hour = args(1).toString
    val version = args(2).toString
    val isKnown = args(3).toInt

    println("parameters:")
    println(s"date=$date, hour=$hour, version=$version, isKnown:$isKnown")
    val resultDF  = getPbData(version, date, hour, spark)

    resultDF
        .withColumn("version", lit(version))
        .select("identifier", "conversion_goal", "cpagiven", "cvrcnt", "kvalue", "version")
//        .repartition(10).write.mode("overwrite").saveAsTable("test.ocpc_prev_pb_once20190317")
        .repartition(10).write.mode("overwrite").insertInto("dl_cpc.ocpc_prev_pb_once")

    savePbPack(resultDF, version, isKnown)
  }

  def getPbData(version: String, date: String, hour: String, spark: SparkSession) = {
    /*
    string identifier = 1;
    int32 conversiongoal = 2;
    double kvalue = 3;
    double cpagiven = 4;
    int64 cvrcnt = 5;
    1. 从dl_cpc.ocpc_pb_result_hourly_v2中抽取数据
    2. 按照实验配置文件给出cpagiven
     */
    // 从dl_cpc.ocpc_pb_result_hourly_v2中抽取数据
    val selectCondition = s"`date`='$date' and `hour`='$hour' and version='$version'"

    val sqlRequest =
      s"""
         |SELECT
         |  identifier,
         |  conversion_goal,
         |  kvalue,
         |  cpagiven as cpagiven1,
         |  cvrcnt
         |FROM
         |  dl_cpc.ocpc_pb_result_hourly_v2
         |WHERE
         |  $selectCondition
         |AND
         |  kvalue > 0
       """.stripMargin
    println(sqlRequest)
    val data = spark.sql(sqlRequest)

    // 按照实验配置文件给出cpagiven
    val cpaGiven = getCPAgivenV3(date, spark)

    // 数据关联
    val result = data
        .join(cpaGiven, Seq("identifier"), "left_outer")
        .withColumn("cpagiven", when(col("cpagiven2").isNotNull, col("cpagiven2")).otherwise(col("cpagiven1")))
        .select("identifier", "conversion_goal", "kvalue", "cpagiven", "cvrcnt", "cpagiven1", "cpagiven2")

    result.printSchema()
    result.show(10)
    val resultDF = result.select("identifier", "conversion_goal", "kvalue", "cpagiven", "cvrcnt")


    resultDF
  }

//  def getUserBlackFlag(date: String, hour: String, spark: SparkSession) ={
//    // 从实验配置文件读取配置的CPAgiven
//    val conf = ConfigFactory.load("ocpc")
//    val expDataPath = conf.getString("ocpc_wz.ocpc_wz_user_blacklist")
//    val confData = spark.read.format("json").json(expDataPath)
//    confData.show(10)
//
//    val userid = confData.filter(s"flag = 1").select("userid")
//
//    // 从mysql抽取对应的unitid
//    val url = "jdbc:mysql://rr-2zehhy0xn8833n2u5.mysql.rds.aliyuncs.com:3306/adv?useUnicode=true&characterEncoding=utf-8"
//    val user = "adv_live_read"
//    val passwd = "seJzIPUc7xU"
//    val driver = "com.mysql.jdbc.Driver"
//    val table = "(select id, user_id from adv.unit where ideas is not null) as tmp"
//
//    val unitData = spark.read.format("jdbc")
//      .option("url", url)
//      .option("driver", driver)
//      .option("user", user)
//      .option("password", passwd)
//      .option("dbtable", table)
//      .load()
//
//    val base = unitData
//      .withColumn("unitid", col("id"))
//      .withColumn("userid", col("user_id"))
//      .select("unitid", "userid")
//
//    base.createOrReplaceTempView("base_table")
//    val sqlRequest =
//      s"""
//         |SELECT
//         |    cast(unitid as string) as identifier,
//         |    cast(userid as int) as userid
//         |FROM
//         |    base_table
//       """.stripMargin
//    println(sqlRequest)
//    val identifierList = spark.sql(sqlRequest).distinct()
//
//    // 数据关联
//    val resultDF = identifierList
//      .join(userid, Seq("userid"), "inner")
//      .withColumn("black_flag", lit(1))
//    resultDF.show(10)
//
//    resultDF.write.mode("overwrite").saveAsTable("test.check_unitid_userid_blacklist20190417")
//
//    resultDF
//  }

  def getCPAgivenV3(date: String, spark: SparkSession) = {
    // 时间分区
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val today = dateConverter.parse(date)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.DATE, -1)
    val yesterday = calendar.getTime
    val date1 = dateConverter.format(yesterday)
    val selectCondition = s"`date` = '$date1' and `hour` = '06' and version = 'qtt_demo'"

    val sqlRequest =
      s"""
         |SELECT
         |  cast(unitid as string) identifier,
         |  cpa as cpagiven2
         |FROM
         |  dl_cpc.ocpc_auto_budget_hourly
         |WHERE
         |  $selectCondition
         |AND
         |  industry in ('wzcp')
       """.stripMargin
    println(sqlRequest)
    val result = spark.sql(sqlRequest)
    result
  }

  def getCPAgivenV2(spark: SparkSession) = {
    // 从实验配置文件读取配置的CPAgiven
    val sqlRequest =
      s"""
         |SELECT
         |  cast(unitid as string) identifier,
         |  cpa as cpagiven2
         |FROM
         |  test.ocpc_suggest_cpa_recommend_hourly_wz
       """.stripMargin
    println(sqlRequest)
    val result = spark.sql(sqlRequest)
    result
  }

  def getCPAgiven(spark: SparkSession) = {
    // 从实验配置文件读取配置的CPAgiven
    val conf = ConfigFactory.load("ocpc")
    val expDataPath = conf.getString("ocpc_wz.suggest_k_path")
    val data = spark.read.format("json").json(expDataPath)

    val resultDF = data
      .select("identifier", "cpa_suggest")
      .groupBy("identifier")
      .agg(avg(col("cpa_suggest")).alias("cpagiven2"))
      .select("identifier", "cpagiven2")

    resultDF
  }

  def savePbPack(dataset: DataFrame, version: String, isKnown: Int): Unit = {
    var list = new ListBuffer[SingleRecord]
    var filename = ""
    if (isKnown == 1) {
      filename = s"Ocpc_" + version + "_known.pb"
    } else {
      filename = s"Ocpc_" + version + "_unknown.pb"
    }
    println("size of the dataframe")
    println(dataset.count)
    println(s"filename: $filename")
    dataset.show(10)
    dataset.printSchema()
    var cnt = 0

    for (record <- dataset.collect()) {
      val identifier = record.getAs[String]("identifier")
      val cpaGiven = record.getAs[Double]("cpagiven")
      val kvalue = record.getAs[Double]("kvalue")
      val cvrCnt = record.getAs[Long]("cvrcnt")
      val conversionGoal = record.getAs[Int]("conversion_goal")

      if (cnt % 100 == 0) {
        println(s"identifier:$identifier, conversionGoal:$conversionGoal, cpaGiven:$cpaGiven, kvalue:$kvalue, cvrCnt:$cvrCnt")
      }
      cnt += 1

      //      string identifier = 1;
      //      int32 conversiongoal = 2;
      //      double kvalue = 3;
      //      double cpagiven = 4;
      //      int64 cvrcnt = 5;

      val currentItem = SingleRecord(
        identifier = identifier,
        conversiongoal = conversionGoal,
        kvalue = kvalue,
        cpagiven = cpaGiven,
        cvrcnt = cvrCnt
      )
      list += currentItem

    }
    val result = list.toArray[SingleRecord]
    val adRecordList = OcpcList(
      adrecord = result
    )

    println("length of the array")
    println(result.length)
    adRecordList.writeTo(new FileOutputStream(filename))

    println("complete save data into protobuffer")

  }

}

