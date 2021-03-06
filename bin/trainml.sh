#!/bin/bash

cur=/home/cpc/$1
SPARK_HOME=/home/spark/spark-2.1.0

jars=(
    "$cur/lib/mysql-connector-java-5.1.41-bin.jar"
    "$cur/lib/hadoop-lzo-0.4.20.jar"
)

$SPARK_HOME/bin/spark-submit --master yarn \
    --executor-memory 5G --executor-cores 5 --total-executor-cores 20 \
    --jars $( IFS=$','; echo "${jars[*]}" ) \
    --class com.cpc.spark.ml.train.LogisticTrain \
    $cur/lib/cpc-anal_2.11-0.1.jar "/user/cpc/svmdata/v1/2017-05-14/"

