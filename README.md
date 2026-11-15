# hongguoTV

手机统一管理 + 电视播放

## v0.5.0

- **覆盖安装**：固定签名 keystore，版本号递增，可直接安装升级
- **UI 资源包**：统一配色、图标、按钮焦点样式、电视/手机主题

### 安装

直接安装新 APK 即可覆盖旧版（`applicationId` 不变）。  
若提示签名冲突，需先卸载旧测试包（早期未固定签名的构建）。

### 结构

- `phone`：连接 / 片源 / 设置 / 遥控
- `tv`：扫码绑定 + 播放器
- `keystore/`：发布签名（保证覆盖安装）

## App 源（v0.6）

设备注册与 x-argus/x-ladon 签名改编自：

- [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) `kotlin-tv/core`（GPL-3.0）
- [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) `playback-device.js` / `playback-signature.js`（GPL-3.0）

加载详情时会尝试为锁定集走 App 取流；失败则保留公共 Web 源。
