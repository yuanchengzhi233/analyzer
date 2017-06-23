package com.cpc.spark.ml.common

/**
  * Created by roydong on 23/06/2017.
  */
object Utils {

  /*
  返回组合特征的位置，和最大位置号
   */
  def combineIntFeatureIdx(ids: Int*): Int = {
    var idx = 0
    for (i <- 0 to ids.length - 1) {
      var v = 1
      for (j <- i + 1 to ids.length - 1) {
        v = v * ids(i)
      }
      idx = idx + (ids(i) - 1) * v
    }
    idx
  }

  def combineIntFeatureMax(m: Int*): Int = {
    var max = 1
    for (i <- 0 to m.length - 1) {
      max = max * m(i)
    }
    max
  }
}
