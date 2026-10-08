#!/bin/bash
# 仅在 MySQL 数据卷首次初始化时执行（docker-entrypoint-initdb.d 只在空数据卷生效）。
# 已存在数据卷的服务器：改完 compose 后手动执行 docs/docker-deploy.md「Canal」一节的 GRANT 语句即可。
set -e
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE USER IF NOT EXISTS '${CANAL_DB_USERNAME:-canal}'@'%' IDENTIFIED BY '${CANAL_DB_PASSWORD}';
    GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${CANAL_DB_USERNAME:-canal}'@'%';
    FLUSH PRIVILEGES;
EOSQL
