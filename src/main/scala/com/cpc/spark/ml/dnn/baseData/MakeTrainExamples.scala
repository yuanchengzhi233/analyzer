package com.cpc.spark.ml.dnn.baseData

import java.io.{File, PrintWriter}

import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.types.{StructField, _}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.sys.process._
import scala.util.Random
import org.apache.spark.util.LongAccumulator

import scala.collection.mutable.ArrayBuffer
import java.text.SimpleDateFormat

import org.apache.commons.lang3.time.DateUtils
import org.apache.commons.lang3.time.DateFormatUtils
import java.util.Date
import java.text.DateFormat

import scala.collection.mutable
import org.apache.commons.lang.StringUtils

/**
  * 解析tfrecord到hdfs并统计区间sparse feature出现的值和做映射以及负采样
  * created time : 2019/07/13 10:38
  * @author fenghuabin
  * @version 1.0
  *
  */

object MakeTrainExamples {



  def delete_hdfs_path(path: String): Unit = {

    val conf = new org.apache.hadoop.conf.Configuration()
    val p = new org.apache.hadoop.fs.Path(path)
    val hdfs = p.getFileSystem(conf)
    val hdfs_path = new org.apache.hadoop.fs.Path(path.toString)

    //val hdfs_path = new org.apache.hadoop.fs.Path(path.toString)
    //val hdfs = org.apache.hadoop.fs.FileSystem.get(new org.apache.hadoop.conf.Configuration())
    if (hdfs.exists(hdfs_path)) {
      hdfs.delete(hdfs_path, true)
    }
  }

  def exists_hdfs_path(path: String): Boolean = {

    val conf = new org.apache.hadoop.conf.Configuration()
    val p = new org.apache.hadoop.fs.Path(path)
    val hdfs = p.getFileSystem(conf)
    val hdfs_path = new org.apache.hadoop.fs.Path(path.toString)
    //val hdfs = org.apache.hadoop.fs.FileSystem.get(new org.apache.hadoop.conf.Configuration())

    if (hdfs.exists(hdfs_path)) {
      true
    } else {
      false
    }
  }

  def writeNum2File(file: String, num: Long): Unit = {
    val writer = new PrintWriter(new File(file))
    writer.write(num.toString)
    writer.close()
  }

  def formatDate(date: Date, pattern: String="yyyy-MM-dd"): String = {
    val formatDate = DateFormatUtils.format(date, pattern)
    formatDate
  }

  def GetDataRangeWithWeek(beginStr: String, endStr: String, format : String = "yyyy-MM-dd"): ArrayBuffer[String] = {
    val ranges = ArrayBuffer[String]()
    val sdf = new SimpleDateFormat(format)
    var dateBegin = sdf.parse(beginStr)
    val dateEnd = sdf.parse(endStr)
    while (dateBegin.compareTo(dateEnd) <= 0) {
      ranges += sdf.format(dateBegin) + ";" + DateFormatUtils.format(dateBegin, "E")
      dateBegin = DateUtils.addDays(dateBegin, 1)
    }
    ranges
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 12) {
      System.err.println(
        """
          |you have to input 12 parameters !!!
        """.stripMargin)
      System.exit(1)
    }
    //val Array(src, des_dir, des_date, des_map_prefix, numPartitions) = args
    val Array(one_hot_feature_names, ctr_feature_dir, src_dir, with_week, date_begin, date_end, des_dir, instances_file, test_data_src, test_data_des, test_data_week, numPartitions) = args

    println(args)

    Logger.getRootLogger.setLevel(Level.WARN)

    val sparkConf = new SparkConf()
    sparkConf.set("spark.driver.maxResultSize", "5g")
    val spark = SparkSession.builder().config(sparkConf).enableHiveSupport().getOrCreate()
    val sc = spark.sparkContext

    //val src_date_list = src_date_str.split(";")
    val src_date_list = ArrayBuffer[String]()
    val src_week_list = ArrayBuffer[String]()
    val src_date_list_with_week = GetDataRangeWithWeek(date_begin, date_end)
    for (pair <- src_date_list_with_week) {
      src_date_list += pair.split(";")(0)
      src_week_list += pair.split(";")(1)
    }
    println("src_date_list:" + src_date_list.mkString(";"))
    println("src_week_list:" + src_week_list.mkString(";"))
    println("src_date_list_with_week:" + src_date_list_with_week.mkString("|"))


