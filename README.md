# 高志凯美股崩盘概率预测程序（OpenAI 版）

同一个项目下包含三部分：

- `source/dashboard-openai/`
  说明：
  OpenAI 版美股崩盘概率监控台源码，负责预测数据生成、Web 仪表盘和下载接口。
- `mobile/android-app/`
  说明：
  安卓 App 与桌面挂件工程，要求每天早上 8 点前自动刷新当天预测结果。
- `server/`
  说明：
  面向个人服务器的部署模板、systemd/Caddy 配置、发布脚本和接入文档。

## 已完成交付

- 服务端已部署到个人服务器，并补齐 `SharedServerDocs` 项目资料。
- 移动端、服务端、源码已放到 GitHub 同一个项目里，按目录管理。
- 新安卓 App 已完成 AI 图标、AI 视觉概念图、主界面和桌面挂件交付。
- 挂件已在真机桌面落地，并在每天早上 `07:55` 自动刷新当天预测结果。

## 目录结构

```text
source/
  dashboard-openai/
mobile/
  android-app/
server/
docs/
assets/
```

## 当前状态

- `source/dashboard-openai` 已经切到 OpenAI / GPT 调用方案。
- `mobile/android-app` 已完成真机安装、挂件落桌和自动刷新验证。
- `server` 已完成共享服务器部署，搜索模型为 `gpt-5.4-mini`，分析模型为 `gpt-5.5`。
- 个人服务器接入资料位于 `C:\Users\a1258\Documents\SharedServerDocs\projects\gaozhikai-us-stock-crash-openai.md`。

## 验收记录

- 项目最终验收说明见 `docs/2026-06-30-final-audit.md`。
