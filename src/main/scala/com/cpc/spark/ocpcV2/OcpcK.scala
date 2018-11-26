package com.cpc.spark.ocpcV2

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.ocpc.utils.OcpcUtils._
import org.apache.commons.math3.fitting.{PolynomialCurveFitter, WeightedObservedPoints}
import org.apache.spark.sql.{Dataset, Row, SparkSession}

import scala.collection.mutable
import org.apache.spark.sql.functions._

import com.cpc.spark.udfs.Udfs_wj._

object OcpcK {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder().appName("ocpc v2").enableHiveSupport().getOrCreate()

    val date = args(0).toString
    val hour = args(1).toString
    // val onDuty = args(2).toInt

    val datehourlist = scala.collection.mutable.ListBuffer[String]()
    val datehourlist2 = scala.collection.mutable.ListBuffer[String]()
    val cal = Calendar.getInstance()
    cal.set(date.substring(0, 4).toInt, date.substring(5, 7).toInt - 1, date.substring(8, 10).toInt, hour.toInt, 0)
    for (t <- 0 to 72) {
      if (t > 0) {
        cal.add(Calendar.HOUR, -1)
      }
      val sf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val dd = sf.format(cal.getTime())
      val d1 = dd.substring(0, 10)
      val h1 = dd.substring(11, 13)
      val datecond = s"`date` = '$d1' and hour = '$h1'"
      val datecond2 = s"`dt` = '$d1' and hour = '$h1'"
      datehourlist += datecond
      datehourlist2 += datecond2
    }

    val dtCondition = "(%s)".format(datehourlist.mkString(" or "))
    val dtCondition2 = "(%s)".format(datehourlist2.mkString(" or "))


    val statSql =
      s"""
         |select
         |  ideaid,
         |  round(ocpc_log_dict['kvalue'] * ocpc_log_dict['cali'] * 100.0 / 5) as k_ratio2,
         |  round(ocpc_log_dict['kvalue'] * ocpc_log_dict['cvr3cali'] * 100.0 / 5) as k_ratio3,
         |  ocpc_log_dict['cpagiven'] as cpagiven,
         |  sum(if(isclick=1,price,0))/sum(COALESCE(label2,0)) as cpa2,
         |  sum(if(isclick=1,price,0))/sum(COALESCE(label3,0)) as cpa3,
         |  sum(if(isclick=1,price,0))/sum(COALESCE(label2,0))/ocpc_log_dict['cpagiven'] as ratio2,
         |  sum(if(isclick=1,price,0))/sum(COALESCE(label3,0))/ocpc_log_dict['cpagiven'] as ratio3,
         |  sum(isclick) clickCnt,
         |  sum(COALESCE(label2,0)) cvr2Cnt,
         |  sum(COALESCE(label3,0)) cvr3Cnt
         |from
         |  (select * from dl_cpc.ocpc_unionlog where $dtCondition2 and ocpc_log_dict['kvalue'] is not null and isclick=1) a
         |  left outer join
         |  (select searchid, label2 from dl_cpc.ml_cvr_feature_v1 where $dtCondition) b on a.searchid = b.searchid
         |  left outer join
         |  (select searchid, iscvr as label3 from dl_cpc.cpc_api_union_log where $dtCondition and iscvr=1 group by searchid, iscvr) c on a.searchid = c.searchid
         |group by ideaid,
         |  round(ocpc_log_dict['kvalue'] * ocpc_log_dict['cali'] * 100.0 / 5),
         |  round(ocpc_log_dict['kvalue'] * ocpc_log_dict['cvr3cali'] * 100.0 / 5),
         |  ocpc_log_dict['cpagiven']
      """.stripMargin

    println(statSql)

    val realCvr3 = getIdeaidCvr3Ratio(date, hour, spark)

    val tablename = "dl_cpc.cpc_ocpc_v2_middle"
    val rawData = spark.sql(statSql)
//    rawData.write.mode("overwrite").saveAsTable("test.cpc_ocpc_v2_middle1")

    val data = rawData
      .join(realCvr3, Seq("ideaid"), "left_outer")
      .withColumn("cvr3_ratio", udfSqrt()(col("cvr_ratio")))
      .withColumn("cpa3", col("cpa3") * 1.0 / col("cvr3_ratio"))
      .withColumn("ratio3", col("ratio3") * 1.0 / col("cvr3_ratio"))
      .withColumn("cvr3Cnt", col("cvr3Cnt") * col("cvr3_ratio"))
      .select("ideaid", "k_ratio2", "k_ratio3", "cpagiven", "cpa2", "cpa3", "ratio2", "ratio3", "clickCnt", "cvr2Cnt", "cvr3Cnt")
      .withColumn("date", lit(date))
      .withColumn("hour", lit(hour))

    data.write.mode("overwrite").insertInto(tablename)

    val ratio2Data = getKWithRatioType(spark, tablename, "ratio2", date, hour)
    val ratio3Data = getKWithRatioType(spark, tablename, "ratio3", date, hour)

