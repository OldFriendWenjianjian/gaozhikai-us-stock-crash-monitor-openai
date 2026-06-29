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

## 当前目标

- 服务端部署到共享服务器。
- 移动端、服务端、源码放到 GitHub 同一个项目里，按目录管理。
- 新安卓 App 采用更完整的产品界面、图标和挂件样式。
- 挂件无需手动打开 App，在每天早上 8 点前自动刷好当天预测。

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

## 状态说明

- `source/dashboard-openai` 已经切到 OpenAI / GPT 调用方案。
- `mobile/android-app` 基于现有风险挂件工程复用自动刷新骨架，后续重做界面、品牌和数据契约。
- `server` 将补充共享服务器部署所需的基础设施文件。
