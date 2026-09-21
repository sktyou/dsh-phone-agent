---
name: phone-agent
description: 通过 DSH Phone Agent App 在局域网内操控 Android 手机——截图、读取界面节点、按文本/ID/状态查找控件、本地 OCR、找色找图、拟人化点击滑动。当需要检查或操作 Android 手机、移动 App、手游,或排查该 App 的连接与权限问题时使用。
---

# DSH Phone Agent

局域网内操控 Android 手机。**App 装在手机上,PC 或 Agent 通过 TCP/HTTP 连它。**

## 接入信息

| | |
|---|---|
| **TCP 控制** | `<手机IP>:7912` 一行一个 JSON,`\n` 分隔 |
| **HTTP 控制台** | `http://<手机IP>:7913/` 浏览器打开即用 |
| **实时画面** | `http://<手机IP>:7913/stream?scale=0.35&fps=5` (MJPEG) |

```json
请求: {"id":1,"cmd":"screenshot","scale":0.5}
响应: {"id":1,"ok":true,"data":{...},"elapsedMs":283}
错误: {"id":1,"ok":false,"error":"...","code":"not_ready"}
```

**令牌**:若在 App 里启用了访问令牌,每个请求加 `"token":"..."`。未启用则不需要。

## 先做这三件事

1. **`info`** — 确认连通、无障碍状态、前台应用
2. **`perms`** — **一次看清所有能力是否可用**(12 项自检)
3. **`screenshot`** — 看一眼真实画面。**别凭想象操作**

```json
{"cmd":"perms"}
→ {"okCount":12,"count":13,"items":[
     {"name":"无障碍服务","ok":true,"detail":"已连接"},
     {"name":"应用列表","ok":true,"detail":"337 个包"},
     {"name":"修改系统设置","ok":false,"detail":"亮度只读"}, ...]}
```

## 命令速查

### 看
| 命令 | 作用 |
|---|---|
| `info` / `device` / `perms` | 设备、状态、**能力自检** |
| `screenshot` | 截图(`scale` / `quality` / `format` / `region`) |
| `observe` | 截图 + 界面树一次返回 |
| `uitree` | 界面节点树(`interactiveOnly`) |
| `ocr` | 本地中文 OCR |
| `colorat` | 单点取色 |

### 找
| 命令 | 作用 |
|---|---|
| `locate` | **定位链**:viewId → text → 包含 → desc → OCR → 颜色,自动尝试并报告用了哪种 |
| `find` | 按选择器找控件(`viewId`/`text`/`desc`/`cls`/状态) |
| `findtext` | **按文字找控件**,融合树 + OCR,可解析到可点父节点 |
| `findcolor` | 单点找色(按簇返回) |
| `findmulti` | **多点找色**(锚点 + 偏移特征点) |
| `findimage` | 模板找图 |
| `sweep` | **滚动 + OCR 自动扫完整屏列表** |

### 做
| 命令 | 作用 |
|---|---|
| `tap` / `doubletap` / `longpress` | 点击(**带执行前安全检查**) |
| `swipe` | 滑动(**到达后自动刹车**,精准) |
| `swipemeasure` | 滑动 + **测量真实位移** |
| **`sequence`** | **动作连发**,多步一次调用、不做网络往返 |
| `flick` / `pinch` / `scroll` | 甩动 / 缩放 / 滚容器 |
| `text` / `send` / `delete` / `clear` | 输入 |
| `key` | HOME / BACK / RECENTS / NOTIFICATIONS / LOCK / SCREENSHOT 等 |
| `volume` / `brightness` / `wake` | 音量 / 亮度 / 唤醒 |
| `launch` / `stopapp` / `apps` | 应用 |
| `clipboard` / `deeplink` / `waitstable` / `wait` | 其它 |

### 诊断
| 命令 | 作用 |
|---|---|
| `incidents` | **未解决的执行事故** —— 失败动作会开启,成功才关闭 |
| `skill` | 返回本文档(方便复制给 Agent) |

## 三个值得优先用的命令

### `locate` —— 别猜坐标,也别猜该用哪个查找命令

```json
{"cmd":"locate","target":"登录"}
→ {"found":true,"strategy":"textContains","confidence":0.85,
   "center":[540,1204],
   "attempts":[{"strategy":"viewId","ok":false,"reason":"未命中"},
               {"strategy":"text","ok":false,"reason":"未命中"},
               {"strategy":"textContains","ok":true}]}
```

**传一个词就行**,内部依次试 6 种策略。`confidence` 告诉你该不该信:

| 策略 | 置信度 |
|---|---|
| `viewId` | 0.98 |
| `text` | 0.95 |
| `textContains` / `desc` | 0.85 |
| `ocr` | 0.7 |
| `color` | **0.5** ← 命中后最好再验证一次 |

### `sequence` —— 处理会消失的瞬时控件

```json
{"cmd":"sequence","steps":[
  {"action":"tap","x":540,"y":1200},
  {"action":"wait","ms":300},
  {"action":"tap","x":540,"y":1400}]}
```

**实测比分开调用快 2.9 倍**(3 次 tap:970ms → 333ms)。省下的是**每次 212ms 的
网络往返**,所以命令本身越快,收益越明显。

**用于** toast、一秒钟后淡出的控制栏、自动关闭的弹窗 —— 这些 UI 一步一次往返会输掉竞速。

`stopOnError` 默认 `true`,失败即停并返回已执行步数。

### `incidents` —— 知道有没有留下未完成的事

```json
{"cmd":"incidents"}
→ {"openCount":1,"open":[{"action":"tap","reason":"...不可点...","attempts":1}],
   "resolvedCount":0}
```

**动作失败会开启一条事故记录**,同类动作成功时**自动关闭**(实测闭环)。重复失败**合并计数**。

**用途**:脚本跑完问一句,就能发现没关掉的弹窗、没通过的权限提示。

## 执行前安全检查(Safety Net)

**`tap` / `longpress` / `doubletap` 默认检查目标位置有什么**:

```json
{"cmd":"tap","x":540,"y":26}
→ {"completed":true,"safety":{
     "code":"not-clickable",
     "hint":"(540,26) 处的节点及其 5 层祖先都不可点 —— 点击不会触发任何东西",
     "hit":{"cls":"View","bounds":[0,0,1080,95]}}}
```

| code | 含义 |
|---|---|
| `ok` | 命中可点节点 |
| `scrollable` | 可滚动容器,拖动有效但点击无效 |
| `not-clickable` | 该处及 5 层祖先都不可点 |
| `empty` | 那里什么都没有 |
| `no-root` | 拿不到节点树 |

**默认只报告不拦截** —— canvas / WebView / 游戏表面本来就没有无障碍节点。
加 **`abortOnUnsafe: true`** 才硬停(此时 `aborted:true`,手势不发出去)。

**为什么需要**:点击落在空白处**不会报错** —— 手势被接受、框架回 success、脚本继续跑。
**这是自动化里最糟的失败模式,因为它是隐藏的。**

