# 动效与手感规范（otsmail-android）

这份文件是**硬约束**，写给人和 agent 都看。数值来自 apple-design（WWDC *Designing Fluid Interfaces*，Emil Kowalski 版）里的物理量，换成 Compose 的参数。

改交互前先看第 4、5 节；写代码看第 6 节。

---

## 1. 三条原则

1. **手指按住的那一刻就必须有反馈**，不能等抬手。
2. **任何动画都能被打断**：从当前屏幕上的位置续上，不是从头播放，也不是跳到目标值。
3. **动效只服务理解**：解释"东西从哪来、到哪去、发生了什么"。解释不了的就不要加。

## 2. 弹簧参数

Apple 用 `damping(0..1) + response(秒)`；Compose 用 `dampingRatio + stiffness`，质量归一（Compose 默认 mass=1）时：

```
stiffness = (2π / response)²
```

| 用途 | damping | response | Compose |
|---|---|---|---|
| 默认移动 / 重定位 | 1.0 | 0.4 | `OTSpring.move()` → stiffness **247** |
| 松手后的惯性回弹 | 0.8 | 0.4 | `OTSpring.momentum()` → 247 |
| 抽屉 / 上拉面板 | 0.8 | 0.3 | `OTSpring.sheet()` → **439** |
| 快速归位、禁止超调 | 1.0 | 0.3 | `OTSpring.settle()` → 439 |

> ⚠️ 不要用 Compose 自带的 `StiffnessMedium(1500)`。它比 Apple 的节奏快 3 倍以上，手感"发硬"，是最常见的翻车点。

**超调只允许出现在"手指真的甩过"之后**。淡入的菜单不允许回弹。

## 3. 手势数值

| 量 | 值 | 说明 |
|---|---|---|
| 横向判定滞回 | **10dp** | 小于它算点击；大于它才进入拖拽 |
| 右边缘守护 | **24dp** | 这段不接管，留给系统返回手势 |
| 左滑归档阈值 | 行宽 **42%** | 或速度 < **-1200 px/s** |
| 橡皮筋常数 | **0.55** | `(over × dim × 0.55) / (dim + 0.55 × |over|)` |
| 动量投射 | `d ≈ 0.998` | `current + (v/1000) · d/(1-d)`，**不是** `v²/2a` |
| 按下缩放 | **0.97**，90ms 进入 | 松手用弹簧回弹 |

**松手判方向看速度的符号，不看位置的符号。** 位置只用来算投影落点。

## 4. 频率分级：什么该动，什么不该动

| 频率 | 例子 | 处理 |
|---|---|---|
| 每秒多次 | 滚动、输入、切行选中态 | **不加过渡**，状态瞬时切换 |
| 每天几十次 | 点击邮件、切分类、翻页 | 只留按下反馈（ripple / 0.97 缩放） |
| 每天几次 | 打开读信、写邮件、切账号 | 短过渡 120–200ms 或 `move()` 弹簧 |
| 稀有 | 发送成功、删除、登录成功、批量操作 | 这才配得上 300ms 以上的动效 |

**列表项进场不要做 stagger 逐个淡入。** 收件箱是高频刷新区域，每次进页面播一遍等于噪音。

## 5. 安卓例外（照抄 apple-design 会错的地方）

| 项 | apple-design 原话 | 安卓上这么做 |
|---|---|---|
| 按下反馈 | `scale(0.97)` | 列表行用 M3 默认 **ripple**（安卓预期）；卡片、FAB 才用 `pressScale()` |
| 左边缘 / 右边缘 | 不限制 | 屏幕边缘 24dp 不接手势，否则和系统返回打架 |
| 返回键 | 无此概念 | sheet / 多选态 / 抽屉打开时，`BackHandler` 先关这一层，**不能退页面** |
| 毛玻璃 | `backdrop-filter: blur(20px)` | 中低端机很贵。用纯色 + 半透明，别做大面积实时模糊 |
| 触感 | 与视觉同帧 | 安卓振子弱、机型差异大，只用于"成功 / 失败"，别用于滚动吸附 |
| 减少动画 | `prefers-reduced-motion` | 读系统的 `ANIMATOR_DURATION_SCALE`，为 0 时把弹簧换成 `snap()` |
| 滚动惯性 | 自己算动量 | 系统滚动的惯性抢不过来，列表滚动交给系统，只有自定义手势才自己算 |

## 6. 代码入口

### `ui/motion/OTMotion.kt`

```kotlin
OTSpring.move()      // 默认
OTSpring.momentum()  // 松手后
OTSpring.sheet()     // 面板
OTSpring.settle()    // 归位

rubberBand(overshoot, dimension)          // 越界衰减
projectMomentum(velocityPxPerSec)         // 投影落点

Modifier.pressScale(interactionSource)    // 卡片 / FAB 的按下反馈
```

### `ui/motion/SwipeToArchiveRow.kt`

```kotlin
SwipeToArchiveRow(
    onArchive = { id -> scope.launch { Api.emailDelete(id) } },
) {
    MailRow(...)   // 原样放进来，别改它的背景
}
```

它已经包含：跟手、滞回、边缘守护、速度交接、动量投射、橡皮筋、可打断。不要在外面再套一层 `clickable` 的拖动，会互相抢事件。

**接入位置**：`InboxScreen.kt` 的 `items(items, key = ...)` 里，把 `MailRow(...)` 换成 `SwipeToArchiveRow(onArchive = { ... }) { MailRow(...) }`。

## 7. 验收清单

在**真机**上过一遍（模拟器不准）：

- [ ] 按下立刻有反馈，抬起前就能看到
- [ ] 拖动时内容粘在手指上，抓哪儿跟哪儿，不跳到中心
- [ ] 拖到一半反向，不跳、不卡顿、不重播
- [ ] 轻甩一下就能归档（不用拖满 42%）
- [ ] 慢慢拖过 42% 再松手，也能归档
- [ ] 拖一点点松手，弹回原位的动画不停顿、不超调
- [ ] 往右拖有阻尼感，不是撞墙
- [ ] 从屏幕右边缘起手，出的是系统返回，不是归档
- [ ] 竖向滚动完全不受影响
- [ ] 开「无障碍 → 移除动画」后动效消失但不影响功能

---

### 这份文档的维护方式

改参数只改 `OTMotion.kt` 和本文档两处，别在页面里写裸数字。新增交互时先在第 4 节找到它的频率档，再决定要不要动画。
