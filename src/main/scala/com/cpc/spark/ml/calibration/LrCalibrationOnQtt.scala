package com.cpc.spark.ml.calibration

import java.io.{File, FileOutputStream, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.cpc.spark.tools.CalcMetrics
import com.cpc.spark.common.Utils
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.ml.feature.{OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.functions._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable


object LrCalibrationOnQtt {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .config("spark serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.KryoSerializer.buffer.max", "2047MB")
      .appName("prepare lookalike sample".format())
      .enableHiveSupport()
      .getOrCreate()
    import spark.implicits._
    // parse and process input
    val endDate = args(0)
    val endHour = args(1)
    val hourRange = args(2).toInt
    val model = "qtt-list-dnn-rawid-v4"
    val calimodel ="qtt-list-dnn-rawid-v4-postcali"


    val endTime = LocalDateTime.parse(s"$endDate-$endHour", DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"))
    val startTime = endTime.minusHours(Math.max(hourRange - 1, 0))

    val startDate = startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val startHour = startTime.format(DateTimeFormatter.ofPattern("HH"))

    println(s"endDate=$endDate")
    println(s"endHour=$endHour")
    println(s"hourRange=$hourRange")
    println(s"startDate=$startDate")
    println(s"startHour=$startHour")

    // build spark session
    val session = Utils.buildSparkSession("hourlyCalibration")

    val timeRangeSql = Utils.getTimeRangeSql_3(startDate, startHour, endDate, endHour)

    // get union log
    val sql = s"""
                 |select isclick, raw_ctr, adslotid, ideaid,user_req_ad_num, hour
                 | from dl_cpc.slim_union_log
                 | where $timeRangeSql
                 | and media_appsid in ('80000001', '80000002') and isshow = 1 and adslot_type = 1
                 | and ctr_model_name in ('$model','$calimodel')
                 | and ideaid > 0 and adsrc = 1 AND userid > 0
                 | AND (charge_type IS NULL OR charge_type = 1)
       """.stripMargin
    println(s"sql:\n$sql")
    val log= session.sql(sql)

    val adslotidArray = log.select("adslotid").distinct().collect()
    val ideaidArray = log.select("ideaid").distinct().collect()
    val hourArray = log.select("hour").distinct().collect()

    val adslotidID = mutable.Map[String,Int]()
    var idxTemp = 0
    val adslotid_feature = adslotidArray.map{r => adslotidID.update(r.getAs[String]("adslotid"), idxTemp); idxTemp += 1; (("adslotid"+ r.getAs[String]("adslotid")), idxTemp -1)}

    val ideaidID = mutable.Map[Long,Int]()
    var idxTemp1 = 0
    val ideaid_feature = ideaidArray.map{r => ideaidID.update(r.getAs[Long]("ideaid"), idxTemp1); idxTemp1 += 1; (( "ideaid"+ r.getAs[Long]("ideaid")), idxTemp1 -1)}

    val hourID = mutable.Map[String,Int]()
    var idxTemp2 = 0
    val hourid_feature = hourArray.map{r => hourID.update(r.getAs[String]("hour"), idxTemp2); idxTemp2 += 1; (("hour" + r.getAs[String]("hour")), idxTemp2 -1)}

    val feature_profile = adslotid_feature ++ ideaid_feature

    val adslotid_sum = adslotidID.size
    val ideaid_sum = ideaidID.size
    val hour_sum = hourID.size
    val profile_num = adslotid_sum + ideaid_sum + hour_sum +2
    val sample = log.rdd.map {
      r =>
        val label = r.getAs[Long]("isclick").toInt
        val raw_ctr = r.getAs[Long]("raw_ctr").toDouble / 1e6d
        val adslotid = r.getAs[String]("adslotid")
        val ideaid = r.getAs[Long]("ideaid")
        val user_req_ad_num = r.getAs[Long]("user_req_ad_num").toDouble
        val hour = r.getAs[String]("hour")
        var els = Seq[(Int, Double)]()
        if (adslotid != null) {
          els = els :+ (adslotidID(adslotid), 1.0)
        }
        if (ideaid != null) {
          els = els :+ (ideaidID(ideaid) + adslotid_sum , 1.0)
        }
        if (hour != null) {
          els = els :+ (adslotid_sum + ideaid_sum + hourID(hour), 1.0)
        }
        if (raw_ctr != null) {
          els = els :+ (adslotid_sum + ideaid_sum + hour_sum , raw_ctr)
        }
        if (user_req_ad_num != null) {
          els = els :+ (adslotid_sum + ideaid_sum + hour_sum+1 , user_req_ad_num)
        }
        (label,els,ideaid)
    }.filter(_ != null).toDF("label","els","ideaid")
      .select($"label", SparseFeature(profile_num)($"els").alias("features"),$"ideaid")
    sample.show(5)

    val sql2 = s"""
                 |select isclick, raw_ctr, adslotid, ideaid,user_req_ad_num,exp_ctr,hour
                 | from dl_cpc.slim_union_log
                 | where dt = '2019-05-19'  and hour = '22'
                 | and media_appsid in ('80000001', '80000002') and isshow = 1 and adslot_type = 1
                 | and ctr_model_name in ('$calimodel')
                 | and ideaid > 0 and adsrc = 1 AND userid > 0
                 | AND (charge_type IS NULL OR charge_type = 1)
       """.stripMargin
    println(s"sql:\n$sql2")
    val testsample = session.sql(sql2)
    val test= testsample.rdd.map {
      r =>
        val label = r.getAs[Long]("isclick").toInt
        val raw_ctr = r.getAs[Long]("raw_ctr").toDouble / 1e6d
        val adslotid = r.getAs[String]("adslotid")
        val ideaid = r.getAs[Long]("ideaid")
        val user_req_ad_num = r.getAs[Long]("user_req_ad_num").toDouble
        val hour = r.getAs[String]("hour")
        var els = Seq[(Int, Double)]()
        if (adslotid != null) {
          if (adslotidID.contains(adslotid)){
            els = els :+ (adslotidID(adslotid), 1.0)
          }
        }
        if (ideaid != null) {
          if (ideaidID.contains(ideaid)){
            els = els :+ (ideaidID(ideaid) + adslotid_sum , 1.0)
          }
        }
        if (hour != null) {
          els = els :+ (adslotid_sum + ideaid_sum + hourID(hour), 1.0)
        }
        if (raw_ctr != null) {
          els = els :+ (adslotid_sum + ideaid_sum + hour_sum , raw_ctr)
        }
        if (user_req_ad_num != null) {
          els = els :+ (adslotid_sum + ideaid_sum + hour_sum+1 , user_req_ad_num)
        }
        (label,els,ideaid)
    }.filter(_ != null).toDF("label","els","ideaid")
      .select($"label", SparseFeature(profile_num)($"els").alias("features"),$"ideaid")
    test.show(5)

      val Array(trainingDF, testDF) = sample.randomSplit(Array(0.8, 0.2), seed = 1)
      println(s"trainingDF size=${trainingDF.count()},testDF size=${testDF.count()}")
      val lrModel = new LogisticRegression().
        setLabelCol("label").
        setFeaturesCol("features").
        setMaxIter(200).
        setThreshold(0.5).
        setRegParam(0.15).
        fit(trainingDF)
      val predictions = lrModel.transform(testDF).select("label", "features","rawPrediction", "probability", "prediction","ideaid")
      predictions.show(5)

        //使用BinaryClassificationEvaluator来评价我们的模型
        val evaluator = new BinaryClassificationEvaluator()
        evaluator.setMetricName("areaUnderROC")
        val auc = evaluator.evaluate(predictions)
      println("model auc:%f".format(auc))
    val newprediction = lrModel.transform(test).select("label","probability","ideaid")

    //取出预测为1的probability
    val result2 = newprediction.map(line => {
      val label = line.get(line.fieldIndex("label")).toString.toInt
      val dense = line.get(line.fieldIndex("probability")).asInstanceOf[org.apache.spark.ml.linalg.DenseVector]
      val y = dense(1).toString.toDouble * 1e6d.toInt
      val ideaid = line.get(line.fieldIndex("ideaid")).toString
      (label,y,ideaid)
    }).toDF("label","prediction","ideaid")
    //   lr calibration
    calculateAuc(result2,"lr",spark)
    //    raw data
    val modelData = testsample.selectExpr("cast(isclick as Int) label","cast(raw_ctr as Int) prediction","ideaid")
    calculateAuc(modelData,"original",spark)

//    online calibration
    val calibData = testsample.selectExpr("cast(isclick as Int) label","cast(exp_ctr as Int) prediction","ideaid")
    calculateAuc(calibData,"online",spark)

  }

 def SparseFeature(profile_num: Int)
  = udf {
    els: Seq[Row] =>
      val new_els: Seq[(Int, Double)] = els.map(x => {
        (x.getInt(0), x.getDouble(1))
      })
      Vectors.sparse(profile_num, new_els)
  }

  def calculateAuc(data:DataFrame,cate:String,spark: SparkSession): Unit ={
    val testData = data.selectExpr("cast(label as Int) label","cast(prediction as Int) score")
    val auc = CalcMetrics.getAuc(spark,testData)
    println("%s auc:%f".format(cate,auc))
    val p1= data.groupBy().agg(avg(col("label")).alias("ctr"),avg(col("prediction")).alias("ectr"))
    val ctr = p1.first().getAs[Double]("ctr")
    val ectr = p1.first().getAs[Double]("ectr")
    println("%s calibration: ctr:%f,ectr:%f,ectr/ctr:%f".format(cate, ctr, ectr/1e6d, ectr/ctr/1e6d))

    val p2 = data.groupBy("ideaid")
      .agg(
        avg(col("label")).alias("ctr"),
        avg(col("prediction")).alias("ectr"),
        count(col("label")).alias("ctrnum")
      )
      .withColumn("pcoc",col("ectr")/col("ctr"))


    val ctr2 = p2.first().getAs[Double]("avgctr")
    val ectr2 = p2.first().getAs[Double]("avgectr")
    val pcoc = p2.first().getAs[Double]("avgpcoc")
    println("%s calibration by ideaid: avgctr:%f,avgectr:%f,avgpcoc:%f".format(cate, ctr2, ectr2/1e6d, pcoc/1e6d))
  }
}