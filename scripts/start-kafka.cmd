@echo off
setlocal

rem Local Kafka 4.1.2 bin\windows\*.bat depends on wmic, removed in Windows 11.
rem Start kafka.Kafka directly with java -cp libs\* to avoid the broken wrapper.

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is required
  exit /b 1
)
if "%KAFKA_HOME%"=="" (
  echo [ERROR] KAFKA_HOME is required
  exit /b 1
)

if not exist "%KAFKA_HOME%\config\server.properties" (
  echo [ERROR] Kafka not found: %KAFKA_HOME%
  exit /b 1
)

if not exist "D:\kafka-data\logs" mkdir "D:\kafka-data\logs"

cd /d "%KAFKA_HOME%"

echo [INFO] starting Kafka from %KAFKA_HOME%
"%JAVA_HOME%\bin\java.exe" -Xmx1G ^
  -Dlog4j2.configurationFile=file:///%KAFKA_HOME:\=/%/config/log4j2.yaml ^
  -Dkafka.logs.dir=D:/kafka-data/logs ^
  -cp "%KAFKA_HOME%\libs\*" kafka.Kafka "%KAFKA_HOME%\config\server.properties"

endlocal
