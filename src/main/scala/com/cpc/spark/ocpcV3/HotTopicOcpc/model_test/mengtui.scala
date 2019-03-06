package com.cpc.spark.ocpcV3.HotTopicOcpc.model_test

import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._


object mengtui {
  def main(args: Array[String]): Unit ={
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
    val sqlRequest =
      s"""
         |select
         |  adslot_type,
         |  unitid,
         |  ideaid,
         |  exp_ctr as score_ctr,
         |  isclick as label_ctr
         | from  dl_cpc.slim_union_log
         |where dt = '2019-03-03'
         |  and adsrc = 1
         |  and userid >0
         |  and isshow = 1
         |  and antispam = 0
         |  and (charge_type is NULL or charge_type = 1)
         |  and media_appsid in ('80000001', '80000002') --qtt
         |  and ideaid in (2640880, 2734591, 2734594, 2753214)
       """.stripMargin

    val df = spark.sql(sqlRequest)
    for(ideaid <- List(2640880, 2734591, 2734594, 2753214)){
      val df1 = df.filter(s"ideaid = $ideaid")
        .withColumn("score", col("score_ctr"))
        .withColumn("label", col("label_ctr"))
        .select("ideaid", "score", "label")
      val auc = getAuc(spark, df1)
      println("IDEAID "+ ideaid +" : AUC"+ auc)
    }

  }

  def getAuc(spark:SparkSession, data:DataFrame): Double = {
    import spark.implicits._
    val scoreAndLable = data.select($"score",$"label")
      .rdd
      .map(x => (x.getAs[Int]("score").toDouble,
        x.getAs[Int]("label").toDouble))
    val metrics = new BinaryClassificationMetrics(scoreAndLable)
    val aucROC = metrics.areaUnderROC
    aucROC
  }

}
