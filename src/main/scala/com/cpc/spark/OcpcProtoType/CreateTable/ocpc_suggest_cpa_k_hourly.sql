CREATE TABLE IF NOT EXISTS dl_cpc.ocpc_suggest_cpa_k_hourly
(
    identifier          string,
    cpa_suggest         double,
    kvalue              double,
    conversion_goal     int,
    duration            int
)
PARTITIONED by (`date` string, `hour` string, version string)
STORED as PARQUET;