    /************make text examples************************/
    println("Make text examples")
    for (date_idx <- src_date_list.indices) {
      val src_date = src_date_list(date_idx)
      val src_week = src_week_list(date_idx)
      val curr_file_src = src_dir + "/" + src_date
      val tf_text = des_dir + "/" + src_date + "-text"
      if (!exists_hdfs_path(tf_text) && exists_hdfs_path(curr_file_src)) {
        val curr_file_src_collect = src_dir + "/" + src_date + "/part-r-*"
        println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
        val importedDf: DataFrame = spark.read.format("tfrecords").option("recordType", "Example").load(curr_file_src_collect)
        println("DF file count:" + importedDf.count().toString + " of file:" + curr_file_src_collect)
        importedDf.printSchema()
        importedDf.show(3)

        importedDf.rdd.map(
          rs => {
            val idx2 = rs.getSeq[Long](0)
            val idx1 = rs.getSeq[Long](1)
            val idx_arr = rs.getSeq[Long](2)
            val idx0 = rs.getSeq[Long](3)
            val sample_idx = rs.getLong(4)
            val label_arr = rs.getSeq[Long](5)
            val dense = rs.getSeq[Long](6)

            var dense_str: Seq[String] = null
            if (with_week == "True") {
              dense_str = dense.map(_.toString) ++ Seq[String](src_week)
            } else {
              dense_str = dense.map(_.toString)
            }

            var label = "0.0"
            if (label_arr.head == 1L) {
              label = "1.0"
            }

            val output = scala.collection.mutable.ArrayBuffer[String]()
            output += sample_idx.toString
            output += label
            output += label_arr.map(_.toString).mkString(";")
            output += dense_str.mkString(";")
            output += idx0.map(_.toString).mkString(";")
            output += idx1.map(_.toString).mkString(";")
            output += idx2.map(_.toString).mkString(";")
            output += idx_arr.map(_.toString).mkString(";")

            output.mkString("\t")
          }
        ).saveAsTextFile(tf_text)
      }
    }
    println("Done.......")

    val dense_list = one_hot_feature_names.split(",")
    if (dense_list.length != 28) {
      println("mismatched, count_one_hot:28, dense_list.length:" + dense_list.length.toString)
      System.exit(1)
    }
    val dense_list_bc = sc.broadcast(dense_list)


    /************Collect instances for non uid features************************/
    println("Collect Other Feature(exclude uid) Values and Map to Continuous Index")
    val instances_all_non_uid = des_dir + "/" + instances_file + "-non-uid"
    val instances_all_non_uid_indexed = des_dir + "/" + instances_file + "-non-uid-indexed"
    if (!exists_hdfs_path(instances_all_non_uid_indexed)) {
      var data = sc.parallelize(Array[(String, Long)]())
      for (date_idx <- src_date_list.indices) {
        val src_date = src_date_list(date_idx)
        val tf_text = des_dir + "/" + src_date + "-text"
        if (exists_hdfs_path(tf_text)) {
          data = data.union(
            sc.textFile(tf_text).map(
              rs => {
                val line_list = rs.split("\t")
                val dense = line_list(3).split(";")
                val idx_arr = line_list(7).split(";")
                val output = ArrayBuffer[String]()
                for (idx <- dense.indices) {
                  if (idx != 25) {
                    output += dense(idx)
                  }
                }
                for (idx <- idx_arr.indices) {
                  output += idx_arr(idx)
                }
                output.mkString("\t")
              }
            ).flatMap(
              rs => {
                val line = rs.split("\t")
                for (elem <- line)
                  yield (elem, 1L)
              }
            ).reduceByKey(_ + _)
          ).reduceByKey(_ + _)
        }
      }
      data.reduceByKey(_ + _).repartition(1).sortBy(_._2 * -1).map {
        case (key, value) =>
          key + "\t" + value.toString
      }.saveAsTextFile(instances_all_non_uid)

      val acc = new LongAccumulator
      spark.sparkContext.register(acc)
      sc.textFile(instances_all_non_uid).coalesce(1, shuffle = false).map{
        rs => {
          acc.add(1L)
          val line = rs.split("\t")
          val key = line(0)
          (key, acc.count)
        }
      }.repartition(1).sortBy(_._2).map{
        case (key, value) => key + "\t" + value.toString
      }.saveAsTextFile(instances_all_non_uid_indexed)
    }
    println("Done.......")

    /************Collect instances for non uid features************************/
    println("Collect Uid Feature's Values and Map to Continuous Index")
    val instances_all_for_uid = des_dir + "/" + instances_file + "-for-uid"
    val instances_all_for_uid_indexed = des_dir + "/" + instances_file + "-for-uid-indexed"
    if (!exists_hdfs_path(instances_all_for_uid_indexed)) {
      var data = sc.parallelize(Array[(String, Long)]())
      for (date_idx <- src_date_list.indices) {
        val src_date = src_date_list(date_idx)
        val tf_text = des_dir + "/" + src_date + "-text"
        if (exists_hdfs_path(tf_text)) {
          data = data.union(
            sc.textFile(tf_text).map(
              rs => {
                val line_list = rs.split("\t")
                val dense = line_list(3).split(";")
                (dense(25), 1L)
              }
            ).reduceByKey(_ + _)
          ).reduceByKey(_ + _)
        }
      }
      data.reduceByKey(_ + _).repartition(1).sortBy(_._2 * -1).map {
        case (key, value) =>
          key + "\t" + value.toString
      }.saveAsTextFile(instances_all_for_uid)

      val acc = new LongAccumulator
      spark.sparkContext.register(acc)
      sc.textFile(instances_all_for_uid).coalesce(1, false).map{
        rs => {
          acc.add(1L)
          val line = rs.split("\t")
          val key = line(0)
          (key, acc.count)
        }
      }.repartition(1).sortBy(_._2).map{
        case (key, value) => key + "\t" + value.toString
      }.saveAsTextFile(instances_all_for_uid_indexed)
    }
    println("Done.......")


