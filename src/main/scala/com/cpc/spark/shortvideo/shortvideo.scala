package com.cpc.spark.shortvideo

import java.io.FileOutputStream
import shortvideothreshold.shortvideothreshold._
import org.apache.spark.sql.SparkSession
import java.text.SimpleDateFormat
import java.util.Calendar
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import com.cpc.spark.ocpcV3.ocpc.OcpcUtils._
import java.time
import java.io.PrintWriter

import scala.collection.mutable.ListBuffer

object shortvideo {
  def main(args: Array[String]): Unit = {
    val date = args(0)
    val hour = args(1)
    val spark = SparkSession.builder()
      .appName(s"""shortvideo_execute +'${date}'+'${hour}'""")
      .enableHiveSupport()
      .getOrCreate()
    import org.apache.spark.sql._
    import spark.implicits._
    import org.apache.spark.sql._
    import scala.collection.mutable.ListBuffer
//    var cala = Calendar.getInstance()
//    val date1= datetime+" "+ hour +":00:00"
////    val date3d = new SimpleDateFormat("yyyy-MM-dd HH:00:00").format(date1)
////    date3d.add(Calendar.HOUR_OF_DAY,-72)
////    val date3d2 = new SimpleDateFormat("yyyy-MM-dd").format(date3d)
//    val unixdate = tranTimeToLong(date1)
//    val unixdate72h=3600*72


//    var cala = Calendar.getInstance()
//    val dateConverter=new SimpleDateFormat("yyyy-MM-dd HH")
//    val date= datetime+" "+ hour
//    val today=dateConverter.parse(date)
//    cala.setTime(today)
//    val recordtime=cala.getTime
//    val tmpDate=dateConverter.format(recordtime)
//    val tmpDateValue=tmpDate.split(" ")
//    val date1=tmpDateValue(0)
//    val hour1=tmpDateValue(1)
//    cala.add(Calendar.HOUR,-72)
//    val date3d = new SimpleDateFormat("yyyy-MM-dd HH:00:00").format(cala.getTime)
//    val unixdate72h = tranTimeToLong(date3d)
//    val selectCondition = getTimeRangeSql2(date1, hour1, date, hour)
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
    val selectCondition = getTimeRangeSql2(date1, hour1, date, hour)
    val selectCondition2 = getTimeRangeSql22(date1, hour1, date, hour)



//    val calb = Calendar.getInstance()
//    calb.add(Calendar.HOUR_OF_DAY)
//    val datetd = new SimpleDateFormat("yyyy-MM-dd").format(calb.getTime)
//    val hourtd = new SimpleDateFormat("HH").format(calb.getTime)


    spark.sql("set hive.exec.dynamic.partition=true")
    //  生成中间表 appdownload_mid
    val sql =
      s"""

         |select   searchid,`timestamp`,adtype,userid,ideaid,isclick,isreport,exp_cvr_ori,exp_cvr,cvr_rank,src,
         |         label_type,planid,unitid, adclass,adslot_type,label2,uid,usertype,'${date}','${hour}'
         |from
         |(
         |  select     `date` date1,hour,`timestamp`,searchid as searchid,isshow,isclick,usertype,userid,ideaid,adtype,interaction,adsrc,media_appsid,price,exp_cvr exp_cvr_ori,
         |             case when isclick=1 then exp_cvr *1000000 end exp_cvr,charge_type,
         |             row_number() over (partition by userid  order by exp_cvr desc ) cvr_rank
         |  from       dl_cpc.ocpc_base_unionlog
         |  where    ${selectCondition}
         |  and      media_appsid in  ("80000001","80000002")
         |  and      interaction=2
         |  and     adtype in (2,8,10)
         |  and     userid>0
         |  and     usertype in (0,1,2)
         |  and     isclick=1
         |) view1
         |left JOIN
         |(
         |  select   `date`,hour hour2,aa.searchid as searchid2,isreport, src,label_type,uid,planid,unitid, adclass,adslot_type,label2
         |  FROM
         |  (
         |    select          `date`,hour,
         |                     final.searchid as searchid,src,label_type,uid,planid,unitid, adclass,adslot_type,label2,
         |                     final.ideaid as ideaid,
         |                     case
         |          when final.src="elds" and final.label_type=6 then 1
         |          when final.src="feedapp" and final.label_type in (4, 5) then 1
         |          when final.src="yysc" and final.label_type=12 then 1
         |          when final.src="wzcp" and final.label_type in (1, 2, 3) then 1
         |          when final.src="others" and final.label_type=6 then 1
         |          else 0     end as isreport
         |          from
         |          (
         |          select  distinct
         |              `date`,hour,searchid, media_appsid, uid,
         |              planid, unitid, ideaid, adclass,adslot_type,label2,
         |              case
         |                  when (adclass like '134%' or adclass like '107%') then "elds"
         |                  when (adslot_type<>7 and adclass like '100%') then "feedapp"
         |                  when (adslot_type=7 and adclass like '100%') then "yysc"
         |                  when adclass in (110110100, 125100100) then "wzcp"
         |                  else "others"
         |              end as src,
         |              label_type
         |          from
         |              dl_cpc.ml_cvr_feature_v1
         |          where
         |              `date`>='${date1}'
         |              and label2=1
         |             and media_appsid in ("80000001", "80000002")
         |            ) final
         |       ) aa
         |  where   aa.isreport=1
         |) a
         |on  a.searchid2=view1.searchid
         |and   a.`date`=view1.date1
         |and   a.hour2 =view1.hour
         |group by searchid,`timestamp`,adtype,userid,ideaid,isclick,isreport,exp_cvr_ori,exp_cvr,cvr_rank,src,label_type,planid,unitid, adclass,adslot_type,label2,uid,usertype
       """.stripMargin
    val tab = spark.sql(sql)
    tab.repartition(100).write.mode("overwrite").insertInto("dl_cpc.cpc_unionevents_appdownload_mid")

    //   生成最终表
    val sql2 =
      s"""
         |select   userid,max(expcvr_d)  as threshreshold,'${date}','${hour}'
         |from
         |(
         |      select userid,expcvr_d, ranking,nums,round(ranking*1.0/nums,3) as cate
         |      from
         |       (
         |         select userid,expcvr_d,row_number() over (partition by userid order by exp_cvr desc) ranking
         |         from   dl_cpc.cpc_unionevents_appdownload_mid
         |         where  ${selectCondition2}
         |         and adtype in ('8','10')
         |        )  view1
         |      JOIN
         |       (
         |         select userid userid2, count(cvr_rank) as nums
         |         from dl_cpc.cpc_unionevents_appdownload_mid
         |         where ${selectCondition2}
         |         and adtype in ('8','10')
         |         group by userid
         |       ) nums
         |       on  view1.userid = nums.userid2
         |       where  round(ranking*1.0/nums,3)=0.990 or ranking=nums
         |)  total
         |group by userid
         | """.stripMargin
    var tab2 = spark.sql(sql2).toDF("userid", "exp_cvr","dt","hr")
    println("result tab count:" + tab2.count())
    tab2.repartition(100).write.mode("overwrite").insertInto("dl_cpc.cpc_appdown_cvr_threshold")
   val tab3=tab2.selectExpr("userid","exp_cvr")
    //   pb写法2

    val list = new scala.collection.mutable.ListBuffer[ShortVideoThreshold]()
    var cnt = 0
    for (record <- tab3.collect()) {
      var userid = record.getAs[String]("userid")
      var exp_cvr = record.getAs[Int]("exp_cvr")
      println(s"""useridr:$userid, expcvr:${exp_cvr}""")

      cnt += 1
      val Item = ShortVideoThreshold(
        userid = userid,
        threshold = exp_cvr
      )
      list += Item
    }
    println("cnt:"+cnt)
    val result = list.toArray
    val ecvr_tslist = ThresholdShortVideo(
      svt = result)
    println("Array length:" + result.length)
    ecvr_tslist.writeTo(new FileOutputStream("shortvideo.pb"))

  }

