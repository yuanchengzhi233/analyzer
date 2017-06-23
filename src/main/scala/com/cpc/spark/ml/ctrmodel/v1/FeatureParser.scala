package com.cpc.spark.ml.ctrmodel.v1

import java.util.Calendar

import com.cpc.spark.log.parser.{ExtValue, UnionLog}
import com.cpc.spark.ml.common.{FeatureDict, Utils}
import mlserver.mlserver._
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.util.MLUtils


/**
  * Created by Roy on 2017/5/15.
  */
object FeatureParser extends FeatureDict {


  def parseUnionLog(x: UnionLog): String = {
    var cls = 0
    if (x.ext != null) {
      val v = x.ext.getOrElse("media_class", null)
      if (v != null) {
        cls = v.int_value
      }
    }
    val ad = AdInfo(
      bid = x.bid,
      ideaid = x.ideaid,
      unitid = x.unitid,
      planid = x.planid,
      userid = x.userid,
      adtype = x.adtype,
      interaction = x.interaction,
      _class = cls
    )
    val m = Media(
      mediaAppsid = x.media_appsid.toInt,
      mediaType = x.media_type,
      adslotid = x.adslotid.toInt,
      adslotType = x.adslot_type,
      floorbid = x.floorbid
    )
    val interests = x.interests.split(",")
      .map{
        x =>
          val v = x.split("=")
          if (v.length == 2) {
            (v(0).toInt, v(1).toInt)
          } else {
            (0, 0)
          }
      }
      .filter(_._1 > 0)
      .sortWith((x, y) => x._2 > y._2)
      .filter(_._2.toInt >= 3)
      .map(_._1)
      .toSeq
    val u = User(
      sex = x.sex,
      age = x.age,
      coin = x.coin,
      uid = x.uid,
      interests = interests
    )
    val n = Network(
      network = x.network,
      isp = x.isp,
      ip = x.ip
    )
    val loc = Location(
      country = x.country,
      province = x.province,
      city = x.city
    )
    val d = Device(
      os = x.os,
      model = x.model
    )

    var svm = ""
    val vector = parse(ad, m, u, loc, n, d, x.timestamp * 1000L)
    if (vector != null) {
      svm = x.isclick.toString
      MLUtils.appendBias(vector).foreachActive {
        (i, v) =>
          svm = svm + " %d:%f".format(i, v)
      }
    }
    svm
  }

  def parse(ad: AdInfo, m: Media, u: User, loc: Location, n: Network, d: Device, timeMills: Long): Vector = {
    val cal = Calendar.getInstance()
    cal.setTimeInMillis(timeMills)
    val week = cal.get(Calendar.DAY_OF_WEEK)
    val hour = cal.get(Calendar.HOUR_OF_DAY)

    var els = Seq[(Int, Double)]()
    var i = 0

    els = els :+ (week, 1D)
    i += 7

    els = els :+ (hour + i, 1D)
    i += 24

    //sex
    if (u.sex > 0) {
      els = els :+ (u.sex + i, 1D)
    }
    i += 2

    //interests
    u.interests.foreach {
      intr =>
        els = els :+ (interests.getOrElse(intr, 0) + i, 1D)
    }
    i += interests.size

    //os
    var os = 0
    if (d.os > 0 && d.os < 3) {
      os = d.os
      els = els :+ (os + i, 1D)
    }
    i += 2

    //adslot type
    if (m.adslotType > 0) {
      els = els :+ (m.adslotType + i, 1D)
    }
    i += 2

    //ad type
    if (ad.adtype > 0) {
      els = els :+ (ad.adtype + i, 1D)
    }
    i += 6

    //ad class
    val adcls = adClass.getOrElse(ad._class, 0)
    if (adcls > 0) {
      els = els :+ (adcls + i, 1D)
    }
    i += adClass.size

    //isp
    if (n.isp > 0) {
      els = els :+ (n.isp + i, 1D)
    }
    i += 20

    //net
    if (n.network > 0) {
      els = els :+ (n.network + i, 1D)
    }
    i += 5

    val city = cityDict.getOrElse(loc.city, 0)
    if (city > 0) {
      els = els :+ (city + i, 1D)
    }
    i += cityDict.size

    //userid
    els = els :+ (ad.userid + i, 1D)
    i += 2000

    //planid
    els = els :+ (ad.planid + i, 1D)
    i += 3000

    //unitid
    els = els :+ (ad.unitid + i, 1D)
    i += 10000

    //ideaid
    els = els :+ (ad.ideaid + i, 1D)
    i += 20000

    //ad slot id
    val slotid = adslotids.getOrElse(m.adslotid, 0)
    if (slotid > 0) {
      els = els :+ (slotid + i, 1D)
    }
    i += adslotids.size

    //adslotid + ideaid
    if (slotid > 0 && ad.ideaid > 0) {
      val v = Utils.combineIntFeatureIdx(slotid, ad.ideaid)
      els = els :+ (i + v, 1D)
    }
    i += adslotids.size * 20000

    //net adclass slot
    if (u.sex > 0 && os > 0 && n.network > 0 && adcls > 0 && ad.adtype > 0) {
      val v = Utils.combineIntFeatureIdx(u.sex, os, n.network, adcls, ad.adtype)
      els = els :+ (i + v, 1D)
    }
    i += 2 * 2 * 4 * adClass.size * 6

    if (u.sex > 0 && city > 0 && adcls > 0 && ad.adtype > 0) {
      val v = Utils.combineIntFeatureIdx(u.sex, city, adcls, ad.adtype)
      els = els :+ (i + v, 1D)
    }
    i += 2 * cityDict.size * adClass.size * 6

    try {
      Vectors.sparse(i, els)
    } catch {
      case e: Exception =>
        println(e.getMessage, els)
        null
    }
  }
}

