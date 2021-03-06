package com.cpc.spark.small.tool.streaming.tool

import redis.clients.jedis.Jedis
import com.typesafe.config.ConfigFactory

import scala.collection.convert.wrapAsScala._

class OffsetRedis extends Serializable {
   //private var jedis:Jedis = new Jedis("192.168.101.48", 6379)  
   
   private var key = "SMALL_KAFKA_OFFSET"

   def setRedisKey(k: String):Unit = {
    key = k
   }

   def getRedis():Jedis = {
//     val prop = new Properties()
//     var in: InputStream = new FileInputStream(new File(SparkFiles.get("redis.properties")))
//         prop.load(in)
     val conf = ConfigFactory.load()
     var jedis:Jedis = new Jedis(conf.getString("redis.host"), conf.getInt("redis.port"))
     jedis
   }

  def setTopicAndPartitionOffSet(topic:String,partion:Int,offset:Long):Unit ={
     var jedis = getRedis()
     var long = jedis.set(key+"_"+topic+"_"+partion,offset.toString())
     jedis.sadd(key +"_"+ topic, partion.toString())
   }
   def getPartitionByTopic(topic:String):Set[String] ={
     var jedis = getRedis()
     val set:java.util.Set[String] =jedis.smembers(key+"_"+topic)// smembers返回java.util.Set[String]
     return set.toSet
   }
   def getTopicAndPartitionOffSet(topic:String,partion:Int):Long ={
     var jedis = getRedis()
     var offset = jedis.get(key+"_"+topic+"_"+partion)
     return offset.toLong     
   }
   def refreshOffsetKey():Unit ={
     var jedis = getRedis()
     val sets:java.util.Set[String]= jedis.keys(key+"*")
     val scalaSets = sets.toSet     
     for(key <- scalaSets){       
       jedis.del(key)
     }
   }
}

object OffsetRedis {
  var offsetRedis: OffsetRedis = _
  def getOffsetRedis: OffsetRedis = {
    synchronized {
      if (offsetRedis == null) {
        offsetRedis = new OffsetRedis()
      }
    }
    offsetRedis
  }

}




