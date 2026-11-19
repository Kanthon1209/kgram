# 部署 kgram 网关

本目录描述如何把 `services/gateway` 放到 OpenIM 同一台机器上，并对外提供 HTTPS / WSS。  
**不要改动、重启或停止正在运行的 OpenIM / Delve 调试进程。** 网关是一个新进程，只访问已有的 OpenIM HTTP/WS 端口。

当前演示 ECS（Aliyun Hangzhou，`8.136.150.149`）上：

- 没有已配置的公网域名，没有 nginx / Caddy / Let’s Encrypt 证书，没有 80/443 监听。
- OpenIM 仍在 `10001/10002/10005/10008/10009` 上以明文 HTTP/WS 对外。这些端口暂时保留给运维和过渡，客户端新版本应只连网关。
- 机器内存约 2Gi，**不要在这台机器上 `docker build` 或拉很大的镜像。**

因此本里程碑**默认不在 ECS 上启用在线 TLS**。需要先准备证书，并在安全组放行 `8443`（或 443），再按下面步骤启动。没有这一步，新客户端里的 `https://` / `wss://` 还连不上。

## 1. 编译（在开发机，不要在 2Gi ECS 上）

```bash
cd services/gateway
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o kgram-gateway .
```

把二进制拷到服务器，例如 `/opt/kgram/gateway/kgram-gateway`。

## 2. 配置（环境变量，不要入库）

```bash
cp deploy/gateway/.env.example /opt/kgram/gateway/.env
# 编辑 .env：填写 OPENIM_SECRET（与 OpenIM 服务器配置相同，不要写进 git / PR）
chmod 600 /opt/kgram/gateway/.env
```

内网地址用 `http://127.0.0.1:10002` 等即可，不必再把管理员口令暴露到公网。

## 3. TLS 证书

### 方案 A：有公网域名（推荐）

把域名解析到这台机器，申请 Let’s Encrypt（可用 `Caddyfile.example` 在 443 反代 `127.0.0.1:8080`），Android 走系统 CA，无需钉扎。

### 方案 B：暂无域名，自签 + 钉扎（仅演示）

在开发机生成（把 IP 换成实际地址），**只把公钥放到客户端仓库**：

```bash
openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
  -keyout gateway-key.pem -out gateway-cert.pem \
  -subj "/CN=8.136.150.149" \
  -addext "subjectAltName=IP:8.136.150.149"
```

- `gateway-key.pem` 只放服务器 `/etc/kgram/tls/`，权限 600，不进 git。
- `gateway-cert.pem` 拷到 Telegram-Android 的 `TMessagesProj/src/main/res/raw/kgram_demo_ca.pem`，并在 `network_security_config.xml` 的对应 domain-config 中增加：

  ```xml
  <certificates src="@raw/kgram_demo_ca" />
  ```

然后设置：

```bash
GATEWAY_TLS_ADDR=:8443
GATEWAY_TLS_CERT_FILE=/etc/kgram/tls/gateway-cert.pem
GATEWAY_TLS_KEY_FILE=/etc/kgram/tls/gateway-key.pem
GATEWAY_PUBLIC_URL=https://8.136.150.149:8443
```

Aliyun 安全组需要放行 TCP **8443**（或你改用的 443）。

## 4. 启动

```bash
cd /opt/kgram/gateway
set -a && . ./.env && set +a
./kgram-gateway
```

可用 systemd 托管。确认：

```bash
curl -s http://127.0.0.1:8080/health
curl -sk https://127.0.0.1:8443/health
```

不要执行 `docker compose down`，不要重启 `openim-server` / `openim-chat`。

## 5. 客户端应对地址

见 Android `KgramServerConfig`：Chat / IM / MinIO / WS 都指向网关的 `https://` 与 `wss://`。

| 客户端常量 | 路径 |
|------------|------|
| `CHAT_API` | `https://HOST:8443/chat` |
| `IM_API` | `https://HOST:8443/im` |
| `MINIO` | `https://HOST:8443/objects` |
| `IM_WS` | `wss://HOST:8443/v1/ws` |
| `GATEWAY_API` | `https://HOST:8443` |

完成 TLS 与安全组之前，旧的明文端口仍然可用，但新客户端不应再默认走明文。
