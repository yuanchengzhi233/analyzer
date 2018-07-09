package com.cpc.spark.log.parser

/**
  * click日志
  *
  * @param searchid
  * @param isshow
  * @param ideaid
  * @param show_timestamp
  * @param show_ip
  * @param ext
  */
case class ParsedShowLog(
                          var searchid: String = "",
                          var isshow: Int = 0,
                          var ideaid: Int = 0,
                          var show_timestamp: Int = 0,
                          var show_ip: String = "",
                          var ext: collection.Map[String, ExtValue] = null
                        ) extends CommonLog {

}

