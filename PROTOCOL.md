# 协议 v0.3

- 手机 `8765`：状态/片源/控制/搜索/详情/**缓存流**
- 电视 `8766`：扫码绑定 register
- `GET /stream/current`：当前片源缓存流（支持 Range，无需配对头）
- `GET /api/search?q=`、`GET /api/detail?seriesId=`
- `POST /api/now` 支持 `useProxy` 与 `playlist`
- 电视播放地址优先为局域网代理 URL，降低 CDN 卡顿与防盗链问题
