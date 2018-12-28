package com.cpc.spark.adcategory

import com.cpc.spark.common.Utils.sendMail
import com.redis.RedisClient
import org.apache.spark.sql.{Dataset, Row, SparkSession}

object AdCategoryShow {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // calculate time period for historical data
    val date = args(0)

    // read data and set redis configuration
    val sqlRequest =
      s"""
         |select
         |  ext_int["category"] as category,
         |  sum(isshow) as imp
         |FROM
         |  dl_cpc.cpc_union_log
         |  WHERE `date` = "$date"
         |  and isshow = 1
         |  and ext['antispam'].int_value = 0
         |  and adsrc = 1
         |  and os = 1
         |  and adslotid in ("7096368","7034978","7453081","7903746","7659152","7132208")
         |  and media_appsid in ("80000001","80000002") and adslot_type = 2
         |  AND userid > 0
         |  AND (ext["charge_type"] IS NULL
         |       OR ext["charge_type"].int_value = 1)
         |  GROUP BY
         |  ext_int["category"]
       """.stripMargin
    println(sqlRequest)

    val dataset = spark.sql(sqlRequest)

    dataset.filter("category=0").collect().foreach{
      x=>{
        val unknown=x.getLong(1)
        if (unknown>100000000){
          sendMail("adcategory errors", "errors",
            Seq("wangyao@qutoutiao.net"))
        }
      }
    }

    val redis = new RedisClient("r-2ze5dd7d4f0c6364.redis.rds.aliyuncs.com", 6379)
    redis.auth("J9Q4wJTZbCk4McdiO8U5rIJW")

    dataset.collect().foreach {
        record => {
          val id = record.get(0).toString
          var key = "ad_category_" + id
          val show = record.getLong(1)
          println(key,show)

    redis.setex(key, 3600 * 24 * 30, show)

          }
        }
    redis.disconnect
  }
}





