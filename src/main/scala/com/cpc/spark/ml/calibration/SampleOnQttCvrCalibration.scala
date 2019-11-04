package com.cpc.spark.ml.calibration

import java.io.{File, FileOutputStream, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import com.cpc.spark.ml.calibration.HourlyCalibration.localDir
import com.cpc.spark.ocpc.OcpcUtils._
import com.cpc.spark.tools.CalcMetrics
import com.typesafe.config.ConfigFactory
import mlmodel.mlmodel.{CalibrationFeature, CalibrationModel}
import org.apache.spark.ml.feature.{OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable.ListBuffer

object SampleOnQttCvrCalibration {
  def main(args: Array[String]): Unit = {
    // new calibration
    val day = args(0)
    val hour = args(1)

    // build spark session
    val spark = SparkSession.builder()
      .appName("cvr calibration sample timely")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits._

    // get union log
    val sql =
      s"""
         |select
         |   a.searchid, a.ideaid,a.user_show_ad_num,a.unitid,a.userid, a.adclass,
         |   b.raw_cvr, b.exp_cvr, b.cvr_model_name, b.adslot_id, b.click_count,b.click_unit_count,b.conversion_from,
         |   a.day,a.hour
         |from dl_cpc.cpc_basedata_click_event a
         |join dl_cpc.cpc_basedata_adx_event b
         |   on a.searchid = b.searchid and a.ideaid = b.ideaid
         |   and day = '$day' and hour = '$hour'
         |   and bid_mode = 0
         |   and charge_type = 1
         |   and conversion_goal>0
         |where
         |  day = '$day' and hour = '$hour'
         |  and isclick = 1
         |  and adsrc in (1,28)
         |  and antispam_score = 10000
       """.stripMargin

    println(s"sql:\n$sql")
    val data = spark.sql(sql)
    data.show(10)
    data.write.mode("overwrite").saveAsTable("dl_cpc.cvr_calibration_sample_all")

  }
}
