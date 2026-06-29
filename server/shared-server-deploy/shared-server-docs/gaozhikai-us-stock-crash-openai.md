# 高志凯美股崩盘概率预测程序（openai版） Server Notes

## Identity

- App name: 高志凯美股崩盘概率预测程序（openai版）
- English project id: `gaozhikai-us-stock-crash-openai`
- Recommended public host: `__PUBLIC_HOST__`
- Public base path: `/us-stock-20260629/crash-monitor/`
- Remote root: `/opt/gaozhikai-us-stock-crash-openai`
- Repo app directory: `/opt/gaozhikai-us-stock-crash-openai/repo/source/dashboard-openai`
- Local service: `127.0.0.1:3080`
- SSH user: `ubuntu`
- SSH key path on local Windows machine: `C:\Users\a1258\Documents\SharedServerDocs\KEY2.pem`
- Systemd service name: `gaozhikai-us-stock-crash-openai`
- Environment file: `/opt/gaozhikai-us-stock-crash-openai/deploy/gaozhikai-us-stock-crash-openai.env`

## Public URLs

- Service root: `https://__PUBLIC_HOST__/us-stock-20260629/crash-monitor/`
- API health: `https://__PUBLIC_HOST__/us-stock-20260629/crash-monitor/api/health`
- Runtime state: `https://__PUBLIC_HOST__/us-stock-20260629/crash-monitor/api/state`

## API Overview

- `GET /api/health`
- `GET /api/state`
- `POST /api/refresh`
- `POST /api/config`
- `POST /api/test`
- `POST /api/monitors`
- `DELETE /api/monitors/:id`
- `POST /api/chat`

## Runtime Notes

- Start command: `npm start`
- Working directory must stay on `source/dashboard-openai`, because the app writes `config.json` and `state.json` into `process.cwd()`
- `NO_OPEN=1` is required on shared servers to suppress local browser auto-open
- Current frontend now supports shared-server subpath deployment when `BASE_PATH` matches the public route
- Morning refresh should call `http://127.0.0.1:3080/api/refresh` directly from local scripts, not from the public internet

## Caddy Route

```text
__PUBLIC_HOST__ {
    handle_path /us-stock-20260629/crash-monitor/* {
        encode zstd gzip

        @blockRefresh {
            path /api/refresh
            not remote_ip 127.0.0.1 ::1
        }
        respond @blockRefresh "refresh endpoint is private" 403

        reverse_proxy 127.0.0.1:3080
    }
}
```

## Morning Refresh Schedule

- 07:45 Asia/Shanghai
- 07:55 Asia/Shanghai
- 08:00 Asia/Shanghai

Suggested units:

- `gaozhikai-us-stock-crash-openai-refresh@.service`
- `gaozhikai-us-stock-crash-openai-refresh-0745.timer`
- `gaozhikai-us-stock-crash-openai-refresh-0755.timer`
- `gaozhikai-us-stock-crash-openai-refresh-0800.timer`

## Deployment Verification

Planned checks after first deployment:

- `curl http://127.0.0.1:3080/api/health`
- `curl http://127.0.0.1:3080/api/state`
- `systemctl status gaozhikai-us-stock-crash-openai.service`
- `systemctl list-timers | grep gaozhikai-us-stock-crash-openai-refresh`
- Open `https://__PUBLIC_HOST__/` and confirm the dashboard can fetch `/api/health`

## Known Risks

- The public subpath and `BASE_PATH` must stay aligned after every deployment
- `POST /api/refresh` has no app-layer authentication; access should stay local-only or be blocked at the proxy layer
- Runtime data currently lives beside the app code, so release procedures need to preserve `config.json` and `state.json`
