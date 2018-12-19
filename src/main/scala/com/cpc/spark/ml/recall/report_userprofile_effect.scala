package com.cpc.spark.ml.recall

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.spark.sql.SparkSession

object report_userprofile_effect {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    val cal = Calendar.getInstance()
    cal.add(Calendar.DATE, -1)
    val date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime)

    val sqlRequest1 =
      s"""
         | select
         |  a.searchid,
         |  a.uid,
         |  a.userid,
         |  a.isclick,
         |  a.isshow,
         |  a.price,
         |  a.interests,
         |  b.tag
         | from
         |      (
         |        select userid, isshow, isclick, searchid, uid, interests, price
         |        from dl_cpc.cpc_union_log
         |        where date='$date'
         |        and media_appsid  in ("80000001", "80000002")
         |        and isshow = 1
         |        and isclick is not null
         |        and ext['antispam'].int_value = 0
         |        and ideaid > 0
         |        and adsrc = 1
         |        and userid is not null
         |      ) a
         |right join
         |      (select tag, userid from dl_cpc.cpc_tag_userid_all group by tag, userid) b
         |on a.userid=b.userid
      """.stripMargin
    println(sqlRequest1)
    val unionlog = spark.sql(sqlRequest1)
    unionlog.createOrReplaceTempView("unionlog_table")

    val sqlRequest2 =
      s"""
         |select
         |  a.searchid,
         |  a.uid,
         |  a.userid,
         |  COALESCE(a.isclick, 0) as isclick,
         |  a.isshow,
         |  COALESCE(a.price, 0) price,
         |  a.tag,
         |  a.interests,
         |  COALESCE(b.label2, 0) as iscvr1,
         |  COALESCE(c.label3, 0) as iscvr2
         |from
         |  unionlog_table as a
         |left join
         |  (select searchid, label2 from dl_cpc.ml_cvr_feature_v1 where date='$date') as b
         |on
         |  a.searchid=b.searchid
         |left join
         |  (select searchid, label as label3 from dl_cpc.ml_cvr_feature_v2 where date='$date') as c
         |on
         |  a.searchid=c.searchid
       """.stripMargin

    println(sqlRequest2)
    val base = spark.sql(sqlRequest2)

    // recalculation with groupby of userid and uid
    base.createOrReplaceTempView("tmpTable")
    //insert into dl_cpc.cpc_profileTag_report_daily partition (`date`='$date')

    val result =
      s"""
         |Select
         |  userid,
         |  tag,
         |  SUM(CASE WHEN isclick == 1 and not interests like '%' + tag + '=100%' then price else 0 end) as costWithoutTag,
         |  SUM(CASE WHEN isclick == 1 and not interests like '%' + tag + '=100%' then 1 else 0 end) as ctrWithoutTag,
         |  SUM(CASE WHEN (iscvr1 == 1 or iscvr2 == 1) and not interests like '%' + tag + '=100%' then 1 else 0 end) as cvrWithoutTag,
         |  SUM(CASE WHEN isshow == 1 and not interests like '%' + tag + '=100%' then 1 else 0 end) as showWithoutTag,
         |  SUM(CASE WHEN isclick == 1 and interests like '%' + tag + '=100%' then price else 0 end) as costWithTag,
         |  SUM(CASE WHEN isclick == 1 and interests like '%' + tag + '=100%' then 1 else 0 end) as ctrWithTag,
         |  SUM(CASE WHEN (iscvr1 == 1 or iscvr2 == 1) and interests like '%' + tag + '=100%'  then 1 else 0 end) as cvrWithTag,
         |  SUM(CASE WHEN isshow == 1 and interests like '%' + tag + '=100%' then 1 else 0 end) as showWithTag
         |FROM tmpTable GROUP BY userid,tag
       """.stripMargin
    val data = spark.sql(result)
    data.show(50)
  }

}