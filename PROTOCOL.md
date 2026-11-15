# 局域网推流协议 v0.1

## 约定

- 传输：HTTP/1.1，JSON，UTF-8
- 默认端口：`8765`
- 配对：请求头 `X-Hongguo-Pair: <4位数字>`
- 手机与电视须在同一局域网；电视访问手机的私网 IP

## 接口

### `GET /api/status`

无需配对。

```json
{ "ok": true, "service": "hongguoTV-phone", "version": "0.1.0", "pairRequired": true, "hasNow": false }
```

### `GET /api/now`

需要配对。

```json
{ "ok": true, "url": "https://cdn.example/video.mp4", "title": "剧名 第1集", "updatedAt": 1730000000000 }
```

### `POST /api/now`

需要配对。Body：

```json
{ "url": "https://cdn.example/video.mp4", "title": "可选标题" }
```

`url` 必须是 `http://` 或 `https://`。

### 后续（规划）

- `GET /stream`：手机代理拉流（应对 CDN 防盗链）
- `POST /api/control`：暂停 / 下一集
- UDP 发现：自动列出手机端
