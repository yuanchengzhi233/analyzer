
import aslog.Aslog.AdSrc
import com.cpc.spark.common.{Event, LogData, Ui}
import com.cpc.spark.streaming.tools.Utils
import eventprotocol.Protocol.ChargeType
import eventprotocol.Protocol.Event.Body

object CpmLogParser {

  val None = Seq(CpmLog(false, 0, "", "", "", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

  def parseShowLog(map: data.Data.Log.Field.Map, date: String, hour: Int): Seq[CpmLog] = {
    val value = map.getValue.getStringType
    val show = Event.parse_show_log(value)
    val pEvent = show.event

    //过滤非cpm计费、视频广告其他时间数据（只取第一次上传的数据）
    if (show == null || (pEvent.getAd.getType == Body.AdType.AD_VIDEO && pEvent.getShowTime != 0)) {
      None
    } else {
      val sid = pEvent.getSearchId
      val adSrc = pEvent.getDspInfo.getDsp.getNumber

      var dspMediaId = ""
      var dspAdslotId = ""
      var idea_id = 0
      var unit_id = 0
      var plan_id = 0
      var user_id = 0

      if (adSrc == 1) {
        val ad = pEvent.getAd
        idea_id = ad.getUnitId
        unit_id = ad.getGroupId
        plan_id = ad.getPlanId
        user_id = ad.getUserId
      } else {
        dspMediaId = pEvent.getDspInfo.getMediaId
        dspAdslotId = pEvent.getDspInfo.getAdslotId
      }

      val media_id_str = pEvent.getMedia.getMediaId
      val media_id = if (media_id_str.trim() != "") media_id_str.toInt else 0

      val adslot_id_str = pEvent.getMedia.getAdslotId
      val adslot_id = if (adslot_id_str.trim() != "") adslot_id_str.toInt else 0
      val adslot_type = show.event.getMedia.getAdslotType.getNumber
      //非计费数据，只计算展示
      val price = if (pEvent.getCharge.getType == ChargeType.CPM) pEvent.getCharge.getPrice else 0
      //val price = 0

      Seq(CpmLog(true, adSrc, dspMediaId, dspAdslotId, sid, date, hour, show.typed, idea_id, unit_id, plan_id, user_id, media_id, adslot_id, adslot_type, price, 0, 0, 1, 0))
    }
  }

  def parseReqLog(map: data.Data.Log.Field.Map, date: String, hour: Int): Seq[CpmLog] = {
    var res = Seq[CpmLog]()
    var isok = true
    val value = map.getValue.getStringType
    val ui = Ui.parseData(value)
    if (ui == null) {
      None
    } else {
      val sid = ui.ui.getSearchid
      var adSrc = 0
      val ad = ui.ui.getAdsList
      var dspMediaId = ""
      var dspAdslotId = ""
      var idea_id = 0
      var unit_id = 0
      var plan_id = 0
      var user_id = 0
      var isfill = 0
      val price = 0
      val isdebug = ui.ui.getDebug
      if (isdebug) {
        isok = false
      }

      val media_id_str = ui.ui.getMedia.getAppsid
      val media_id = if (media_id_str.trim() != "") media_id_str.toInt else 0

      val adslot_id_str = ui.ui.getAdslot(0).getId
      val adslot_id = if (adslot_id_str.trim() != "") adslot_id_str.toInt else 0

      val adslot_type = ui.ui.getAdslot(0).getType.getNumber

      if (ad.size() > 0) {
        if (ui.ui.getAds(0).getSrc.getNumber == AdSrc.CPC_VALUE) {
          idea_id = ui.ui.getAds(0).getAdid
          unit_id = ui.ui.getAds(0).getGroupid
          plan_id = ui.ui.getAds(0).getPlanid
          user_id = ad.get(0).getUserid
        } else {
          dspMediaId = ui.ui.getAds(0).getDspMediaId
          dspAdslotId = ui.ui.getAds(0).getDspAdslotId
        }
        adSrc = ui.ui.getAds(0).getSrc.getNumber
        isfill = 1
      } else {
        adSrc = 1
      }
      val cpmLog = CpmLog(isok, adSrc, dspMediaId, dspAdslotId, sid, date, hour, ui.typed, idea_id, unit_id, plan_id, user_id, media_id, adslot_id, adslot_type, price, 1, isfill, 0, 0)
      res = res :+ cpmLog

      if (adslot_type == 7) {
        for (i <- 1 until ad.size()) {
          if (ui.ui.getAds(i).getSrc.getNumber == AdSrc.CPC_VALUE) {
            idea_id = ui.ui.getAds(i).getAdid
            unit_id = ui.ui.getAds(i).getGroupid
            plan_id = ui.ui.getAds(i).getPlanid
            user_id = ad.get(i).getUserid
          } else {
            dspMediaId = ui.ui.getAds(i).getDspMediaId
            dspAdslotId = ui.ui.getAds(i).getDspAdslotId
          }
          adSrc = ui.ui.getAds(i).getSrc.getNumber

          res = res :+ cpmLog.copy(ideaId = idea_id, unitId = unit_id, planId = plan_id, userId = user_id,
            dspMediaId = dspMediaId, dspAdslotId = dspAdslotId, adsrc = adSrc)
        }
      }

      res
    }
  }

  def parse(logdata: LogData): Seq[CpmLog] = {

    val ts = if (logdata.log.hasLogTimestamp()) logdata.log.getLogTimestamp else 0L

    val (date, hour) = Utils.get_date_hour_from_time(ts)

    val map: data.Data.Log.Field.Map =
      if (logdata.log.hasField() && logdata.log.getField.getMapCount > 0)
        logdata.log.getField.getMap(0)
      else {
        null
      }


    if (ts == 0 || map == null) {
      None
    } else {
      val key = map.getKey
      if (key.startsWith("cpc_search")) {
        parseReqLog(map, date, hour)
      } else if (key.startsWith("cpc_show")) {
        parseShowLog(map, date, hour)
      }
      else None
    }
  }
}