## MCP:让任何 AI IDE 接入

```bash
node pc/mcp-server/index.mjs --host <手机IP>
```

**18 个工具,零依赖**,Claude Code / Cursor / Codex / Windsurf 都能接。截图返回真正的
`image` block,模型直接看得到画面。配置见 `pc/mcp-server/README.md`。

## ⚠️ IP 会变,而连错 IP 的现象极具误导性

手机 IP 变了之后,连**旧 IP** 会表现为:

- `ping` **通**(那个 IP 上有别的设备回 ARP)
- **TCP 永远建不了连接**(超时,而不是"连接被拒绝")

**看起来像 App 挂了或者死锁了**,实际只是连错了地址 —— 我为此排查了很久。

**权威来源是 App 首页显示的地址**。PC 桥、MCP 配置里缓存的 IP 都会静默失效。

## 七个测试台 Tab

`http://<手机IP>:7913/`

| Tab | 用途 |
|---|---|
| **手势** | 起止坐标、时长、刹车、轨迹(按速度着色)、真实位移 |
| **找色** | 单击取色、单点找色、**多点找色特征编辑** |
| **找图** | Shift+拖动选区取模板,**或选本地图片** |
| **识字** | OCR、按文字找控件、文字输入 |
| **节点** | 节点树、选中详情、**对该节点执行命令** |
| **设备** | 设备状态、**权限网格**、系统操作、音量亮度、滚动识别、应用 |
| **SKILL** | 本文档,一键复制 |

**图像交互**:单击 = 起点+取色 · 双击 = 终点 · 拖动 = 滑动 · **Shift+拖动 = 选区域** · 滚轮 = 滚动

**「实时 开」** 切换 MJPEG 长连接推流(约 2.5 fps,见下文速度一节)。
顶部**连接状态徽章**由真实流量推导:在线 / 迟滞 / 掉线。

## 更新功能就要更新这个文档

`skills/phone-agent/SKILL.md` 是**唯一真相源**,构建时由 Gradle 的 `syncSkillDoc`
任务自动复制进 APK 的 assets,页面「SKILL」tab 直接读它。

**所以加功能时改这里就够了** —— 说明和二进制不会脱节。

---

# 以下是设计笔记与踩坑记录

项目位置:`E:\DSH-Phone-Agent`

## 为什么用这个而不是 adb 方案

| | adb 方案(OpenGUI / ATX) | 本方案 |
|---|---|---|
| 连接 | USB 或无线调试配对 | 局域网 TCP,同 Wi-Fi 即可 |
| 前置 | adb server、开发者选项 | 仅需无障碍权限 |
| 滑动轨迹 | 直线 + 匀速 | 贝塞尔弧线 + ease-in-out + 抖动 |
| 文字输入 | `input text` 仅 ASCII / 剪贴板 | `ACTION_SET_TEXT`,原生支持中文 |
| 断连风险 | adb server 被抢占、配对失效 | 无 |

**adb 掉了不影响本方案** —— 这是它最大的实际优势,已在 DSH 重启导致 adb 断开的场景中验证过。

## 连接

手机端 App 界面会显示 `IP:端口`(默认 **7912**)。协议是 **一行一个 JSON** 的 TCP 流:

```json
{"id":1,"cmd":"ping"}
{"id":2,"cmd":"observe","includeUi":true}
```

响应:`{"id":1,"ok":true,"data":{...}}` 或 `{"id":1,"ok":false,"error":"..."}`。
单条失败**不断连接**。

电脑端有两种用法:

1. **DSH 插件**(工具名 `phone_lan`)—— 见 `E:\DSH-Phone-Agent\pc\dsh-plugin`
2. **自己写脚本** —— 完整协议见 `E:\DSH-Phone-Agent\docs\PROTOCOL.md`,参考实现 `pc/pyclient/phone_client.py`

## 常驻与保活

App 依赖两个独立的授权/机制,别把它们混为一谈:

| | 作用 | 掉了会怎样 |
|---|---|---|
| **常驻通知**(前台服务) | 让进程活着,端口继续监听 | 手机从电脑视野里"消失" |
| **无障碍服务** | 允许读界面、截图、注入手势 | 端口还在,但操作类命令全部失败 |

保活做了这几件事:

- `startForeground` + `setOngoing(true)`,通知不会被划掉
- 通知渠道用 `IMPORTANCE_LOW` 而非 `MIN`——`MIN` 会被系统折叠隐藏,而隐藏的通知
  等于用户无法察觉服务已死
- `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`(Android 12+),
  否则前台通知可能被延迟显示
- `android:stopWithTask="false"`——**从最近任务划掉 App 不会停服务**
- 通知上带「停止服务」按钮,不必进 App 就能关
- 服务用 `START_STICKY`,被系统回收后会重建

> 重装 App 会清空无障碍授权(受限设置),但**不影响前台服务**——这是两个独立状态,
> 排查时先看哪个掉了。

## 命令全表

| cmd | 作用 | 成本 |
|---|---|---|
| `ping` | 连通性 + 无障碍状态 | — |
| `info` | 设备与屏幕信息 | — |
| `observe` | 截图 + UI 树(一次往返拿齐) | 中 |
| `screenshot` | 仅截图 | 中 |
| `uitree` | 仅 UI 树 | 低 |
| `find` | **控件选择器**:按条件筛节点,只回命中项 | 低 |
| `findtext` | **按文本找控件**:融合控件树 + OCR,并解析可点击目标 | 低~高 |
| `ocr` | **本地 OCR**:识别屏幕文字 | 高 |
| `findcolor` | **找色**:返回同色区域(聚类) | 中 |
| `findimage` | **找图**:模板匹配 | 中~高 |
| `tap` / `longpress` | 点击 / 长按 | 低 |
| `swipe` | 滑动(`human:false` 可切机械直线) | 低 |
| `key` | 系统按键 | 低 |
| `text` | 向焦点输入框写文本(走 `ACTION_SET_TEXT`) | 低 |
| `send` | **提交输入**(IME 动作:发送 / 搜索 / 完成) | 低 |
| `delete` | **回删**(从末尾删 N 个字符) | 低 |
| `clear` | **清空输入框** | 低 |
| `launch` | 启动应用 | 低 |
| `wait` | 等待 | — |
| `device` | **设备状态**:电量/音量/亮度/网络/存储/内存/服务状态 | — |
| `doubletap` | **拟人双击** | 低 |
| `pinch` | **双指缩放** | 低 |
| `flick` | **惯性甩动** | 低 |
| `scroll` | 按**滚动容器**滚动一屏 | 低 |
| `waitstable` | **等待界面稳定** | — |
| `colorat` | **单点取色** | 低 |
| `wake` | 唤醒屏幕 | — |
| `clipboard` | 剪贴板读 / 写 / 粘贴 | — |
| `deeplink` | 打开 URI(直达页面) | — |
| `apps` | 已安装应用列表 | — |
| `stopapp` | 结束后台应用 | — |
| `volume` | 调节音量 | — |
| `brightness` | 调节亮度(需 `WRITE_SETTINGS`) | — |