  def tranTimeToLong(tm:String) :Long= {
      val fm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val dt = fm.parse(tm)
      val aa = fm.format(dt)
      val tim: Long = dt.getTime()
      tim
    }
//  case class adcvr (var userid : String="",
//                    var exp_cvr : Int=0)
   def getTimeRangeSql22(startDate: String, startHour: String, endDate: String, endHour: String): String = {
  if (startDate.equals(endDate)) {
    return s"(`date` = '$startDate' and hour <= '$endHour' and hour > '$startHour')"
  }
  return s"((dt = '$startDate' and hr > '$startHour') " +
    s"or (dt = '$endDate' and hr <= '$endHour') " +
    s"or (dt > '$startDate' and dt < '$endDate'))"
}


}

/*
中间表 mid
create table if not exists dl_cpc.cpc_unionevents_appdownload_mid
(
    searchid string,
    timestamp     int,
    adtype   string,
    userid   string,
    ideaid   int,
    isclick  int,
    isreport int,
    exp_cvr  double,
    expcvr_d int,
    cvr_rank bigint,
    src      string,
    label_type int,
    planid   int,
    unitid   int,
    adclass  int,
    adslot_type  int,
    label2   int,
    uid      string,
    usertype  int
)
partitioned by (dt string,hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';



pb文件的表结构
create table  if not exists dl_cpc.cpc_appdown_cvr_threshold
(
userid   string comment'广告主id',
expcvr_threshold   bigint comment'expcvr阈值'

)
partitioned by (dt string, hr string)
row format delimited fields terminated by '\t' lines terminated by '\n'
*/