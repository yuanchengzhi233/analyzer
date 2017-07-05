#!/bin/bash

cur=/home/cpc/$1
SPARK_HOME=/home/spark/spark-2.1.0

jars=(
    "$cur/lib/mysql-connector-java-5.1.41-bin.jar"
    "$cur/lib/hadoop-lzo-0.4.20.jar"
)

$SPARK_HOME/bin/spark-submit --master yarn \
    --executor-memory 8G --driver-memory 8G \
    --executor-cores 4 --total-executor-cores 20 --num-executors 5 \
    --jars $( IFS=$','; echo "${jars[*]}" ) \
    --class com.cpc.spark.ml.train.LRTrain \
    $cur/lib/dev.jar "train" \
          "/user/cpc/svmdata/v16_full/2017-06-{21}" \
          "/user/cpc/model/v16_full_1d" \
          0.99 1

