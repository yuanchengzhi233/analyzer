package com.cpc.spark.ml.calibration.debug

import com.cpc.spark.qukan.userprofile.SetUserProfileTag.SetUserProfileTagInHiveHourly
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import com.cpc.spark.common.Murmur3Hash.stringHash32

/**
  * @author WangYao
  * @date 2019/03/14
  */
object UidGroup {
  def main(args: Array[String]): Unit = {

      val date = args(0)
      val label = args(1).toInt

          val spark = SparkSession.builder()
            .appName(s"midu_userprofile")
            .enableHiveSupport()
            .getOrCreate()

      val sql =
          s"""
             |select distinct uid, from_unixtime(unix_timestamp(day,'yyyy-mm-dd'),'yyyymmdd') as dt,day
             |    from
             |      dl_cpc.cpc_basedata_union_events
             |    where
             |      day = '$date'
           """.stripMargin
    println(sql)
      val data= spark.sql(sql)
        .withColumn("hashuid",hash(label)(concat(col("uid"),col("dt"))))
        .withColumn("num",col("hashuid")%1000)
        .withColumn("label",lit(label))
        .select("uid","hashuid","num","day","label")

    data.show(10)

    data.repartition(10).write.mode("overwrite").insertInto("dl_cpc.cvr_mlcpp_uid_label")


  }

  def hash(seed:Int)= udf {
    x:String => {
      var a = stringHash32(x,seed).toDouble
      if(a<0){
        a += scala.math.pow(2,32)
      }
       a
    }
  }
}