    /************************load map********************************/
    println("Load Uid SparseMap")
    val instances_all_map_uid = des_dir + "/" + instances_file + "-for-uid-indexed"
    val sparseMapUid = sc.textFile(instances_all_map_uid).map{
      rs => {
        val line = rs.split("\t")
        val field = line(0).toLong
        val key = (line(1).toLong - 1L).toString
        (field, key)
      }
    }
    println("sparseMapUid.size=" + sparseMapUid.count)
    val sparse_map_uid_count = sparseMapUid.count

    println("Load Others SparseMap")
    val instances_all_map_others = des_dir + "/" + instances_file + "-non-uid-indexed"
    val sparseMapOthers = sc.textFile(instances_all_map_others).map{
      rs => {
        val line = rs.split("\t")
        val field = line(0)
        val key = (line(1).toLong - 1L).toString
        (field, key)
      }
    }.collectAsMap()
    println("sparseMapOthers.size=" + sparseMapOthers.size)
    val sparse_map_others_count = sparseMapOthers.size

    /************check sid************************/
    //println("Check Sample Index")
    //for (date_idx <- src_date_list.indices) {
    //  val src_date = src_date_list(date_idx)
    //  val src_week = src_week_list(date_idx)
    //  val tf_text = des_dir + "/" + src_date + "-text"
    //  if (exists_hdfs_path(tf_text)) {
    //    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    //    println("date:" + src_date)
    //    val rdd = sc.textFile(tf_text).map(
    //      f = rs => {
    //        val line_list = rs.split("\t")
    //        (line_list(0), 1)
    //      }
    //    )
    //    println("text lines:" + rdd.count.toString)
    //    val reduce_lines = rdd.reduceByKey(_ + _).count
    //    println("reduced lines:" + reduce_lines)
    //  }
    //}
    //println("Done.......")

    val schema_new = StructType(List(
      StructField("sample_idx", LongType, nullable = true),
      StructField("label_single", FloatType, nullable = true),
      StructField("label", ArrayType(LongType, containsNull = true)),
      StructField("dense", ArrayType(LongType, containsNull = true)),
      StructField("idx0", ArrayType(LongType, containsNull = true)),
      StructField("idx1", ArrayType(LongType, containsNull = true)),
      StructField("idx2", ArrayType(LongType, containsNull = true)),
      StructField("id_arr", ArrayType(LongType, containsNull = true))
    ))


