-- 短剧布隆过滤器重建任务
-- 已有 xxl_job 库时手动执行；全新环境按 01、03 顺序执行。
-- Windows 执行请加 --default-character-set=utf8mb4，否则中文任务名会报 1366。
-- xxl-job 3.5 的注册校验使用执行器组 access_token，必须与 msd-drama.yaml 的
-- xxl.job.accessToken 一致。下面先写本地 dev 默认值；test/prod 执行前改成对应 token。
USE xxl_job;

INSERT INTO xxl_job_group (app_name, name, address_type, address_list, access_token, update_time)
SELECT 'msd-drama-executor', '短剧缓存执行器', 0, NULL, 'change-me', NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM xxl_job_group WHERE app_name = 'msd-drama-executor'
);

-- 已存在但 token 为空的组补齐；不覆盖管理员手工改过的值
UPDATE xxl_job_group
SET access_token = 'change-me', update_time = NOW()
WHERE app_name = 'msd-drama-executor'
  AND access_token IS NULL;

INSERT INTO xxl_job_info (
    job_group, name, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy,
    executor_route_strategy, executor_handler, executor_param,
    executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_source, glue_remark, glue_updatetime, child_jobid,
    trigger_status, trigger_last_time, trigger_next_time, add_time, update_time
)
SELECT g.id, '短剧缓存重建', 'msd', '',
       'CRON', '0 */5 * * * ? *', 'DO_NOTHING',
       'FIRST', 'dramaCacheRebuildHandler', '',
       'SERIAL_EXECUTION', 300, 0,
       'BEAN', '', '布隆过滤器重建', NOW(), '',
       1, 0, 0, NOW(), NOW()
FROM xxl_job_group g
WHERE g.app_name = 'msd-drama-executor'
  AND NOT EXISTS (
    SELECT 1 FROM xxl_job_info WHERE executor_handler = 'dramaCacheRebuildHandler'
  );
