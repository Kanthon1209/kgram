# 部署 kgram 网关

本目录描述如何把 `services/gateway` 放到 OpenIM 同一台机器上，并对外提供 HTTPS / WSS。  
**不要改动、重启或停止正在运行的 OpenIM / Delve 调试进程。** 网关是一个新进程，只访问已有的 OpenIM HTTP/WS 端口。

当前演示 ECS（Aliyun Hangzhou，`8.136.150.149`）：

- 没有公网域名。演示 TLS 使用**自签 CA + 服务器证书**（SAN = `IP:8.136.150.149`），Android 钉扎 CA 公钥。
- OpenIM 仍在 `10001/10002/10005/10008/10009` 上以明文 HTTP/WS 对外，这些端口保持不动，仅供运维和过渡。
- **不要在这台机器上 `docker build` 或编译大型产物。** 网关二进制在开发机交叉编译后拷过来，用 systemd 运行。

## 1. 编译（在开发机）

```bash
cd services/gateway
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o kgram-gateway .
```

把二进制拷到服务器 `/opt/kgram/gateway/kgram-gateway`。

## 2. 配置（环境变量，不要入库）

```bash
cp deploy/gateway/.env.example /opt/kgram/gateway/.env
# 填写 OPENIM_SECRET（与 OpenIM 服务器配置相同，不要写进 git / PR）
chmod 640 /opt/kgram/gateway/.env
```

内网地址用 `http://127.0.0.1:10002` 等即可。

建议的对外 TLS 环境变量：

```bash
GATEWAY_ADDR=127.0.0.1:8080
GATEWAY_TLS_ADDR=:8443
GATEWAY_TLS_CERT_FILE=/etc/kgram/tls/gateway-chain.pem
GATEWAY_TLS_KEY_FILE=/etc/kgram/tls/gateway-key.pem
GATEWAY_PUBLIC_URL=https://8.136.150.149:8443
```

`gateway-chain.pem` 是叶子证书 + CA，便于客户端用钉扎的 CA 校验。私钥权限 640，属主 `root:kgram`，不进 git。CA 私钥只留在开发机，不要拷到 ECS。

## 3. TLS 证书

### 方案 A：有公网域名（推荐，日后切换）

把域名解析到这台机器，申请 Let’s Encrypt（可用 `Caddyfile.example` 在 443 反代 `127.0.0.1:8080`），Android 走系统 CA。

### 方案 B：当前演示 — 自签 CA + 钉扎

在开发机生成独立 CA，再签发带 `subjectAltName=IP:8.136.150.149` 的服务器证书。**只把 CA 公钥**放到 Android：

- `TMessagesProj/src/main/res/raw/kgram_demo_ca.pem`
- `network_security_config.xml` 中该 IP 的 domain-config 增加 `<certificates src="@raw/kgram_demo_ca" />`

不要把任何私钥提交进 git。演示证书大约有效至 2029-02-21（UTC）。轮换时需要同时更新 Android 钉扎的 CA 公钥并重签服务器证书。

Aliyun 安全组需要放行 TCP **8443**。机器上的 host firewall（ufw/iptables/firewalld）默认未拦截；若公网仍连不上，多半是安全组未放行。

## 4. 用 systemd 启动

单元文件见同目录 `kgram-gateway.service`。

```bash
# 在服务器上创建系统用户 kgram，安装二进制、证书与单元，然后：
systemctl daemon-reload
systemctl enable --now kgram-gateway
systemctl status kgram-gateway --no-pager
```

确认（不要用会打印敏感头的方式）：

```bash
curl -s --cacert /etc/kgram/tls/ca-cert.pem https://8.136.150.149:8443/health
# 本机环回可用 -k，因为证书 SAN 只有公网 IP：
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

未在安全组放行 8443、或未部署本网关之前，新客户端无法登录演示环境。
