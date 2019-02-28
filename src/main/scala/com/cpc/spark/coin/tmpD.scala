package com.cpc.spark.coin

import com.cpc.spark.tools.CalcMetrics
import org.apache.spark.sql.SparkSession

/**
  * @author Jinbao
  * @date 2019/2/28 17:17
  */
object tmpD {
    def main(args: Array[String]): Unit = {
        val date = "2019-02-28"
        val spark = SparkSession.builder()
          .appName(s"tmpD date = $date")
          .enableHiveSupport()
          .getOrCreate()
        import spark.implicits._

        val tmpDate = date.replace("-","")

        println(tmpDate)

        val unionSql =
            s"""
               |select ideaid,
               |    isshow,
               |    ext_int,
               |    isclick,
               |    if (b.searchid is null,0,1) as label2,
               |    price,
               |    uid,
               |    userid,
               |    ext
               |from
               |    (
               |        select searchid,ideaid,isshow,ext_int,isclick,price,uid,userid,ext
               |        from dl_cpc.cpc_union_log
               |        where `date`='$date' and hour in ('13','14','15')
               |        and media_appsid  in ("80000001", "80000002") and isshow = 1
               |        and ext['antispam'].int_value = 0 and ideaid > 0
               |        and adsrc = 1
               |        and ext['city_level'].int_value != 1
               |        AND (ext["charge_type"] IS NULL OR ext["charge_type"].int_value = 1)
               |        and userid not in (1001028, 1501875)
               |        and adslotid not in ("7774304","7636999","7602943","7783705","7443868","7917491","7868332")
               |        and round(ext["adclass"].int_value/1000) != 132101
               |        and adslot_type in (1,2)
               |    ) a
               |    left outer join
               |    (
               |        select tmp.searchid
               |        from
               |        (
               |            select
               |                final.searchid as searchid,
               |                final.ideaid as ideaid,
               |                case
               |                    when final.src="elds" and final.label_type=6 then 1
               |                    when final.src="feedapp" and final.label_type in (4, 5) then 1
               |                    when final.src="yysc" and final.label_type=12 then 1
               |                    when final.src="wzcp" and final.label_type in (1, 2, 3) then 1
               |                    when final.src="others" and final.label_type=6 then 1
               |                    else 0
               |                end as isreport
               |            from
               |            (
               |                select
               |                    searchid, media_appsid, uid,
               |                    planid, unitid, ideaid, adclass,
               |                    case
               |                        when (adclass like '134%' or adclass like '107%') then "elds"
               |                        when (adslot_type<>7 and adclass like '100%') then "feedapp"
               |                        when (adslot_type=7 and adclass like '100%') then "yysc"
               |                        when adclass in (110110100, 125100100) then "wzcp"
               |                        else "others"
               |                    end as src,
               |                    label_type
               |                from
               |                    dl_cpc.ml_cvr_feature_v1
               |                where
               |                    `date`='$date'
               |                    and label2=1
               |                    and media_appsid in ("80000001", "80000002")
               |                ) final
               |            ) tmp
               |        where tmp.isreport=1
               |    ) b
               |    on a.searchid = b.searchid
             """.stripMargin

        val union = spark.sql(unionSql)
        //保存到临时表里
        //union.write.mode("overwrite").saveAsTable("test.union")

        union.createOrReplaceTempView("union")

        val useridAucListSql =
            s"""
               |select cast(userid as string) as userid,ext['exp_cvr'].int_value as score,label2 as label
               |from union
             """.stripMargin

        val useridAucList = spark.sql(useridAucListSql)

        val uAuc = CalcMetrics.getGauc(spark,useridAucList,"userid")
          .select("name","auc")

        val testTable = s"test.uauc_$tmpDate"

        uAuc.write.mode("overwrite").saveAsTable(testTable)

        val useridSql =
            s"""
               |select
               |    c.userid as userid,
               |    show_num,
               |    coin_show_num,
               |    if (show_num!=0,round(coin_show_num/show_num, 6),0) as coin_show_rate,
               |    click_num,
               |    coin_click_num,
               |    if (click_num!=0,round(coin_click_num/click_num, 6),0) as coin_click_rate,
               |    if (show_num!=0,round(click_num/show_num, 6),0) as ctr,
               |    if (coin_show_num!=0,round(coin_click_num/coin_show_num, 6),0) as coin_ctr,
               |    convert_num,
               |    coin_convert_num,
               |    if (convert_num!=0,round(coin_convert_num/convert_num,6),0) as coin_convert_rate,
               |    if (click_num!=0,round(convert_num/click_num, 6),0) as cvr,
               |    if (coin_click_num!=0,round(coin_convert_num/coin_click_num, 6),0) as coin_cvr,
               |    click_total_price,
               |    coin_click_total_price,
               |    uid_num,
               |    if (show_num!=0,round(click_total_price*10/show_num,6),0) as cpm,
               |    if (click_num!=0,round(click_total_price*10/click_num,6),0) as acp,
               |    if (uid_num!=0,round(click_total_price*10/uid_num,6),0) as arpu,
               |    if (uid_num!=0,round(show_num/uid_num,6),0) as aspu,
               |    if (uid_num!=0,round(convert_num*100/uid_num,6),0) as acpu,
               |    '$date' as `date`
               |from
               |(
               |    select userid,
               |        sum(isshow) as show_num, --展示数
               |        sum(if (isshow=1 and ext_int['is_auto_coin'] = 1, 1, 0)) as coin_show_num, --金币展示数
               |        sum(isclick) as click_num, --点击数
               |        sum(if (isclick=1 and ext_int['is_auto_coin'] = 1, 1, 0)) as coin_click_num, --金币点击数
               |        sum(case when label2 = 1 then 1 else 0 end) as convert_num, --转化数
               |        sum(case when label2 = 1 and ext_int['is_auto_coin'] = 1 then 1 else 0 end) as coin_convert_num, --金币样式转化数
               |        sum(case WHEN isclick = 1 then price else 0 end) as click_total_price, --点击总价
               |        sum(case WHEN isclick = 1 and ext_int['is_auto_coin'] = 1 then price else 0 end) as coin_click_total_price, --金币点击总价
               |        count(distinct uid) as uid_num --用户数
               |    from union
               |    group by userid
               |) c
             """.stripMargin

        println(useridSql)

        val useridOtherMetrics = spark.sql(useridSql)

        val testTable2 = s"test.userid_other_$tmpDate"

        useridOtherMetrics.write.mode("overwrite").saveAsTable(testTable2)

        val resultSql =
            s"""
               |select a.userid as userid,
               |  show_num,
               |  coin_show_num,
               |  coin_show_rate,
               |  click_num,
               |  coin_click_num,
               |  coin_click_rate,
               |  ctr,
               |  coin_ctr,
               |  convert_num,
               |  coin_convert_num,
               |  coin_convert_rate,
               |  cvr,
               |  coin_cvr,
               |  click_total_price,
               |  coin_click_total_price,
               |  uid_num,
               |  cpm,
               |  acp,
               |  arpu,
               |  aspu,
               |  acpu,
               |  b.auc as auc,
               |  '$date' as `date`
               |from $testTable2 a left outer join
               |(
               |  select cast(name as bigint) userid,
               |    auc
               |  from $testTable
               |) b
               |on a.userid = b.userid
             """.stripMargin

        val result = spark.sql(resultSql)

        result.repartition(1)
          .write
          .mode("overwrite")
          .saveAsTable("test.tmp_20190228")

        val delSql1 = s"drop table if exists $testTable2"
        spark.sql(delSql1)
        println(s"drop table $testTable2 success!")
    }
}
