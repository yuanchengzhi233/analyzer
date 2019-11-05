package com.cpc.ml.snapshot

import mlmodel.mlmodel.ModelType
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * created by xiongyao on 2019/10/29
  */
object SnapshotExample {

  def main(args: Array[String]): Unit = {

    Logger.getRootLogger.setLevel(Level.WARN)
    val spark = SparkSession.builder()
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryoserializer.buffer.max", "2047MB")
      .appName("snapshot-example")
      .enableHiveSupport()
      .getOrCreate()

    var day = args(0).toString
    var hour = args(1).toString
    var minute = args(2).toString

    val sql =
      s"""
         |select
         | *
         |from
         |algo_cpc.cpc_snapshot
         |where day = '$day'
         |and hour = '$hour'
         |and minute = '$minute'
      """.stripMargin

    val rawDataFromSnapshotLog = spark.sql(sql).rdd.map(
      x => {
        val mediaappsid = x.getAs[Int]("mediaappsid").toString
        val adslottype = x.getAs[Int]("adslottype")
        val feature_int64_offset = x.getAs[Array[Int]]("feature_int64_offset")
        val feature_name = x.getAs[Array[String]]("feature_name")
        val feature_str_offset = x.getAs[Array[Int]]("feature_str_offset")
        val feature_float_list = x.getAs[Array[Float]]("feature_float_list")
        val userid = x.getAs[Int]("userid")
        val version = x.getAs[String]("version")
        val insertionid = x.getAs[String]("insertionid")
        val ideaid = x.getAs[Int]("ideaid")
        val feature_int64_list = x.getAs[Array[Long]]("feature_int64_list")
        val feature_int_list = x.getAs[Array[Int]]("feature_int_list")
        val feature_type = x.getAs[Int]("feature_type")
        val uid = x.getAs[String]("uid")
        val feature_float_offset = x.getAs[Array[Int]]("feature_float_offset")
        val modeltype = x.getAs[Int]("modeltype")
        val adslotid = x.getAs[Int]("adslotid")
        val feature_int_offset = x.getAs[Array[Int]]("feature_int_offset")
        val feature_str_list = x.getAs[Array[String]]("feature_str_list")
        val day = x.getAs[String]("day")
        val hour = x.getAs[String]("hour")
        val minute = x.getAs[String]("minute")

        val pt = {
          if (modeltype == ModelType.MTYPE_CTR) {
            "qtt"
          } else if (modeltype == ModelType.MTYPE_CVR) {
            "qtt-cvr"
          } else {
            "unknown"
          }
        }

        val snapshotEvent = CpcSnapshotEvent(
          searchid = insertionid,
          media_appsid = mediaappsid,
          uid = uid,
          ideaid = ideaid,
          userid = userid,
          adslotid = adslotid,
          adslot_type = adslottype,
          day = day,
          hour = hour,
          minute = minute,
          pt = pt
        )
        snapshotEvent.setFeatures(feature_name, feature_str_offset, feature_str_list, feature_int_offset, feature_int_list, feature_int64_offset, feature_int64_list)

      }
    ).filter(x => x != null)

    val snapshotDataToGo = spark.createDataFrame(rawDataFromSnapshotLog)
    snapshotDataToGo.createOrReplaceTempView("snapshotDataToGo")

    val snapshotDataAsDataFrame = spark.sql(
      s"""
         |select
         |  searchid
         |  , media_appsid
         |  , uid
         |  , ideaid
         |  , userid
         |  , adslotid
         |  , adslot_type
         |  , contentStr
         |  , featureStr
         |  , feature_int32
         |  , feature_int64
         |  , val_rec as val_rec
         |  , day
         |  , hour
         |  , minute
         |  , pt
         |from snapshotDataToGo
       """.stripMargin)
      .repartition(100)
      .write
      .partitionBy("day", "hour", "minute", "pt")
      .mode(SaveMode.Append)
      .parquet(
        s"""
           |hdfs://emr-cluster2/warehouse/dl_cpc.db/cpc_snapshot_v2/
        """
          .stripMargin.trim
      )

    println("-- write to hive successfully -- ")

  }

}
