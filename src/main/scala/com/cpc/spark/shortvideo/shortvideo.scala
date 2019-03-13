package com.cpc.spark.shortvideo

import java.io.FileOutputStream

import com.google.protobuf.struct.Struct
import shortvideo._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions
import org.apache.spark.sql.types.StructField
//import shortvideothreshold.Shortvideothreshold.ShortVideoThreshold
import shortvideothreshold.shortvideothreshold.{ShortVideoThreshold,ThresholdShortVideo}
import java.text.SimpleDateFormat
import java.util.Calendar
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import com.cpc.spark.ocpcV3.ocpc.OcpcUtils._
import java.time
import java.io.PrintWriter
import org.apache.spark.sql.functions
import scala.collection.mutable.ListBuffer

object shortvideo {
  def main(args: Array[String]): Unit = {
    val date = args(0)
    val hour = args(1)
    val traffic = 0
    val spark = SparkSession.builder()
      .appName(s"""shortvideo_execute +'${date}'+'${hour}'""")
      .enableHiveSupport()
      .getOrCreate()
    import org.apache.spark.sql._
    import spark.implicits._
    import org.apache.spark.sql._
    import scala.collection.mutable.ListBuffer

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
    val selectCondition = getTimeRangeSql21(date1, hour1, date, hour)
    val selectCondition2 = getTimeRangeSql22(date1, hour1, date, hour)
    val selectCondition3 = getTimeRangeSql23(date1, hour1, date, hour)



    //    val calb = Calendar.getInstance()
    //    calb.add(Calendar.HOUR_OF_DAY)
    //    val datetd = new SimpleDateFormat("yyyy-MM-dd").format(calb.getTime)
    //    val hourtd = new SimpleDateFormat("HH").format(calb.getTime)


    spark.sql("set hive.exec.dynamic.partition=true")
    //  生成中间表 appdownload_mid
    val sql =
      s"""

         |sselect   searchid,adtype,userid,ideaid,isclick,isreport,exp_cvr_ori as  exp_cvr,exp_cvr expcvr_d,cvr_rank,src,
         label_type,planid,unitid, adclass,view1.adslot_type,label2,view1.uid,usertype,view1.adslotid,isshow,'2019-03-13','11'
from
(
  select     day,hour,searchid,`timestamp`,isshow,exp_cvr/1000000 as exp_cvr_ori,exp_cvr,isclick,price,cvr_model_name,uid,userid,adslot_id as adslotid,
             charge_type,adtype,ideaid,usertype,adslot_type,
             row_number() over (partition by userid  order by exp_cvr desc ) cvr_rank
  from       dl_cpc.cpc_basedata_union_events
  where    ${selectCondition}
  and      media_appsid in  ("80000001")
  and      interaction=2
  and     adtype in (2,8,10)
  and     userid>0
  and     usertype in (0,1,2)
  and     isclick=1
  and     adslot_type = 1
  and     adsrc = 1
  and     isshow = 1
  and     ideaid > 0
  and      (charge_type is null or charge_type=1)
  and     uid not like "%.%"
  and     uid not like "%000000%"
  and     length(uid) in (14, 15, 36)
) view1
left JOIN
(
  select   `date`,hour hour2,aa.searchid as searchid2,isreport, src,label_type,uid,planid,unitid, adclass,adslot_type,label2
  FROM
  (
    select          `date`,hour,
                     final.searchid as searchid,src,label_type,uid,planid,unitid, adclass,adslot_type,label2,
                     final.ideaid as ideaid,
                     case
          when final.src="elds" and final.label_type=6 then 1
          when final.src="feedapp" and final.label_type in (4, 5) then 1
          when final.src="yysc" and final.label_type=12 then 1
          when final.src="wzcp" and final.label_type in (1, 2, 3) then 1
          when final.src="others" and final.label_type=6 then 1
          else 0     end as isreport
          from
          (
          select  distinct
              `date`,hour,searchid, media_appsid, uid,
              planid, unitid, ideaid, adclass,adslot_type,label2,
              case
                  when (adclass like '134%' or adclass like '107%') then "elds"
                  when (adslot_type<>7 and adclass like '100%') then "feedapp"
                  when (adslot_type=7 and adclass like '100%') then "yysc"
                  when adclass in (110110100, 125100100) then "wzcp"
                  else "others"
              end as src,
              label_type
          from
              dl_cpc.ml_cvr_feature_v1
          where
              ${selectCondition2}
              and label2=1
             and media_appsid in ("80000001")
            ) final
       ) aa
  where   aa.isreport=1
) a
on  a.searchid2=view1.searchid
and   a.`date`=view1.day
and   a.hour2 =view1.hour
group by searchid,adtype,userid,ideaid,isclick,isreport,exp_cvr_ori,exp_cvr ,cvr_rank,src,
         label_type,planid,unitid, adclass,view1.adslot_type,label2,view1.uid,usertype,view1.adslotid,isshow
         |""".stripMargin
    val tab0 = spark.sql(sql).selectExpr(
      "searchid","`timestamp` as timestamp","adtype","userid","ideaid","isclick","isreport","exp_cvr",
      "expcvr_d","cvr_rank","src","label_type","planid","unitid","adclass","adslot_type","label2","uid",
      "usertype","adslotid","isshow",s"""'${date}' as dt""",s"""'${hour}' as hr""").toDF
    (    "searchid","timestamp","adtype","userid","ideaid","isclick","isreport","exp_cvr",
      "expcvr_d","cvr_rank","src","label_type","planid","unitid","adclass","adslot_type","label2","uid",
      "usertype","adslotid","isshow","dt","hr"
    )
    tab0.repartition(100).write.mode("overwrite").insertInto("dl_cpc.cpc_unionevents_appdownload_mid2")
     println("dl_cpc.cpc_unionevents_appdownload_mid insert success!")
      //  动态取threshold,计算每个短视频userid下面所有的exp_cvr，进行排序
     //   RDD方法,获得短视频userid阈值
    val tabb = tab0.rdd.map(row => (row.getAs[String]("userid") ->
                                     List(row.getAs[Double]("exp_cvr")))).
      reduceByKey((x, y) => x ::: y).
      mapValues(x => {
        val sorted = x.sorted
        val th0 = 0
        val th1 = sorted((sorted.length * 0.05).toInt)
        val th2 = sorted((sorted.length * 0.10).toInt)
        val th3 = sorted((sorted.length * 0.15).toInt)
        val th4 = sorted((sorted.length * 0.2).toInt)
        val th5 = sorted((sorted.length * 0.25).toInt)
        val th6 = sorted((sorted.length * 0.3).toInt)
        (th0, th1, th2, th3, th4, th5, th6)
      }).collect()
    println("spark 7 threshold success!")
    val tabc = spark.createDataFrame(tabb)
    val tabd = tabc.rdd.map(r => {
      val userid = r.getAs[String](0)
      val rank0per = r.getAs[Array[Double]](1)(0)
      val rank5per = r.getAs[Array[Double]](1)(1)
      val rank10per = r.getAs[Array[Double]](1)(2)
      val rank15per = r.getAs[Array[Double]](1)(3)
      val rank20per = r.getAs[Array[Double]](1)(4)
      val rank25per = r.getAs[Array[Double]](1)(5)
      val rank30per = r.getAs[Array[Double]](1)(6)
      (userid,rank0per, rank5per, rank10per, rank15per, rank20per, rank25per, rank30per)
    }).map(s => (s._1, s._2, s._3, s._4, s._5, s._6, s._7,s._8)).
      toDF("userid_d", "expcvr_0per", "expcvr_5per", "expcvr_10per", "expcvr_15per", "expcvr_20per", "expcvr_25per", "expcvr_30per")
    println("spark 7 threshold tab success!")
    //计算大图和短视频实际cvr
    val sql4=
      s"""
         |select
         |    userid,adtype_cate,
         |    sum(isshow) as show_num,
         |    sum(isclick) as click_num,
         |    round(sum(isclick)/sum(isshow),6) as ctr,
         |    round(sum(case WHEN isclick = 1 then price else 0 end)*10/sum(isshow), 6) as cpm,
         |    sum(if(b.searchid is null,0,1)) as convert_num,
         |    sum(case when isreport=1 then 1   end ) cvr_n,
         |    round(sum(if(b.searchid is null,0,1))/sum(isclick),6) as cvr,
         |    round(sum(exp_cvr)/sum(isshow),6) as exp_cvr,
         |    dt,hr
         |from
         |(
         |    select
         |        searchid,isshow,exp_cvr/1000000 as exp_cvr,isclick,price,cvr_model_name,uid,userid,adslot_id as adslotid,
         |       case when adtype in (8,10) then 'video' when adtype =2 then 'bigpic' end adtype_cate,
         |       day dt,hour hr
         |    from
         |        dl_cpc.cpc_basedata_union_events
         |    where
         |        ${selectCondition}
         |        and media_appsid in ('80000001')
         |        and adslot_type = 1
         |        and adtype in (2,8,10)
         |         and adsrc = 1   --我们自己的广告
         |         and isshow = 1
         |        and ideaid > 0
         |        and  interaction=2
         |        and userid > 0
         |        and uid not like "%.%"
         |        and uid not like "%000000%"
         |        and length(uid) in (14, 15, 36)
         |        and (charge_type is null or charge_type=1)
         |) a
         |left join
         |(
         |    select
         |    tmp.*
         |                        from
         |                            (
         |                                select
         |                                    final.searchid as searchid,
         |                                    final.ideaid as ideaid,
         |                                    case
         |                                        when final.src="elds" and final.label_type=6 then 1
         |                                        when final.src="feedapp" and final.label_type in (4, 5) then 1
         |                                        when final.src="yysc" and final.label_type=12 then 1
         |                                        when final.src="wzcp" and final.label_type in (1, 2, 3) then 1
         |                                        when final.src="others" and final.label_type=6 then 1
         |                                        else 0
         |                                    end as isreport
         |                                from
         |                                    (
         |                                        select
         |                                            searchid, media_appsid, uid,
         |                                            planid, unitid, ideaid, adclass,
         |                                            case
         |                                                when (adclass like '134%' or adclass like '107%') then "elds"
         |                                                when (adslot_type<>7 and adclass like '100%') then "feedapp"
         |                                                when (adslot_type=7 and adclass like '100%') then "yysc"
         |                                                when adclass in (110110100, 125100100) then "wzcp"
         |                                                else "others"
         |                                            end as src,
         |                                            label_type
         |                                        from
         |                                            dl_cpc.ml_cvr_feature_v1
         |                                        where
         |                                            `date`='${selectCondition2}'
         |                                            and label2=1
         |                                            and media_appsid in ("80000001" )
         |                                    ) final
         |                            ) tmp
         |                        where
         |                            tmp.isreport=1
         |) b
         |on a.searchid = b.searchid
         |group by userid,adtype_cate,a.dt,a.hr
       """.stripMargin
    val  cvrcomparetab = spark.sql(sql4).selectExpr("userid","adtype_cate adtype","show_num","click_num",
    "ctr","cpm","convert_num","cvr_n","cvr","exp_cvr")
    cvrcomparetab.repartition(100).write.mode("overwrite").
      insertInto("dl_cpc.cpc_bigpicvideo_cvr")
    println("compare video bigpic act cvr midtab  success")
    val sql5=
      s"""
         |select video.userid,video.video_act_cvr1,bigpic.bigpic_act_cvr,bigpic.bigpic_expcvr
         |from
         |(
         |select userid,adtype,cvr video_act_cvr1
         |from  dl_cpc.cpc_bigpicvideo_cvr
         |where  ${selectCondition3}
         |and   adtype='video'
         |)   video
         |join
         |(
         |  select  userid,adtype,cvr  bigpic_act_cvr,exp_cvr bigpic_expcvr
         |  from  dl_cpc.cpc_bigpicvideo_cvr
         |  where  ${selectCondition3}
         |  and   adtype='bigpic'
         |) bigpic
         |on  bigpic.userid=video.userid
         |where   video_act_cvr1<bigpic_act_cvr
      """.stripMargin

    val bigpiccvr=spark.sql(sql5).selectExpr("userid as userid_b","bigpic_act_cvr","video_act_cvr1")
    println(" video_act_cvr1<bigpic_act_cvr  userid tab success!")

    //过滤大图cvr<短视频cvr的userid,待计算剩下的userid 的cvr
    val tab1=tab0.join(bigpiccvr,tab0("userid")===bigpiccvr("userid_b"),"inner").
      selectExpr("userid","isshow","isclick","price","isreport","exp_cvr","video_act_cvr1",
        "bigpic_act_cvr","bigpic_expcvr","dt","hr")
    println(" join tab0 success!")
    //计算短视频cvr
    val taba = spark.sql(
      s"""
         |select     distinct
         |           userid,expcvr_0per, expcvr_5per, expcvr_10per, expcvr_15per, expcvr_20per, expcvr_25per, expcvr_30per,
         |           round(sum(if(isreport =1 and ${traffic}=0,1,0))/sum(isclick),6) as traffic_0per_expcvr,
         |           round(sum(if(isreport =1 and exp_cvr>=expcvr_5per and ${traffic}<=0.05,1,0))/sum(isclick),6) as traffic_5per_expcvr,
         |           round(sum(if(isreport =1 and exp_cvr>=expcvr_10per and ${traffic}<=0.10,1,0))/sum(isclick),6) as traffic_10per_expcvr,
         |           round(sum(if(isreport =1 and exp_cvr>=expcvr_15per and ${traffic}<=0.15,1,0))/sum(isclick),6) as traffic_15per_expcvr,
         |           round(sum(if(isreport =1 and exp_cvr>=expcvr_20per and ${traffic}<=0.20,1,0))/sum(isclick),6) as traffic_20per_expcvr,
         |           round(sum(if(isreport =1 and exp_cvr>=expcvr_25per and ${traffic}<=0.25,1,0))/sum(isclick),6) as traffic_25per_expcvr,
         |           round(sum(if(isreport =1 and exp_cvr>=expcvr_30per and ${traffic}<=0.30,1,0))/sum(isclick),6) as traffic_30per_expcvr,
         |           ${date},${hour}
         | from
         | (   select userid,exp_cvr,cvr_rank,isshow,isclick,isreport,searchid,bigpic_expcvr
         |    from   ${tab1}
         |    where  ${selectCondition3}
         |    and  adtype in (8,10)
         | ) view
         | join
         | (
         |     select  userid_d, expcvr_0per, expcvr_5per, expcvr_10per, expcvr_15per, expcvr_20per, expcvr_25per, expcvr_30per
         |     from    ${tabd}
         | )   threshold
         |on    view.userid=threshold.userid_d
         |
         |""".stripMargin).
      selectExpr("userid","expcvr_0per","expcvr_5per","expcvr_10per", "expcvr_15per", "expcvr_20per", "expcvr_25per", "expcvr_30per",
        "traffic_0per_expcvr","traffic_5per_expcvr","traffic_10per_expcvr","traffic_15per_expcvr","traffic_20per_expcvr","traffic_25per_expcvr","traffic_30per_expcvr"
        ,"dt","hr").distinct()
      taba.write.mode("overwrite").insertInto("dl_cpc.video_trafficcut_threshold_mid")
      println("dl_cpc.video_trafficcut_threshold_mid  insert success!")
     val sqlfinal=
       s"""
          |select   userid, case
          |         when traffic_0per_expcvr=max_expcvr then expcvr_threshold0per
          |         when traffic_5per_expcvr=max_expcvr then expcvr_threshold5per
          |         when traffic_10per_expcvr=max_expcvr then expcvr_threshold10per
          |         when traffic_15per_expcvr=max_expcvr then expcvr_threshold15per
          |         when traffic_20per_expcvr=max_expcvr then expcvr_threshold20per
          |         when traffic_25per_expcvr=max_expcvr then expcvr_threshold25per
          |         when traffic_30per_expcvr=max_expcvr then expcvr_threshold30per
          |         end  max_expcvr,
          |         ${date},${hour}
          |(
          |select    userid userid2,
          |case when  traffic_0per_expcvr>=traffic_5per_expcvr then traffic_0per_expcvr
          |    else (
          |         case  when traffic_5per_expcvr>=traffic_10per_expcvr then traffic_5per_expcvr
          |               else (
          |                     case when traffic_10per_expcvr>=traffic_15per_expcvr then traffic_10per_expcvr
          |                     else (
          |                          case when case when traffic_15per_expcvr>=traffic_20per_expcvr then traffic_15per_expcvr
          |                          else (
          |                               case when traffic_20per_expcvr>=traffic_25per_expcvr then traffic_20per_expcvr
          |                               else (
          |                                    case when traffic_25per_expcvr>=traffic_30per_expcvr then traffic_25per_expcvr
          |                                    else
          |                                         traffic_30per_expcvr
          |                                    end  )))))  as max_expcvr
          |from   ${taba}
          |where   ${selectCondition3}
          |)  maxexpcvr
          |join
          |(
          |   select   *
          |   from    ${taba}
              where   ${selectCondition3}
          |)  threshold_mid
          |on  maxexpcvr.userid2=threshold_mid.useid
          |
        """.stripMargin
     val tabfinal=spark.sql(sqlfinal).selectExpr("userid","max_expcvr as expcvr",s"${date} as dt",s"${hour} as hr")
     tabfinal.write.mode("overwrite").insertInto("dl_cpc.cpc_appdown_cvr_threshold")
     println("dl_cpc.cpc_appdown_cvr_threshold  insert success!")
    val tabfinal2=tabfinal.selectExpr("userid","expcvr")
    /*#########################################################################*/
    //   pb写法2

    val list = new scala.collection.mutable.ListBuffer[ShortVideoThreshold]()
    var cnt = 0
    for (record <- tabfinal2.collect()) {
      var userid = record.getAs[String]("userid")
      var exp_cvr = record.getAs[Int]("expcvr")
      println(s"""useridr:$userid, expcvr:${exp_cvr}""")

      cnt += 1
      val Item = ShortVideoThreshold(
        userid = userid,
        threshold = exp_cvr
      )
      list += Item
    }
    println("finla userid cnt:" + cnt)
    val result = list.toArray
    val ecvr_tslist = ThresholdShortVideo(
      svt = result)
    println("Array length:" + result.length)
    ecvr_tslist.writeTo(new FileOutputStream("shortvideo.pb"))
    println("shortvideo.pb insert success!")

    /*#################################################################################*/

  }

