package com.cpc.spark.ml.calibration

import java.io.{File, FileInputStream, PrintWriter}

import org.apache.spark.sql.functions._
import com.cpc.spark.common.Utils
import com.cpc.spark.ml.calibration.exp.LrCalibrationOnQtt.calculateAuc
import com.google.protobuf.CodedInputStream
import mlmodel.mlmodel.{CalibrationConfig, IRModel, PostCalibrations}
import org.apache.spark.sql.functions.{col, concat_ws, udf}

import scala.collection.mutable
//import com.cpc.spark.ml.calibration.MultiDimensionCalibOnQtt.computeCalibration

/**
  * author: wangyao
  * date: 5/14/19
  */
object CalibrationCheckOnMidu {
  def main(args: Array[String]): Unit = {
    val modelPath = args(0)
    val dt = args(1)
    val hour = args(2)
    val modelName = args(3)


    println(s"modelPath=$modelPath")
    println(s"dt=$dt")
    println(s"hour=$hour")
    println(s"modelName=$modelName")

    val calimap = new PostCalibrations().mergeFrom(CodedInputStream.newInstance(new FileInputStream(modelPath))).caliMap

    val localPath ="/home/cpc/wy/novel_v8_postcali.txt"
    val outFile = new File(localPath)
    outFile.getParentFile.mkdirs()
    new PrintWriter(localPath) { write(calimap.toString); close() }

    val modelset=calimap.keySet
    val session = Utils.buildSparkSession("calibration_check")
    val timeRangeSql = Utils.getTimeRangeSql_3(dt, hour, dt, hour)

    // get union log
    val sql = s"""
                 |select isclick, raw_ctr, cast(exp_ctr as bigint) as ectr, searchid, ctr_model_name,
                 |adslotid as adslot_id, cast(ideaid as string) ideaid,
                 |case
                 |  when user_req_ad_num = 0 then '0'
                 |  when user_req_ad_num = 1 then '1'
                 |  when user_req_ad_num = 2 then '2'
                 |  when user_req_ad_num in (3,4) then '4'
                 |  when user_req_ad_num in (5,6,7) then '7'
                 |  else '8' end as user_req_ad_num
                 | from dl_cpc.slim_union_log
                 | where $timeRangeSql
                 | and media_appsid in ('80001098', '80001292') and isshow = 1
                 | and
                 | and ctr_model_name in ('$modelName')
                 | and ideaid > 0 and adsrc = 1 AND userid > 0
                 | AND (charge_type IS NULL OR charge_type = 1)
                 |left join
                 |(select search
       """.stripMargin
    println(s"sql:\n$sql")
    val log = session.sql(sql).withColumn("group1",concat_ws("_",col("ctr_model_name"),col("ideaid"),col("user_req_ad_num"),col("adslot_id")))
      .withColumn("group2",concat_ws("_",col("ctr_model_name"),col("ideaid"),col("user_req_ad_num")))
      .withColumn("group3",concat_ws("_",col("ctr_model_name"),col("ideaid")))
      .withColumn("group",when(searchMap(modelset)(col("group3")),col("group3")).otherwise(lit("")))
      .withColumn("group",when(searchMap(modelset)(col("group2")),col("group2")).otherwise(col("group")))
      .withColumn("group",when(searchMap(modelset)(col("group1")),col("group1")).otherwise(col("group")))
      .withColumn("len",length(col("group")))
      .select("isclick","raw_ctr","ectr","searchid","group","group1","group2","group3","ctr_model_name","adslot_id","ideaid","user_req_ad_num","len")

    log.show(50)
    println("total data:%d".format(log.count()))
    log.repartition(5).write.mode("overwrite").saveAsTable("test.wy01")

    val data = log.filter("length(group)>0")
    println("calibration data:%d".format(data.count()))
    var uncalibrated = 0
    data.rdd.toLocalIterator.foreach( x => {
      val isClick = x.getLong(0).toDouble
      val rawCtr = x.getLong(1).toDouble / 1e6d
      val onlineCtr = x.getLong(2).toDouble / 1e6d
      val searchid = x.getString(3)
      val group = x.getString(4)
      val irModel = calimap.get(group).get
      val calibrated = computeCalibration(rawCtr, irModel.ir.get)
      if (Math.abs(onlineCtr - calibrated) / calibrated > 0.2) {
        uncalibrated += 1
        if(uncalibrated%10000==1){
          println(s"rawCtr: $rawCtr")
          println(s"onlineCtr: $onlineCtr")
          println(s"calibrated: $calibrated")
          println(s"searchid: $searchid")
          println(s"group: $group")
          println("======")
        }
      }
    })
    println("uncalbrated:%d".format(uncalibrated))
    val result = data.rdd.map( x => {
      val isClick = x.getLong(0).toDouble
      val ectr = x.getLong(1).toDouble / 1e6d
      val onlineCtr = x.getLong(2).toDouble / 1e6d
      val group = x.getString(4)
      val irModel = calimap.get(group).get
      val calibrated = computeCalibration(ectr, irModel.ir.get)
      var mistake = 0
      if (Math.abs(onlineCtr - calibrated) / calibrated > 0.2) {
        mistake = 1
      }
      (isClick, ectr, calibrated, 1.0, onlineCtr, mistake)
    }).reduce((x, y) => (x._1 + y._1, x._2 + y._2, x._3 + y._3, x._4 + y._4, x._5 + y._5, x._6 + y._6))
    val ctr = result._1 / result._4
    val ectr = result._2 / result._4
    val calibrated_ctr = result._3 / result._4
    val onlineCtr = result._5 / result._4
    println(s"impression: ${result._4}")
    println(s"mistake: ${result._6}")
    println(s"ctr: $ctr")
    println(s"ectr: $ectr")
    println(s"online ctr: $onlineCtr")
    println(s"calibrated_ctr: $calibrated_ctr")
    println(s"no calibration: ${ectr / ctr}")
    println(s"online calibration: ${onlineCtr / ctr}")
    println(s"new calibration: ${calibrated_ctr / ctr}")
}

  def searchMap(modelset:Set[String])= udf{
    (key: String)=>{
      var flag = true
      if(modelset contains(key)){
        flag = true
      }
      else{
        flag = false
      }
      flag
    }
  }

  def binarySearch(num: Double, boundaries: Seq[Double]): Int = {
    var index = 0
    var min = 0
    var max = boundaries.size - 1
    while (min <= max) {
      index = (min + max)/ 2
      if (num > boundaries(index)){
        min = index + 1
      }
      else if (num < boundaries(index)) {
        max = index - 1
      }
      else {
        return index
      }
    }
    return min
  }

  def computeCalibration(prob: Double, irModel: IRModel): Double = {
    if (prob <= 0) {
      return 0.0
    }
    var index = binarySearch(prob, irModel.boundaries)
    if (index == 0) {
      return  Math.min(1.0, irModel.predictions(0) * prob/ irModel.boundaries(0))
    }
    if (index == irModel.boundaries.size) {
      index = index - 1
    }
    return Math.max(0.0, Math.min(1.0, irModel.predictions(index-1) +
      (irModel.predictions(index) - irModel.predictions(index-1))
        * (prob - irModel.boundaries(index-1))
        / (irModel.boundaries(index) - irModel.boundaries(index-1))))
  }
}
