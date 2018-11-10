package com.cpc.spark.ml.dnn

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

/**
  * 在d3版本特征的基础上实时增加当天看过和点击过的广告ideaid
  * created time : 2018/11/10 10:35
  *
  * @author zhj
  * @version 1.0
  *
  */
object DNNSampleV4_test {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .enableHiveSupport()
      .getOrCreate()

    val Array(trdate, trpath, tedate, tepath) = args

    val sample = new DNNSampleV4_test(spark, trdate, trpath, tedate, tepath)
    sample.saveTrain()
    sample.saveTest(gauc = true)
  }
}


/**
  * 详情页在d3版本特征上增加文章id和文章category
  * created time : 2018/11/10 14:13
  *
  * @author zhj
  * @version 1.0
  *
  */
class DNNSampleV4_test(spark: SparkSession, trdate: String = "", trpath: String = "",
                       tedate: String = "", tepath: String = "")
  extends DNNSample(spark, trdate, trpath, tedate, tepath) {

  //union log 取数
  private def sql(date: String, adslot_type: Int = 1) =
    s"""
       |select if(isclick>0, array(1,0), array(0,1)) as label,
       |  media_type, media_appsid as mediaid,
       |  ext['channel'].int_value as channel,
       |  ext['client_type'].string_value as sdk_type,
       |
       |  adslot_type, adslotid,
       |
       |  adtype, interaction, bid, ideaid, unitid, planid, userid,
       |  ext_int['is_new_ad'] as is_new_ad, ext['adclass'].int_value as adclass,
       |  ext_int['siteid'] as site_id,
       |
       |  os, network, ext['phone_price'].int_value as phone_price,
       |  ext['brand_title'].string_value as brand,
       |
       |  province, city, ext['city_level'].int_value as city_level,
       |
       |  uid, age, sex, ext_string['dtu_id'] as dtu_id,
       |
       |  hour, ext_int['content_id'] as content_id, ext_int['category'] as content_category
       |
       |from dl_cpc.cpc_union_log where `date` = '$date'
       |  and isshow = 1 and ideaid > 0 and adslot_type = $adslot_type
       |  and media_appsid in ("80000001", "80000002")
       |  and uid not like "%.%"
       |  and uid not like "%000000%"
       |  and uid > 0
      """.stripMargin

  //用户历史点击和看过的广告
  private def behavior_sql(date: String) =
    s"""
       |select uid,
       |       collect_set(if(load_date='${getDay(date, 1)}',show_ideaid,null)) as s_ideaid_1,
       |       collect_set(if(load_date='${getDay(date, 1)}',show_adclass,null)) as s_adclass_1,
       |       collect_set(if(load_date='${getDay(date, 2)}',show_ideaid,null)) as s_ideaid_2,
       |       collect_set(if(load_date='${getDay(date, 2)}',show_adclass,null)) as s_adclass_2,
       |       collect_set(if(load_date='${getDay(date, 3)}',show_ideaid,null)) as s_ideaid_3,
       |       collect_set(if(load_date='${getDay(date, 3)}',show_adclass,null)) as s_adclass_3,
       |
       |       collect_list(if(load_date='${getDay(date, 1)}',click_ideaid,null)) as c_ideaid_1,
       |       collect_list(if(load_date='${getDay(date, 1)}',click_adclass,null)) as c_adclass_1,
       |
       |       collect_list(if(load_date='${getDay(date, 2)}',click_ideaid,null)) as c_ideaid_2,
       |       collect_list(if(load_date='${getDay(date, 2)}',click_adclass,null)) as c_adclass_2,
       |
       |       collect_list(if(load_date='${getDay(date, 3)}',click_ideaid,null)) as c_ideaid_3,
       |       collect_list(if(load_date='${getDay(date, 3)}',click_adclass,null)) as c_adclass_3,
       |
       |       collect_list(if(load_date>='${getDay(date, 7)}'
       |                  and load_date<='${getDay(date, 4)}',click_ideaid,null)) as c_ideaid_4_7,
       |       collect_list(if(load_date>='${getDay(date, 7)}'
       |                  and load_date<='${getDay(date, 4)}',click_adclass,null)) as c_adclass_4_7
       |from dl_cpc.cpc_user_behaviors
       |where load_date in ('${getDays(date, 1, 7)}')
       |group by uid
      """.stripMargin

  override def getTrainSample(spark: SparkSession, date: String): DataFrame = {
    import spark.implicits._
    val trainSql = sql(date, 2)
    val behaviorSql = behavior_sql(date)

    println("=================PREPARING TRAIN DATA==============")
    println(trainSql)
    println("====================================================")
    println(behaviorSql)
    println("====================================================")

    val rawBehavior = spark.sql(behaviorSql)

    val userAppIdx = getUidApp(spark, date)
      .select($"uid", hashSeq("m1", "string")($"pkgs").alias("m1"))

    val behavior_data = rawBehavior
      .select(
        $"uid",
        hashSeq("m2", "int")($"s_ideaid_1").alias("m2"),
        hashSeq("m3", "int")($"s_ideaid_2").alias("m3"),
        hashSeq("m4", "int")($"s_ideaid_3").alias("m4"),
        hashSeq("m5", "int")($"s_adclass_1").alias("m5"),
        hashSeq("m6", "int")($"s_adclass_2").alias("m6"),
        hashSeq("m7", "int")($"s_adclass_3").alias("m7"),
        hashSeq("m8", "int")($"c_ideaid_1").alias("m8"),
        hashSeq("m9", "int")($"c_ideaid_2").alias("m9"),
        hashSeq("m10", "int")($"c_ideaid_3").alias("m10"),
        hashSeq("m11", "int")($"c_adclass_1").alias("m11"),
        hashSeq("m12", "int")($"c_adclass_2").alias("m12"),
        hashSeq("m13", "int")($"c_adclass_3").alias("m13"),
        hashSeq("m14", "int")($"c_adclass_4_7").alias("m14"),
        hashSeq("m15", "int")($"c_adclass_4_7").alias("m15")
      )

    spark.sql(trainSql)
      .join(behavior_data, Seq("uid"), "left")
      .join(userAppIdx, Seq("uid"), "left")
      .select($"label",

        hash("uid")($"uid").alias("sample_idx"),
        hash("f1")($"media_type").alias("f1"),
        hash("f2")($"mediaid").alias("f2"),
        hash("f3")($"channel").alias("f3"),
        hash("f4")($"sdk_type").alias("f4"),
        hash("f5")($"adslot_type").alias("f5"),
        hash("f6")($"adslotid").alias("f6"),
        hash("f7")($"sex").alias("f7"),
        hash("f8")($"dtu_id").alias("f8"),
        hash("f9")($"adtype").alias("f9"),
        hash("f10")($"interaction").alias("f10"),
        hash("f11")($"bid").alias("f11"),
        hash("f12")($"ideaid").alias("f12"),
        hash("f13")($"unitid").alias("f13"),
        hash("f14")($"planid").alias("f14"),
        hash("f15")($"userid").alias("f15"),
        hash("f16")($"is_new_ad").alias("f16"),
        hash("f17")($"adclass").alias("f17"),
        hash("f18")($"site_id").alias("f18"),
        hash("f19")($"os").alias("f19"),
        hash("f20")($"network").alias("f20"),
        hash("f21")($"phone_price").alias("f21"),
        hash("f22")($"brand").alias("f22"),
        hash("f23")($"province").alias("f23"),
        hash("f24")($"city").alias("f24"),
        hash("f25")($"city_level").alias("f25"),
        hash("f26")($"uid").alias("f26"),
        hash("f27")($"age").alias("f27"),
        hash("f28")($"hour").alias("f28"),
        hash("f29")($"content_id").alias("f29"),
        hash("f30")($"content_category").alias("f30"),

        array($"m1", $"m2", $"m3", $"m4", $"m5", $"m6", $"m7", $"m8", $"m9", $"m10",
          $"m11", $"m12", $"m13", $"m14", $"m15").alias("raw_sparse")
      )
      .select(
        array($"f1", $"f2", $"f3", $"f4", $"f5", $"f6", $"f7", $"f8", $"f9",
          $"f10", $"f11", $"f12", $"f13", $"f14", $"f15", $"f16", $"f17", $"f18", $"f19",
          $"f20", $"f21", $"f22", $"f23", $"f24", $"f25", $"f26", $"f27", $"f28").alias("dense"),

        mkSparseFeature_m($"raw_sparse").alias("sparse"),

        $"label", $"sample_idx"
      )
      .select(
        $"sample_idx",
        $"label",
        $"dense",
        $"sparse._1".alias("idx0"),
        $"sparse._2".alias("idx1"),
        $"sparse._3".alias("idx2"),
        $"sparse._4".alias("id_arr")
      )

  }

  override def getTestSamle4Gauc(spark: SparkSession, date: String, percent: Double = 0.05): DataFrame = {
    val testSql = sql(date, 2)
    val behaviorSql = behavior_sql(date)

    println("=================PREPARING TEST DATA================")
    println(testSql)
    println("====================================================")
    println(behaviorSql)
    println("====================================================")

    import spark.implicits._
    val rawTrain = spark.sql(testSql)
    val uid = rawTrain.groupBy("uid").agg(expr("sum(label[0]) as count"))
      .filter("count>0")
      .sample(withReplacement = false, percent)

    val userAppIdx = getUidApp(spark, date)
      .select($"uid", hashSeq("m1", "string")($"pkgs").alias("m1"))

    val rawBehavior = spark.sql(behaviorSql)
    val behavior_data = rawBehavior
      .select(
        $"uid",
        hashSeq("m2", "int")($"s_ideaid_1").alias("m2"),
        hashSeq("m3", "int")($"s_ideaid_2").alias("m3"),
        hashSeq("m4", "int")($"s_ideaid_3").alias("m4"),
        hashSeq("m5", "int")($"s_adclass_1").alias("m5"),
        hashSeq("m6", "int")($"s_adclass_2").alias("m6"),
        hashSeq("m7", "int")($"s_adclass_3").alias("m7"),
        hashSeq("m8", "int")($"c_ideaid_1").alias("m8"),
        hashSeq("m9", "int")($"c_ideaid_2").alias("m9"),
        hashSeq("m10", "int")($"c_ideaid_3").alias("m10"),
        hashSeq("m11", "int")($"c_adclass_1").alias("m11"),
        hashSeq("m12", "int")($"c_adclass_2").alias("m12"),
        hashSeq("m13", "int")($"c_adclass_3").alias("m13"),
        hashSeq("m14", "int")($"c_adclass_4_7").alias("m14"),
        hashSeq("m15", "int")($"c_adclass_4_7").alias("m15")
      )

    rawTrain.join(uid, Seq("uid"), "inner")
      .join(behavior_data, Seq("uid"), "left")
      .join(userAppIdx, Seq("uid"), "left")
      .select($"label",

        hash("uid")($"uid").alias("sample_idx"),
        hash("f1")($"media_type").alias("f1"),
        hash("f2")($"mediaid").alias("f2"),
        hash("f3")($"channel").alias("f3"),
        hash("f4")($"sdk_type").alias("f4"),
        hash("f5")($"adslot_type").alias("f5"),
        hash("f6")($"adslotid").alias("f6"),
        hash("f7")($"sex").alias("f7"),
        hash("f8")($"dtu_id").alias("f8"),
        hash("f9")($"adtype").alias("f9"),
        hash("f10")($"interaction").alias("f10"),
        hash("f11")($"bid").alias("f11"),
        hash("f12")($"ideaid").alias("f12"),
        hash("f13")($"unitid").alias("f13"),
        hash("f14")($"planid").alias("f14"),
        hash("f15")($"userid").alias("f15"),
        hash("f16")($"is_new_ad").alias("f16"),
        hash("f17")($"adclass").alias("f17"),
        hash("f18")($"site_id").alias("f18"),
        hash("f19")($"os").alias("f19"),
        hash("f20")($"network").alias("f20"),
        hash("f21")($"phone_price").alias("f21"),
        hash("f22")($"brand").alias("f22"),
        hash("f23")($"province").alias("f23"),
        hash("f24")($"city").alias("f24"),
        hash("f25")($"city_level").alias("f25"),
        hash("f26")($"uid").alias("f26"),
        hash("f27")($"age").alias("f27"),
        hash("f28")($"hour").alias("f28"),
        hash("f29")($"content_id").alias("f29"),
        hash("f30")($"content_category").alias("f30"),

        array($"m1", $"m2", $"m3", $"m4", $"m5", $"m6", $"m7", $"m8", $"m9", $"m10",
          $"m11", $"m12", $"m13", $"m14", $"m15").alias("raw_sparse")
      )
      .select(
        array($"f1", $"f2", $"f3", $"f4", $"f5", $"f6", $"f7", $"f8", $"f9",
          $"f10", $"f11", $"f12", $"f13", $"f14", $"f15", $"f16", $"f17", $"f18", $"f19",
          $"f20", $"f21", $"f22", $"f23", $"f24", $"f25", $"f26", $"f27", $"f28").alias("dense"),

        mkSparseFeature_m($"raw_sparse").alias("sparse"),

        $"label", $"sample_idx"
      )
      .select(
        $"sample_idx",
        $"label",
        $"dense",
        $"sparse._1".alias("idx0"),
        $"sparse._2".alias("idx1"),
        $"sparse._3".alias("idx2"),
        $"sparse._4".alias("id_arr")
      )
  }
}
