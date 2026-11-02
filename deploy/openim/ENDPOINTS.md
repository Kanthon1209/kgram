# kgram OpenIM demo endpoints (Aliyun ECS)

**Host:** `8.136.150.149` (Ubuntu 24.04, 2C / ~1.7Gi RAM, 40G)  
**Deploy root on server:** `/opt/kgram/`  
**Stack:** openim-docker `v3.8.3-patch.1` (slim) — LiveKit deferred on 2Gi

## Public URLs (open Aliyun security group)

| Role | URL / Port | Notes |
|------|------------|--------|
| OpenIM API | `http://8.136.150.149:10002` | REST for SDK |
| OpenIM WebSocket | `ws://8.136.150.149:10001` | Msg gateway |
| Chat / Register API | `http://8.136.150.149:10008` | `openim-chat` — **registration/login business API** |
| Admin API | `http://8.136.150.149:10009` | Admin |
| MinIO (files) | `http://8.136.150.149:10005` | Object storage for clients |

### Security group (inbound)

Must allow from your test clients (or `0.0.0.0/0` for demo):

- TCP **22** (SSH)
- TCP **10001** (WS)
- TCP **10002** (OpenIM API)
- TCP **10008** (Chat register/login) ← **needed for registration page**
- TCP **10009** (optional admin)
- TCP **10005** (MinIO uploads; optional for register-only)

HTTPS/80/443 not configured yet (plain HTTP demo).

## Register API (OpenIM Chat)

Typical paths (OpenIM Chat v1.8.x):

- Register: `POST http://8.136.150.149:10008/account/register`
- Login: `POST http://8.136.150.149:10008/account/login`

Exact JSON fields follow OpenIM Chat OpenAPI (`userID` / `password` / `nickname` etc.).  
After login, use returned tokens with OpenIM SDK against API `10002` + WS `10001`.

Default demo secret (server `.env`): `OPENIM_SECRET=openIM123` (change for anything beyond demo).

## Ops on server

```bash
cd /opt/kgram/openim-docker
docker compose up -d mongo redis etcd kafka minio openim-server openim-chat
docker compose ps
docker compose logs -f openim-server openim-chat
docker compose stop openim-chat   # if OOM
docker compose down               # full stop
```

## Memory reality (2Gi)

Full stack (deps + openim-server + openim-chat) is **tight / may OOM**.  
If SSH dies after starting chat: reboot ECS in Aliyun console, then start **without** LiveKit/UI; prefer **4Gi** upgrade.
