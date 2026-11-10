-- 码上短剧 - 本地开发数据库初始化
-- 执行方式：mysql -uroot -p < deploy/mysql/init/00_databases.sql
-- 创建 DL（业务）、xxl_job、seata（Seata Server DB 存储）三个库
-- 再把 deploy/mysql/init/01_xxl_job.sql 导入（脚本内已包含 CREATE DATABASE xxl_job）
--
-- 注意：下面的密码仅用于本地开发，生产环境必须替换。
-- 如果修改了密码，应用侧同步设置 MSD_DB_PASSWORD 环境变量。

CREATE DATABASE IF NOT EXISTS DL     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS xxl_job DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS seata  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'msd'@'%' IDENTIFIED BY 'change-me';
ALTER USER 'msd'@'%' IDENTIFIED BY 'change-me';

GRANT ALL PRIVILEGES ON DL.*      TO 'msd'@'%';
GRANT ALL PRIVILEGES ON xxl_job.* TO 'msd'@'%';
GRANT ALL PRIVILEGES ON seata.*   TO 'msd'@'%';
FLUSH PRIVILEGES;

USE DL;

CREATE TABLE IF NOT EXISTS t_infra_smoke (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
