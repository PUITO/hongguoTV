# 协议 v0.4

## 播放模式 playMode

- `proxy`：`url` = `http://{phoneIp}:8765/stream/current`
- `direct`：`url` = 源站 `sourceUrl`

## 设置 /api/settings

手机为唯一配置源。GET/POST JSON：

```json
{
  "playMode": "proxy|direct",
  "autoNext": true,
  "defaultSpeed": 1.0,
  "showTvOverlay": true,
  "tvKeepScreenOn": true,
  "allowTvLocalControl": true,
  "overlayHideMs": 5000,
  "revision": 3,
  "bound": true,
  "boundTvIp": "192.168.1.20"
}
```

`GET /api/now` 与 `GET /api/control` 响应中包含 `settings` 与 `playMode`，电视轮询应用。
