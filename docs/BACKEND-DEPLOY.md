# mail-worker 上线清单（客户端依赖的后端改动）

> 位置：`E:\DeepSeek workers\otsmail\mail-worker`（当前**未提交、未部署**，线上仍是旧代码）
> 客户端：`com.otscup.otsmail` 0.2.0 起依赖下表中的接口与修复

## 一、必须先跑的本地验收

```powershell
cd E:\DeepSeek workers\otsmail\mail-worker
npm run check                                  # wrangler dry-run 打包校验，应 Total Upload ≈ 5191 KiB
# 另开一个窗口：
npx wrangler dev --config wrangler-dev.toml --port 8789 --local
# 首次或换机器：GET http://127.0.0.1:8789/api/init/dev-only-change-me
npm run smoke:mobile                           # 21 项冒烟，应 21 passed, 0 failed
```

## 二、7 个 bug 修复（都影响网页版，不是只为手机）

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 1 | `src/service/star-service.js:81` | 调了不存在的 `emailService.toListText()`，`GET /star/list` **必然 500**（网页版星标页也坏） | 改成 `listText = String(text\|\|'').replace(/\s+/g,' ').trim().slice(0,120)` |
| 2 | `src/service/att-service.js` `saveArticleAtt` | 站内图只写 key、不发 buff，而这里 `if (!attData.buff) continue` → 图永远不落附件行，正文却已成 `cid:` | 改成 `if (!attData.key) continue;` + 有 buff 才上传 |
| 3 | 同上 `toImageUrlHtml` | 末尾 `filter(image => image.content)` 把还没入库的站内图滤掉 | 查库补 size/filename/mime 后，**总是**按 key 取字节 |
| 4 | `src/index.js` `/attachments/**` | 只读 KV（`kvObjService`），R2/S3 存储取图必 500 `Incorrect type for Promise` | 新增 `r2Service.publicObjResp`，按 `storageType()` 分派 KV/R2/S3，取不到回 404 |
| 5 | `src/utils/jwt-utils.js:51` | 未登录时 `token.split('.')` 抛 `TypeError`，`websiteConfig` 每次都崩一遍 | `verifyToken` 开头 `if (!token \|\| typeof token !== 'string') return null;` |
| 6 | `src/service/email-service.js` read/unread | 传非数组时 `inArray(undefined)` → D1 `D1_TYPE_ERROR` 500 | 加 `if (!Array.isArray(emailIds) \|\| emailIds.length === 0) return;` |
| 7 | `src/service/att-service.js:175,184` | 只认 `att.type`，客户端传 `contentType` → R2 对象没有 `Content-Type`，下载全变 octet-stream | 统一 `att.mime = att.type \|\| att.contentType \|\| 'application/octet-stream'` |

## 三、两个新接口（手机端在用）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/my/uploadImage` | 正文插图；body `{base64}`，限 `png/jpeg/webp/gif`、≤5MB；按内容哈希存 `attachments/<hash>.<ext>`，返回 `{key,url}` |
| `PUT` | `/my/setting` | 个人设置；body `{signature?, sendOnEnter?}`，`sendOnEnter` 只收 `enter\|ctrl\|off`（存 0/1/2），回读返回规范化值 |

配套：`src/entity/user.js` 加 `signature` / `sendOnEnter` 两列；迁移脚本 `dev/migrate-mobile-settings.sql`（**线上 D1 已执行过**，新环境仍需跑一次）。

## 四、部署前必须确认

1. `git status --porcelain` —— 工作区里混着**他人的未提交改动**：`mail-vue/src/components/email-scroll/index.vue`、`mail-vue/src/views/sys-setting/index.vue`、`mail-vue/src/layout/aside/index.vue`、`mail-vue/src/request/email.js`、`mail-worker/src/api/email-api.js`、`src/email/email.js`、`src/entity/setting.js`、`src/hono/hono.js`、`src/init/init.js`、`src/service/setting-service.js`、`wrangler-dev.toml`。deploy 会**一起上生产**，需先与作者确认。
2. `wrangler-dev.toml` 里的 `allow_origin`（本地 vite 8791/3001）与 `[[r2_buckets]]` 是**本地开发配置**，不要带进生产。
3. 线上 D1 已加列；`storageType`/`r2Domain` 若为空，插图会走本站域名，属正常配置。

## 五、部署后验证（3 条就够）

1. `GET /api/setting/websiteConfig` 200 且响应头里没有白名单 origin（生产应保持同源限制）；
2. 登录后 `GET /api/star/list?size=20&page=1` **200 且 total 正确**（这条是 bug #1 的回归点）；
3. 任意 `GET /attachments/<已存在的 key>` 返回 200 + 正确的 `Content-Type`（bug #4/#7 的回归点）。
