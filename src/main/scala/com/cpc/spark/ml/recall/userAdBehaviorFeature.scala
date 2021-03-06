package com.cpc.spark.ml.recall

import java.text.SimpleDateFormat
import java.util.Calendar

import com.cpc.spark.common.Murmur3Hash
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.udf

object userAdBehaviorFeature {
  Logger.getRootLogger.setLevel(Level.WARN)
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .enableHiveSupport()
      .getOrCreate()

    val date = args(0)
    saveUserDailyFeatures(spark, date)
  }

  private def saveUserDailyFeatures(spark: SparkSession, date: String): Unit = {
    import spark.implicits._

    //用户天级别过去访问广告情况
    val ud_sql1 =
      s"""
         |select uid,
         |       collect_set(if(load_date='${getDay(date, 1)}',show_ideaid,null)) as s_ideaid_1,
         |       collect_set(if(load_date='${getDay(date, 1)}',show_adclass,null)) as s_adclass_1,
         |       collect_set(if(load_date='${getDay(date, 2)}',show_ideaid,null)) as s_ideaid_2,
         |       collect_set(if(load_date='${getDay(date, 2)}',show_adclass,null)) as s_adclass_2,
         |       collect_set(if(load_date='${getDay(date, 3)}',show_ideaid,null)) as s_ideaid_3,
         |       collect_set(if(load_date='${getDay(date, 3)}',show_adclass,null)) as s_adclass_3,
         |
         |       collect_set(if(load_date='${getDay(date, 1)}',click_ideaid,null)) as c_ideaid_1,
         |       collect_set(if(load_date='${getDay(date, 1)}',click_adclass,null)) as c_adclass_1,
         |       collect_set(if(load_date='${getDay(date, 2)}',click_ideaid,null)) as c_ideaid_2,
         |       collect_set(if(load_date='${getDay(date, 2)}',click_adclass,null)) as c_adclass_2,
         |       collect_set(if(load_date='${getDay(date, 3)}',click_ideaid,null)) as c_ideaid_3,
         |       collect_set(if(load_date='${getDay(date, 3)}',click_adclass,null)) as c_adclass_3,
         |       collect_set(if(load_date>='${getDay(date, 7)}'
         |                  and load_date<='${getDay(date, 4)}',click_ideaid,null)) as c_ideaid_4_7,
         |       collect_list(if(load_date>='${getDay(date, 7)}'
         |                  and load_date<='${getDay(date, 4)}',click_adclass,null)) as c_adclass_4_7,
         |
         |       collect_set(if(load_date='${getDay(date, 1)}',cvr_ideaid,null)) as r_ideaid_1,
         |       collect_set(if(load_date='${getDay(date, 1)}',cvr_adclass,null)) as r_adclass_1,
         |       collect_set(if(load_date='${getDay(date, 2)}',cvr_ideaid,null)) as r_ideaid_2,
         |       collect_set(if(load_date='${getDay(date, 2)}',cvr_adclass,null)) as r_adclass_2,
         |       collect_set(if(load_date='${getDay(date, 3)}',cvr_ideaid,null)) as r_ideaid_3,
         |       collect_set(if(load_date='${getDay(date, 3)}',cvr_adclass,null)) as r_adclass_3,
         |       collect_set(if(load_date>='${getDay(date, 7)}'
         |                  and load_date<='${getDay(date, 4)}',cvr_ideaid,null)) as r_ideaid_4_7,
         |       collect_list(if(load_date>='${getDay(date, 7)}'
         |                  and load_date<='${getDay(date, 4)}',cvr_adclass,null)) as r_adclass_4_7
         |
         |from dl_cpc.cpc_user_behaviors_recall_cvr
         |where load_date in ('${getDays(date, 1, 7)}')
         |group by uid
      """.stripMargin

    //用户点击过的广告分词
    val ud_sql2 =
      s"""
         |select uid,
         |       interest_ad_words_1 as word1,
         |       interest_ad_words_3 as word3
         |from dl_cpc.cpc_user_interest_words
         |where load_date='$date'
    """.stripMargin

    println("============= user daily features =============")
    println(ud_sql1)
    println("-------------------------------------------------")
    println(ud_sql2)
    println("-------------------------------------------------")

    val ud_features = spark.sql(ud_sql1).repartition(5000)
      //.join(spark.sql(ud_sql2), Seq("uid"), "outer")
      .select($"uid",
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
        hashSeq("m14", "int")($"c_ideaid_4_7").alias("m14"),
        hashSeq("m15", "int")($"c_adclass_4_7").alias("m15"),
        hashSeq("m16", "int")($"r_ideaid_1").alias("m16"),
        hashSeq("m17", "int")($"r_ideaid_2").alias("m17"),
        hashSeq("m18", "int")($"r_ideaid_3").alias("m18"),
        hashSeq("m19", "int")($"r_adclass_1").alias("m19"),
        hashSeq("m20", "int")($"r_adclass_2").alias("m20"),
        hashSeq("m21", "int")($"r_adclass_3").alias("m21"),
        hashSeq("m22", "int")($"r_ideaid_4_7").alias("m22"),
        hashSeq("m23", "int")($"r_adclass_4_7").alias("m23")
      )
    val day = getDay(date, 1)
    ud_features.repartition(100).write.mode("overwrite")
      .parquet(s"hdfs://emr-cluster/user/cpc/features/adBehaviorFeature/$day")
  }
  /**
    * 获取时间序列
    *
    * @param startdate : 日期
    * @param day1      ：日期之前day1天作为开始日期
    * @param day2      ：日期序列数量
    * @return
    */
  def getDays(startdate: String, day1: Int = 0, day2: Int): String = {
    val format = new SimpleDateFormat("yyyy-MM-dd")
    val cal = Calendar.getInstance()
    cal.setTime(format.parse(startdate))
    cal.add(Calendar.DATE, -day1)
    var re = Seq(format.format(cal.getTime))
    for (i <- 1 until day2) {
      cal.add(Calendar.DATE, -1)
      re = re :+ format.format(cal.getTime)
    }
    re.mkString("','")
  }

  /**
    * 获取时间
    *
    * @param startdate ：开始日期
    * @param day       ：开始日期之前day天
    * @return
    */
  def getDay(startdate: String, day: Int): String = {
    val format = new SimpleDateFormat("yyyy-MM-dd")
    val cal = Calendar.getInstance()
    cal.setTime(format.parse(startdate))
    cal.add(Calendar.DATE, -day)
    format.format(cal.getTime)
  }

  /**
    * 获取hash code
    *
    * @param prefix ：前缀
    * @param t      ：类型
    * @return
    */
  private def hashSeq(prefix: String, t: String) = {
    t match {
      case "int" => udf {
        seq: Seq[Int] =>
          val re = if (seq != null && seq.nonEmpty) for (i <- seq) yield Murmur3Hash.stringHash64(prefix + i, 0)
          else Seq(Murmur3Hash.stringHash64(prefix, 0))
          re.slice(0, 1000)
      }
      case "string" => udf {
        seq: Seq[String] =>
          val re = if (seq != null && seq.nonEmpty) for (i <- seq) yield Murmur3Hash.stringHash64(prefix + i, 0)
          else Seq(Murmur3Hash.stringHash64(prefix, 0))
          re.slice(0, 1000)
      }
    }
  }
}
