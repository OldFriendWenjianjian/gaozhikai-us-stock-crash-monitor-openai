# 美股崩盘概率 · AI 实时监控台

基于高志凯「明年年底可能爆发金融危机」的思路，让 AI（OpenAI / GPT）实时联网采集信息，
在页面顶部给出「美股崩盘概率 XX.XX%」，并列出各动态监控项（事件 → 概率 ±X.XX%）。
可与 AI 聊天讨论，也可用大白话新增自己的监控项。

## 运行

```bash
cp .env.example .env       # 填入 OPENAI_API_KEY
npm install
npm start                  # → http://localhost:3000
```

## 配置（.env）

| 变量 | 说明 |
|---|---|
| `OPENAI_API_KEY` | 必填。OpenAI API Key（或兼容 OpenAI Responses API 的 key） |
| `OPENAI_BASE_URL` | 可选。留空用 OpenAI 官方端点；填兼容网关地址即可换端点 |
| `LLM_MODEL` | 可选。默认 `gpt-5.5`，随时换型号 |
| `BASE_PATH` | 可选。部署到共享服务器子路径时填写，例如 `/us-stock-20260629/crash-monitor/` |
| `PORT` / `BASELINE` / `COLLECT_INTERVAL_MINUTES` | 可选 |

> 换模型/端点：改 `.env` 重启即可。端点须兼容 OpenAI Responses API；
> 换到不支持内置网页搜索工具的端点时，联网采集会降级（该项标记采集失败），其余功能照常。

## 共享服务器入口

服务端对外建议至少保留这些入口：

- `/health`
- `/download`
- `/api/download`
- `/latest.json`
- `/api/health`

## 概率怎么算

`总概率 = clamp(基线 + Σ(各项 严重度×权重×方向), 0, 100)`，保留两位小数。
每个监控项的「严重度(0~1)」由 AI 联网评估，权重与方向在监控项配置里。

## 数据

所有状态存在程序同目录的 `state.json`（人可直接读）；配置存 `config.json`。删除 `state.json` 会用预置项重新初始化。

## 测试

```bash
npm test
```

## 打包成 Windows 单文件（傻瓜一键启动）

```bash
npm install            # 首次
npm run build:win      # 产出 dist/美股崩盘概率.exe（需联网下载 Win 版 Node 基座）
```

把 `dist/美股崩盘概率.exe` 拷到任意目录，**双击**即可：
- 自动起本地服务并打开浏览器；
- 首次进入显示「配置大模型」向导，填入 API Key / Base URL（选填）/ 模型名，点「保存并进入」；
- 配置与运行数据存在 exe 同目录的 `config.json` 与 `state.json`（可直接用记事本查看）。

> 构建在 Linux 上交叉编译 Windows 二进制（pkg + node22-win-x64 基座）；Windows 上的双击实跑请在 Windows 机器验证。
> 用户机器无需安装 Node。换模型：点右上角 ⚙ 设置，或直接编辑 `config.json` 后重启。
