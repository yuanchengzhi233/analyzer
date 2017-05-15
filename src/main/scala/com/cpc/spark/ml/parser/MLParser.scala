package com.cpc.spark.ml.parser

import com.cpc.spark.log.parser.UnionLog
import org.apache.spark.mllib.feature.IDF

import scala.util.hashing.MurmurHash3.stringHash

/**
  * Created by Roy on 2017/5/15.
  */
object MLParser {

  def unionLogToSvm(x: UnionLog): String = {
    var cols = Seq[Double](
      x.network.toDouble,
      x.isp.toDouble,
      x.media_appsid.toDouble,
      x.bid.toDouble,
      x.ideaid.toDouble,
      x.unitid.toDouble,
      x.planid.toDouble,
      x.city.toDouble,
      x.adslotid.toDouble,
      x.adtype.toDouble,
      x.interaction.toDouble,
      Math.abs(stringHash(x.date)).toDouble,
      x.hour.toDouble
    )

    var n = 1
    var svm = x.isclick.toString
    for (col <- cols) {
      svm = svm + " :%d %f".format(n, col)
    }
    svm
  }
}

