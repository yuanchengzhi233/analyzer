package com.cpc.spark.OCPC_elds

import com.cpc.spark.tools.OperateMySQL
import org.apache.spark.sql.SparkSession
import com.cpc.spark.tools.CalcMetrics

object ocpc_elds_ld {
  def main(args: Array[String]): Unit = {
    val date = args(0)
    val spark = SparkSession.builder()
      .appName(s"ocpc_elds_ld date = $date ")
      .enableHiveSupport()
      .getOrCreate()
    import spark.implicits._

    val tmpDate = date.replace("-", "")
    val Sql1 =
      s"""
         |select
         |a.day,
         |a.unitid,
         |a.userid,
         |a.isclick,
         |a.isshow,
         |a.price,
         |a.siteid,
         |a.is_ocpc,
         |a.ocpc_log,
         |if(b.searchid is not null,1,0) as iscvr
         |from
         |(select
         |day,
         |unitid,
         |userid,
         |searchid,
         |isclick,
         |isshow,
         |price,
         |is_ocpc,
         |ocpc_log,
         |siteid,
         |conversion_goal
         |from dl_cpc.cpc_basedata_union_events
         |where day ='$date'
         |and (cast(adclass as string) like "134%" or cast(adclass as string) like "107%")
         |and media_appsid  in ("80000001", "80000002")
         |and isshow=1
         |and adsrc = 1
         |and ( charge_type = 1 or charge_type IS NULL) )a
         |left join (
         |    select
         |    searchid,
         |    case when cvr_goal='cvr1' then 1
         |    when cvr_goal = 'cvr2' then  2
         |    when cvr_goal = 'cvr3' then 3 end as conversion_goal
         |     from dl_cpc.ocpc_label_cvr_hourly
         |     where `date`='$date'
         |     group by searchid,case when cvr_goal='cvr1' then 1
         |    when cvr_goal = 'cvr2' then  2
         |    when cvr_goal = 'cvr3' then 3 end
         |  ) b on a.searchid = b.searchid and a.conversion_goal = b.conversion_goal
             """.stripMargin
    println(Sql1)
    val union = spark.sql(Sql1)

    //保存到临时表里
    union.repartition(1)
      .write
      .mode("overwrite")
      .saveAsTable("test.ocpc_elds_ld_union")

    val Sql2 =
      s"""
         |select
         |'可获取转化单元' as type,
         |sum(case when is_ocpc=1 and length(ocpc_log)>0 and isclick=1 then price else null end)/100 as ocpc_cost,
         |sum(case when siteid>0 and isclick=1 then price else null end)/100 as cost,
         |sum(case when is_ocpc=1 and length(ocpc_log)>0 and isclick=1 then price else null end)/sum(case when siteid>0 and isclick=1 then price else null end) as cost_ratio,
         |sum(case when siteid>0 then isshow else null end) as show_cnt,
         |sum(case when siteid>0 then isclick else null end) as click_cnt,
         |sum(case when siteid>0 then iscvr else null end) as cvr_cnt,
         |count(distinct case when siteid>0 and price>0 then userid else null end) as userid_cnt,
         |count(distinct case when siteid>0 and price>0 then unitid else null end) as unitid_cnt,
         |day
         |from test.ocpc_elds_ld_union
         |group by day,'可获取转化单元'
             """.stripMargin

    println(Sql2)
    val result1 = spark.sql(Sql2)
    result1.createOrReplaceTempView("result1")
    println ("result1 is successful! ")

    val Sql22 =
      s"""
         |select
         |'可获取转化单元-赤兔' as type,
         |sum(case when is_ocpc=1 and length(ocpc_log)>0 and isclick=1 then price else null end)/100 as ocpc_cost,
         |sum(case when isclick=1 then price else null end)/100 as cost,
         |sum(case when is_ocpc=1 and length(ocpc_log)>0 and isclick=1 then price else null end)/sum(case when isclick=1 then price else null end) as cost_ratio,
         |sum(isshow) as show_cnt,
         |sum(isclick) as click_cnt,
         |sum(iscvr) as cvr_cnt,
         |count(distinct case when price>0 then userid else null end) as userid_cnt,
         |count(distinct case when price>0 then unitid else null end) as unitid_cnt,
         |day
         |from test.ocpc_elds_ld_union
         |where siteid>5000000
         |group by day,'可获取转化单元-赤兔'
             """.stripMargin

    println(Sql22)
    val result11 = spark.sql(Sql22)
    result11.createOrReplaceTempView("result11")
    println ("result11 is successful! ")


    val Sql3 =
      s"""
         |select
         |"满足准入单元" as type,
         |sum(case when q.is_ocpc=1 and length(q.ocpc_log)>0 and q.isclick=1 then q.price else null end)/100 as ocpc_cost,
         |sum(case when q.isclick=1 then q.price else null end)/100 as cost,
         |sum(case when q.is_ocpc=1 and length(q.ocpc_log)>0 and q.isclick=1 then q.price else null end)/sum(case when q.isclick=1 then q.price else null end) as cost_ratio,
         |sum(q.isshow) as show_cnt,
         |sum(q.isclick) as click_cnt,
         |sum(q.iscvr) as cvr_cnt,
         |count(distinct case when q.price>0 then q.userid else null end) as userid_cnt,
         |count(distinct case when q.price>0 then q.unitid else null end) as unitid_cnt,
         |q.day
         |from
         |(select
         |a.unitid
         |from
         |(select
         |unitid
         |from dl_cpc.ocpc_suggest_cpa_recommend_hourly
         |where `date`='$date'
         |and hour = '06'
         |and version = 'qtt_demo'
         |and is_recommend=1
         |group by unitid
         |UNION
         |select unitid
         |from test.ocpc_elds_ld_union
         |where is_ocpc=1 and length(ocpc_log)>0
         |group by unitid )a
         |group by a.unitid)p
         |join
         |(select *
         |from test.ocpc_elds_ld_union )q on p.unitid=q.unitid
         |group by q.day,"满足准入单元"
             """.stripMargin

    println(Sql3)

    val result2 = spark.sql(Sql3)
    result2.createOrReplaceTempView("result2")
    println ("result2 is successful! ")

    val Sql33 =
      s"""
         |select
         |"满足准入单元-赤兔" as type,
         |sum(case when q.is_ocpc=1 and length(q.ocpc_log)>0 and q.isclick=1 then q.price else null end)/100 as ocpc_cost,
         |sum(case when q.isclick=1 then q.price else null end)/100 as cost,
         |sum(case when q.is_ocpc=1 and length(q.ocpc_log)>0 and q.isclick=1 then q.price else null end)/sum(case when q.isclick=1 then q.price else null end) as cost_ratio,
         |sum(q.isshow) as show_cnt,
         |sum(q.isclick) as click_cnt,
         |sum(q.iscvr) as cvr_cnt,
         |count(distinct case when q.price>0 then q.userid else null end) as userid_cnt,
         |count(distinct case when q.price>0 then q.unitid else null end) as unitid_cnt,
         |q.day
         |from
         |(select
         |m.unitid
         |from
         |((select
         |a.unitid
         |from
         |(select
         |unitid
         |from dl_cpc.ocpc_suggest_cpa_recommend_hourly
         |where `date`='$date'
         |and hour = '06'
         |and version = 'qtt_demo'
         |and is_recommend=1
         |group by unitid )a
         |join
         |(select
         |unitid
         |from test.ocpc_elds_ld_union
         |where siteid>5000000
         |group by unitid) b on a.unitid=b.unitid
         |group by a.unitid )
         |UNION
         |select unitid
         |from test.ocpc_elds_ld_union
         |where is_ocpc=1 and length(ocpc_log)>0
         |and siteid>5000000
         |group by unitid )m
         |group by m.unitid)p
         |join
         |(select *
         |from test.ocpc_elds_ld_union )q on p.unitid=q.unitid
         |group by q.day,"满足准入单元-赤兔"
             """.stripMargin

    println(Sql33)

    val result22 = spark.sql(Sql33)
    result22.createOrReplaceTempView("result22")
    println ("result22 is successful! ")



    val Sql4 =
      s"""
         |select
         |"ocpc单元" as type,
         |sum(case when isclick=1 then price else null end)/100 as ocpc_cost,
         |sum(case when isclick=1 then price else null end)/100 as cost,
         |1.0 as cost_ratio,
         |sum(isshow) as show_cnt,
         |sum(isclick) as click_cnt,
         |sum(iscvr) as cvr_cnt,
         |count(distinct case when price>0 then userid else null end) as userid_cnt,
         |count(distinct case when price>0 then unitid else null end) as unitid_cnt,
         |day
         |from test.ocpc_elds_ld_union
         |where is_ocpc=1
         |and length(ocpc_log)>0
         |group by day,"ocpc单元",1.0
             """.stripMargin

    println(Sql4)
    val result3 = spark.sql(Sql4)
    result3.createOrReplaceTempView("result3")
    println ("result3 is successful! ")

    val Sql44 =
      s"""
         |select
         |"ocpc单元-赤兔" as type,
         |sum(case when isclick=1 then price else null end)/100 as ocpc_cost,
         |sum(case when isclick=1 then price else null end)/100 as cost,
         |1.0 as cost_ratio,
         |sum(isshow) as show_cnt,
         |sum(isclick) as click_cnt,
         |sum(iscvr) as cvr_cnt,
         |count(distinct case when price>0 then userid else null end) as userid_cnt,
         |count(distinct case when price>0 then unitid else null end) as unitid_cnt,
         |day
         |from test.ocpc_elds_ld_union
         |where is_ocpc=1
         |and length(ocpc_log)>0
         |and siteid>5000000
         |group by day,"ocpc单元-赤兔",1.0
             """.stripMargin

    println(Sql44)
    val result33 = spark.sql(Sql44)
    result33.createOrReplaceTempView("result33")
    println ("result33 is successful! ")

    val Sql5 =
      s"""
         |select *
         |from result1
         |UNION ALL
         |select *
         |from result2
         |UNION ALL
         |select *
         |from result3
             """.stripMargin

    println(Sql5)
    val result = spark.sql(Sql5)
    result.show(10)
    result.repartition(1)
      .write
      .mode("overwrite")
      .insertInto("dl_cpc.ocpc_elds_ld_data")
    println("result is successful! ")

    val Sql55 =
      s"""
         |select *
         |from result11
         |UNION ALL
         |select *
         |from result22
         |UNION ALL
         |select *
         |from result33
             """.stripMargin

    println(Sql55)
    val result0 = spark.sql(Sql55)
    result0.show(10)
    result0.repartition(1)
      .write
      .mode("overwrite")
      .insertInto("dl_cpc.ocpc_elds_ct_ld_data")
    println("result0 is successful! ")




    //    val tableName1 = "report2.ocpc_elds_ld_data"
    //    val deleteSql1 = s"delete from $tableName1 where day = '$date' "
    //    OperateMySQL.update(deleteSql1) //先删除历史数据
    //    OperateMySQL.insert(result,tableName1) //插入到MySQL中的report2库中
  }
}
