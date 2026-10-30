# 待做批次（做完再一次性上机验证）

> 规则：以下全部在本地写完、编译通过即可；不再逐项装包。全部完成后一次性装包、按清单逐屏验证。

## A. 功能缺口
1. 读信页：译文替换正文（现在只弹译文前 40 字）
   - `DetailScreen.kt`：把正文 HTML 提成 `var bodyHtml by remember`；`Api.translate` 成功后 `bodyHtml = translated`，WebView 通过 `LaunchedEffect(bodyHtml)` 重新 `loadDataWithBaseURL`
2. 草稿箱
   - 最短路径：`SharedPreferences` 存 JSON（to / subject / text / at），最多 20 条
   - 写信页点「取消」时先落一条；抽屉「草稿」入口列表 → 点回填写信页
   - 备注：网页版草稿在 IndexedDB，后端无表；要跨端就加 `draft` 表
3. 全部邮件页：清理入口（`POST /email/physicsDelete` 单封 / 批量）
4. 收件箱：下拉刷新（`pullRefresh`），未读数用 `menu-counts` 的真实值

## B. 一致性
5. 三处返回键（系统设置 / 设置 / 管理页）已换 `tapArea`，**尚未真机确认**
6. 管理页空状态 / 错误态统一（现在只显示一行红字）
7. 深色主题逐屏走查；横屏（`user_rotation 1`）两栏或至少不崩

## C. 收尾
8. 装包后按以下顺序验：收件箱 → 详情（点翻译）→ 写信（发一封带附件）→ 草稿 → 设置 → 系统设置（改一项保存）→ 管理四页 → AI
9. 把结果写进 `README.md`，并列出仍未接的真接口