## 坐标约定 ⚠️

**所有坐标一律是「全屏截图像素」,原点在屏幕左上角。**

`info` / `device` 的 `screenWidth` / `screenHeight` 走 `getRealMetrics`(**全屏**,含导航栏),
与截图一致。

> 别用 `resources.displayMetrics`——那是应用可用区域,手势导航机型上会少掉导航栏高度
> (实测 2261 vs 2400)。据此换算会让坐标整体偏移。

`observe` / `screenshot` 支持 `scale` 与 `region` 时,响应会一并给出
`screenWidth` / `screenHeight` / `scale` / `imageWidth` / `imageHeight`,换算规则:

```
触摸坐标 = 图像坐标 / scale + region左上角
```

## 通用参数

| 参数 | 适用 | 说明 |
|---|---|---|
| `retry` | 所有手势 | 被取消时重试 1–5 次,默认 1。手势取消通常是瞬时的 |
| `scale` | `observe` / `screenshot` | 0.05–1,缩小截图,边长减半省约 4 倍带宽 |
| `region` | `observe`/`screenshot`/`ocr`/`findcolor`/`findimage` | 限定范围,显著提速 |
| `interactiveOnly` | `observe` / `uitree` | 只回可操作节点(**扁平列表**),树缩到 1/10 |
| `typing` | `text` | `instant`(默认)/ `natural` 逐字符 + 随机间隔 |
| `mode` | `text` | `replace`(默认)/ `append` / `clear` |

## 系统控制与输入

```json
{"cmd":"wake"}
{"cmd":"clipboard","action":"get"}
{"cmd":"clipboard","action":"set","text":"要复制的文本"}
{"cmd":"clipboard","action":"paste"}
{"cmd":"deeplink","uri":"weixin://","package":"com.tencent.mm"}
{"cmd":"apps","query":"微信"}
{"cmd":"stopapp","package":"com.tencent.mm"}
{"cmd":"colorat","x":540,"y":1200}
```

**`wake` 很关键**:息屏时派发的手势会被系统**静默丢弃**——不报错、也不回调,是最难排查
的一类失败。操作前先 `wake`,并检查返回的 `locked`。它只点亮屏幕,**不解锁**。

**`paste` 是救命路径**:某些输入框拒绝 `ACTION_SET_TEXT`,只认真实输入事件,
此时只能先把文本写进剪贴板再 `paste`。

**`stopapp` 的诚实边界**:用的是 `killBackgroundProcesses`,**只回收已在后台的进程**。
前台应用杀不掉(那需要 shell 权限)。返回里的 `stillRunning` / `note` 会如实说明,
不会假装它是 force-stop。

## 滚动与等待

```json
{"cmd":"scroll","direction":"forward","index":0}
{"cmd":"waitstable","timeoutMs":5000,"stableFrames":2}
```

`scroll` 找第 `index` 个可滚动容器并下发 `ACTION_SCROLL_FORWARD`。比盲滑屏幕可靠:
手势锚定在容器边界内,不会误触浮动按钮或底部导航栏。

`waitstable` 靠连续相同的界面指纹判断"加载完了",比固定 `wait` 可靠,**并且会如实报告
是否真的稳定**——调用方能区分"已就绪"和"到点了还在变"。

## 设备状态

```json
{"cmd":"device"}
```

**不需要无障碍权限**,因此服务还没绑定时也能用它排查。返回身份、屏幕、电量、亮灭屏、
锁屏、音量、亮度、网络、存储、内存,以及**本服务自身的状态**(是否运行、端口、已运行
时长、客户端数、操作数、最近活动)。

要点:
- 取不到的值返回 `null` 或 `-1`,**不编造一个看起来合理的数**
- `brightness.writable` 表示是否已授予 `WRITE_SETTINGS`——先查再改,省一次失败
- `service.running` 与 `accessibility` 是两个独立事实:服务可能在跑但无障碍没开,
  此时除 `ping` / `info` / `device` 外的命令都会失败

## 调节音量与亮度

```json
{"cmd":"volume","stream":"music","action":"set","level":6}
{"cmd":"volume","stream":"ring","action":"mute"}
{"cmd":"brightness","percent":40}
```

- `volume` 的 `stream`:`music` / `ring` / `alarm` / `notification` / `voice`;
  `action`:`up` / `down` / `mute` / `unmute` / `set`
- 走 **AudioManager 而不是合成按键事件**——息屏时也能用,且不会把焦点抢给别的应用
- `brightness` 需要 **`WRITE_SETTINGS`**,这个权限**不能通过运行时对话框授予**,
  必须去「设置 → 应用 → 本 App → 修改系统设置」手动打开
- 写亮度时会**同时把亮度模式置为手动**,否则自动亮度机型会立刻覆盖回去

## 手势:全部拟人,且没有"精准"开关

四种手势默认都带人类特征,**这不是可选项**——`human:false` 只是留给 A/B 对照的开关,
正常使用不应打开:

| 手势 | 拟人化内容 |
|---|---|
| `tap` | 落点高斯散点(σ≈5.5px)、亚像素微滑、52–139ms 随机按压 |
| `doubletap` | 两次接触**间隔 88–209ms 随机**,第二次落点独立散开 |
| `swipe` | 贝塞尔弧线、三段 ease-in-out、法线方向生理性抖动(两端收敛) |
| `longpress` | 按住期间分 3 段微动(真人手指不会完全静止) |

**双击的实现细节值得记**:两次接触放在**同一条 GestureDescription 的两个 stroke** 里,
而不是发两次请求。框架正是靠"同一个输入序列内的两次接触"识别双击的,分两次往返反而不成立。

## 核心选择原则:按成本递进

**不要一上来就把截图丢给视觉模型。** 按这个顺序找目标,绝大多数情况在第一层就解决了:

| 层 | 手段 | 成本 | 覆盖 |
|---|---|---|---|
| 1 | `find` 控件树 | 毫秒级 | 原生控件(大多数界面) |
| 2 | `findtext` + `source:"ocr"` | 200–800ms | WebView、H5、游戏、Canvas、图片文字 |
| 3 | `findcolor` / `findimage` | 百毫秒级 | 无文字但颜色/图形稳定的目标 |
| 4 | `observe` 交给视觉模型 | 秒级 + token | 前三层都失败时兜底 |

### find —— 控件选择器

```json
{"cmd":"find","selector":{"textContains":"登录","clickable":true},"max":5}
```

字段(AND 关系,未提供的字段不约束):
`text` / `textContains` / `textRegex` / `desc` / `descContains` /
`viewId`(**按后缀匹配**,传 `btn_login` 即可匹配 `com.foo:id/btn_login`)/
`viewIdContains` / `cls` / `clickable` / `longClickable` / `scrollable` / `editable` /
`enabled` / `checked` / `selected` / `focused` / `depthMin` / `depthMax` / `scanLimit`

