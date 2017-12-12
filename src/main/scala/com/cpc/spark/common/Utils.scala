package com.cpc.spark.common

import javax.mail.internet.InternetAddress

import com.github.jurajburian.mailer._
import com.typesafe.config.ConfigFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

/**
  * Created by roydong on 09/08/2017.
  */
object Utils {

  def deleteHdfs(path: String): Boolean = {
    val conf = new Configuration()
    val p = new Path(path)
    val hdfs = FileSystem.get(conf)
    hdfs.delete(p, true)
  }

  def sendMail(txt: String, sub: String, to: Seq[String]): Boolean = {
    val conf = ConfigFactory.load()
    val session = (SmtpAddress(conf.getString("mail.host"), conf.getInt("mail.port")) :: SessionFactory())
      .session(Some(conf.getString("mail.username") -> conf.getString("mail.password")))
    val toAdd = to.map(new InternetAddress(_))
    val msg = Message(
      from = new InternetAddress(conf.getString("mail.sender")),
      subject = sub,
      content = Content().text(txt),
      to = toAdd)
    try {
      Mailer(session).send(msg)
      true
    } catch {
      case e: Exception =>
        println(e.getMessage)
        false
    }
  }

  //得到所有排列组合 C(n, m)
  def getCombination[T: Manifest](all: Seq[T], n: Int): Seq[Seq[T]] = {
    var combs = Seq[Seq[T]]()
    val comb = new Array[T](n)
    def mapCombination(n: Int, start: Int, idx: Int, comb: Array[T]): Unit = {
      if (n > 0) {
        for (i <- start until all.length) {
          comb(idx) = all(i)
          mapCombination(n - 1, i + 1, idx + 1, comb)
        }
      } else {
        var seq = Seq[T]()
        comb.foreach {
          v => seq = seq :+ v
        }
        combs :+= seq
      }
    }
    mapCombination(n, 0, 0, comb)
    combs
  }
}