  def tranTimeToLong(tm:String) :Long= {
      val fm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val dt = fm.parse(tm)
      val aa = fm.format(dt)
      val tim: Long = dt.getTime()
      tim
    }

  def getTimeRangeSql21(startDate: String, startHour: String, endDate: String, endHour: String): String = {
    if (startDate.equals(endDate)) {
      return s"(`date` = '$startDate' and hour <= '$endHour' and hour > '$startHour')"
    }
    return s"((day = '$startDate' and hour > '$startHour') " +
      s"or (day = '$endDate' and hour <= '$endHour') " +
      s"or (day > '$startDate' and day < '$endDate'))"
  }

   def getTimeRangeSql22(startDate: String, startHour: String, endDate: String, endHour: String): String = {
  if (startDate.equals(endDate)) {
    return s"(`date` = '$startDate' and hour <= '$endHour' and hour > '$startHour')"
  }
  return s"((`date` = '$startDate' and hour > '$startHour') " +
    s"or (`date` = '$endDate' and hour <= '$endHour') " +
    s"or (`date` > '$startDate' and `date` < '$endDate'))"
}
  def getTimeRangeSql23(startDate: String, startHour: String, endDate: String, endHour: String): String = {
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
    usertype  int,
    adslot_type string,
    isshow   int
)
partitioned by (dt string,hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';

--大图和短视频的实际cvr table
create table if not exists test.cpc_bigpicvideo_cvr
(
   userid    string,
   adtype    string,   --区分是2-大图的，还是8，10-短视频的
   show_num  bigint,
   click_num bigint,
   ctr       double,
   cpm       double,
   convert_num  bigint,
   cvr_n     bigint,
   cvr       double,
   exp_cvr   double
)
partitioned by (dt string,hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';

---不同流量切分等级的expcvr阈值和expcvr效果
create table if not exists dl_cpc.video_trafficcut_threshold_mid
(
   userid string,
   expcvr_threshold0per  bigint,
   expcvr_threshold5per  bigint,
   expcvr_threshold10per  bigint,
   expcvr_threshold15per  bigint,
   expcvr_threshold20per  bigint,
   expcvr_threshold25per  bigint,
   expcvr_threshold30per  bigint,
   traffic_0per_expcvr    double,
   traffic_5per_expcvr    double,
   traffic_10per_expcvr    double,
   traffic_15per_expcvr    double,
   traffic_20per_expcvr    double,
   traffic_25per_expcvr    double,
   traffic_30per_expcvr    double

)
partitioned by (dt string, hr string)
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