返回的 `center` 可直接喂给 `tap`。

### findtext —— 按文本找控件 ⭐

**关键坑**:显示文字的节点和能点击的节点**通常不是同一个**。文字常在一个不可点击的
`TextView` 里,真正响应点击的是它的父 `LinearLayout`。点文字中心可能毫无反应。

`findtext` 命中后会对该点做**命中测试**,返回这一点之下「最深且最小」的可点击节点:

```json
{"cmd":"findtext","text":"登录","tappable":true}
```
```json
{"matches":[{
  "text":"登录","from":"ocr","center":[540,1210],
  "tapTarget":{"cls":"LinearLayout","center":[540,1210],"clickable":true,"depth":9}
}]}
```

**点 `tapTarget.center`,不要点 `matches[0].center`。**

参数:
- `mode`:`contains`(默认)/ `exact` / `regex`
- `source`:`auto`(默认,**先树后 OCR**)/ `node` / `ocr`
- `tappable`:默认 `true`

### ocr / findcolor / findimage

```json
{"cmd":"ocr","region":[0,800,1080,1600]}
{"cmd":"findcolor","color":"#FF5722","tolerance":16,"maxClusters":10}
{"cmd":"findimage","template":"<base64>","threshold":0.85,"region":[0,800,1080,1600]}
```

- `region` 是 `[left, top, right, bottom]`,**缩小它能显著提速**,三个命令都支持
- OCR 用 ML Kit **bundled** 版,模型在 APK 内,**不需要 Google Play 服务**(国行无 GMS 可用)
- `findcolor` 返回的是**按像素数排序的聚类区域**,不是原始像素点
- `findimage` 内部先降采样粗筛再原分辨率精修(朴素匹配会要几十秒),模板每边 ≤ 512px

## 常用配方

**点击一个已知文字的按钮**(最稳)
```json
{"cmd":"findtext","text":"立即购买","tappable":true}
→ 用返回的 tapTarget.center 做 tap
```

**滚动找元素**(列表里内容不在首屏)
```json
{"cmd":"swipe","x1":540,"y1":1600,"x2":540,"y2":800,"durationMs":380}
{"cmd":"wait","ms":600}
{"cmd":"find","selector":{"textContains":"目标"}}
```

**表单填写**
```json
{"cmd":"tap","x":540,"y":900}          ← 先点输入框使其获得焦点
{"cmd":"text","text":"你好,世界"}      ← 中文无需输入法,也不走剪贴板
```

**游戏 / 自绘界面**
```json
{"cmd":"findcolor","color":"#3B82F6","tolerance":20,"region":[0,1200,1080,1800]}
```

## 排错

| 现象 | 检查 |
|---|---|
| 连不上 7912 | 手机与电脑**同一网段**;App 里服务是否显示"监听中" |
| `accessibility: false` | 手机设置里 App 的无障碍服务未开启(**重装 App 会清空该授权**) |
| 点击 `completed:false` | 息屏、被其他手势抢占,或手势超时 |
| `find` 命中 0 但肉眼可见文字 | 该界面是 WebView/Canvas,**改用 `findtext` 或 `ocr`** |
| 输入无反应 | 没有焦点输入框,先 `tap` 到输入框 |

**Android 12 侧载的坑**:侧载 App 的无障碍服务默认**不允许开启**(受限设置),
需去「应用信息 → 右上角三点 → 允许受限设置」。也可以直接用 adb 写入绕过:

```sh
adb shell settings put secure enabled_accessibility_services com.dsh.phoneagent/com.dsh.phoneagent.AgentAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## 输入与提交

```json
{"cmd":"tap","x":540,"y":900}   ← 先点输入框让它获得焦点
{"cmd":"text","text":"你好"}
{"cmd":"send"}                   ← 提交(聊天框=发送,搜索框=搜索)
{"cmd":"delete","count":1}       ← 回删
{"cmd":"clear"}                  ← 清空
```

**这三条都受同一个硬限制约束:无障碍服务无法注入按键事件**(那要 `INJECT_EVENTS`,
只有 shell / 系统权限才有)。所以:

- **`send`** 走 `ACTION_IME_ENTER`,让输入法执行它自己的编辑器动作——**和用户按键盘
  右下角那个键是同一个动作**,应用对两者的处理完全一致。字段若没声明编辑器动作会返回
  `sent:false`,此时改用点击发送按钮。
- **`delete`** 是读出当前文本、写回去掉末尾的内容。**内容等价,但落地是一次原子写入**,
  会检查按键节奏的输入框能察觉。
- **`clear`** 等价于 `text` 的 `mode:"clear"`,单独成命令只是因为「清空重填」太常见。

## 已知边界(免 root 的固有天花板)

`dispatchGesture` 注入的事件由系统统一生成,**压力(`getPressure()`)、接触面积
(`getSize()`)、来源设备 id(`getDeviceId()`)无法自定义**。这三项在应用层都是
可读的公开 API,属于免 root 路线的硬上限;要覆盖它们只有 `sendevent` 直接写
`/dev/input/eventX`,那需要 root。

好消息:`InputEvent.isTainted()` 是 `@hide` 的内部标志,普通 App 读不到。

## 频率与限流 ⚠️

服务端**串行执行**所有操作:同一时刻只处理一个,后到的排队。

**所以绝不要不等响应就连发。** 一次 tap 本身只要 50–150ms(它不返回截图),但如果
连发 100 个,它们会排在互斥锁后面,最后一个的响应要等很久才回,看起来就是卡死。

App 侧有两道保护:

1. **点击节流** —— 两次点击之间强制最小间隔,默认 **110ms**(大致是人手最快连点速度)。
   用 `minIntervalMs` 调整,设 0 关闭。
2. **并发上限** —— 同时有 **4 个**操作在跑时,新请求直接返回 `busy` 错误,**而不是排队**。
   宁可拒绝也不让调用方盲目等待。

**滑动和长按不受节流约束** —— 它们本身就是长动作,强行间隔没有物理依据。

正确写法是**串行等待**:发一个 → 等响应 → 再发下一个。

## 错误码

失败响应的 `code` 告诉你该重试还是该改参数:

| code | 含义 | 应对 |
|---|---|---|
| `bad_request` | 参数不合法 | **不要重试**,改参数 |
| `not_ready` | 界面或服务未就绪 | 稍等再试 |
| `timeout` | 超时 | 可重试一次 |
| `busy` | 操作积压 | 等一会儿再发 |
| `unauthorized` | 令牌不匹配 | 检查 token |
| `io` | 读写失败 | 重连 |

**`bad_request` 重复发送必然重复失败**,这是最容易浪费请求的一种。

## 应用故障时的行为

目标应用崩溃、正在切换、或无响应时:

- `rootInActiveWindow` 可能返回 null → 返回 `not_ready`,**不会崩溃**
- 截图失败 → 返回 `not_ready`
- `observe` 的界面树若拿不到,会返回 `{"unavailable": true}` 而**不是失败**——
  截图仍然有效,调用方可以继续用画面判断

所有异常都在 `process()` 层被捕获并归类成上面的错误码,**单条失败不断开连接**。

## 访问令牌

App 的「访问令牌」区块可以给端口加一道共享密钥。

| 状态 | 行为 |
|---|---|
| **关闭**(默认) | 同一局域网内任意设备都能连接,**调用不需要令牌** |
| **开启** | 每次调用都必须带 `token`,否则返回 `code:"unauthorized"` |

```json
{"id":1,"cmd":"ping","token":"ae452e120c134a35"}
```

几点设计上的取舍:

- **令牌与开关分开存** —— 关掉保护不会丢弃密钥,否则每切换一次都要重新配置电脑端
- **重启保持** —— 存在 SharedPreferences 里,App 重启、手机重启都不变
- **更换必须人工点击**,且有二次确认弹窗。原因:更换会让**所有正在使用旧令牌的脚本和插件立刻失效**,并持续返回 `unauthorized` 直到重新配置。这个后果值得一次明确的确认,而不是点一下密钥就静默生效
- **首次启动自动生成**一个 16 位随机密钥,但**不会自动开启**保护 —— 值出现在界面上不等于用户同意锁端口
- **改令牌/开关会重启服务**,因为令牌在服务启动时读一次;这样换取的是请求热路径上不必每次查偏好设置

> 如果开了令牌,DSH 插件的配置里也要带上同一个值,否则调用会被拒。

## 可视化测试台 ⭐

调试手机能力时,**不要用"截图 → 猜坐标 → 发命令"这种循环**,直接开测试台。
同一个页面有**两种托管方式**,页面完全一样(用的是相对路径):

| 方式 | 地址 | 适用 |
|---|---|---|
| **手机托管** | `http://<手机IP>:7913/` | 任何设备可开,**不依赖 PC** |
| PC 托管 | `http://127.0.0.1:8099/` | 改页面即时刷新,不用重装 App |

