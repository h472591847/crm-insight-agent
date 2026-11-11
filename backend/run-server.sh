#!/usr/bin/env bash
set -euo pipefail
dnf install -y maven >/dev/null 2>&1 || true
mkdir -p /root/.m2
cat > /root/.m2/settings.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings><localRepository>/root/.m2/repository</localRepository><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>
EOF
# 容器内访问宿主机 MySQL 用 host.docker.internal
export MYSQL_HOST=host.docker.internal
export MYSQL_PORT=3336
echo "=== Starting mcp-server :9060 ==="
cd /app
mvn -B -s /root/.m2/settings.xml -pl mcp-common -am install -DskipTests
mvn -B -s /root/.m2/settings.xml -pl mcp-server spring-boot:run