    println("Do Mapping Features")
    for (date_idx <- src_date_list.indices) {
      val src_date = src_date_list(date_idx)
      val src_week = src_week_list(date_idx)
      val tf_text_sampled = des_dir + "/" + src_date + "-text-sampled"
      println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
      println("mapping sampled text file:" + tf_text_sampled)
      val mapping_info = des_dir + "/mapping-info/" + src_date + "-mapping-info"
      if (!exists_hdfs_path(mapping_info + "/_SUCCESS") && exists_hdfs_path(tf_text_sampled)) {
        delete_hdfs_path(mapping_info)
        println("make " + mapping_info)
        sc.textFile(tf_text_sampled).map(
          rs => {
            val line_list = rs.split("\t")
            val sid = line_list(0)
            val dense = line_list(3).split(";")
            val idx_arr = line_list(7).split(";")

            val uid_value = dense(25)
            val value_list_prefix = dense.slice(0, 25)
            val value_list_tail = dense.slice(26, 28)

            val mapped_prefix = value_list_prefix.map(x => sparseMapOthers.getOrElse(x, "-1"))
            val mapped_tail = value_list_tail.map(x => sparseMapOthers.getOrElse(x, "-1"))
            val mapped_multi_hot = idx_arr.map(x => sparseMapOthers.getOrElse(x, "-1"))

            (uid_value.toLong, (sid, mapped_prefix.mkString(";"), mapped_tail.mkString(";"), mapped_multi_hot.mkString(";")))
          }
        ).join(sparseMapUid).map(
          rs => {
            val sid = rs._2._1._1
            val mapped_prefix = rs._2._1._2
            val mapped_tail = rs._2._1._3
            val mapped_multi_hot = rs._2._1._4
            val mapped_uid = rs._2._2.toLong + sparseMapOthers.size
            sid + "\t" + mapped_prefix + ";" + mapped_uid + ";" + mapped_tail + "\t" + mapped_multi_hot
          }
        ).saveAsTextFile(mapping_info)

      }

      val tf_text_sampled_mapped = des_dir + "/" + src_date + "-text-sampled-mapped"
      val tf_text_sampled_mapped_tfr = des_dir + "/" + src_date + "-text-sampled-mapped-tfr"
      if (!exists_hdfs_path(tf_text_sampled_mapped_tfr + "/_SUCCESS") && exists_hdfs_path(mapping_info) && exists_hdfs_path(tf_text_sampled)) {
        delete_hdfs_path(tf_text_sampled_mapped)
        delete_hdfs_path(tf_text_sampled_mapped_tfr)
        println("make mapped files:" + tf_text_sampled_mapped)
        val mapping_info_rdd = sc.textFile(mapping_info).map({
          rs =>
            val line_list = rs.split("\t")
            (line_list(0), (line_list(1), line_list(2)))
        })

        val ult_rdd = sc.textFile(tf_text_sampled).map(
          rs => {
            val line_list = rs.split("\t")
            val sid = line_list(0)
            val label = line_list(1)
            val label_arr = line_list(2)
            val idx0 = line_list(4)
            val idx1 = line_list(5)
            val idx2 = line_list(6)

            (sid, (label, label_arr, idx0, idx1, idx2))
          }
        ).join(mapping_info_rdd).map(
          rs => {
            val sid = rs._1
            val label = rs._2._1._1
            val label_arr = rs._2._1._2
            val idx0 = rs._2._1._3
            val idx1 = rs._2._1._4
            val idx2 = rs._2._1._5
            val dense = rs._2._2._1
            val idx_arr = rs._2._2._2
            Array[String](sid, label, label_arr, dense, idx0, idx1, idx2, idx_arr)
          }
        )

        val tf_ult_rdd = ult_rdd.map({
          rs_list =>
            val sample_idx = rs_list(0).toLong
            val label = rs_list(1).toFloat
            val label_arr = rs_list(2).split(";").map(_.toLong).toSeq
            val dense = rs_list(3).split(";").map(_.toLong).toSeq
            val idx0 = rs_list(4).split(";").map(_.toLong).toSeq
            val idx1 = rs_list(5).split(";").map(_.toLong).toSeq
            val idx2 = rs_list(6).split(";").map(_.toLong).toSeq
            val idx_arr = rs_list(7).split(";").map(_.toLong).toSeq
            Row(sample_idx, label, label_arr, dense, idx0, idx1, idx2, idx_arr)
        })

        ult_rdd.map({rs=>rs.mkString("\t")}).repartition(100).saveAsTextFile(tf_text_sampled_mapped)

        val tf_ult_rdd_count = tf_ult_rdd.count
        println(s"tf_ult_rdd_count is : $tf_ult_rdd_count")

        val text_df: DataFrame = spark.createDataFrame(tf_ult_rdd, schema_new)
        text_df.repartition(100).write.format("tfrecords").option("recordType", "Example").save(tf_text_sampled_mapped_tfr)

        val fileName = "count_" + Random.nextInt(100000)
        writeNum2File(fileName, tf_ult_rdd_count)
        s"hadoop fs -put $fileName $tf_text_sampled_mapped_tfr/count" !
      }
    }
    println("Done.......")