    val res = ratio2Data.join(ratio3Data, Seq("ideaid", "date", "hour"), "outer")
      .select("ideaid", "k_ratio2", "k_ratio3", "date", "hour")
//    res.write.mode("overwrite").saveAsTable("test.ocpc_v2_k")
    res.write.mode("overwrite").insertInto("dl_cpc.ocpc_v2_k")

  }

  def getKWithRatioType(spark: SparkSession, tablename: String, ratioType: String, date: String, hour: String): Dataset[Row] = {

    val condition = s"`date` = '$date' and hour = '$hour' and $ratioType is not null"
    println("getKWithRatioType", condition)
    val res = spark.table(tablename).where(condition)
      .withColumn("str", concat_ws(" ", col(s"k_$ratioType"), col(s"$ratioType"), col("clickCnt")))
      .groupBy("ideaid")
      .agg(collect_set("str").as("liststr"))
      .select("ideaid", "liststr").collect()

    val targetK = 0.95
    var resList = new mutable.ListBuffer[(String, Double, String, String)]()
    for (row <- res) {
      val ideaid = row(0).toString
      val pointList = row(1).asInstanceOf[scala.collection.mutable.WrappedArray[String]].map(x => {
        val y = x.trim.split("\\s+")
        (y(0).toDouble, y(1).toDouble, y(2).toInt)
      })
      val coffList = fitPoints(pointList.toList)
      val k = (targetK - coffList(0)) / coffList(1)
      val realk: Double = k * 5.0 / 100.0
      println("ideaid " + ideaid, "coff " + coffList, "target k: " + k, "realk: " + realk)
      if (coffList(1)>0 && realk > 0) {
        resList.append((ideaid, realk, date, hour))
      }
    }
    val data = spark.createDataFrame(resList)
      .toDF("ideaid", s"k_$ratioType", "date", "hour")
    data
  }

  def fitPoints(pointsWithCount: List[(Double, Double, Int)]): List[Double] = {
    var obs: WeightedObservedPoints = new WeightedObservedPoints();
    var count = 0
    for ((x, y, n) <- pointsWithCount) {
      for (i <- 1 to n) {
        obs.add(x, y);
      }
      count = count + n
      println("sample", x, y, n)
    }

    for (i <- 0 to count / 10) {
      obs.add(0.0, 0.0);
    }

    // Instantiate a third-degree polynomial fitter.
    var fitter: PolynomialCurveFitter = PolynomialCurveFitter.create(1);

    var res = mutable.ListBuffer[Double]()
    // Retrieve fitted parameters (coefficients of the polynomial function).
    for (c <- fitter.fit(obs.toList)) {
      res.append(c)
    }
    res.toList
  }

  def getIdeaidCvr3Ratio(date: String, hour: String, spark: SparkSession) = {
    // 取历史数据
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd HH")
    val newDate = date + " " + hour
    val today = dateConverter.parse(newDate)
    val calendar = Calendar.getInstance
    calendar.setTime(today)
    calendar.add(Calendar.HOUR, -72)
    val yesterday = calendar.getTime
    val tmpDate = dateConverter.format(yesterday)
    val tmpDateValue = tmpDate.split(" ")
    val date1 = tmpDateValue(0)
    val hour1 = tmpDateValue(1)
    val selectCondition1 = getTimeRangeSql2(date1, hour1, date, hour)
    val selectCondition2 = getTimeRangeSql3(date1, hour1, date, hour)

    val sqlRequest0 =
      s"""
         |SELECT
         |  ideaid,
         |  SUM(iscvr) as total_cvr_cnt
         |FROM
         |  dl_cpc.cpc_api_union_log
         |WHERE
         |  $selectCondition1
         |AND
         |  iscvr=1
         |GROUP BY ideaid
       """.stripMargin

    val rawData = spark.sql(sqlRequest0)

    val sqlRequest =
      s"""
         |SELECT
         |  a.ideaid,
         |  b.label
         |FROM
         |  (select * from dl_cpc.ocpc_unionlog where $selectCondition2 and ocpc_log_dict['kvalue'] is not null and isclick=1) as a
         |LEFT JOIN
         |  (select searchid, iscvr as label from dl_cpc.cpc_api_union_log where $selectCondition1 and iscvr=1 group by searchid, iscvr) as b
         |ON
         |  a.searchid=b.searchid
       """.stripMargin

    println(sqlRequest)
    val filteredData = spark
      .sql(sqlRequest)
      .groupBy("ideaid")
      .agg(sum(col("label")).alias("cvr_cnt"))
      .select("ideaid", "cvr_cnt")

    val resultDF = filteredData
      .join(rawData, Seq("ideaid"), "left_outer")
      .withColumn("cvr_ratio", col("total_cvr_cnt") * 1.0 / col("cvr_cnt"))
      .withColumn("cvr_ratio", when(col("cvr_ratio")<1, 1.0).otherwise(col("cvr_ratio")))
      .withColumn("cvr_ratio", when(col("cvr_ratio")>10, 10.0).otherwise(col("cvr_ratio")))

    // TODO 删除临时表
    resultDF.write.mode("overwrite").saveAsTable("test.test_ocpc_check_cvr3_ratio")

    resultDF

  }


}