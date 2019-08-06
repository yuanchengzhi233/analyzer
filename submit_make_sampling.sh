#!/bin/bash

set -e

cur_date="2019-06-11-bak"
cur_date=$1
#modelVersion=$2
#today=`date -d "${cur_time} 1 hours ago" +%Y-%m-%d`
#hour=`date -d "${cur_time} 1 hours ago" +%H`

jarLib=hdfs://emr-cluster/warehouse/azkaban/lib/fhb_start_v1.jar

queue=root.cpc.bigdata
jars=("/home/cpc/anal/lib/spark-tensorflow-connector_2.11-1.10.0.jar" )

randjar="fhb_start"`date +%s%N`".jar"
hadoop fs -get ${jarLib} ${randjar}

src_dir="hdfs://emr-cluster/user/cpc/aiclk_dataflow/daily/adlist-v4"
date_begin=`date --date='31 days ago' +%Y-%m-%d`
date_end=`date --date='1 days ago' +%Y-%m-%d`
des_dir="hdfs://emr-cluster/user/cpc/fenghuabin/adlist-v4-sampled"
test_data_src="2019-08-03/part-r-000*"
test_data_des="test-2019-08-03"
test_data_week="Sat"
partitions=1000

spark-submit --master yarn --queue ${queue} \
    --name "adlist-tf-make-example" \
    --driver-memory 4g --executor-memory 4g \
    --num-executors 100 --executor-cores 4 \
    --conf spark.hadoop.fs.defaultFS=hdfs://emr-cluster2 \
    --conf "spark.yarn.executor.memoryOverhead=4g" \
    --conf "spark.sql.shuffle.partitions=500" \
    --jars $( IFS=$','; echo "${jars[*]}" ) \
    --class com.cpc.spark.ml.dnn.baseData.MakeSampling \
    ${randjar} ${src_dir} ${date_begin} ${date_end} ${des_dir} ${test_data_src} ${test_data_des} ${test_data_week} ${partitions}

chmod_des="hdfs://emr-cluster/user/cpc/fenghuabin/adlist-v4-sampled"
hadoop fs -chmod -R 0777 ${chmod_des}
