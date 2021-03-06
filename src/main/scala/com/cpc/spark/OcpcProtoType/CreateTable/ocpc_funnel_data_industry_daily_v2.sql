CREATE TABLE IF NOT EXISTS dl_cpc.ocpc_funnel_data_industry_daily_v2
(
    unitid                  int,
    planid                  int,
    userid                  int,
    click                   bigint,
    show                    bigint,
    cv                      bigint,
    cost                    double,
    ocpc_cpagiven           double,
    ocpc_cpareal            double,
    ocpc_click              bigint,
    ocpc_show               bigint,
    ocpc_cv                 bigint,
    ocpc_cost               double,
    hidden_cpagiven         double,
    hidden_cpareal          double,
    hidden_click            bigint,
    hidden_show             bigint,
    hidden_cv               bigint,
    hidden_cost             double,
    budget                  double,
    adslot_type             int,
    site_type               string,
    media                   string
)
PARTITIONED by (industry string, `date` string)
STORED as PARQUET;