    /**val dense_list_mapped = one_hot_feature_names_mapped.split(",")
    if (dense_list_mapped.length != 28) {
      println("mismatched, count_one_hot:28, dense_list_mapped.length:" + dense_list_mapped.length.toString)
      System.exit(1)
    }

    name_idx_map.clear()
    for (idx <- dense_list_mapped.indices) {
      name_idx_map += (dense_list_mapped(idx) -> idx)
    }
    name_idx_map_bc = sc.broadcast(name_idx_map)


    /************down sampling************************/
    println("Down Sampling")
    val negativeSampleRatio = 0.19
    for (date_idx <- src_date_list.indices) {
      val src_date = src_date_list(date_idx)
      val src_week = src_week_list(date_idx)
      println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
      val tf_text_mapped = des_dir + "/" + src_date + "-text-mapped"
      val tf_text_mapped_tf = des_dir + "/" + src_date + "-text-mapped-tf"
      val tf_text_mapped_sampled = des_dir + "/" + src_date + "-text-mapped-sampled"
      val tf_text_mapped_sampled_tf = des_dir + "/" + src_date + "-text-mapped-tf-sampled"
      if (exists_hdfs_path(tf_text_mapped) && (!exists_hdfs_path(tf_text_mapped_sampled_tf))) {
        println("tf_text_mapped:" + tf_text_mapped)
        println("tf_text_mapped_sampled_tf:" + tf_text_mapped_sampled_tf)
        delete_hdfs_path(tf_text_mapped_tf)
        delete_hdfs_path(tf_text_mapped_sampled)
        delete_hdfs_path(tf_text_mapped_sampled_tf)
        val tf_text_mapped_collect = tf_text_mapped + "/part*"
        println("now load data frame:" + tf_text_mapped_collect)
        val text_rdd = sc.textFile(tf_text_mapped_collect).map({
            rs =>
              val rs_list = rs.split("\t")
              val sample_idx = rs_list(0).toLong
              val label = rs_list(1).toFloat
              val label_arr = rs_list(2).split(";").map(_.toLong).toSeq
              val dense = rs_list(3).split(";").map(_.toLong).toSeq
              val idx0 = rs_list(4).split(";").map(_.toLong).toSeq
              val idx1 = rs_list(5).split(";").map(_.toLong).toSeq
              val idx2 = rs_list(6).split(";").map(_.toLong).toSeq
              val idx_arr = rs_list(7).split(";").map(_.toLong).toSeq
              Row(sample_idx, label, label_arr, dense, idx0, idx1, idx2, idx_arr)
        })

        val text_rdd_count = text_rdd.count
        println(s"text_rdd_count is : $text_rdd_count")

        val text_df: DataFrame = spark.createDataFrame(text_rdd, schema_new)
        text_df.repartition(500).write.format("tfrecords").option("recordType", "Example").save(tf_text_mapped_tf)

        //保存count文件
        //val text_df_count = text_df.count()
        //println(s"text_df_count is : $text_df_count")
        var fileName = "count_" + Random.nextInt(100000)
        writeNum2File(fileName, text_rdd_count)
        s"hadoop fs -put $fileName $tf_text_mapped_tf/count" !

        val sampled_rdd = text_rdd.filter(
          rs => {
            val label = rs.getFloat(1)
            var filter = false
            if (label > 0.0 || Random.nextFloat() < math.abs(negativeSampleRatio)) {
              filter = true
            }
            filter
          }
        )

        val sampled_rdd_count = sampled_rdd.count
        println(s"sampled_rdd_count is : $sampled_rdd_count")

        //Save DataFrame as TFRecords
        val sampled_df: DataFrame = spark.createDataFrame(sampled_rdd, schema_new)
        sampled_df.repartition(100).write.format("tfrecords").option("recordType", "Example").save(tf_text_mapped_sampled_tf)

        //保存count文件
        val sampled_df_count = sampled_df.count()
        println(s"sampled_df_count is : $sampled_df_count")
        fileName = "count_" + Random.nextInt(100000)
        writeNum2File(fileName, sampled_df_count)
        s"hadoop fs -put $fileName $tf_text_mapped_sampled_tf/count" !
      }

      val tf_ctr_feature = ctr_feature_dir + "/" + src_date
      val tf_float = des_dir + "/" + src_date + "-text-mapped-tf-sampled-float-full"
      if (exists_hdfs_path(tf_text_mapped_sampled_tf) && exists_hdfs_path(tf_ctr_feature)) {
        println("exit ctr_feature_file:" + tf_ctr_feature)
        if (!exists_hdfs_path(tf_float + "/_SUCCESS")) {
          s"hadoop fs -rm -r $tf_float" !

          println("Load Ctr Feature Map:" + tf_ctr_feature)
          val ctrMap = sc.textFile(tf_ctr_feature).map(
            rs => {
              val line = rs.split("\t")

              val name = line(0)
              var value = line(1)
              if (value.split("x").length == 2) {
                val value1 = sparseMapOthers.getOrElse(value.split("x")(0), "-1")
                val value2 = sparseMapOthers.getOrElse(value.split("x")(1), "-1")
                (name + "\t" + value1 + "x" + value2, line(4))
              } else {
                (name + "\t" + sparseMapOthers.getOrElse(value, "-1"), line(4))
              }
            }
          ).collectAsMap()
          println("ctrMap.size=" + ctrMap.size)

          val importedDf: DataFrame = spark.read.format("tfrecords").option("recordType", "Example").load(tf_text_mapped_sampled_tf + "/part*")
          //println("DF file count:" + importedDf.count().toString + " of file:" + tf_text_mapped_sampled_tf + "/part*")
          //importedDf.printSchema()
          //importedDf.show(3)

          val float_rdd = importedDf.rdd.map(
            rs => {
              val idx2 = rs.getSeq[Long](0)
              val idx1 = rs.getSeq[Long](1)
              val label = rs.getFloat(2)
              val idx_arr = rs.getSeq[Long](3)
              val idx0 = rs.getSeq[Long](4)
              val sample_idx = rs.getLong(5)
              val label_arr = rs.getSeq[Long](6)
              val dense = rs.getSeq[Long](7)

              val float_list = scala.collection.mutable.ArrayBuffer[String]()
              for (name <- cross_features_list_bc.value) {
                val idx = name_idx_map_bc.value(name)
                val key = name + "\t" + dense(idx).toString
                float_list += ctrMap.getOrElse(key, "0.0")
              }

              for (name_pair <- cross_features_list_2_bc.value) {
                val idx1 = name_idx_map_bc.value(name_pair._1)
                val idx2 = name_idx_map_bc.value(name_pair._2)
                val key = name_pair._1 + "x" + name_pair._2 + "\t" + dense(idx1) + "x" + dense(idx2)
                float_list += ctrMap.getOrElse(key, "0.0")
              }
              Row(sample_idx, float_list.map(_.toFloat), label, label_arr, dense, idx0, idx1, idx2, idx_arr)
            }
          )

          val float_df: DataFrame = spark.createDataFrame(float_rdd, schema_with_float)
          float_df.repartition(500).write.format("tfrecords").option("recordType", "Example").save(tf_float)
          s"hadoop fs -cp $tf_text_mapped_sampled_tf/count $tf_float/count" !

        }
      }
    }
    println("Done.......")

