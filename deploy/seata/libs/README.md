# Seata Server MySQL 驱动

Seata 2.1+ 官方镜像不再内置 MySQL Connector/J。启动 DB 存储模式前，把本机 Maven 仓库中的驱动复制为本目录的 `mysql-connector-j.jar`：

```bash
cp "$HOME/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar" \
   deploy/seata/libs/mysql-connector-j.jar
```

Compose 会把该文件挂载到容器的 `/seata-server/libs/mysql-connector-j.jar`。`deploy/seata/libs/*.jar` 已在 `.gitignore` 中忽略，不要提交驱动二进制。
