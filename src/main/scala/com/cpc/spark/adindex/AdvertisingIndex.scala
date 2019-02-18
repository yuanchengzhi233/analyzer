package com.cpc.spark.adindex

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.spark.sql.SparkSession
import scalaj.http.Http

object AdvertisingIndex {
  def main(args: Array[String]): Unit = {
    val url = "http://192.168.80.229:9090/reqdumps?filename=index.dump&hostname=dumper&fileMd5=1"

    val spark = SparkSession.builder()
      .appName(" ad index table to hive")
      .enableHiveSupport()
      .getOrCreate()


    //获取当前时间
    val cal = Calendar.getInstance()
    val timestamp = (cal.getTimeInMillis / 1000).toInt
    val format = new SimpleDateFormat("yyyy-MM-dd")
    val date = format.format(cal.getTime)
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)

    //    val client = new HttpClient
    //    val method = new GetMethod(url)
    //    val response=client.executeMethod(method)
    //    println("status:" + method.getStatusLine.getStatusCode)
    //
    //    val body = method.getResponseBodyAsString
    //    method.releaseConnection()
    //    val data = body.substring(15)
    val reponse = Http(url)
      .timeout(connTimeoutMs = 2000, readTimeoutMs = 5000)
      .asBytes

    println(reponse.code)
    var data = Array[Byte]()
    if (reponse.code == 200) {
      data = reponse.body.drop(16)
    }

    println(data.length)

    val idxItems = idxinterface.Idx.IdxItems.parseFrom(data)

    val gitemsCount = idxItems.getGitemsCount
    val ditemsCount = idxItems.getDitemsCount
    println("count: " + gitemsCount + ", ditemsCount: " + ditemsCount)


    for(i<-0 until gitemsCount){
      println("groupid: "+idxItems.getGitems(i))
    }


println("---------------------")

    var ideaItemSeq = Seq[Idea]()
    var unitItemSeq = Seq[Group]()
    var idx = Seq[Group]()

    for (i <- 0 until ditemsCount) {
      val dItem = idxItems.getDitems(i) //ideaItem

      val ideaid = dItem.getIdeaid
      val idea = GetItem.getIdea(dItem)
      ideaItemSeq :+= idea
    }

    for (i <- 0 until gitemsCount) {
      val gItems = idxItems.getGitems(i) //groupItem

      val unitid = GetItem.getGroup(gItems)
      unitid.foreach { u =>
        val ideaid = u.ideaid
        unitItemSeq :+= u
      }
    }
    println(unitItemSeq.foreach(x => println(x.unitid)))
    println("unitItemSeq count:  " + unitItemSeq.size, "head:" + unitItemSeq.head)
    println("ideaItemSeq count:  " + ideaItemSeq.size, "head:" + ideaItemSeq.head)


    unitItemSeq.foreach { u =>
      val uIdeaid = u.ideaid
      val unitItem = u
      ideaItemSeq.foreach { i =>
        val iIdeaid = i.ideaid
        val ideaItem = i
        if (uIdeaid == iIdeaid) {
          unitItem.copy(mtype = ideaItem.mtype,
            width = ideaItem.width,
            height = ideaItem.height,
            interaction = ideaItem.interaction,
            `class` = ideaItem.`class`,
            material_level = ideaItem.material_level,
            siteid = ideaItem.siteid,
            white_user_ad_corner = ideaItem.white_user_ad_corner,
            date = date,
            hour = hour.toString,
            minute = minute.toString,
            timestamp = timestamp)
        }
        idx :+= unitItem
      }
    }
    println("idx count:  " + idx.size, "head:" + idx.head)

    val idxRDD = spark.sparkContext.parallelize(idx)
    spark.createDataFrame(idx)
      .repartition(1)
      .write
      .mode("overwrite")
      .parquet(s"hdfs://emr-cluster2/warehouse/dl_cpc.db/cpc_ad_index/date=$date/hour=$hour/minute=$minute")

    spark.sql(
      s"""
         |alter table dl_cpc.cpc_ad_index add if not exists partition(date = "$date",hour="$hour",minute="$minute")
         |location 'hdfs://emr-cluster2/warehouse/dl_cpc.db/cpc_ad_index/date=$date/hour=$hour/minute=$minute'
           """.stripMargin)

    println("done.")

  }


}
