#!/bin/bash

cur=/data/cpc/anal
SPARK_HOME=/usr/lib/spark-current
queue=root.cpc.develop

jars=(
    "$cur/lib/mysql-connector-java-5.1.41-bin.jar"
    "$cur/lib/hadoop-lzo-0.4.20.jar"
    "$cur/lib/config-1.2.1.jar"
)

$SPARK_HOME/bin/spark-submit --master yarn --queue $queue \
    --conf 'spark.port.maxRetries=100' \
    --executor-memory 20g --driver-memory 20g \
    --executor-cores 10 --num-executors 20  \
    --conf 'spark.yarn.executor.memoryOverhead=4g'\
    --conf 'spark.dynamicAllocation.maxExecutors=50'\
    --jars $( IFS=$','; echo "${jars[*]}" ) \
    --class com.cpc.spark.OcpcProtoType.model_v5.OcpcCalculateCalibration \
    /home/cpc/wangjun/analyzer/target/scala-2.11/cpc-anal_2.11-0.1.jar $1 $2 $3 $4 $5 $6 $7 $8 $9

#val date = args(0).toString
#val hour = args(1).toString
#val version = args(2).toString
#val media = args(3).toString
#val conversionGoal = args(4).toInt
#val minCV = args(5).toInt
#
#// 主校准回溯时间长度
#val hourInt1 = args(6).toInt
#// 备用校准回溯时间长度
#val hourInt2 = args(7).toInt
#// 兜底校准时长
#val hourInt3 = args(8).toInt
