# kgram OpenIM demo endpoints (Aliyun ECS)

**Host:** `8.136.150.149` (Ubuntu 24.04, ~3.5Gi RAM)  
**Deploy root:** `/opt/kgram/` (compose: `/opt/kgram/openim-docker`)  
**Status:** openim-server + openim-chat **healthy** (verified register/login)

## Public URLs

| Role | URL |
|------|-----|
| **Chat Register / Login** | `http://8.136.150.149:10008` |
| OpenIM API | `http://8.136.150.149:10002` |
| OpenIM WebSocket | `ws://8.136.150.149:10001` |
| Admin API | `http://8.136.150.149:10009` |
| MinIO | `http://8.136.150.149:10005` |

### Security group (TCP inbound)

`22`, `10001`, `10002`, `10008`, optional `10009`, `10005`

### Register (verified)

```bash
curl -sS -X POST "http://8.136.150.149:10008/account/register" \
  -H "Content-Type: application/json" \
  -H "operationID: demo-1" \
  -d '{
    "verifyCode":"666666",
    "platform":1,
    "autoLogin":true,
    "user":{
      "nickname":"demo",
      "areaCode":"+86",
      "phoneNumber":"13800138000",
      "password":"test123456"
    }
  }'
```

Super verify code default: `666666`.  
Login: `POST http://8.136.150.149:10008/account/login` with `platform`, `areaCode`, `phoneNumber`, `password`.

SDK: `apiAddr=http://8.136.150.149:10002`, `wsAddr=ws://8.136.150.149:10001`.

## Ops

```bash
cd /opt/kgram/openim-docker
docker compose up -d mongo redis etcd kafka minio openim-server openim-chat
docker compose stop
```

LiveKit deferred. See `/opt/kgram/README.md` on the server.
