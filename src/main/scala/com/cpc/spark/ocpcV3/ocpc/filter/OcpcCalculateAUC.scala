package com.cpc.spark.ocpcV3.ocpc.filter

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.ocpcV3.ocpc.OcpcUtils.getTimeRangeSql2
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object OcpcCalculateAUC {
  def main(args: Array[String]): Unit = {
    // 计算日期周期
    val date = args(0).toString
    val hour = args(1).toString
    val spark = SparkSession
      .builder()
      .appName(s"ocpc ideaid auc: $date, $hour")
      .enableHiveSupport().getOrCreate()

    // 抽取数据
    val data = getData(date, hour, spark)

    // 计算auc
    // cvr1
    val auc1Data = getAuc(data, 1, date, hour, spark)
    // cvr2
    val auc2Data = getAuc(data, 2, date, hour, spark)
    // cvr3
    val auc3Data = getAuc(data, 3, date, hour, spark)

    // 合并数据
    val aucData = auc1Data.union(auc2Data).union(auc3Data)
    aucData.show(10)
    aucData.write.mode("overwrite").saveAsTable("test.ocpc_qtt_auc_ideaid20190103")
  }

  def getData(date: String, hour: String, spark: SparkSession) = {
    // 取历史区间
    val hourCnt = 6
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

    // 取数据: score数据
    val sqlRequest =
      s"""
         |select
         |    searchid,
         |    ideaid,
         |    ext['exp_cvr'].int_value as score
         |from dl_cpc.cpc_union_log
         |where $selectCondition
         |and isclick = 1
         |and ext['exp_ctr'].int_value is not null
         |and media_appsid  in ('80001098', '80001292')
         |and ext['antispam'].int_value = 0
         |and ideaid > 0 and adsrc = 1
         |and ext_int['dsp_adnum_by_src_1'] > 1
         |and userid > 0
         |and (ext['charge_type'] IS NULL OR ext['charge_type'].int_value = 1)
       """.stripMargin
    println(sqlRequest)
    val scoreData = spark.sql(sqlRequest)

    // cvr1数据
    val cvr1Data = spark
      .table("dl_cpc.ml_cvr_feature_v1")
      .where(selectCondition)
      .selectExpr("searchid", "label2 as label")
      .filter("label=1")
      .distinct()

    // cvr2数据
    val cvr2Data = spark
      .table("dl_cpc.ml_cvr_feature_v2")
      .where(selectCondition)
      .selectExpr("searchid", "label")
      .filter("label=1")
      .distinct()

    // cvr3数据
    val cvr3Data = spark
      .table("dl_cpc.site_form_unionlog")
      .where(selectCondition)
      .filter("ideaid > 0")
      .withColumn("label", lit(1))
      .select("searchid", "label")
      .distinct()

    // 关联数据
    val result1 = scoreData
      .join(cvr1Data, Seq("searchid"), "left_outer")
      .withColumn("conversion_goal", lit(1))
      .select("searchid", "ideaid", "score", "label", "conversion_goal")
    val result2 = scoreData
      .join(cvr2Data, Seq("searchid"), "left_outer")
      .withColumn("conversion_goal", lit(2))
      .select("searchid", "ideaid", "score", "label", "conversion_goal")
    val result3 = scoreData
      .join(cvr3Data, Seq("searchid"), "left_outer")
      .withColumn("conversion_goal", lit(3))
      .select("searchid", "ideaid", "score", "label", "conversion_goal")

    // 合并数据
    val resultDF = result1.union(result2).union(result3)
    resultDF.show(10)
    resultDF
  }

  def getAuc(rawData: DataFrame, conversionGoal: Int, date: String, hour: String, spark: SparkSession) = {
    import spark.implicits._
    //获取模型标签

//    val aucGaucBuffer = ListBuffer[AucGauc.AucGauc]()
    val data = rawData.filter(s"conversion_goal=$conversionGoal")
    val aucList = new mutable.ListBuffer[(String, Double)]()
    val ideaidList = data.select("ideaid").distinct().rdd.map(x=>x.toString())

    //按栏位遍历
    for (ideaid <- ideaidList.collect()) {

      val ideaidData = data.filter(s"ideaid=$ideaid")
      val scoreAndLabel = ideaidData
        .select("score", "label")
        .rdd
        .map(x=>(x.getAs[Int]("score").toDouble, x.getAs[Int]("label").toDouble))
      val scoreAndLabelNum = scoreAndLabel.count()
      if (scoreAndLabelNum > 0) {
        val metrics = new BinaryClassificationMetrics(scoreAndLabel)
        val aucROC = metrics.areaUnderROC
        aucList.append((ideaid, aucROC))

      }
//      dataFilterDaslotType.cache()
//      //按模型遍历
//      for (exp <- exptag) {
//        val dataFilterExp = dataFilterDaslotType.filter(s"exptag = '$exp'")
//        val scoreAndLable = dataFilterExp.select($"score",$"label")
//          .rdd
//          .map(x => (x.getAs[Int]("score").toDouble, x.getAs[Int]("label").toDouble))
//        val scoreAndLabelNum = scoreAndLable.count()
//        if (scoreAndLabelNum > 0) {
//          val metrics = new BinaryClassificationMetrics(scoreAndLable)
//          val aucROC = metrics.areaUnderROC
//
//          val aucAndSum = dataFilterExp
//            .select($"uid",$"score",$"label")
//            .rdd
//            .map(x => (x.getAs[String]("uid"),
//              (x.getAs[Int]("score"), x.getAs[Int]("label"))))
//            .combineByKey(
//              x => List(x),
//              (x: List[(Int, Int)], y: (Int, Int)) => y :: x,
//              (x: List[(Int, Int)], y: List[(Int, Int)]) => x ::: y
//            )
//            .mapValues(x => {
//              val label = x.map(x => x._2)
//              val max = x.map(x => x._1).max + 2
//              val pos = Array.fill(max)(0)
//              val neg = Array.fill(max)(0)
//              val n = label.sum //正样本数
//              val m = x.length - n  //负样本数
//
//              for ((s,l) <- x){
//                if (l == 0) neg(s) += 1
//                else pos(s) += 1
//              }
//
//              var negSum = 0
//              var auc: Double = 0
//              for (i <- 0 to max - 1) {
//                auc += 1.0 * pos(i) * negSum + pos(i) * neg(i) * 0.5
//                negSum += neg(i)
//              }
//              val result = if (m <= 0 || n <= 0) (0.0, 0.0) else (auc / (1.0 * m * n), 0.0 + m + n)
//              result
//            })
//            .map(x => x._2)
//
//          //计算分子
//          val auc = aucAndSum.map(x => x._1 * x._2).reduce((x, y) => x+y)
//          //计算分母
//          val sum = aucAndSum.map(x => x._2).reduce((x, y) => x+y)
//
//          val gaucROC = if (sum > 1e-6) auc / sum else 0.0
//
//          aucGaucBuffer += AucGauc.AucGauc(auc = aucROC,
//            gauc = gaucROC,
//            adslot_type = adslot_type,
//            model = s"%$exp%",
//            date = date,
//            hour = hour)
//
//        }
//      }
//      dataFilterDaslotType.unpersist()
    }
    val resultDF = spark
      .createDataFrame(aucList)
      .toDF("ideaid", "auc")
      .withColumn("conversion_goal", lit(conversionGoal))

    resultDF
//    aucGaucBuffer.toList
  }
}