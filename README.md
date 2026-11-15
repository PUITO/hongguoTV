# hongguoTV

手机解析 / 推流 + 电视极简播放（局域网 companion）。

> 目标：旧电视不跑复杂 WebView 签名；手机完成登录与取源，电视只负责播。

## 模块

| 模块 | 包名 | 说明 |
|------|------|------|
| `phone` | `com.hongguotv.phone` | 手机端：局域网 HTTP 服务 + 设置当前片源 |
| `tv` | `com.hongguotv.tv` | 电视端：输入 IP/配对码，拉取片源并播放 |
| `shared` | `com.hongguotv.shared` | 协议常量 |

## 快速使用

1. 手机与电视连接 **同一 WiFi**
2. 安装并打开 **红果推流（phone）** → 启动服务 → 记住 IP 与配对码
3. 在手机填入可播的 `https` 地址并点「推送到电视」
4. 打开 **红果电视（tv）** → 输入 IP 与配对码 → 连接并播放

后续会把红果登录解析接到手机端，自动写入当前片源。

## 协议摘要

- 端口默认 `8765`
- `GET /api/status` 探活
- `GET /api/now` + 请求头 `X-Hongguo-Pair: 四位码` → `{ url, title, updatedAt }`
- `POST /api/now` 同配对头，body：`{"url":"https://...","title":"..."}`

详见 [PROTOCOL.md](PROTOCOL.md)。

## 构建

```bash
./gradlew :phone:assembleDebug :tv:assembleDebug
```

GitHub Actions 会在 push 到 `main` 时自动构建并上传 APK Artifact。

## 版本

- 0.1.0：协议骨架 + 手机推流服务 + 电视播放器 + CI
