create table if not exists dl_cpc.ocpc_aa_base_index_value(
    `date`                  string,
    unitid                  int,
    userid                  int,
    cpagiven                double,
    cpareal1                double,
    cpareal2                double,
    cpareal3                double,
    cpm                     double,
    arpu                    double,
    show                    int,
    click                   int,
    cv1                     int,
    cv2                     int,
    cv3                     int,
    pre_cvr                 double,
    post_cvr1               double,
    post_cvr2               double,
    post_cvr3               double,
    acp                     double,
    acb                     double,
    kvalue                  double,
    ratio                   double
)
partitioned by (dt string, version string)
stored as parquet;