    println("Do Mapping Test Examples' features")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    val test_file_src = src_dir + "/" + test_data_src
    val test_file_text_mapped = des_dir + "/" + test_data_des + "-text-mapped"
    if (!exists_hdfs_path(test_file_text_mapped)) {
      val importedDf: DataFrame = spark.read.format("tfrecords").option("recordType", "Example").load(test_file_src)
      println("DF file count:" + importedDf.count().toString + " of file:" + test_file_src)
      val mapped_rdd = importedDf.rdd.map(
        rs => {
          val idx2 = rs.getSeq[Long](0)
          val idx1 = rs.getSeq[Long](1)
          val idx_arr = rs.getSeq[Long](2)
          val idx0 = rs.getSeq[Long](3)
          val sample_idx = rs.getLong(4)
          val label_arr = rs.getSeq[Long](5)
          val dense = rs.getSeq[Long](6)
          var label = "0.0"
          if (label_arr.head == 1L) {
            label = "1.0"
          }

          var dense_str: Seq[String] = null
          if (with_week == "True") {
            dense_str = dense.map(_.toString) ++ Seq[String](test_data_week)
          } else {
            dense_str = dense.map(_.toString)
          }


          val output = scala.collection.mutable.ArrayBuffer[String]()
          output += sample_idx.toString
          output += label
          output += label_arr.map(_.toString).mkString(";")
          output += dense_str.mkString(";")
          output += idx0.map(_.toString).mkString(";")
          output += idx1.map(_.toString).mkString(";")
          output += idx2.map(_.toString).mkString(";")
          output += idx_arr.map(_.toString).mkString(";")
          output
        }
      ).map(
        line_list => {
          val sid = line_list(0)
          val label = line_list(1)
          val label_arr = line_list(2)
          val dense = line_list(3).split(";")
          val idx0 = line_list(4)
          val idx1 = line_list(5)
          val idx2 = line_list(6)
          val idx_arr = line_list(7).split(";")

          val uid_value = dense(25)

          var value_list_one_hot: Array[String] = null
          if (with_week == "True") {
            value_list_one_hot = dense.slice(0, 25) ++ dense.slice(26, 29)
          } else {
            value_list_one_hot = dense.slice(0, 25) ++ dense.slice(26, 28)
          }

          val mapped_one_hot = value_list_one_hot.map(x => sparseMapOthers.getOrElse(x, "-1"))
          val mapped_multi_hot = idx_arr.map(x => sparseMapOthers.getOrElse(x, "-1"))

          (uid_value.toLong, (sid, mapped_one_hot.mkString(";"), mapped_multi_hot.mkString(";"), label, label_arr, idx0, idx1, idx2))
        }).join(sparseMapUid).map(
        rs => {
          val sid = rs._2._1._1
          val mapped_one_hot = rs._2._1._2
          val mapped_mul_hot = rs._2._1._3
          val label = rs._2._1._4
          val label_arr = rs._2._1._5
          val idx0 = rs._2._1._6
          val idx1 = rs._2._1._7
          val idx2 = rs._2._1._8
          val mapped_uid = rs._2._2.toLong + sparseMapOthers.size

          val ult_list:Array[String] = new Array[String](8)
          ult_list(0) = sid
          ult_list(1) = label
          ult_list(2) = label_arr
          ult_list(3) = mapped_uid + ";" + mapped_one_hot
          ult_list(4) = idx0
          ult_list(5) = idx1
          ult_list(6) = idx2
          ult_list(7) = mapped_mul_hot
          ult_list.mkString("\t")
        }
      )
      val mapped_rdd_count = mapped_rdd.count
      println(s"mapped_rdd_count : $mapped_rdd_count")
      mapped_rdd.repartition(60).saveAsTextFile(test_file_text_mapped)
    }

