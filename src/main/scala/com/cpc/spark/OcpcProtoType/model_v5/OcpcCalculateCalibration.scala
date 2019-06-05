package com.cpc.spark.OcpcProtoType.model_v5

import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
//import com.cpc.spark.OcpcProtoType.model_v5.OcpcSmoothFactor


object OcpcCalculateCalibration {
  def main(args: Array[String]): Unit = {
    /*
    pb文件格式：
    string identifier = 1;
    int32 conversiongoal = 2;
    double kvalue = 3;
    double cpagiven = 4;
    int64 cvrcnt = 5;
    对于明投广告，cpagiven=1， cvrcnt使用ocpc广告记录进行关联，k需要进行计算，每个conversiongoal都需要进行计算


     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    // 计算日期周期
    // bash: 2019-01-02 12 1 qtt_demo qtt
    val date = args(0).toString
    val hour = args(1).toString
    val conversionGoal = args(2).toInt
    val version = args(3).toString
    val media = args(4).toString

    // 主校准回溯时间长度
    val hourInt1 = args(5).toInt
    // 备用校准回溯时间长度
    val hourInt2 = args(6).toInt


    println("parameters:")
    println(s"date=$date, hour=$hour, conversionGoal=$conversionGoal, version=$version, media=$media, hourInt1=$hourInt1, hourInt2=$hourInt2")
    // 抽取媒体id
    val conf = ConfigFactory.load("ocpc")
    val conf_key = "medias." + media + ".media_selection"
    val mediaSelection = conf.getString(conf_key)
    val cvrType = "cvr" + conversionGoal.toString

    val data1 = OcpcSmoothFactor.OcpcSmoothFactor(date, hour, version, media, hourInt1, cvrType, spark)
    val data2 = OcpcSmoothFactor.OcpcSmoothFactor(date, hour, version, media, hourInt2, cvrType, spark)

    
  }

  def getPbByConversion(conversionGoal: Int, version: String, hourInt1: Int, hourInt2: Int, date: String, hour: String, spark: SparkSession) = {
    /*
    计算步骤
    1. 获取主校准的数据
    2. 获取备用校准的数据
    3. 更新数据：主校准优先级更高
     */
    // 主校准模型
    val dataRaw1 = getCalibrationData(conversionGoal, version, hourInt1, date, hour, spark)
    val data1 = dataRaw1
      .withColumn("pcoc1", col("pcoc"))
      .withColumn("jfb1", col("jfb"))
      .withColumn("post_cvr1", col("post_cvr"))
      .withColumn("flag", lit(1))
      .select("identifier", "pcoc1", "jfb1", "post_cvr1", "flag")

    // 备用校准模型
    val dataRaw2 = getCalibrationData(conversionGoal, version, hourInt2, date, hour, spark)
    val data2 = dataRaw2
      .withColumn("pcoc2", col("pcoc"))
      .withColumn("jfb2", col("jfb"))
      .withColumn("post_cvr2", col("post_cvr"))
      .select("identifier", "pcoc2", "jfb2", "post_cvr2")

    // 数据表关联
    val data = data2
      .join(data1, Seq("identifier"), "left_outer")
      .na.fill(0, Seq("flag"))
      .withColumn("pcoc", when(col("flag") === 1, col("pcoc1")).otherwise(col("pcoc2")))
      .withColumn("jfb", when(col("flag") === 1, col("jfb1")).otherwise(col("jfb2")))
      .withColumn("post_cvr", when(col("flag") === 1, col("post_cvr1")).otherwise(col("post_cvr2")))

    data.show()
//    data
//        .write.mode("overwrite").saveAsTable("test.check_ocpc_pb_data20190529")

    data

  }

  def udfSelectData() = udf((flag: Int, value1: Double, value2: Double) => {
    var result = value1
  })

  def getCalibrationData(conversionGoal: Int, version: String, hourInt: Int, date: String, hour: String, spark: SparkSession) = {
    val finalVersion = version + hourInt.toString

    val sqlRequest =
      s"""
         |SELECT
         |  identifier,
         |  pcoc,
         |  jfb,
         |  post_cvr
         |FROM
         |  dl_cpc.ocpc_pcoc_jfb_hourly
         |WHERE
         |  `date` = '$date'
         |AND
         |  `hour` = '$hour'
         |AND
         |  conversion_goal = $conversionGoal
         |AND
         |  version = '$finalVersion'
       """.stripMargin
    println(sqlRequest)
    val data = spark.sql(sqlRequest)

    data

  }

}


