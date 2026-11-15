# 局域网推流协议 v0.2

## 约定

- 手机服务端口：`8765`
- 电视绑定端口：`8766`
- 配对头：`X-Hongguo-Pair: <4位数字>`
- 二维码：
  - 手机展示：`HGTV1|{phoneIp}|8765|{pair}`
  - 电视展示：`HGTV2|{tvIp}|8766|{session}`（手机扫码绑定）

## 扫码绑定流程

1. 电视启动绑定服务，展示 `HGTV2` 二维码  
2. 手机启动推流服务，扫描电视码  
3. 手机 `POST http://{tvIp}:8766/api/register`  
   `{ "session","phoneIp","port","pair" }`  
4. 电视保存手机地址，拉取 `/api/now` 并播放  

## 接口（手机 8765）

### GET /api/status
### GET /api/now  （含 playing/speed/autoNext/seekMs/commandId）
### POST /api/now  `{url,title}` 或 `{playlist:[{url,title}], index}`
### GET|POST /api/control  
POST body 示例：
- `{"action":"play"}` / `pause` / `toggle` / `next` / `prev`
- `{"action":"speed","value":1.5}`
- `{"action":"autonext","value":true}`
- `{"action":"seek","positionMs":120000}`

电视约每 1.2s 轮询 control/now，跟随手机控制。
