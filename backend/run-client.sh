#!/usr/bin/env bash
set -euo pipefail

# 安装 Maven（必须成功，不能静默）
if ! command -v mvn &>/dev/null; then
    echo "=== Installing Maven via dnf ==="
    dnf install -y maven
fi
echo "=== Maven ready: $(mvn -version | head -1) ==="

# 修正版 settings.xml
mkdir -p /root/.m2
cat > /root/.m2/settings.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings><localRepository>/root/.m2/repository</localRepository><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>
EOF

# 容器内访问宿主机服务用 host.docker.internal
export MCP_SERVER_URL=http://host.docker.internal:9060
export REDIS_HOST=host.docker.internal
export REDIS_PORT=9379

echo "=== Starting mcp-client on :9090 ==="
cd /app
mvn -B -s /root/.m2/settings.xml -pl mcp-client spring-boot:run
