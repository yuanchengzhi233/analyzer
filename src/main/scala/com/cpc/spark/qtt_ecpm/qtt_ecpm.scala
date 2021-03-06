package com.cpc.spark.qtt_ecpm
import java.io.FileOutputStream
import org.apache.spark.sql.SparkSession
import qttall_ecpm_dev.qttall_ecpm_dev.{Threshold_qttall_ecpm, qttall_ecpm_Threshold}


/*2019-05-16 qttall_ecpm工程开发*/

object qtt_ecpm {
  def main(args: Array[String]): Unit = {
    val date = args(0)
    val traffic1 = args(1)
    val traffic2 = args(2)
    val traffic3 = args(3)
    val spark = SparkSession.builder()
      .appName(s"""qttzq_ecpm_execute +'${date}' """)
      .enableHiveSupport()
      .getOrCreate()
    import java.text.SimpleDateFormat
    import java.util.Calendar
    val dateConverter = new SimpleDateFormat("yyyy-MM-dd")
    val newDate = date
    val today = dateConverter.parse(newDate)
    val calendar = Calendar.getInstance
     calendar.setTime(today)
////  设定前一天的时间点
//    val cala=calendar.add(Calendar.DATE,0)
//    val yes=cala.setTime
//    val yesday=dateConverter.format(cala)
//    val yesdate=yesday.split(" ")(0)
//    val yestime=yesday.split(" ")(1)
//    设定过去3天的时间点
    calendar.add(Calendar.DATE, -3)
    val yesterday = calendar.getTime
    val tmpDate = dateConverter.format(yesterday)
    val tmpDateValue = tmpDate.split(" ")
    val date1 = tmpDateValue(0)
//    val hour1 = tmpDateValue(1)
//    val selectCondition = getTimeRangeSql21(date1, hour1, date, hour)
//    val selectCondition2 = getTimeRangeSql22(date1, hour1, date, hour)
//    val selectCondition3 = getTimeRangeSql23(date1, hour1, date, hour)


    spark.sql("set hive.exec.dynamic.partition=true")
//  每日更新前一天24qtt usertype 视频广告数据
//    val sql2 =
//      s"""
//         |select  distinct
//         |        searchid  ,
//         |        is_ocpc   ,
//         |        media_appsid  ,
//         |        adslot_id   ,
//         |        adclass     ,
//         |        adtype      ,
//         |        adsrc       ,
//         |        charge_type ,
//         |        unitid      ,
//         |        planid      ,
//         |        ideaid      ,
//         |        userid      ,
//         |        usertype    ,
//         |        uid         ,
//         |        city_level  ,
//         |        isshow      ,
//         |        isclick     ,
//         |        price       ,
//         |        bid         ,
//         |        exp_ctr     ,
//         |        raw_ctr     ,
//         |        exp_ctr*1.0*bid/100000        ,
//         |        day,
//         |        hour
//         |from    dl_cpc.cpc_basedata_union_events
//         |where   day=date_add('${date1}',2)
//         |and      adsrc in (0,1,28)
//         |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
//         |and      adtype in (8,10)
//         |and     unitid > 0
//         |and     userid > 0
//         |and     is_ocpc=0
//         |
//       """.stripMargin
//    val tab00=spark.sql(sql2).persist()
//    tab00.show(10,false)
//    tab00.repartition(100).write.mode("overwrite").insertInto("dl_cpc.qttusertype_ecpm_detail_mid_qbj")
//    println(s"dl_cpc.qttusertype_ecpm_detail_mid_qbj yesterday insert success")
    //-----阈值试算 traffic1----------
    val  threstab1 = spark.sql(
      s"""
           |select   nd.adslot_id,nd.hour,nd.adclass,nd.usertype,max(nd.ecpm) as threshold
           |from
           |(
           |select   '${date}' as dt,adslot_id, hour, adclass,usertype,ecpm,
           |         row_number() over (order by ecpm desc) as ecpm_rank
           |from    dl_cpc.qttusertype_ecpm_detail_mid_qbj
           |where  dt>='${date1}' and dt<=date_add('${date1}',2)
           |and      adsrc in (0,1,28)
           |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
           |and      adtype in (8,10)
           |and     ideaid > 0
           |and     userid > 0
           |and     is_ocpc=0
           |)  nd
           |left join
           |(
           |   select  count(ecpm) as max_num,usertype,'${date}' as dt
           |   from    dl_cpc.qttusertype_ecpm_detail_mid_qbj
           |   where  dt>='${date1}' and dt<=date_add('${date1}',2)
           |and      adsrc in (0,1,28)
           |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
           |and      adtype in (8,10)
           |and     ideaid > 0
           |and     userid > 0
           |and     is_ocpc=0
           |group by usertype
           |)  hd
           |on  hd.dt=nd.dt
           |and hd.usertype=nd.usertype
           |where   nd.ecpm_rank>round(hd.max_num*${traffic1},0)
           |group by nd.adslot_id,nd.hour,nd.adclass,nd.usertype
       """.stripMargin).selectExpr("adslot_id","hour","adclass","usertype","threshold",s"""'${date}' as dt""",s"""${traffic1} as traffic""").
      toDF("adslot_id","hour","adclass","usertype","threshold","dt","traffic")
      threstab1.show(10,false)
    threstab1.repartition(100).write.mode("overwrite").insertInto("dl_cpc.cpc_qttall_ecpm_threshold_qbj")
      println(s"dl_cpc.cpc_qttall_ecpm_threshold_qbj traffic:${traffic1} insert success")

    //-----阈值试算 traffic2----------
    val  threstab2 = spark.sql(
      s"""
         |select   nd.adslot_id,nd.hour,nd.adclass,nd.usertype,max(nd.ecpm) as threshold
         |from
         |(
         |select   '${date}' as dt,adslot_id, hour, adclass,usertype,ecpm,
         |         row_number() over (order by ecpm desc) as ecpm_rank
         |from    dl_cpc.qttusertype_ecpm_detail_mid_qbj
         |where  dt>='${date1}' and dt<=date_add('${date1}',2)
         |and      adsrc in (0,1,28)
         |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
         |and      adtype in (8,10)
         |and     ideaid > 0
         |and     userid > 0
         |and     is_ocpc=0
         |)  nd
         |left join
         |(
         |   select  count(ecpm) as max_num,usertype,'${date}' as dt
         |   from    dl_cpc.qttusertype_ecpm_detail_mid_qbj
         |   where  dt>='${date1}' and dt<=date_add('${date1}',2)
         |and      adsrc in (0,1,28)
         |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
         |and      adtype in (8,10)
         |and     ideaid > 0
         |and     userid > 0
         |and     is_ocpc=0
         |group by usertype
         |)  hd
         |on  hd.dt=nd.dt
         |and hd.usertype=nd.usertype
         |where   nd.ecpm_rank>round(hd.max_num*${traffic2},0)
         |group by nd.adslot_id,nd.hour,nd.adclass,nd.usertype
       """.stripMargin).selectExpr("adslot_id","hour","adclass","usertype","threshold",s"""'${date}' as dt""",s"""${traffic2} as traffic""").
      toDF("adslot_id","hour","adclass","usertype","threshold","dt","traffic")
    threstab2.show(10,false)
    threstab2.repartition(100).write.mode("overwrite").insertInto("dl_cpc.cpc_qttall_ecpm_threshold_qbj")
    println(s"dl_cpc.cpc_qttall_ecpm_threshold_qbj traffic:${traffic2} insert success")

    //-----阈值试算 traffic3----------
    val  threstab3 = spark.sql(
      s"""
         |select   nd.adslot_id,nd.hour,nd.adclass,nd.usertype,max(nd.ecpm) as threshold
         |from
         |(
         |select   '${date}' as dt,adslot_id, hour, adclass,usertype,ecpm,
         |         row_number() over (order by ecpm desc) as ecpm_rank
         |from    dl_cpc.qttusertype_ecpm_detail_mid_qbj
         |where  dt>='${date1}' and dt<=date_add('${date1}',2)
         |and      adsrc in (0,1,28)
         |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
         |and      adtype in (8,10)
         |and     ideaid > 0
         |and     userid > 0
         |and     is_ocpc=0
         |)  nd
         |left join
         |(
         |   select  count(ecpm) as max_num,usertype,'${date}' as dt
         |   from    dl_cpc.qttusertype_ecpm_detail_mid_qbj
         |   where  dt>='${date1}' and dt<=date_add('${date1}',2)
         |and      adsrc in (0,1,28)
         |and      media_appsid in ('80000001', '80000002')  --看详情页、列表页
         |and      adtype in (8,10)
         |and     ideaid > 0
         |and     userid > 0
         |and     is_ocpc=0
         |group by usertype
         |)  hd
         |on  hd.dt=nd.dt
         |and hd.usertype=nd.usertype
         |where   nd.ecpm_rank>round(hd.max_num*${traffic3},0)
         |group by nd.adslot_id,nd.hour,nd.adclass,nd.usertype
       """.stripMargin).selectExpr("adslot_id","hour","adclass","usertype","threshold",s"""'${date}' as dt""",s"""${traffic3} as traffic""").
      toDF("adslot_id","hour","adclass","usertype","threshold","dt","traffic")
    threstab3.show(10,false)
    threstab3.repartition(100).write.mode("overwrite").insertInto("dl_cpc.cpc_qttall_ecpm_threshold_qbj")
    println(s"dl_cpc.cpc_qttall_ecpm_threshold_qbj traffic:${traffic3} insert success")

    /*增加段子分组对应关系 */
    var taball=spark.read.table("dl_cpc.cpc_qttall_ecpm_threshold_qbj").filter(s"dt='${date}'").
      selectExpr("adslot_id","hour","adclass","usertype", "threshold","traffic",s"""0 as exp_id""").na.fill(0)

    /*#########################################################################*/
    //   pb写法


    val list = new scala.collection.mutable.ListBuffer[qttall_ecpm_Threshold]()
    var cnt = 0
    for (record <- taball.collect()) {
      var adslotid0 = record.getAs[Long]("adslot_id")
      var hour0 = record.getAs[Int]("hour")
      var adclass0 = record.getAs[Long]("adclass")
      var usertype0=record.getAs[Int]("usertype")
      var ecpmt0 = record.getAs[Double]("threshold")
      var traffic0 = record.getAs[Double]("traffic")

      println(
        s"""adslot_id:${adslotid0},
           |hour     :${hour0},
           |adclass  :${adclass0},
           |usertype :${usertype0},
           |ecpm_t   :${ecpmt0},
           |traffic  :${traffic0}
           |""".stripMargin)

      cnt += 1
      val Item = qttall_ecpm_Threshold(
        adslotidall=adslotid0,
        hourall=hour0,
        adclassall=adclass0,
        usertypeall=usertype0,
        ecpmtall=ecpmt0,
        trafficall=traffic0
      )
      list += Item
    }
    println("final userid cnt:" + cnt)
    val result = list.toArray
    val ecpmlist = Threshold_qttall_ecpm(
      det = result )
    println("Array length:" + result.length  + s" '${date}'")
    ecpmlist.writeTo(new FileOutputStream("qttall_ecpm_qbj.pb"))
    println("qttall_ecpm_qbj.pb insert success!")



    /*#################################################################################*/


//    /*------调试ecpm参数,验证消耗比例 traffic1-------*/
//    val  valitab1 = spark.sql(
//       s"""
//          |select  nd.adslot_id,nd.hour,nd.adclass,
//          |        case when expcpm<threshold    then '3ddrop>${traffic1}'
//          |             when expcpm>=threshold   then '3dkeep<=${traffic1}'
//          |             else   'no threshold'       end as cate,
//          |        count(*) as nd_query,
//          |        (sum(case WHEN isclick = 1 and (charge_type = 1 or charge_type IS NULL)  then price else 0 end)
//          |      + sum(case when isshow  = 1 and  charge_type = 2                          then price else 0 end)/1000.0)/100.0 as  nd_consume,
//          |      (sum(case WHEN isclick = 1 and (charge_type = 1 or charge_type IS NULL)  then price else 0 end)        + sum(case when isshow  = 1 and  charge_type = 2                          then price else 0 end)/1000.0)/100.0/count(*) as nd_cpq,
//          |     (sum(if(isclick=1,1,0))/count(*))*1.0/(avg(raw_ctr/1000000)*1.0) as nd_pcoc
//          |
//          |from
//          |(
//          |    select searchid,adslot_id,hour,adclass,exp_ctr*1.0*bid/100000 as expcpm,isclick,isshow,charge_type,price,raw_ctr,
//          |          row_number() over (order by exp_ctr*1.0*bid/100000 desc ) as expcpm_rank,day dt
//          |    from  dl_cpc.cpc_basedata_union_events
//          |    where  day='${date}'
//          |    and      adsrc =1
//          |and      media_appsid in ( '80000002')  --看详情页
//          |and      adtype in (8,10)
//          |and      usertype=2
//          |and      (uid rlike '^\\w{14,16}' or uid like '________-____-____-____-____________')
//          |and     unitid > 0
//          |and     userid > 0
//          |and     is_ocpc=0
//          |)  nd
//          |left join
//          |(
//          |    select  dt,adslot_id,hour,adclass,threshold
//          |    from     dl_cpc.cpc_qttzq_ecpm_threshold_qbj
//          |    where  dt='${date}'
//          |    group by dt,adslot_id,hour,adclass,threshold
//          |) nd2
//          |on  nd2.dt=nd.dt
//          |and nd2.adslot_id=nd.adslot_id
//          |and nd2.hour=nd.hour
//          |and nd2.adclass=nd.adclass
//          |group by nd.adslot_id,nd.hour,nd.adclass,
//          |        case when expcpm<threshold    then '3ddrop>${traffic1}'
//          |             when expcpm>=threshold   then '3dkeep<=${traffic1}'
//          |             else   'no threshold'       end
//    """.stripMargin).selectExpr("adslot_id","hour","adclass", "cate","nd_query","nd_consume","nd_cpq","nd_pcoc",s"""'${date}' as dt""",s"""${traffic1} as traffic""").
//      toDF("adslot_id","hour","adclass", "cate","nd_query","nd_consume","nd_cpq","nd_pcoc","dt","traffic")
//    valitab1.show(10,false)
//    valitab1.write.mode("overwrite").insertInto("dl_cpc.cpc_qttzq_ecpm_datadis_qbj")
//
//    //  -------测算请求&消耗占比-------
//    val checktab1=spark.sql(
//      s"""
//         |select  cate,sum(nd_query),sum(nd_consume),dt,traffic
//         |from    dl_cpc.cpc_qttzq_ecpm_datadis_qbj
//         |where   dt= '${date}'
//         |and     traffic='${traffic1}'
//         |group by dt,traffic,cate
//      """.stripMargin).selectExpr("cate","query","consume","dt","traffic").
//      toDF("cate","query","consume","dt","traffic")
//    checktab1.show(10,false)
//    println(" check1 success!")
//
////    /*------调试ecpm参数,验证消耗比例 traffic2-------*/
//    val  valitab2 = spark.sql(
//      s"""
//         |select  nd.adslot_id,nd.hour,nd.adclass,
//         |        case when expcpm<threshold    then '3ddrop>${traffic2}'
//         |             when expcpm>=threshold   then '3dkeep<=${traffic2}'
//         |             else   'no threshold'       end as cate,
//         |        count(*) as nd_query,
//         |        (sum(case WHEN isclick = 1 and (charge_type = 1 or charge_type IS NULL)  then price else 0 end)
//         |      + sum(case when isshow  = 1 and  charge_type = 2                          then price else 0 end)/1000.0)/100.0 as  nd_consume,
//         |      (sum(case WHEN isclick = 1 and (charge_type = 1 or charge_type IS NULL)  then price else 0 end)        + sum(case when isshow  = 1 and  charge_type = 2                          then price else 0 end)/1000.0)/100.0/count(*) as nd_cpq,
//         |     (sum(if(isclick=1,1,0))/count(*))*1.0/(avg(raw_ctr/1000000)*1.0) as nd_pcoc
//         |
// |from
//         |(
//         |    select searchid,adslot_id,hour,adclass,exp_ctr*1.0*bid/100000 as expcpm,isclick,isshow,charge_type,price,raw_ctr,
//         |          row_number() over (order by exp_ctr*1.0*bid/100000 desc ) as expcpm_rank,day dt
//         |    from  dl_cpc.cpc_basedata_union_events
//         |    where  day='${date}'
//         |    and      adsrc =1
//         |and      media_appsid in ( '80000002')  --看详情页
//         |and      adtype in (8,10)
//         |and      usertype=2
//         |and      (uid rlike '^\\w{14,16}' or uid like '________-____-____-____-____________')
//         |and     unitid > 0
//         |and     userid > 0
//         |and     is_ocpc=0
//         |)  nd
//         |left join
//         |(
//         |    select  dt,adslot_id,hour,adclass,threshold
//         |    from     dl_cpc.cpc_qttzq_ecpm_threshold_qbj
//         |    where  dt='${date}'
//         |    group by dt,adslot_id,hour,adclass,threshold
//         |) nd2
//         |on  nd2.dt=nd.dt
//         |and nd2.adslot_id=nd.adslot_id
//         |and nd2.hour=nd.hour
//         |and nd2.adclass=nd.adclass
//         |group by nd.adslot_id,nd.hour,nd.adclass,
//         |        case when expcpm<threshold    then '3ddrop>${traffic2}'
//         |             when expcpm>=threshold   then '3dkeep<=${traffic2}'
//         |             else   'no threshold'       end
//         """.stripMargin).selectExpr("adslot_id","hour","adclass", "cate","nd_query","nd_consume","nd_cpq","nd_pcoc",s"""'${date}' as dt""",s"""${traffic1} as traffic""").
//      toDF("adslot_id","hour","adclass", "cate","nd_query","nd_consume","nd_cpq","nd_pcoc","dt","traffic")
//    valitab2.show(10,false)
//    valitab2.write.mode("overwrite").insertInto("dl_cpc.cpc_qttzq_ecpm_datadis_qbj")
//
//
////  -------测算请求&消耗占比-------
//    val checktab2=spark.sql(
//      s"""
//         |select  cate,sum(nd_query),sum(nd_consume),dt,traffic
//         |from    dl_cpc.cpc_qttzq_ecpm_datadis_qbj
//         |where   dt= '${date}'
//         |and     traffic='${traffic2}'
//         |group by dt,traffic,cate
//      """.stripMargin).selectExpr("cate","query","consume","dt","traffic").
//      toDF("cate","query","consume","dt","traffic")
//    checktab2.show(10,false)
//    println(" check2 success!")
//
//
////    /*------调试ecpm参数,验证消耗比例 traffic3-------*/
//    val  valitab3 = spark.sql(
//      s"""
//         |select  nd.adslot_id,nd.hour,nd.adclass,
//         |        case when expcpm<threshold    then '3ddrop>${traffic3}'
//         |             when expcpm>=threshold   then '3dkeep<=${traffic3}'
//         |             else   'no threshold'       end as cate,
//         |        count(*) as nd_query,
//         |        (sum(case WHEN isclick = 1 and (charge_type = 1 or charge_type IS NULL)  then price else 0 end)
//         |      + sum(case when isshow  = 1 and  charge_type = 2                          then price else 0 end)/1000.0)/100.0 as  nd_consume,
//         |      (sum(case WHEN isclick = 1 and (charge_type = 1 or charge_type IS NULL)  then price else 0 end)        + sum(case when isshow  = 1 and  charge_type = 2                          then price else 0 end)/1000.0)/100.0/count(*) as nd_cpq,
//         |     (sum(if(isclick=1,1,0))/count(*))*1.0/(avg(raw_ctr/1000000)*1.0) as nd_pcoc
//         |
// |from
//         |(
//         |    select searchid,adslot_id,hour,adclass,exp_ctr*1.0*bid/100000 as expcpm,isclick,isshow,charge_type,price,raw_ctr,
//         |          row_number() over (order by exp_ctr*1.0*bid/100000 desc ) as expcpm_rank,day dt
//         |    from  dl_cpc.cpc_basedata_union_events
//         |    where  day='${date}'
//         |    and      adsrc =1
//         |and      media_appsid in ( '80000002')  --看详情页
//         |and      adtype in (8,10)
//         |and      usertype=2
//         |and      (uid rlike '^\\w{14,16}' or uid like '________-____-____-____-____________')
//         |and     unitid > 0
//         |and     userid > 0
//         |and     is_ocpc=0
//         |)  nd
//         |left join
//         |(
//         |    select   dt,adslot_id,hour,adclass,threshold
//         |    from     dl_cpc.cpc_qttzq_ecpm_threshold_qbj
//         |    where    dt='${date}'
//         |    group by dt,adslot_id,hour,adclass,threshold
//         |) nd2
//         |on  nd2.dt=nd.dt
//         |and nd2.adslot_id=nd.adslot_id
//         |and nd2.hour=nd.hour
//         |and nd2.adclass=nd.adclass
//         |group by nd.adslot_id,nd.hour,nd.adclass,
//         |        case when expcpm<threshold    then '3ddrop>${traffic3}'
//         |             when expcpm>=threshold   then '3dkeep<=${traffic3}'
//         |             else   'no threshold'       end
//         """.stripMargin).selectExpr("adslot_id","hour","adclass", "cate","nd_query","nd_consume","nd_cpq","nd_pcoc",s"""'${date}' as dt""",s"""${traffic3} as traffic""").
//      toDF("adslot_id","hour","adclass", "cate","nd_query","nd_consume","nd_cpq","nd_pcoc","dt","traffic")
//    valitab3.show(10,false)
//    valitab3.write.mode("overwrite").insertInto("dl_cpc.cpc_qttzq_ecpm_datadis_qbj")
//
//
//    //  -------测算请求&消耗占比-------
//    val checktab3=spark.sql(
//      s"""
//         |select  cate,sum(nd_query),sum(nd_consume),dt,traffic
//         |from    dl_cpc.cpc_qttzq_ecpm_datadis_qbj
//         |where   dt= '${date}'
//         |and     traffic='${traffic3}'
//         |group by dt,traffic,cate
//      """.stripMargin).selectExpr("cate","query","consume","dt","traffic").
//      toDF("cate","query","consume","dt","traffic")
//    checktab3.show(10,false)
//    println(" check3 success!")

  }

//  def tranTimeToLong(tm:String) :Long= {
//      val fm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
//      val dt = fm.parse(tm)
//      val aa = fm.format(dt)
//      val tim: Long = dt.getTime()
//      tim
//    }

//  def getTimeRangeSql21(startDate: String, startHour: String, endDate: String, endHour: String): String = {
//    if (startDate.equals(endDate)) {
//      return s"( dt = '$startDate' and hour <= '$endHour' and hour > '$startHour')"
//    }
//    return s"((dt = '$startDate' and hour >='$startHour') " +
//      s"or (dt = '$endDate' and hour <'$endHour') " +
//      s"or (dt > '$startDate' and dt < '$endDate'))"
//  }
//
//   def getTimeRangeSql22(startDate: String, startHour: String, endDate: String, endHour: String): String = {
//  if (startDate.equals(endDate)) {
//    return s"(`date` = '$startDate' and hour <= '$endHour' and hour > '$startHour')"
//  }
//  return s"((`date` = '$startDate' and hour >= '$startHour') " +
//    s"or (`date` = '$endDate' and hour < '$endHour') " +
//    s"or (`date` > '$startDate' and `date` < '$endDate'))"
//}
//  def getTimeRangeSql23(startDate: String, startHour: String, endDate: String, endHour: String): String = {
//    if (startDate.equals(endDate)) {
//      return s"(`date` = '$startDate' and hour <= '$endHour' and hour > '$startHour')"
//    }
//    return s"((dt = '$startDate' and hr >= '$startHour') " +      s"or (dt = '$endDate' and hr < '$endHour') " +
//      s"or (dt > '$startDate' and dt < '$endDate'))"
//  }



}

