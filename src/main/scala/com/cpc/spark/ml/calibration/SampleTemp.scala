package com.cpc.spark.ml.calibration

import java.io.File

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, concat_ws}

object SampleTemp {
  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.WARN)
    val date = args(0)
    val spark = SparkSession.builder()
      .appName(s"midu_sample")
      .enableHiveSupport()
      .getOrCreate()
    //穿山甲直投米读
    val sql =
      s"""
         |select a.searchid, cast(raw_cvr/1000000 as double) as raw_cvr, substring(adclass,1,6) as adclass,
         |adslot_id, a.ideaid,exp_cvr,unitid,userid,click_unit_count,conversion_from, hour,a.day,
         |if(c.iscvr is not null,1,0) iscvr,case when siteid = 0 then '外链' when siteid>=5000000 then '赤兔' when siteid>=2000000 then '鲸鱼' else '老建站' end siteid,
         |case
         |  when user_show_ad_num = 0 then '0'
         |  when user_show_ad_num = 1 then '1'
         |  when user_show_ad_num = 2 then '2'
         |  when user_show_ad_num in (3,4) then '4'
         |  when user_show_ad_num in (5,6,7) then '7'
         |  else '8' end as user_show_ad_num
         |from
         |  (select * from
         |  dl_cpc.cvr_calibration_sample_all
         |  where day in ('2019-11-16','2019-11-17')
         |  and media_appsid in ('80000001','80000002')
         |  and cvr_model_name =  "qtt-cvr-dnn-rawid-v1wzjf-ldy"
         |  and is_ocpc = 1) a
         | left join
         | (select distinct searchid,conversion_goal,1 as iscvr
         |  from dl_cpc.ocpc_quick_cv_log
         |  where  `date` in ('2019-11-16','2019-11-17')) c
         |  on a.searchid = c.searchid and a.conversion_goal = c.conversion_goal
             """.stripMargin
    println(sql)
    val data = spark.sql(sql)
      .select("searchid","ideaid","adclass","adslot_id","iscvr","unitid","raw_cvr","user_show_ad_num",
        "exp_cvr","day","userid","conversion_from","click_unit_count","hour","siteid")
    val avgs = data.rdd.map(f => {
      f.mkString("\001")
    })
      .collect()

//    val avgs = data.rdd
//      .map( t=>
//        t(0).toString()+"\001"+t(1).toString())
//      .collect()

    printToFile(new File("/home/cpc/wy/calibration_sample/calibration_sample.csv"),
      "searchid\001ideaid\001adclass\001adslot_id\001iscvr\001unitid\001raw_cvr\001user_show_ad_num\001exp_cvr\001day\001userid\001conversion_from\001click_unit_count\001hour\001siteid") {
      p => avgs.foreach(p.println) // avgs.foreach(p.println)
    }

  }

  def printToFile(f: java.io.File,ColumnName:String)(op: java.io.PrintWriter => Unit)
  {
    val p = new java.io.PrintWriter(f);
    p.write(s"$ColumnName\n")
    try { op(p) }
    finally { p.close() }
  }
}