    val test_file_text_mapped_tf = des_dir + "/" + test_data_des + "-text-mapped-tf"
    if (!exists_hdfs_path(test_file_text_mapped_tf) && exists_hdfs_path(test_file_text_mapped)) {
      val test_text_rdd = sc.textFile(test_file_text_mapped).map({
        rs =>
          val rs_list = rs.split("\t")
          val sample_idx = rs_list(0).toLong
          val label = rs_list(1).toFloat
          val label_arr = rs_list(2).split(";").map(_.toLong).toSeq
          val dense = rs_list(3).split(";").map(_.toLong).toSeq
          val idx0 = rs_list(4).split(";").map(_.toLong).toSeq
          val idx1 = rs_list(5).split(";").map(_.toLong).toSeq
          val idx2 = rs_list(6).split(";").map(_.toLong).toSeq
          val idx_arr = rs_list(7).split(";").map(_.toLong).toSeq
          Row(sample_idx, label, label_arr, dense, idx0, idx1, idx2, idx_arr)
      })

      val test_text_rdd_count = test_text_rdd.count
      println(s"test_text_rdd_count is : $test_text_rdd_count")

      val test_text_df: DataFrame = spark.createDataFrame(test_text_rdd, schema_new)
      test_text_df.repartition(60).write.format("tfrecords").option("recordType", "Example").save(test_file_text_mapped_tf)
    }

    val test_file_text_mapped_float_tf = des_dir + "/" + test_data_des + "-text-mapped-float-tf-full"
    val test_file_text_mapped_float = des_dir + "/" + test_data_des + "-text-mapped-float-full"
    if (!exists_hdfs_path(test_file_text_mapped_float_tf) && exists_hdfs_path(test_file_text_mapped)) {
      val tf_ctr_feature = ctr_feature_dir + "/" + test_data_src.split("/")(0)
      println("exit ctr_feature_file:" + tf_ctr_feature)
      if (!exists_hdfs_path(test_file_text_mapped_float_tf + "/_SUCCESS")) {
        s"hadoop fs -rm -r $test_file_text_mapped_float_tf" !

        println("Load Ctr Feature Map:" + tf_ctr_feature)
        val ctrMap = sc.textFile(tf_ctr_feature).map(
          rs => {
            val line = rs.split("\t")

            val name = line(0)
            var value = line(1)
            if (value.split("x").length == 2) {
              val value1 = sparseMapOthers.getOrElse(value.split("x")(0), "-1")
              val value2 = sparseMapOthers.getOrElse(value.split("x")(1), "-1")
              (name + "\t" + value1 + "x" + value2, line(4))
            } else {
              (name + "\t" + sparseMapOthers.getOrElse(value, "-1"), line(4))
            }
          }
        ).collectAsMap()
        println("ctrMap.size=" + ctrMap.size)

        //ctrMap.foreach{case (e,i) => println(e,i)}

        val test_text_float_rdd = sc.textFile(test_file_text_mapped).map({
          rs =>
            val rs_list = rs.split("\t")
            val sample_idx = rs_list(0).toLong
            val label = rs_list(1).toFloat
            val label_arr = rs_list(2).split(";").map(_.toLong).toSeq
            val dense = rs_list(3).split(";").map(_.toLong).toSeq
            val idx0 = rs_list(4).split(";").map(_.toLong).toSeq
            val idx1 = rs_list(5).split(";").map(_.toLong).toSeq
            val idx2 = rs_list(6).split(";").map(_.toLong).toSeq
            val idx_arr = rs_list(7).split(";").map(_.toLong).toSeq
            //Row(sample_idx, label, label_arr, dense, idx0, idx1, idx2, idx_arr)

            val float_list = scala.collection.mutable.ArrayBuffer[String]()
            for (name <- cross_features_list_bc.value) {
              val idx = name_idx_map_bc.value(name)
              val key = name + "\t" + dense(idx).toString
              float_list += ctrMap.getOrElse(key, "0.0")
            }

            for (name_pair <- cross_features_list_2_bc.value) {
              val idx1 = name_idx_map_bc.value(name_pair._1)
              val idx2 = name_idx_map_bc.value(name_pair._2)
              val key = name_pair._1 + "x" + name_pair._2 + "\t" + dense(idx1) + "x" + dense(idx2)
              float_list += ctrMap.getOrElse(key, "0.0")
            }
            Row(sample_idx, float_list.map(_.toFloat), label, label_arr, dense, idx0, idx1, idx2, idx_arr)
        })

        val test_text_float_rdd_count = test_text_float_rdd.count
        println(s"test_text_float_rdd_count is : $test_text_float_rdd_count")

        val test_text_df: DataFrame = spark.createDataFrame(test_text_float_rdd, schema_with_float)
        test_text_df.repartition(60).write.format("tfrecords").option("recordType", "Example").save(test_file_text_mapped_float_tf)

        test_text_float_rdd.repartition(60).saveAsTextFile(test_file_text_mapped_float)
      }

    }
    println("Done.......")**/