/*
中间表 mid
create table if not exists dl_cpc.cpc_union_events_video_mid
(
    searchid string,
    adtype   string,
    userid   string,
    ideaid   int,
    isclick  int,
    isreport int,
    expcvr_d  double,
    exp_cvr int,
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
    adslot_id string,
    isshow   int,
    price    bigint
)
partitioned by (dt string,hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';

--大图和短视频的实际cvr table
create table if not exists dl_cpc.cpc_bigpicvideo_cvr
(
   userid    string,
   adtype_cate    string,   --区分是2-大图的，还是8，10-短视频的
   adclass   string,
   show_num  bigint,
   click_num bigint,
   ctr       double,
   cpm       double,
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
   traffic_30per_expcvr    double,
   video_act_cvr           double,
   bigpic_act_cvr          double,
   adclass_act_cvr         double

)
partitioned by (dt string, hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';

--计算useri的降序分位数expcvr表
create table if not exists dl_cpc.userid_expcvr_lastpercent
(
userid   string,
expcvr_0per  bigint,
expcvr_5per  bigint,
expcvr_10per bigint,
expcvr_15per  bigint,
expcvr_20per  bigint,
expcvr_25per  bigint,
expcvr_30per  bigint
)
partitioned by (dt string,hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';

--计算userid 的视频实际cvr小于大图实际cvr或行业实际cvr
create table if not exists dl_cpc.bigpic_adclass_ls_actcvr_userid
(
userid  string,
isshow  bigint,
isclick bigint,
price   double,
isreport  int,
exp_cvr  bigint,
video_act_cvr1  double,
bigpic_act_cvr   double,
adclass_act_cvr  double

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

//后续新增每日更新的video的基础中间表，作为dl_cpc.cpc_union_events_video_mid的from来源
create table if not exists dl_cpc.cpc_unionevents_video_mid
(
    searchid string,
    adtype   string,
    userid   string,
    ideaid   int,
    isclick  int,
    isreport int,
    expcvr_d  double,
    exp_cvr int,
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
    adslot_id string,
    isshow   int,
    price    bigint,
    media_appsid string,
    interaction  int,
    adsrc  int,
    charge_type  int
)
partitioned by (dt string,hr string)
row format delimited fields terminated by '\t' lines terminated by '\n';
*/

