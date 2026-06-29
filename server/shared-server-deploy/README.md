# 高志凯美股崩盘概率预测程序（openai版）共享服务器部署包

这套文件只覆盖共享服务器接入层，不改业务源码，默认面向 Linux + `systemd` + `Caddy`。

## 目录说明

- `systemd/`: 常驻服务与晨间单次刷新定时器模板
- `caddy/`: 反向代理路由片段
- `env/`: 环境变量样例
- `scripts/`: 07:55 晨间刷新脚本模板
- `shared-server-docs/`: 可复制到 `SharedServerDocs/projects` 的项目接入文档草稿

## 推荐远端目录

建议在共享服务器上使用以下布局:

```text
/opt/gaozhikai-us-stock-crash-openai/
  repo/                         # 完整仓库
    source/dashboard-openai/    # Node 应用实际运行目录
  deploy/
    gaozhikai-us-stock-crash-openai.env
  shared-server/
    systemd/
    caddy/
    scripts/
```

其中:

- `repo/` 放完整仓库，因为运行入口在 `source/dashboard-openai`
- `source/dashboard-openai` 既是代码目录，也是默认 `config.json` / `state.json` 数据目录
- `shared-server/` 可直接拷贝当前目录内容

## 部署前需要替换的占位符

以下文件里包含占位符，落地前统一替换:

- `__PROJECT_ROOT__`: 例如 `/opt/gaozhikai-us-stock-crash-openai`
- `__APP_WORKDIR__`: 例如 `/opt/gaozhikai-us-stock-crash-openai/repo/source/dashboard-openai`
- `__ENV_FILE__`: 例如 `/opt/gaozhikai-us-stock-crash-openai/deploy/gaozhikai-us-stock-crash-openai.env`
- `__LOCAL_PORT__`: 例如 `3080`
- `__PUBLIC_HOST__`: 例如 `gaozhikai-risk.example.com`
- `__SERVICE_USER__`: 例如 `ubuntu`
- `__SERVICE_GROUP__`: 例如 `ubuntu`

## 最小部署步骤

1. 把完整仓库同步到服务器，例如 `/opt/gaozhikai-us-stock-crash-openai/repo`
2. 在服务器的 `source/dashboard-openai` 内安装依赖: `npm install --omit=dev`
3. 复制 `env/gaozhikai-us-stock-crash-openai.env.example` 为真实 env 文件并填入密钥
4. 复制 `systemd/*.service`、`systemd/*.timer` 到 `/etc/systemd/system/`
5. 复制 `scripts/*.sh` 到 `__PROJECT_ROOT__/shared-server/scripts/` 并赋予执行权限
6. 执行 `systemctl daemon-reload`
7. 启用常驻服务: `systemctl enable --now gaozhikai-us-stock-crash-openai.service`
8. 启用晨间刷新定时器:
   `systemctl enable --now gaozhikai-us-stock-crash-openai-refresh-0755.timer`
9. 把 `caddy/gaozhikai-us-stock-crash-openai.Caddyfile` 片段合并到现有站点后重载 Caddy

## systemd 角色划分

- `gaozhikai-us-stock-crash-openai.service`: 常驻 Web 服务
- `gaozhikai-us-stock-crash-openai-refresh@.service`: 晨间刷新模板服务
- `gaozhikai-us-stock-crash-openai-refresh-0755.timer`: 07:55 刷新
- `COLLECT_INTERVAL_MINUTES=0`: 关闭服务进程内循环采集，只保留早上一次

## 验证清单

服务起来后至少验证:

1. `curl http://127.0.0.1:__LOCAL_PORT__/api/health`
2. `curl http://127.0.0.1:__LOCAL_PORT__/api/state`
3. `systemctl status gaozhikai-us-stock-crash-openai.service`
4. `systemctl list-timers | grep gaozhikai-us-stock-crash-openai-refresh`
5. 公网入口可打开首页，且仪表盘能正常读到 `/api/health`

## 已知限制

### 1. 共享服务器子路径部署需要同步 `BASE_PATH`

当前源码已经支持 `BASE_PATH`，并且前端接口使用相对路径。

- 如果走共享服务器子路径，请让 `BASE_PATH` 与反向代理暴露的公共路径保持一致
- 例如: `/us-stock-20260629/crash-monitor/`
- 反向代理可以选择保留前缀转发，也可以去掉前缀后再转发，但前后必须一致

### 2. `/api/refresh` 没有应用层鉴权

本部署模板默认让晨间脚本直接打本机端口，不经公网。
如果公网也暴露该接口，建议继续在 Caddy 或上游网络层限制访问。

### 3. 运行目录会生成 `config.json` 和 `state.json`

当前源码会把运行数据写入 `process.cwd()`，所以:

- `WorkingDirectory` 不能随意切到纯只读目录
- 代码升级时要注意保留同目录数据文件，或后续再改源码把数据目录独立出来
