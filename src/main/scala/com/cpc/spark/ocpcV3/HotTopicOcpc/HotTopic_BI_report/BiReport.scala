package com.cpc.spark.ocpcV3.HotTopicOcpc.HotTopic_BI_report

import java.util.Properties
import java.sql.{Connection, DriverManager}

import breeze.numerics.round
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

object BiReport {
  def main(args: Array[String]): Unit ={
    val spark = SparkSession.builder().appName("hottopic_bi_report").enableHiveSupport().getOrCreate()

    val date = args(0)
    val sql1 =
      s"""
         |select
         |  unitid,
         |  usertype,
         |  adclass,
         |  case
         |    when media_appsid = '80002819' then 'hottopic'
         |    else 'qtt'
         |    end as media,
         |  sum(if( isclick = 1, price, 0)) as money,
         |  sum(isshow)  as show_cnt,
         |  sum(isclick) as click_cnt,
         |  dt as `date`
         |from dl_cpc.slim_union_log
         |where dt = '$date'
         |  and adsrc = 1
         |  and userid >0
         |  and isshow = 1
         |  and antispam = 0
         |  and (charge_type is NULL or charge_type = 1)
         |  and media_appsid in ('80000001', '80000002', '80002819')
         |group by
         |  unitid,
         |  usertype,
         |  adclass,
         |  case
         |    when media_appsid = '80002819' then 'hottopic'
         |    else 'qtt'
         |    end,
         |  dt
       """.stripMargin
//    create table dl_cpc.unit_ect_summary_sjq
//    ( unitid    bigint, usertype  bigint, adclass   bigint, media     string, money     bigint, show_cnt  bigint, click_cnt bigint )
//    comment "group by unitid, usertype, adclass, media to sum price|isclick = 1, isshow, isclick"
//    partitioned by (`date` string);
    val tb1 = "dl_cpc.unit_ect_summary_sjq"
    spark.sql(sql1).select( "unitid", "usertype", "adclass", "media", "money", "show_cnt", "click_cnt", "date" ).write.mode("overwrite").insertInto(tb1)

    val sql2 =
      s"""select
         | unitid,
         | --unit_money,
         | unit_money_qtt,
         | unit_money_hottopic,
         | if( unit_money_qtt = 0 and unit_money_hottopic > 0, 1, 0 ) as if_direct
         |from (
         |      select
         |        unitid,
         |        --sum(money) as unit_money,
         |        sum(if(media = 'qtt', money, 0)) as unit_money_qtt,
         |        sum(if(media = 'hottopic', money, 0)) as unit_money_hottopic
         |      from dl_cpc.unit_ect_summary_sjq
         |      where `date` = '$date'
         |      group by unitid
         |      ) a
     """.stripMargin

//    create table dl_cpc.hottopic_unit_ect_summary_sjq
//    ( unitid    bigint,
//      usertype  bigint,
//      adclass   bigint,
//      money     bigint,
//      show_cnt  bigint,
//      click_cnt bigint,
//      unit_money_qtt bigint,
//      unit_money_hottopic bigint,
//      if_direct int)
//    partitioned by (`date` string);
    val tb2 = "dl_cpc.hottopic_unit_ect_summary_sjq"
    val unit_direct = spark.sql(sql2).select("unitid", "unit_money_qtt", "unit_money_hottopic", "if_direct" )
    spark.sql(s"select * from dl_cpc.unit_ect_summary_sjq where `date` = '$date' and media = 'hottopic' ")
        .join(unit_direct, Seq("unitid"))
        .select("unitid", "usertype", "adclass", "money", "show_cnt", "click_cnt", "unit_money_qtt", "unit_money_hottopic", "if_direct", "date")
        .repartition(1).write.mode("overwrite").insertInto(tb2)

    val sql3 =
      s"""
         |select
         |  if_direct                        as direct,
         |  sum( money )                     as money,
         |  10*sum( money )/sum( show_cnt )  as cpm, --单位：元
         |  sum(money)/sum(click_cnt)        as acp, --单位：分
         |  100*sum(click_cnt)/sum(show_cnt) as ctr,  --单位：%
         |  `date`
         |from dl_cpc.hottopic_unit_ect_summary_sjq
         |where `date` = '$date'
         |group by if_direct
       """.stripMargin

    val data0 = spark.sql(sql3)
    val total_money = data0.select("money").rdd.map( x => x.getAs[Long]("money")).reduce(_+_).toDouble
    val data1 = data0.withColumn("money_acount", col("money")/total_money)
      .select("direct", "money", "money_acount", "cpm", "acp", "ctr", "`date`")

    val report_tb1 = "report2.hottopic_direct_summary"
    val deletesql1 = s"delete from report2.hottopic_direct_summary where date = '$date'"
    update(deletesql1)
    insert(data1, report_tb1)



  }

  def update(sql: String): Unit ={
    val conf = ConfigFactory.load()
    val url      = conf.getString("mariadb.report2_write.url")
    val driver   = conf.getString("mariadb.report2_write.driver")
    val username = conf.getString("mariadb.report2_write.user")
    val password = conf.getString("mariadb.report2_write.password")
    var connection: Connection = null
    try{
      Class.forName(driver) //动态加载驱动器
      connection = DriverManager.getConnection(url, username, password)
      val statement = connection.createStatement
      val rs = statement.executeUpdate(sql)
      println(s"execute $sql success!")
    }
    catch{
      case e: Exception => e.printStackTrace
    }
    connection.close  //关闭连接，释放资源
  }

  def insert(data:DataFrame, table: String): Unit ={
    val conf = ConfigFactory.load()
    val mariadb_write_prop = new Properties()

    val url      = conf.getString("mariadb.report2_write.url")
    val driver   = conf.getString("mariadb.report2_write.driver")
    val username = conf.getString("mariadb.report2_write.user")
    val password = conf.getString("mariadb.report2_write.password")

    mariadb_write_prop.put("user", username)
    mariadb_write_prop.put("password", password)
    mariadb_write_prop.put("driver", driver)

    data.write.mode(SaveMode.Append)
      .jdbc(url, table, mariadb_write_prop)
    println(s"insert into $table successfully!")

  }




}