PC 托管跑 `node tools/webui/server.mjs`;改完 `tools/webui/index.html` 记得复制到
`android/app/src/main/assets/webui/index.html` 才能让手机托管生效。

### ⚠️ Web 不能挂在 7912 上

**7912 是裸 TCP + 行分隔 JSON,不是 HTTP**。浏览器发 HTTP 请求它不认识,所以
`http://<ip>:7912/tools` 这类地址**不可能工作**。HTTP 必须用**独立端口 7913**,
由 `WebConsole` 在手机内部转发到 7912:

```
浏览器 ──HTTP──> 7913 (WebConsole) ──裸TCP──> 7912 (ControlServer)
```

**转发而不是直接调用命令处理**,是为了只有一份命令实现——页面看到的校验、错误码、
行为和其他客户端完全一致。

### 页面核心能力

- **左栏截屏**:单击 = tap、**按住拖动 = swipe**、滚轮 = scroll。
  底部实时显示**图像尺寸 / 手机尺寸 / 指针坐标 / 拖拽起止点与距离**。
  坐标用**渲染后的元素框**换算,所以截屏缩放多少都准——这是页面存在的核心理由,
  肉眼无法从缩放图上看出手机坐标。
- **节点树**:只列**有用的节点**(有文字/id/可点/可滚/可编辑),因为一个页面动辄
  十几层 FrameLayout,全渲染会把真正重要的节点埋掉;缩进仍按真实深度。
- **选中详情**:点节点看完整属性,并**自动生成可用选择器**(viewId / text /
  textContains / class+clickable),点击即复制,还能直接点该节点中心。

### 用测试台看清一件重要的事

打开饿了么这类**跨端(小程序/WebView)页面**时会看到:节点树里**只有 4 个布局容器**,
没有任何菜品或按钮。**这不是 bug,是这类页面的渲染方式决定的**——所以这种场景
必须靠 `ocr` / `sweep`,节点树帮不上忙。

## 真人滑动长什么样(实测)

## 真人滑动长什么样(实测)

用 `adb shell getevent -lt /dev/input/event4` 录了 35 次真人滑动(触摸设备是
`/dev/input/event4`,坐标范围正好 0-1079 × 0-2399):

| 指标 | 实测 |
|---|---|
| 起点 y | 1941–2111(**屏幕底部 83%**,不是中部) |
| 位移 | 823–1355px(均值 1130) |
| 时长 | 440–607ms(均值 525) |
| 速度曲线 | **梯形**:0 → 130ms 达峰 ~4900px/s → 匀速 70ms → 减速 220ms → 0 |
| **抬手速度** | **33 px/s(全程的 2%)** |

**关键结论:真人滑完会减速到几乎静止才松手**,所以**不靠惯性**,位移≈手指位移。
想模拟的话,别用"匀速直线",也别指望甩出去。

**踩过的坑**:给 App 加"记录用户操作"的功能是**做不到的** —— 全局触摸只有
API 34+ 的 `onMotionEvent` 能读(本机是 API 31),靠悬浮窗拦截又会挡住其他 App。
**开发期用 `getevent` 录**,这才是可行路径。

## 滑动精度:靠「到达后刹车」,而且刹车必须是独立的一段 ⚠️

**同样一段坐标,时长越短越不精准**——系统在手指离开后按**抬手瞬间的速度**决定要不要
继续惯性滚动。末尾速度快就"甩"出去、落点超出坐标。

### 两个错误的解法(都试过,都失败)

**① 把减速写成"时间占比"** —— 300ms 只分到 135ms 减速,不够。

**② 用路径点疏密表达速度** —— **完全无效**。我原以为"点距大=快、点距小=慢",
但 **`StrokeDescription` 是按路径长度均匀分配时间的**:密点疏点对速度没有任何影响。
所以"用 1.5% 距离换 60% 时间"的停留段,实际只拿到 1.5% 的时间,根本没停。
整条手势始终是**匀速**的——这才是"300ms 越过、1000ms 不越过"的真正原因:
唯一的变量就是平均速度。

### 正确的解法:两段 `GestureDescription` + `continueStroke`

```
第一段: 移动           ── willContinue = true
   ↓ onCompleted
第二段: 原地刹车 ~300ms ── continueStroke(刹车路径, ...)
```

**刹车路径只需要约 1 像素长**:框架把刹车时长铺在这段路径上,1px/300ms 的速度
自然接近 0,抬手时就不会被判成 fling。

**必须是两个 `GestureDescription`**。加到**同一个** builder 里的 stroke 是**同时进行**
的多指手势——那正是前面 23% 那个 bug 的成因。

