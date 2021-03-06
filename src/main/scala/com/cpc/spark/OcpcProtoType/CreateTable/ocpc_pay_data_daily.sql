create table if not exists dl_cpc.ocpc_pay_data_daily(
    unitid                  int,
    adslot_type             int,
    pay                     bigint,
    cost                    bigint,
    cpareal                 double,
    cpagiven                double,
    cv                      int,
    start_date              string
)
partitioned by (`date` string, version string)
stored as parquet;


--alter table dl_cpc.ocpc_pay_data_daily add columns (cpc_flag int);
--alter table dl_cpc.ocpc_pay_data_daily add columns (ocpc_charge_time string);



--create table test.ocpc_pay_data_daily
--like dl_cpc.ocpc_pay_data_daily;