    /**var name_idx_map: mutable.Map[String, Int] = mutable.Map()
    for (idx <- dense_list.indices) {
      name_idx_map += (dense_list(idx) -> idx)
    }
    var name_idx_map_bc = sc.broadcast(name_idx_map)

    val cross_features_str ="sex,adtype,adclass,os,network,phone_price,brand,city_level,age,hour"
    val cross_features_list = cross_features_str.split(",")
    val cross_features_list_bc = sc.broadcast(cross_features_list)

    val cross_features_list_2 = ArrayBuffer[(String, String)]()
    for (idx <- 0 until cross_features_list.length) {
      for (inner <- (idx + 1) until cross_features_list.length) {
        cross_features_list_2 += ((cross_features_list(idx), cross_features_list(inner)))
      }
    }
    println("cross_features_list_2 len:" + cross_features_list_2.length)
    for (pair <- cross_features_list_2) {
      println(pair._1 + " X " + pair._2)
    }
    val cross_features_list_2_bc = sc.broadcast(cross_features_list_2)

    val schema_with_float = StructType(List(
      StructField("sample_idx", LongType, nullable = true),
      StructField("floats", ArrayType(FloatType, containsNull = true)),
      StructField("label_single", FloatType, nullable = true),
      StructField("label", ArrayType(LongType, containsNull = true)),
      StructField("dense", ArrayType(LongType, containsNull = true)),
      StructField("idx0", ArrayType(LongType, containsNull = true)),
      StructField("idx1", ArrayType(LongType, containsNull = true)),
      StructField("idx2", ArrayType(LongType, containsNull = true)),
      StructField("id_arr", ArrayType(LongType, containsNull = true))
    ))

    /************Collect Float Features************************/
    println("Collect Float Features")
    for (date_idx <- src_date_list.indices) {
      val src_date = src_date_list(date_idx)
      val src_week = src_week_list(date_idx)
      val tf_ctr_feature = ctr_feature_dir + "/collect/" + src_date
      val tf_text = des_dir + "/" + src_date + "-text"
      val tf_text_float = des_dir + "/" + src_date + "-text-float"
      val tf_text_float_tf = des_dir + "/" + src_date + "-text-float-tf"
      if (exists_hdfs_path(tf_text) && exists_hdfs_path(tf_ctr_feature)) {
        println("exit ctr_feature_file:" + tf_ctr_feature)
        if (!exists_hdfs_path(tf_text_float_tf + "/_SUCCESS")) {
          s"hadoop fs -rm -r $tf_text_float" !

          s"hadoop fs -rm -r $tf_text_float_tf" !

          println("Load Ctr Feature Map:" + tf_ctr_feature)
          val ctrMap = sc.textFile(tf_ctr_feature).map(
            rs => {
              val line = rs.split("\t")
              val name = line(0)
              val value = line(1)
              (name + "\t" + value, line(4))
            }
          ).collectAsMap()
          println("ctrMap.size=" + ctrMap.size)

          val float_rdd = sc.textFile(tf_text).map(
            rs => {
              val line_list = rs.split("\t")
              val sample_idx = line_list(0)
              val label = line_list(1)
              val label_arr = line_list(2)
              val dense = line_list(3)
              val idx0 = line_list(4)
              val idx1 = line_list(5)
              val idx2 = line_list(6)
              val idx_arr = line_list(7)

              val float_list = scala.collection.mutable.ArrayBuffer[String]()
              for (name <- cross_features_list_bc.value) {
                val idx = name_idx_map_bc.value(name)
                val key = name + "\t" + dense(idx).toString
                float_list += ctrMap.getOrElse(key, "0.0")
              }

              for (name_pair <- cross_features_list_2_bc.value) {
                val idx1 = name_idx_map_bc.value(name_pair._1)
                val idx2 = name_idx_map_bc.value(name_pair._2)
                val key = name_pair._1 + "x" + name_pair._2 + "\t" + dense(idx1) + "x" + dense(idx2)
                float_list += ctrMap.getOrElse(key, "0.0")
              }
              Row(sample_idx, float_list.map(_.toFloat), label, label_arr, dense, idx0, idx1, idx2, idx_arr)
            }
          )

          val float_rdd_count = float_rdd.count
          println(s"float_rdd_count : $float_rdd_count")
          float_rdd.repartition(1000).saveAsTextFile(tf_text_float)

          val float_df: DataFrame = spark.createDataFrame(float_rdd, schema_with_float)
          float_df.repartition(1000).write.format("tfrecords").option("recordType", "Example").save(tf_text_float_tf)

          //保存count文件
          val fileName = "count_" + Random.nextInt(100000)
          writeNum2File(fileName, float_rdd_count)
          s"hadoop fs -put $fileName $tf_text_float_tf/count" !
        }
      }
    }**/

  }
}