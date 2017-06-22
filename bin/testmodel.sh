#!/bin/bash

cur=/home/cpc/$1
SPARK_HOME=/home/spark/spark-2.1.0

jars=(
    "$cur/lib/mysql-connector-java-5.1.41-bin.jar"
    "$cur/lib/hadoop-lzo-0.4.20.jar"
)

$SPARK_HOME/bin/spark-submit --master yarn \
    --executor-memory 4G --executor-cores 4 --total-executor-cores 20 \
    --jars $( IFS=$','; echo "${jars[*]}" ) \
    --class com.cpc.spark.ml.train.LRTrain \
    $cur/lib/cpc-ml_2.11-0.1.jar "test" \
          "/user/cpc/svmdata/v7/2017-06-13" \
          "/user/cpc/model/v7" \
          0.5 1