**刹车时长随机 250–350ms**,不是固定值:真人不会每次都刹一样久,固定值本身就是
一个会重复出现的特征。实测 300ms 滑动的效率从 **151% 降到 112%**(对照 adb 官方
`input swipe` 是 154%)。

### 实践建议

- **要精确落点** → `durationMs` 用 **600–1200**
- **要快速翻页** → 200–350,靠刹车同样能基本停住
- **要读完整列表** → 用 `sweep`,别自己控制滚动
- **想知道滚了多少** → 用 `swipemeasure`,别猜

## 测量滑动有个前提:起止位置必须可控 ⚠️

排查时我有一整批数据作废,因为**每次的起始位置都不同**:

- 用「点击左侧某个分类」当回归锚点是**不可靠的**——**侧边导航列表自己会滚动**,
  同一个坐标在不同时刻对应不同分类(实测点 (131,1064) 有时是「9月特惠」,
  有时是「限量套餐」)
- App 重启后会跑到前台,量的可能是 App 自己的界面(内容只有一屏,当然滚不动)
- 点错店可能进到「会员页」,底部写着「已经到底了」,同样滚不动

**可靠做法**:
1. 用**连续 flick 向上**回到顶部作为统一起点
2. **每次测量前截图确认页面**
3. 结论**用截图肉眼复核**,不能只信 `matchScore`

**另一个坑**:外卖菜单有**大量重复卡片布局**,NCC 在错误偏移处也能拿到接近 1 的
相关系数(实测出现过 -159% 这种反向结果)。分数高不等于答案对。

## addStroke 是多指,不是接续 ⚠️ 血泪教训

**`GestureDescription.Builder.addStroke()` 添加的 stroke 是「同时进行」的**——那是
构建多指手势的方式(`getMaxStrokeCount()` 默认 10)。想用多个 stroke 拼出「一条
长滑动」是**错的**:系统会理解成多根手指同时拖,整条手势基本被丢弃。

`StrokeDescription.continueStroke()` 也不是用来往同一个 builder 里加段的。

**症状**:`completed=true`,轨迹看着正常,**但内容几乎不动**。

| 实现 | 内容位移 / 手指位移 |
|---|---|
| 拆成 3 个 stroke | **23%** |
| **合并成 1 个 stroke** | **140%** |
| `adb shell input swipe`(对照) | 154% |

**23% 恰好等于第一段占的距离比例(22%)**——这个巧合就是定位问题的钥匙。以后看到
"只走了一部分",先怀疑手势构造。

**正确做法:一个手势 = 一个 `addStroke`**。速度曲线靠**路径点疏密**表达:一条 stroke
按**恒定速率**走完路径,所以**点距大 = 快,点距小 = 慢**。把「均匀的时间进度」映射到
「非均匀的距离进度」即可(加速段 34% 时间走 22% 距离,减速段 28% 时间走 22% 距离)。

**代价与收益**:单 stroke 失去"分段控制时长"的能力,但换来手势真正生效——而且路径点
疏密本来就能表达同样的速度曲线,所以并没有真正损失拟人度。

## 必须确认页面再测量

排查过程中我有一批测量**全部作废**,因为:
- App 重启后跑到了前台,**量的其实是 App 自己的界面**——它内容只有一屏,当然滚不动
- 点击跑偏进了一家店的**会员页**,页面底部写着「已经到底了」——同样滚不动

**结论:任何"滚不动/效率为 0"的结果,先截图确认停在哪个页面、是否已到底。**
`contentShift:0` 在正确的页面上才有意义。

## 滑动效率:手指走了多远 ≠ 内容滚了多远 ⚠️

**这是最容易误判自己代码的地方。** `swipe` 返回 `completed:true` 只说明手势被系统
接受了,**完全不代表内容滚动了相应的距离**。

用 `swipemeasure` 实测(手机截图前后对比,归一化互相关找垂直偏移):

| 时长 | 手指移动 | 内容实际滚动 | 效率 |
|---|---|---|---|
| 525ms | 1130px | 216px | **19%** |
| 350ms | 1130px | 270px | 24% |
| 150ms | 1130px | 301px | **27%** |

**滑得越快效率越高,但都在 20–30% 区间。** 也就是说手指走 1130px,列表只前进
约 250px。这解释了"为什么每次只移动一小步"的观感——不是算法在偷懒,是页面只跟了
这么多。

**`swipemeasure` 的用法**:

```json
{"cmd":"swipemeasure","x1":540,"y1":2100,"x2":540,"y2":970,"durationMs":350,"settleMs":900}
→ {"fingerDistance":1130,"contentShift":270,"efficiency":0.24,
   "matchScore":0.98,"matchConfident":true,
   "path":[[x,y,timeMs], ...]}
```

### 为什么必须量,而不能只看 `completed`

- **列表到边界就不再跟手**,手势照样被接受
- **惯性会让内容在手势结束后继续走**,结束后立刻采样会少算
- **页面可能根本不滚动**(弹窗、不可滚区域),但手势依然"成功"
- `contentShift:-1` 和 `0` 是两回事:**-1 = 无法测量**(页面变了/没匹配上),
  `0` = 量到了,内容确实没动

### 踩过的坑:均值差异会系统性低估位移

第一版用「平均像素差」找最佳偏移,结果把 1130px 的真实滚动量成了 272px。原因是
**参与比较的行数随偏移量变化**——偏移越小重叠行越多,平均差被稀释得越厉害,搜索
自然偏向"几乎没动"。

改用**归一化互相关(NCC)**,并在 `before` 上取**固定窗口**映射过去,分数就不再
受重叠行数影响。NCC 还顺带免疫懒加载图片带来的亮度漂移。

**另一个陷阱**:单靠 NCC 也不够——**外卖菜单有大量重复卡片布局**,在错误的偏移处
也能拿到接近 1 的相关分数。所以结论要用**截图肉眼复核**一次,别只信分数。

## 手势轨迹要带时间戳

只返回坐标的轨迹没有信息量:**同样的像素路径,爬过去的和甩过去的是两种手势**。
`trace:true` 时返回 `[x, y, timeMs]` 三元组,这样调用方才能画出速度——快慢节奏
才是"像不像人"的关键,而不是那条线的形状。

## 投屏速度:瓶颈在哪,以及一次失败的优化 ⚠️

### 瓶颈不是协议

实测单帧成本(scale 0.35,quality 70):**截图 + JPEG 编码 ≈ 283ms**,而**局域网传
29KB 只要 0.2ms**。

| scale | 单帧耗时 | base64 传输 | 实际 JPEG |
|---|---|---|---|
| 1.0 | 1244ms | 166KB | 124KB |
| 0.5 | 582ms | 62KB | 46KB |
| **0.35** | **283ms** | 39KB | 29KB |

所以**换 WebSocket 毫无意义**:MJPEG 每帧多 70 字节头,占 25KB 一帧的 **0.28%**,
WebSocket 只能省到 0.024%。**网络占比不到 0.1%。**

