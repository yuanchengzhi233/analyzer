package com.cpc.spark.ml.ftrl

/**
  * author: huazhenhao
  * date: 9/18/18
  */

import com.cpc.spark.common.{Murmur3Hash, Utils}
import com.cpc.spark.ml.common.{Utils => MUtils}
import com.cpc.spark.ml.train.Ftrl
import com.typesafe.config.ConfigFactory
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object FtrlNewHourlyID {

  val XGBOOST_FEATURE_SIZE = 500000

  val ADVERTISER_ID_NAME = "advertiser"
  val PLAN_ID_NAME = "plan"

  val LOCAL_DIR = "/home/cpc/ftrl/"
//  val HDFS_MODEL_DIR = "hdfs:///user/cpc/qtt-ftrl-model/"
  val LOCAL_FTRL_PARAM = "/home/cpc/ftrl_param/"
//  val HDFS_MODEL_HISTORY_DIR = "hdfs:///user/cpc/qtt-ftrl-model-history/"
  val LOCAL_FTRL_HISTORY = "/home/cpc/ftrl_history/"
  val DEST_DIR = "/home/work/mlcpp/data/"

  val DOWN_SAMPLE_RATE = 0.2

  // return (searchid, label, xgfeature, error)
  def mapFunc(line: String): (String, Double, String, Int) = {
    val array = line.split("\t")
    if (array.length < 3) {
      return ("", 0, "", 1)
    }
    val label = array(0).toDouble
    return (array(2).trim, label, array(1), 0)
  }

  def main(args: Array[String]): Unit = {

    val dt = args(0)
    val hour = args(1)
    val upload = args(2).toBoolean
    val startFresh = args(3).toBoolean
    val typename = args(4)
    val gbdtVersion = args(5).toInt
    val ftrlVersion = args(6).toInt
    val typearray = typename.split("-")
    val adslot = typearray(0)
    val ctrcvr = typearray(1)

    println(s"dt=$dt")
    println(s"hour=$hour")
    println(s"typename=$typename (list-ctr, content-ctr, interact-ctr, all-cvr)")
    println(s"gbdtVersion=$gbdtVersion")
    println(s"ftrlVersion=$ftrlVersion")
    println(s"upload=$upload")
    println(s"forceNew=$startFresh")
    println(s"adslot=$adslot")
    println(s"ctrcvr=$ctrcvr")


    val inputName = s"/user/cpc/qtt-portrait-ftrl/sample_for_ftrl_with_id/ftrl-with-id-${dt}-${hour}-${typename}-${gbdtVersion}.svm"
    println(s"inputname = $inputName")

    val spark: SparkSession = Utils.buildSparkSession(name = "full_id_ftrl")

//    val currentHDFS = s"${HDFS_MODEL_DIR}ftrl-$typename-$ftrlVersion.mlm"
    val currentPath = s"${LOCAL_FTRL_PARAM}ftrl-$typename-$ftrlVersion.mlm"
//    val ftrl = Ftrl.getModelProtoFromLocal(startFresh, currentPath)
    val ftrl = Ftrl.deserializeFromLocal(startFresh, currentPath)
    println("before training model info:")
    printModelInfo(ftrl)

    import spark.implicits._

    // id, label, features
    val sample = spark.sparkContext
      .textFile(inputName, 50)
      .map(mapFunc)
      .toDF("searchid", "label", "xgBoostFeatures", "hasError")

    println(s"xgBoost total data size = ${sample.count()}")
    println(s"xgBoost filtered data size = ${sample.filter(x => x.getAs[Int]("hasError") > 0).count()}")
    println(s"xgBoost correct data size = ${sample.filter(x => x.getAs[Int]("hasError") == 0).count()}")

    val log = spark.table("dl_cpc.cpc_union_log")
      .filter(s"`date` = '$dt' and hour = '$hour'")
      .filter("media_appsid  in ('80000001', '80000002') and isshow = 1 and ext['antispam'].int_value = 0 " +
        "and ideaid > 0 and adsrc = 1 and adslot_type in (1) AND userid > 0")

    var merged = sample
      .filter(x => x.getAs[Int]("hasError") == 0)
      .join(log, Seq("searchid"), "inner")
    println(s"join with log size = ${merged.select("searchid").distinct().count()}")

    // join user app
    val userApps = spark.table("dl_cpc.cpc_user_installed_apps").filter(s"load_date='$dt'")
    merged = merged.join(userApps, Seq("uid"), joinType = "left")

    val dataWithID = createFeatures(merged)

    println("start model training")
    ftrl.trainWithDict(spark, dataWithID)

    println("after training model info:")
    printModelInfo(ftrl)

    // save model file locally
    val name = s"$ctrcvr-protrait$ftrlVersion-ftrl-id-qtt-$adslot"
    val filename = s"$LOCAL_DIR$name.mlm"
    Ftrl.saveLrPbPackWithDict(ftrl, filename, "ctr-ftrl-v1", name)
    println(s"Save model locally to $filename")

    if (upload) {
//      Ftrl.saveToProtoToHDFS(currentHDFS, spark, ftrl)
//      Ftrl.saveProtoToLocal(currentPath, ftrl)
      Ftrl.serializeLrToLocal(ftrl, currentPath)
//      val historyHDFS = s"${HDFS_MODEL_HISTORY_DIR}ftrl-$typename-$ftrlVersion-$dt-$hour.mlm"
      val historyPath = s"${LOCAL_FTRL_HISTORY}ftrl-$typename-$ftrlVersion-$dt-$hour.mlm"
//      Ftrl.saveToProtoToHDFS(historyHDFS, spark, ftrl)
//      Ftrl.saveProtoToLocal(historyPath, ftrl)
      Ftrl.serializeLrToLocal(ftrl, historyPath)
      println(MUtils.updateMlcppOnlineData(filename, s"$DEST_DIR$name.mlm", ConfigFactory.load()))
    }
  }

  def printModelInfo(ftrl: Ftrl): Unit = {
    println(s"Model dict size: ${ftrl.wDict.size}")
  }

  def createFeatures(df: DataFrame): RDD[(Array[Int], Double)] = {
     df.rdd.map(x => {
      // prepare xgboost features
      val array = x.getAs[String]("xgBoostFeatures").split("\\s+")
      val xgBoostFeatures = array.map(x => {
        val vals = x.split(":")
        vals(0).toInt
      })
      // get label
      val label = x.getAs[Double]("label")
      // generate original string id
      val allId = getAllIDFeatures(x)
      // get hashed id
      val hashedID = allId.map(a => getRawHashedID(a))
      // combine xgboost feature and hashed id
      ((xgBoostFeatures.toSet ++ hashedID.toSet).toArray, label)
    })
  }

  // return: string formed id
  def getAllIDFeatures(row: Row): Seq[String] = {
    val idFeatures = new ListBuffer[String]()
    // advertiser id
    val advertiserID = row.getAs[Int]("userid")
    idFeatures.append(ADVERTISER_ID_NAME + advertiserID.toString)
    // plan id
    val planID = row.getAs[Int]("planid")
    idFeatures.append(PLAN_ID_NAME + planID.toString)

    // installed apps
    if (row.getAs[Object]("pkgs") != null) {
      val apps = row.getAs[mutable.WrappedArray[String]]("pkgs")
      apps.foreach(x => {
        idFeatures.append(x + "_installed")
        // app cross advertiser id
        idFeatures.append(x + "_installed" + advertiserID.toString + "_adv")
      })
    }
    idFeatures
  }

  def getRawHashedID(name: String): Int = {
    Murmur3Hash.stringHash64(name, 0).toInt
  }
}