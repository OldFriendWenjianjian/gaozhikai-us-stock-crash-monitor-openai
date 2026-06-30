# 高志凯美股崩盘概率预测程序（OpenAI 版）最终验收

日期：`2026-06-30`

## 1. GitHub 项目结构

- 仓库：`OldFriendWenjianjian/gaozhikai-us-stock-crash-monitor-openai`
- 顶层目录已按要求存在：
  - `mobile/`
  - `server/`
  - `source/`
- 验证基线提交：`a77530e`

## 2. 服务端部署

- 服务器：`ubuntu@[2402:4e00:c013:8600:5602:3dc2:a2d0:0]`
- 部署根目录：`/opt/gaozhikai-us-stock-crash-openai`
- 运行目录：`/opt/gaozhikai-us-stock-crash-openai/repo/source/dashboard-openai`
- 配置文件：`/opt/gaozhikai-us-stock-crash-openai/deploy/gaozhikai-us-stock-crash-openai.env`

当前已验证配置：

- `OPENAI_BASE_URL=https://www.niumacode.cc/v1`
- `OPENAI_ANALYSIS_MODEL=gpt-5.5`
- `OPENAI_SEARCH_MODEL=gpt-5.4-mini`
- `COLLECT_INTERVAL_MINUTES=0`

当前已验证运行状态：

- `/api/health` 正常
- `gaozhikai-us-stock-crash-openai-refresh-0755.timer` 正常
- 定时晨刷时间：`07:55`

## 3. SharedServerDocs 资料

- 已补充项目资料：
  - `C:\Users\a1258\Documents\SharedServerDocs\projects\gaozhikai-us-stock-crash-openai.md`

## 4. 移动端交付

- 包名：`com.codexrisk.widget`
- 真机设备：`ZY22L34FHN`
- 型号：`XT2437_4`
- 安装用户：仅 `user 0`

AI 视觉资产：

- `assets/branding/gaozhikai-app-icon-ai.png`
- `assets/branding/gaozhikai-app-icon-ai-round.png`
- `assets/branding/gaozhikai-mobile-ui-concept-ai.png`

主界面已验证内容：

- 崩盘概率
- 风险等级
- 关键触发因素
- 运行状态
- 手动刷新按钮
- 打开完整面板按钮
- 添加桌面挂件按钮

## 5. 挂件与自动刷新

真机权限与调度：

- `SCHEDULE_EXACT_ALARM: allow`
- 手机系统中已存在 `com.codexrisk.widget.PRE_TARGET_REFRESH`
- 下一次定时触发时间为次日 `07:55`

服务器晨刷执行记录：

- `2026-06-30 07:55:00` 开始执行
- `2026-06-30 07:58:35` 完成刷新

桌面挂件已真实落地的关键证据：

- `dumpsys appwidget` 中，`com.motorola.launcher3` 下已出现：
  - `provider=ComponentInfo{com.codexrisk.widget/com.codexrisk.widget.RiskWidgetProvider}`
- `com.motorola.launcher3` 的 `widgets.size` 已变为 `3`
- 本地验收工件：
  - `mobile/android-app/artifacts/final-audit/home-with-widget.xml`
  - `mobile/android-app/artifacts/final-audit/home-with-widget.png`

挂件内容已验证字段：

- 标题：`高志凯美股崩盘概率`
- 分数：`31`
- 阶段：`风险中等`
- 触发因素：`美联储利率与降息路径`

## 6. 结论

本项目已完成以下目标：

- 服务端已部署到个人服务器
- GitHub 同仓库已包含移动端、服务端、源码
- 真机新 App 已完成安装与界面交付
- AI 图标与视觉方案已交付
- 桌面挂件已真实落到手机桌面
- 挂件已具备每天早上 `08:00` 前自动刷新所需的服务端和手机端链路
