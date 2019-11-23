package com.cpc.spark.oCPX.oCPC.calibration_x.pcoc_prediction

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.log4j.{Level, Logger}
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.ml.feature.{OneHotEncoder, StandardScaler, StringIndexer, VectorAssembler}
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable.ListBuffer

object pcocModel {
  def main(args: Array[String]): Unit = {
    /*
    采用拟合模型进行pcoc的时序预估
     */
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    Logger.getRootLogger.setLevel(Level.WARN)

    // 计算日期周期
    // bash: 2019-01-02 12 1 qtt_demo qtt
    val date = args(0).toString
    val hour = args(1).toString
    val hourDiff = args(2).toInt
    val version = args(3).toString


    println("parameters:")
    println(s"date=$date, hour=$hour, hourDiff=$hourDiff, version=$version")

    val data = getData(date, hour, version, spark)

    val trainingData = getTrainingData(data, spark)

    val predictData = getPredictData(date, hour, hourDiff, version, spark)

    val result = trainAndPredict(trainingData, predictData, spark)

    result
      .write.mode("overwrite").saveAsTable("test.check_ocpc_predict_pcoc_data20191123")

  }

  def getPredictData(date: String, hour: String, hourDiff: Int, version: String, spark: SparkSession) = {
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")
    val newDate = date + " " + hour
    val today = dateConverter.parse(newDate)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.HOUR, hourDiff)
    val tmpDate1 = dateConverter.format(calendar.getTime)
    val tmpDateValue1 = tmpDate1.split(" ")
    val hour1 = tmpDateValue1(1)

    val sqlRequest =
      s"""
         |SELECT
         |  *
         |FROM
         |  test.ocpc_pcoc_sample_part1_hourly
         |WHERE
         |  date = '$date'
         |AND
         |  hour = '$hour'
         |AND
         |  version = '$version'
         |""".stripMargin
    println(sqlRequest)
    val data = spark
      .sql(sqlRequest)
      .withColumn("avg_pcoc", col("feature_list").getItem(0))
      .withColumn("diff1_pcoc", col("feature_list").getItem(1))
      .withColumn("diff2_pcoc", col("feature_list").getItem(2))
      .withColumn("recent_pcoc", col("feature_list").getItem(3))
      .withColumn("hour", lit(hour1))
      .withColumn("time", concat_ws(" ", col("date"), col("hour")))
      .select("identifier", "media", "conversion_goal", "conversion_from", "avg_pcoc", "diff1_pcoc", "diff2_pcoc", "recent_pcoc", "hour", "time")
      .cache()

    data.show(10)

    data
  }

  def trainAndPredict(dataRaw: DataFrame, predictFeatures: DataFrame, spark: SparkSession) = {
    /*
    对pre_pcoc ~ pre_cv做scaler
     */
    val data = dataRaw.cache()
    data.show(10)
    // 模型训练
    val stagesArray = new ListBuffer[PipelineStage]()

    val hourIndexer = new StringIndexer().setInputCol("hour").setOutputCol("hour_index")
    stagesArray.append(hourIndexer)
    val hourEncoder = new OneHotEncoder().setInputCol("hour_index").setOutputCol("hour_vec")
    stagesArray.append(hourEncoder)
    val featureArray = Array("hour_vec", "avg_pcoc", "diff1_pcoc", "diff2_pcoc", "recent_pcoc")
    val assembler = new VectorAssembler().setInputCols(featureArray).setOutputCol("features")
    stagesArray.append(assembler)

    val pipeline = new Pipeline()
    pipeline.setStages(stagesArray.toArray)

    val pipelineModel = pipeline.fit(data)
    val dataset = pipelineModel.transform(data)
    val predictData = pipelineModel.transform(predictFeatures)


    val lrModel = new LinearRegression().setFeaturesCol("features").setLabelCol("label").setRegParam(0.001).setElasticNetParam(0.1).fit(dataset)

    val predictions = lrModel.transform(dataset).select("identifier", "media", "conversion_goal", "conversion_from", "time", "hour", "avg_pcoc", "diff1_pcoc", "diff2_pcoc", "recent_pcoc", "features", "prediction")

    predictions
  }

  def parseFeatures(rawData: DataFrame, spark: SparkSession) = {
//    udfAggregateFeature()(col("avg_pcoc"), col("diff1_pcoc"), col("diff2_pcoc"), col("recent_pcoc"))
    val dataRaw = rawData
      .withColumn("avg_pcoc", col("double_feature_list").getItem(0))
      .withColumn("diff1_pcoc", col("double_feature_list").getItem(1))
      .withColumn("diff2_pcoc", col("double_feature_list").getItem(2))
      .withColumn("recent_pcoc", col("double_feature_list").getItem(3))
      .withColumn("hour", col("string_feature_list").getItem(0))
      .select("identifier", "media", "conversion_goal", "conversion_from", "time", "avg_pcoc", "diff1_pcoc", "diff2_pcoc", "recent_pcoc", "hour", "label")

    dataRaw
  }

  def getTrainingData(rawData: DataFrame, spark: SparkSession) = {
    /*
    需要的特征包括：
    1. hour
    2. 四小时前的pcoc
    3. 四小时前的pcoc一阶差分
    4. 四小时前的pcoc二阶差分
    5. 四小时前的cv
     */
    val dataRaw = parseFeatures(rawData, spark)

    // hour特征：one-hot编码
//    val hourIndexer = new StringIndexer()
//      .setInputCol("hour")
//      .setOutputCol("hour_index")
//      .fit(dataRaw)
//    val hourIndexed = hourIndexer.transform(dataRaw)
//    val hourEncoder = new OneHotEncoder()
//      .setInputCol("hour_index")
//      .setOutputCol("hour_vec")
//    val hourFeature = hourEncoder
//      .transform(hourIndexed)
//      .select("identifier", "media", "conversion_goal", "conversion_from", "hour_vec")
//    hourFeature.show(10)

    // 数据关联
    val data = dataRaw
      .select("identifier", "media", "conversion_goal", "conversion_from", "time", "hour", "avg_pcoc", "diff1_pcoc", "diff2_pcoc", "recent_pcoc", "label")

    data
  }



  def getData(date: String, hour: String, version: String, spark: SparkSession) = {
    val selectCondition = s"`date` = '$date' and `hour` = '$hour'"
    val sqlRequest =
      s"""
         |SELECT
         |  *
         |FROM
         |  test.ocpc_pcoc_sample_hourly
         |WHERE
         |  $selectCondition
         |AND
         |  version = '$version'
         |""".stripMargin
    println(sqlRequest)
    val data = spark.sql(sqlRequest)

    data
  }

}


