@echo off
setlocal

pushd "%~dp0.."

if "%MYSQL_PWD%"=="" (
    set /p MYSQL_PWD=Enter MySQL password for msd:
)

echo [INFO] creating business tables...
mysql -umsd -h127.0.0.1 --default-character-set=utf8mb4 < deploy\mysql\init\02_business_schema.sql
if errorlevel 1 goto :error

echo [INFO] loading seed data...
mysql -umsd -h127.0.0.1 --default-character-set=utf8mb4 < deploy\mysql\init\03_business_seed.sql
if errorlevel 1 goto :error

echo [INFO] loading extended mock data...
mysql -umsd -h127.0.0.1 --default-character-set=utf8mb4 < deploy\mysql\init\04_mock_data.sql
if errorlevel 1 goto :error

echo [INFO] applying consistency migration...
mysql -umsd -h127.0.0.1 --default-character-set=utf8mb4 < deploy\mysql\init\05_consistency.sql
if errorlevel 1 goto :error

echo [INFO] creating seata server tables (requires seata database from 00_databases.sql)...
mysql -umsd -h127.0.0.1 --default-character-set=utf8mb4 < deploy\mysql\init\06_seata_server.sql
if errorlevel 1 goto :error

echo [INFO] database initialized
popd
exit /b 0

:error
echo [ERROR] database init failed
popd
exit /b 1
