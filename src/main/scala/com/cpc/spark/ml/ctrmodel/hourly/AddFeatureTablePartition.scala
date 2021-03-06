package com.cpc.spark.ml.ctrmodel.hourly

import org.apache.spark.sql.SparkSession

/**
  * Created by roydong on 16/05/2018.
  */
object AddFeatureTablePartition {
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println(
        s"""
           |Usage: SaveFeatures <date=string> <hour=string>
           |
        """.stripMargin
      )
      System.exit(1)
    }

    val dates = args(0).split(" ").toSeq

    val spark = SparkSession.builder()
      .appName("add features partition ")
      .enableHiveSupport()
      .getOrCreate()

    for (date <- dates) {
      for (hour <- 0 to 23) {
        val sql =
          """
            |ALTER TABLE dl_cpc.ml_cvr_feature_v1 add if not exists PARTITION(`date` = "%s", `hour` = "%02d")
            | LOCATION  '/user/cpc/lrmodel/cvrdata_v2/%s/%02d'
          """.stripMargin.format(date, hour, date, hour)

        println(sql)

        spark.sql(sql)
      }

    }
  }
}