**要提速只能换捕获通道**(MediaProjection + 硬件编码,30–60fps,代价是每次会话一次
授权弹窗),**不是换传输层**。

> 参考:小米妙享桌面 `com.xiaomi.mirror` 是 `PRIVILEGED` 系统应用,持有
> `INJECT_EVENTS` / `MANAGE_ACTIVITY_STACKS` / `CONTROL_KEYGUARD` 等 signature|privileged
> 权限。**它快是因为它有系统特权,不是因为"没用 adb"** —— 普通应用拿不到这些权限。

### 失败的优化:别对 hardware bitmap 直接缩放

为了省掉每帧 10.4MB 的全分辨率软件副本,我把 `capture()` 改成不 copy,直接对
`wrapHardwareBuffer` 得到的 hardware bitmap 调 `createScaledBitmap`。**两个指标都变差了:**

| | 原始 | 这个"优化" |
|---|---|---|
| 帧率 | 2.5 fps | **2.0 fps** ↓ |
| Native Heap | 15.6 MB | **45.7 MB** ↑ |

**原因**:对 hardware bitmap 缩放要 **GPU→CPU 回读**,要同步等 GPU,内部还会再分配
staging 缓冲。**为了省一次软件副本,换来一次更贵的回读。**

**教训:能用"分配量"推理,但不能用分配量下结论。** 内存账算得再漂亮,也要用**帧率和
实际堆占用**验证——我这次两个指标都没测就改了,结果适得其反。

### 留下的那个小优化

JPEG 输出缓冲复用(一个 `ByteArrayOutputStream` reset 重用,而不是每帧新建 96KB)。
**纯收益**,实测推流中 Native Heap 从 15.6MB 降到 11.7MB。

## takeScreenshot 有速率限制 ⚠️

**连续调用会失败。** 实测:单独一次正常,连发四次挂了三次,报 `screenshot failed`。

平台在**同一时刻只允许一个截图请求**,而且这台设备还会拒绝**离上一次太近**的调用。
任何"取色 → 找色 → 找图"的组合都会连发截图,所以这个坑一定会踩到。

**解法是把串行化和重试放进 `capture()` 本身**,而不是散在十个调用点:

```
synchronized → 距上次不足 180ms 就等 → 最多试 4 次(退避 120/240/360ms)
```

修完后连发 8 次取色、6 次截图**全部成功**。

**教训**:这类"平台节流"的正确位置是**最底层的那个封装**,让所有调用方自动受益;
在调用点各写一遍既容易漏,行为也不一致。

## 猜字段名 = 白干

前端调 `findcolor` 时我按印象写了 `data.matches`,结果**命中数永远是 0**。实际返回的是:

```json
{"count":5001,          // 匹配到的像素数
 "clusterCount":20,     // 分成了几簇  ← 这才是"几处"
 "truncated":true,
 "clusters":[{"center":[352,56],"bounds":[336,48,359,71],"pixels":220}, ...]}
```

`findimage` 用的是 `matches`(带 `similarity`)。**两个命令字段不同。**

**规则:接一个新命令前,先拿真实响应打一遍,再写解析。** 猜错的代价是"功能看起来
完全没反应",比报错更难查。

## 采集菜单/列表的完整流程

目标:把一个长列表(菜单、商品、评论)完整抓成结构化数据。

### 步骤

1. **人工把手机开到目标页面** —— 导航到某家店依赖具体 App 的界面,半自动的点击
   序列比让操作者点一下更脆弱。**从"页面已经打开"这一步开始自动化。**
2. **确认页面**(截图看一眼)。这一步不能省——实测有三次因为页面不对导致整批数据作废。
3. **`sweep` 一次拿全**:
   ```json
   {"cmd":"sweep","scrolls":80,"distance":1100,"perCapture":2,
    "region":[280,400,1080,2100],"settleMs":700,"durationMs":420}
   ```
   `region` 排除左侧分类导航(每屏重复、纯噪音)。返回的 `lines` 已去重、按首次出现
   排序,并自带 `stopReason` 判断是否到底。
4. **逐屏截图校对**。OCR 有错字(实测"莴皮"→"莒皮"、"鸡柳"→"鸿柳"),而且去重会
   打乱行序,所以**名字和价格的配对可能错位**。截图是唯一的校对依据。

### 实测数据(一个 55+ SKU 的店铺)

| 指标 | 值 |
|---|---|
| 滚动次数 | 17 |
| 识别行数 | 268 |
| 耗时 | **48 秒** |
| 停止原因 | `reached-end`(自动到底) |
| 提取 SKU | 55+ |

**全程零 adb** —— 采集过程中 USB 恰好断了,反而验证了纯 App 通道可用。

### 一次性脚本

`tools/collect-menu.mjs` 封装了"扫 + 初步提取 + 导出 CSV":

```powershell
node tools/collect-menu.mjs --name "店名"
# → docs/menu-<店名>.txt   原始 OCR 行
# → docs/menu-<店名>.csv   初步提取(带 BOM,Excel 双击不乱码)
```

**CSV 是草稿,不是成品**:OCR 错字和行序问题让自动配对只能作为起点,必须对着截图
修正。导出时注意给可能被 Excel 当公式或数字的单元格加前缀,否则 `¥9.9` 这类会串。

## 长列表:用 sweep,不要一屏一屏截图 ⚠️

读一个长菜单/长列表时,**最慢的做法是"滚动 → 截图 → 交给模型看图"**。每轮要为
一次滑动、一次等待、一次截图、一次传输付费,而且调用方还得先理解一张图才能决定
要不要继续。

实测对比(同一个外卖菜单):

| 做法 | 耗时 |
|---|---|
| 一屏一屏:swipe + 截图 + 看图 | **约 40 分钟还没读完一半** |
| **`sweep`:手机端滚动+OCR+去重** | **19 秒读完整个菜单(196 行)** |

```json
{"cmd":"sweep","scrolls":16,"distance":1100,
 "region":[280,400,1080,2100],"settleMs":650,"durationMs":420}
```

**它做了什么**:在手机本地循环「截图 → OCR → 去重 → 滚动」,只回传**纯文本**。

关键设计:

- **`region` 排除左侧导航和顶栏**。饿了么这类页面左侧有分类导航、顶部有筛选栏,
  它们每屏都一样,纳进来只是噪音。
- **自动到底**:连续两屏没有新增行就停,所以 `scrolls` 是上限而不是固定成本——
  在只有 6 屏的列表上传 30,仍然 7 次就结束。
- **去重**:跨屏重复出现的行只留一次,返回的 `lines` 可以直接读。
- **不传图片**:整个调用只回传文本,带宽和延迟都极低。

**OCR 有错字是正常的**(实测"莴皮"→"莒皮"、"鸡柳"→"鸿柳")。菜品名和价格仍能
辨认;如果需要精确定位某个控件,再用 `findtext` 复核那一条。

## 不要依赖 adb

