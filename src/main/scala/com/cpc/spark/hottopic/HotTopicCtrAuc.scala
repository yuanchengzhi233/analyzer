package com.cpc.spark.hottopic

import org.apache.spark.sql.SparkSession
import com.cpc.spark.tools.CalcMetrics
/**
  * @author Jinbao
  * @date 2019/1/16 10:44
  */
object HotTopicCtrAuc {
    def main(args: Array[String]): Unit = {
        val date = args(0)
        val spark = SparkSession.builder()
          .appName(s"HotTopicCtrAuc date = $date")
          .enableHiveSupport()
          .getOrCreate()
        import spark.implicits._
        val media = "hot_topic"
        val sql =
            s"""
               |select exp_ctr as score,
               |  isclick as label,
               |  ctr_model_name,
               |  cast(adclass as string) as adclass,
               |  cast(adslot_id as string) as adslot_id,
               |  cast(userid as string) as userid,
               |  cast(adslot_type as string) as adslot_type
               |from dl_cpc.cpc_basedata_union_events
               |where day = '$date'
               |and media_appsid in ('80002819')
               |and adsrc = 1
               |and isshow = 1
               |and ideaid > 0
               |and userid > 0
               |and (charge_type is null or charge_type = 1)
             """.stripMargin

        val union = spark.sql(sql).cache()
        val DetailAucListBuffer = scala.collection.mutable.ListBuffer[DetailAuc]()
        val time1=System.currentTimeMillis()
        //分模型
        val ctrModelNames = union.select("ctr_model_name")
          .distinct()
          .collect()
          .map(x => x.getAs[String]("ctr_model_name"))
        println("ctrModelNames 's num is " + ctrModelNames.length)
        for (ctrModelName <- ctrModelNames) {
            val ctrModelUnion = union.filter(s"ctr_model_name = '$ctrModelName'")
            val ctrModelAuc = CalcMetrics.getAuc(spark,ctrModelUnion)
            DetailAucListBuffer += DetailAuc(tag = "ctr_model_name",
                name = ctrModelName,
                auc = ctrModelAuc,
                sum = ctrModelUnion.count().toDouble,
                media = media,
                day = date)
        }
        val time2=System.currentTimeMillis()
        println("ctrModelNames cost is " + (time2-time1))
        //分行业
        val adclassAucList = CalcMetrics.getGauc(spark,union,"adclass").rdd
          .map(x => {
              val adclass = x.getAs[String]("name")
              val auc = x.getAs[Double]("auc")
              val sum = x.getAs[Double]("sum")
              (adclass,auc,sum)
          })
          .collect()
        for(adclassAuc <- adclassAucList) {
            DetailAucListBuffer += DetailAuc(tag = "adclass",
                name = adclassAuc._1,
                auc = adclassAuc._2,
                sum = adclassAuc._3,
                media = media,
                day = date)
        }
        val time3=System.currentTimeMillis()
        println("adclassAucList cost is " + (time3-time2))
        //分栏位
        val adslotIds = union.select("adslot_id")
          .distinct()
          .collect()
          .map(x => x.getAs[String]("adslot_id"))
        println("adslotIds 's num is " + adslotIds.length)
        for (adslotId <- adslotIds) {
            val adslotIdUnion = union.filter(s"adslot_id = '$adslotId'")
            val adslotIdAuc = CalcMetrics.getAuc(spark,adslotIdUnion)
            DetailAucListBuffer += DetailAuc(tag = "adslot_id",
                name = adslotId,
                auc = adslotIdAuc,
                sum = adslotIdUnion.count().toDouble,
                media = media,
                day = date)
        }
        val time4=System.currentTimeMillis()
        println("adslotIds cost is " + (time4-time3))
        //分广告主
        val userIdAucList = CalcMetrics.getGauc(spark,union,"userid").rdd
          .map(x => {
              val userid = x.getAs[String]("name")
              val auc = x.getAs[Double]("auc")
              val sum = x.getAs[Double]("sum")
              (userid,auc,sum)
          })
          .collect()
        for(userIdAuc <- userIdAucList) {
            DetailAucListBuffer += DetailAuc(tag = "userid",
                name = userIdAuc._1,
                auc = userIdAuc._2,
                sum = userIdAuc._3,
                media = media,
                day = date)
        }
        val time5=System.currentTimeMillis()
        println("userIdAucList cost is " + (time5-time4))
        //分栏位类型
        val adslotTypes = union.select("adslot_type")
          .distinct()
          .collect()
          .map(x => x.getAs[String]("adslot_type"))
        println("adslotTypes 's num is " + adslotTypes.length)
        for (adslotType <- adslotTypes) {
            val adslotIdUnion = union.filter(s"adslot_type = '$adslotType'")
            val adslotIdAuc = CalcMetrics.getAuc(spark,adslotIdUnion)
            DetailAucListBuffer += DetailAuc(tag = "adslot_type",
                name = adslotType,
                auc = adslotIdAuc,
                sum = adslotIdUnion.count().toDouble,
                media = media,
                day = date)
        }
        val time6=System.currentTimeMillis()
        println("adslotTypes cost is " + (time6-time5))

        val detailAuc = DetailAucListBuffer.toList.toDF()

        detailAuc.repartition(1)
          .write
          .mode("overwrite")
          .insertInto("dl_cpc.cpc_detail_auc")

    }
    case class DetailAuc(var tag:String = "",
                         var name:String = "",
                         var auc:Double = 0,
                         var sum:Double = 0,
                         var media:String = "",
                         var day:String = "")
}

/**
create table if not exists `dl_cpc`.`cpc_detail_auc`
(
    name string,
    auc double,
    sum double
)
PARTITIONED BY (day string,media string,tag string)
STORED AS PARQUET;
  */