`adb` 只是开发期的调试通道。**实际部署时它是断开的**,所以任何"用 adb 截图/操作"
的流程都不成立。所有读写都必须走 App 自己的 TCP 接口——`observe`、`ocr`、
`sweep`、`findtext` 就是为此存在的。

排查问题时可以用 adb 看进程和日志,但**不能用它替代 App 的能力**。

## 内存压力会让响应慢到怀疑人生

实测过 `ping` 从 21ms 恶化到 **13 秒**。根因不是 App,而是系统内存耗尽:

```
Mem: 7.75 GB total, 7.45 GB used, 291 MB free
Swap: 2.0 GB used
```

外卖 App 滑久了能吃到 **1.15 GB**(大量菜品图),加上微信、相机、设置等,7.7 GB
的机器会打光。表现是**所有请求都变慢、超时、甚至 ECONNRESET**。

处理:杀掉不用的后台应用(`am force-stop`,注意不是 `stopapp`——那个只能杀后台进程),
或重启 Agent App。恢复后 `MemAvailable` 从 291MB 回到 4.2GB,响应也回到 7–21ms。

**排查顺序**:先测 `ping` 延迟 → 高就查 `/proc/meminfo` → 再决定清内存还是重启 App。

## 项目维护

### ⚠️ 新增功能必须同步帮助页

**`HelpContent.kt` 是功能清单的唯一数据源。** 每加一个命令,必须在那里补一条
(`Entry(name, summary, detail, example, params)`),否则 App 内的帮助页会开始说谎——
而过时的参考页比没有参考页更糟,因为它会被信任。

App 内的帮助页和桌面版 HTML 都从它渲染:

```powershell
node E:\DSH-Phone-Agent\tools\gen-help-html.mjs   # → docs/help.html
```

桌面版是**自动生成**的,不要手工编辑 `docs/help.html`,改了下次生成就没了。

帮助页的形态:窄屏(手机)是「列表 → 点击展开详情」,宽屏(电脑)是左右分栏。
手机一屏放不下两栏,所以用的展开式——这样整份目录在一次滚动里保持可扫,
不会因为跳走再跳回而丢失位置。

### 连接手机(实战经验)

**无线调试("无线调试"开关)天生不稳定**,别依赖它:它走 mDNS + TLS + **随机端口**,
adb server 一重启、网络一抖就断,安装到一半掉线是常态。

**推荐:切到固定端口 5555**

```powershell
# 前提:此刻得有一个活着的连接(USB 或无线调试的窗口期)
adb tcpip 5555
adb connect 192.168.12.138:5555      # 之后一直用这条
```

切过去之后端口固定、无 TLS、不受"无线调试"开关影响。**手机重启后失效,要重做一次。**

**注意**:`adb devices` 输出的序列号可能含空格(如 `adb-xxx (2)._adb-tls-connect._tcp`),
按空格切分会截断。**直接用 `192.168.12.138:5555` 当 `-s` 参数**,或者干脆不指定 `-s`。

**装完 APK 后:开启无障碍(MIUI 尤其重要)**

重装会清空无障碍授权。在 MIUI 上光写设置**不够**——系统不会重新绑定:

```powershell
adb shell settings put secure enabled_accessibility_services com.dsh.phoneagent/com.dsh.phoneagent.AgentAccessibilityService
adb shell settings put secure accessibility_enabled 0     # ← 关键:先关
adb shell settings put secure accessibility_enabled 1     # ← 再开,触发系统重新评估
adb shell dumpsys accessibility | grep "Bound services"  # 确认非空
```

**重装后首次启动会卡在 dex 优化**(`dex2oat ... first-use`),50MB 的 APK 要等
**30–60 秒**,期间进程在但服务不监听。**别急着判定失败。**

**MIUI 的 task 管理会捣乱**:`am start` 有时回 "intent has been delivered to currently
running top-most instance" 而 App 其实没到前台。用这个绕过:

```powershell
adb shell monkey -p com.dsh.phoneagent -c android.intent.category.LAUNCHER 1
```

### 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Chat2DB Pro\runtime"
$env:ANDROID_HOME = "C:\Users\you\Android\Sdk"
cd E:\DSH-Phone-Agent\android
.\gradlew.bat assembleDebug

# 安装(需要 adb)
adb install -r E:\DSH-Phone-Agent\android\app\build\outputs\apk\debug\app-debug.apk
```

> ⚠️ **项目路径必须是纯 ASCII**。AGP 8.5 在含中文的路径上直接失败,且
> `android.overridePathCheck` 已不再生效——这也是项目放在 `E:\` 根目录而非同步盘的原因。

**插件依赖解析的坑**:DSH 的 `@deepseek-ai/*` 包链接在
`$DSH_HOME\profiles\node_modules\@deepseek-ai\`,而插件以 Junction 安装时 Node 会
解析到真实路径 `E:\DSH-Phone-Agent\...`,向上找不到那一层。因此插件目录内需要有
`node_modules\@deepseek-ai\{dsh-tools,dsh-attachment}` 的 Junction。

## 踩过的实现坑

**亮度刻度不是 0–255**。0–255 只是 AOSP 默认值,MIUI 等 OEM 会覆盖
`config_screenBrightnessSettingMaximum`(实测小米约 4095)。硬编码 255 会让 `device`
报出「亮度 159%」这种不可能的值,也会让 `brightness` 命令把屏幕设到实际亮度的零头。
**读和写两个方向必须用同一个真实上限。**

**工具 output schema 是严格的**。想通过 `execute` 的返回值往 `render` 传额外字段
(比如图片附件引用),必须在 `output.schema.properties` 里声明它——在
`additionalProperties: false` 下,未声明的键会让**整个工具调用失败**,而不是被忽略。

**Kotlin 的 raw string(`"""`)里 `$` 仍会触发字符串模板**。写 PowerShell / shell 示例
时会直接编译失败,要用 `${'$'}` 转义;反过来 `\d` 在 raw string 里就是字面量,不需要
写双反斜杠。

**对比截图不能只看文件 hash**。状态栏时间每秒都在变,两张内容完全相同的截图 hash
也会不同。曾因此误判「adb 点击有效而我们的 App 无效」,实际两者都无效——真正原因是
坐标没落在可点击区域。

**`continueStroke` 的 `startTime` 语义**:它从**整段手势开始**计时,不是从前一段结束。
给所有续段传 `0` 会让第二段与第一段时间重叠,系统**静默取消**整个手势
(`onCompleted` 永不回调)——表现为"拟人滑动 100% 失败、机械滑动正常"。必须累加已用时长。

**每秒整屏重绘而不是局部更新**:状态面板上能独立变化的状态有六七处,逐字段更新
容易漏掉某一处导致显示不一致;整屏重建在这个尺寸下开销可忽略。

**图标不用 emoji**:emoji 在 MIUI 与原生 Android 上字形差异很大,而 App 图标必须稳定,
所以全部改用几何图形/文字符号绘制。
