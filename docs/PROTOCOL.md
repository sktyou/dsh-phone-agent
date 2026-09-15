# DSH Phone Agent — 控制协议

手机端 App 在局域网开放一个 TCP 端口,电脑端用**一行一个 JSON** 的方式收发。
没有握手,没有帧头,没有第三方依赖 —— 一个 socket + 一个 JSON 解析器就能对接。

- 默认端口:**7912**
- 传输:TCP,UTF-8,`\n` 分隔
- 请求:客户端发一行 JSON
- 响应:服务端回一行 JSON

## 请求 / 响应信封

请求:

```json
{"id": 1, "cmd": "tap", "x": 540, "y": 1200}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 否 | 客户端自增序号,原样回显,默认 0 |
| `cmd` | 是 | 命令名,大小写不敏感 |
| `token` | 否 | 仅当手机端设置了令牌时必填 |

成功响应:

```json
{"id": 1, "ok": true, "data": {"completed": true, "x": 540, "y": 1200, "human": true}}
```

失败响应(**连接保持,可继续发下一条**):

```json
{"id": 1, "ok": false, "error": "accessibility service is not connected; ..."}
```

> 服务端按行处理,单条请求失败不会断开连接。连接只在读写错误或客户端主动关闭时结束。

---

## 命令一览

| cmd | 作用 | 是否拟人化 |
|---|---|---|
| `ping` | 连通性 + 无障碍状态 | — |
| `info` | 设备与屏幕信息 | — |
| `observe` | 截图 + UI 树(一次往返拿齐) | — |
| `screenshot` | 仅截图 | — |
| `uitree` | 仅 UI 树 | — |
| `tap` | 点击 | ✅ 可关 |
| `longpress` | 长按 | ✅ |
| `swipe` | 滑动 | ✅ 可关 |
| `key` | 系统按键 | — |
| `text` | 向焦点输入框写入文本 | — |
| `send` | **提交输入**(IME 动作:发送 / 搜索 / 完成) | 低 |
| `delete` | **回删**(从末尾删 N 个字符) | 低 |
| `clear` | **清空输入框** | 低 |
| `launch` | 启动应用 | — |
| `wait` | 等待 | — |
| `device` | **设备状态**:电量/音量/亮度/网络/存储/内存/服务状态 | — |
| `doubletap` | **拟人双击** | 低 |
| `pinch` | **双指缩放**(多指手势) | 低 |
| `flick` | **惯性甩动**(快速短滑) | 低 |
| `scroll` | 按**滚动容器**滚动一屏,而非盲滑屏幕 | 低 |
| `waitstable` | **等待界面稳定**(比固定 sleep 可靠) | — |
| `colorat` | **单点取色** | 低 |
| `wake` | 唤醒屏幕 | — |
| `clipboard` | 剪贴板读 / 写 / 粘贴 | — |
| `deeplink` | 打开 URI(直达具体页面) | — |
| `apps` | 已安装应用列表 | — |
| `stopapp` | 结束后台应用 | — |
| `volume` | 调节音量 | — |
| `brightness` | 调节亮度(需 `WRITE_SETTINGS`) | — |

## 坐标约定 ⚠️

**所有坐标一律是「全屏截图像素」,原点是屏幕左上角。**

`info` / `device` 里的 `screenWidth` / `screenHeight` 用 `getRealMetrics` 取**全屏**尺寸
(含导航栏),与截图一致。不要使用 `resources.displayMetrics`——那是应用可用区域,
在手势导航机型上会少掉导航栏高度(实测 2261 vs 2400),据此换算会让横向坐标整体偏移。

`observe` / `screenshot` 支持 `scale` 与 `region`,此时响应会同时给出:

```json
{"screenWidth":1080,"screenHeight":2400,"scale":0.5,"imageWidth":540,"imageHeight":1200}
```

**换算规则**:`触摸坐标 = 图像坐标 / scale + region左上角`。
响应里给全屏尺寸和缩放系数,就是为了让这个换算无需猜测。

## 通用参数

| 参数 | 适用 | 说明 |
|---|---|---|
| `retry` | 所有手势命令 | 被取消时的重试次数,1–5,默认 1。手势被取消通常是瞬时的(界面在动画、系统窗口短暂抢占了触摸流) |
| `scale` | `observe` / `screenshot` | 0.05–1,缩小截图。1080×2400 JPEG 约 100KB,边长减半可省约 4 倍带宽 |
| `region` | `observe` / `screenshot` / `ocr` / `findcolor` / `findimage` | `[left,top,right,bottom]`,限定范围可显著提速 |
| `interactiveOnly` | `observe` / `uitree` | 只返回可操作节点(**扁平列表**),树能缩到 1/10 |
| `typing` | `text` | `instant`(默认)/ `natural` 逐字符输入 |
| `mode` | `text` | `replace`(默认)/ `append` / `clear` |
| `find` | **控件选择器**:按文本/ID/状态筛选节点,只回命中项 | — |
| `findtext` | **按文本找控件**:融合控件树与 OCR,并解析出可点击目标 ⭐ | — |
| `ocr` | **本地 OCR**:识别屏幕上的文字(含 WebView/游戏/图片文字) | — |
| `findcolor` | **找色**:返回同色区域(聚类后) | — |
| `findimage` | **找图**:模板匹配(降采样粗筛 + 精修) | — |

---

### ping

```json
{"id":1,"cmd":"ping"}
```

```json
{"id":1,"ok":true,"data":{"pong":true,"ts":1757654321000,"accessibility":true}}
```

`accessibility` 为 `false` 时,除 `ping`/`info` 外的命令都会失败。

### info

```json
{"id":2,"cmd":"info"}
```

```json
{"id":2,"ok":true,"data":{
  "model":"Redmi K30","manufacturer":"Xiaomi","android":"12","sdk":31,
  "screenWidth":1080,"screenHeight":2400,"density":2.75,
  "accessibility":true,"clients":1,"operations":42
}}
```

### observe —— 最常用

一次往返同时拿到帧和 UI 树,避免截图与树不同步。

```json
{"id":3,"cmd":"observe","includeUi":true,"format":"jpeg","quality":82}
```

```json
{"id":3,"ok":true,"data":{
  "imageWidth":1080,"imageHeight":2400,"imageFormat":"jpeg",
  "image":"<base64>",
  "ui":{"nodeCount":137,"truncated":false,"root":{...}}
}}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `includeUi` | `true` | 是否附带 UI 树 |
| `format` | `jpeg` | `jpeg` 或 `png` |
| `quality` | `82` | JPEG 质量 1–100(PNG 忽略) |
| `maxDepth` | `30` | UI 树最大深度 |
| `maxNodes` | `2000` | UI 树最大节点数 |

UI 树节点字段:

```json
{"cls":"TextView","text":"设置","desc":"","viewId":"com.android.settings:id/title",
 "bounds":[100,200,700,320],
 "clickable":true,"longClickable":false,"scrollable":false,"editable":false,
 "enabled":true,"focused":false,"selected":false,"checkable":false,"checked":false,
 "depth":4,"children":[...]}
```

`bounds` 是 `[left, top, right, bottom]`,与截图像素同一坐标系 —— **可直接用 `bounds` 中心点做 `tap`**。

### tap

```json
{"id":4,"cmd":"tap","x":540,"y":1200}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `x`,`y` | 必填 | 截图像素坐标 |
| `human` | `true` | `false` 时退化为直线点击,用于 A/B 对比 |

```json
{"id":4,"ok":true,"data":{"completed":true,"x":540,"y":1200,"human":true}}
```

`completed:false` 表示手势被系统取消(常见原因:息屏、被别的手势抢占)。

### swipe

```json
{"id":5,"cmd":"swipe","x1":540,"y1":1800,"x2":540,"y2":600,"durationMs":320,"human":true}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `x1,y1,x2,y2` | 必填 | 截图像素坐标 |
| `durationMs` | `320` | 60–4000 |
| `human` | `true` | `false` 时为匀速直线 |

### key

```json
{"id":6,"cmd":"key","key":"BACK"}
```

可选值:`BACK`、`HOME`、`RECENTS`(别名 `APPSWITCH`)、`NOTIFICATIONS`、`QUICK_SETTINGS`、`POWER`、`LOCK`、`SCREENSHOT`。

### text

写入**当前焦点输入框**,走 `ACTION_SET_TEXT`,因此中文、emoji 都不需要输入法,也不走剪贴板。

```json
{"id":7,"cmd":"text","text":"你好,世界"}
```

```json
{"id":7,"ok":true,"data":{"inserted":true,"length":5}}
```

失败情形:没有焦点输入框(需先 `tap` 到输入框使其获得焦点)。

### send / delete / clear —— 提交、回删、清空

```json
{"cmd":"send"}
{"cmd":"delete","count":1}
{"cmd":"clear"}
```

**`send`** 走 `ACTION_IME_ENTER`,让输入法执行它自己的编辑器动作:聊天框里是「发送」、
搜索框里是「搜索」、表单里是「完成」——**和你按键盘右下角那个键是同一个动作**,
所以应用对两者的处理完全一致。

> ⚠️ 这**不是**合成的 Enter 按键。无障碍服务无法注入按键事件,那需要 `INJECT_EVENTS`,
> 而它只有 shell / 系统权限才有。字段若返回 `sent:false`,说明它没有声明编辑器动作,
> 这时改用点击发送按钮。

**`delete`** 从末尾删除 N 个字符(默认 1)。同样没有「发送退格键」的动作,实现是
读出当前文本、写回去掉末尾的内容。**内容上等价,但落地是一次原子写入**——
会检查按键节奏的输入框能够察觉。这是免 root 的硬限制,不是实现疏漏。

**`clear`** 清空焦点输入框。等价于 `text` 的 `mode:"clear"`,单独成命令是因为
「清空重填」太常见,值得少写一个参数。

### launch

```json
{"id":8,"cmd":"launch","package":"com.android.settings"}
```

### wait

```json
{"id":9,"cmd":"wait","ms":800}
```

上限 15000 ms。适用于界面正在加载时。

### find —— 控件选择器 ⭐

按条件在无障碍树里筛选节点,**只返回命中项**,不返回整棵树。
这是省 token 的关键:聊天列表或 WebView 动辄几千个节点,全量丢给模型又贵又慢,而手机本地筛完只要几毫秒。

```json
{"id":10,"cmd":"find","selector":{"textContains":"登录","clickable":true},"max":5}
```

条件也可以平铺在顶层(两种写法都接受):

```json
{"id":10,"cmd":"find","text":"登录","max":5}
```

**选择器字段**(AND 关系,未提供的字段不参与约束):

| 字段 | 说明 |
|---|---|
| `text` | 文本**完全相等** |
| `textContains` | 文本**包含** |
| `textRegex` | 文本**正则**(Java 语法) |
| `desc` / `descContains` | contentDescription 相等 / 包含 |
| `viewId` | 资源 ID,**按后缀匹配**——传 `btn_login` 即可匹配 `com.foo:id/btn_login` |
| `viewIdContains` | 资源 ID 包含 |
| `cls` | 类名短名,大小写不敏感,如 `TextView`、`Button` |
| `clickable` `longClickable` `scrollable` `editable` `enabled` `checked` `selected` `focused` | 布尔状态 |
| `depthMin` / `depthMax` | 深度范围 |
| `scanLimit` | 最多扫描多少节点(默认 20000) |

```json
{"id":10,"ok":true,"data":{
  "count":1,"scanned":137,"truncated":false,
  "matches":[{
    "cls":"Button","text":"登录","desc":"","viewId":"com.foo:id/btn_login",
    "bounds":[100,200,700,320],"center":[400,260],
    "clickable":true,"enabled":true,"depth":4
  }]
}}
```

`center` 可直接用于 `tap`。

### device —— 设备状态 ⭐

一次拿到手机的完整状态。**不需要无障碍权限**,因此在服务还没绑定时也能用来排查。

```json
{"id":15,"cmd":"device"}
```

```json
{"id":15,"ok":true,"data":{
  "model":"Redmi K30","brand":"Xiaomi","manufacturer":"Xiaomi","device":"phoenix",
  "android":"12","sdk":31,"abi":"arm64-v8a",

  "screen":{"width":1080,"height":2400,"density":2.75,"densityDpi":440,"rotation":0},
  "battery":{"percent":47,"charging":true,"plugged":"usb","temperatureC":30.5,"health":"good"},
  "screenOn":true,"locked":false,

  "volume":{
    "music":{"current":8,"max":15},
    "ring":{"current":5,"max":7},
    "alarm":{"current":7,"max":7},
    "notification":{"current":5,"max":7},
    "mode":"normal"
  },
  "brightness":{"auto":false,"level":128,"max":255,"percent":50,"writable":false},

  "network":{"type":"wifi","online":true,"metered":false},
  "storage":{"totalBytes":...,"freeBytes":...,"usedPercent":62},
  "memory":{"totalBytes":...,"availableBytes":...,"lowMemory":false,"usedPercent":71},

  "service":{"running":true,"port":7912,"uptimeMs":2520000,"clients":1,"operations":18,
             "recent":[{"cmd":"observe","label":"截图 + 界面树","at":...,"detail":"1080x2400"}]},
  "accessibility":true
}}
```

**注意**:值拿不到时返回 `null` 或 `-1`,而不是编造一个看起来合理的数——
例如 `battery.percent` 在广播异常时是 `-1`,`brightness.percent` 同理。

### doubletap —— 拟人双击

```json
{"id":16,"cmd":"doubletap","x":540,"y":1200}
```

两次接触的**间隔**(88–209 ms)和**第二次落点**都随机。实现上是在**同一条
GestureDescription 里放两个独立 stroke**,而不是发两次请求——框架正是靠"同一个输入
序列内的两次接触"来识别双击的,分两次往返反而不成立。

### volume —— 调节音量

```json
{"id":17,"cmd":"volume","stream":"music","action":"set","level":6}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `stream` | `music` | `music` / `ring` / `alarm` / `notification` / `voice` |
| `action` | `up` | `up` / `down` / `mute` / `unmute` / `set` |
| `level` | — | `action:"set"` 时必填 |

响应返回调节后的 `current` 与 `max`。

> 走 AudioManager 而不是合成按键事件:息屏时也能用,而且不会把焦点抢给别的应用。

### brightness —— 调节亮度

```json
{"id":18,"cmd":"brightness","percent":40}
{"id":18,"cmd":"brightness","level":102}
```

| 参数 | 说明 |
|---|---|
| `percent` | 0–100 |
| `level` | 0–255(与二选一) |

**需要 `WRITE_SETTINGS` 特殊权限**——这个权限**不能通过运行时对话框授予**,必须让用户
去「设置 → 应用 → DSH Phone Agent → 修改系统设置」手动打开。先查 `device` 返回的
`brightness.writable` 可以避免无谓失败;未授权时命令会返回带指引的错误。

写入时会同时把亮度模式置为手动——否则在自动亮度机型上,设置会被系统立刻覆盖回去。

### pinch —— 双指缩放

```json
{"id":20,"cmd":"pinch","x":540,"y":1200,"startSpread":200,"endSpread":600,"durationMs":400}
```

`endSpread` 大于 `startSpread` 是放大,反之缩小。两根手指放在**同一条
GestureDescription** 里,所以是同时落下——框架正是靠这个区分"捏合"和"两次独立滑动"。

### flick —— 惯性甩动

```json
{"id":21,"cmd":"flick","x1":540,"y1":1700,"x2":540,"y2":900,"durationMs":90}
```

同样距离**慢慢拖**和**快速甩**在应用看来是两种操作:前者精确跟随手指,后者让列表带着
惯性继续滚。`durationMs` 默认 90,范围 40–400。

### scroll —— 按容器滚动

```json
{"id":22,"cmd":"scroll","direction":"forward","index":0}
```

找到第 `index` 个可滚动容器,对它下发 `ACTION_SCROLL_FORWARD` / `BACKWARD`。

比"盲滑屏幕"可靠:手势锚定在容器自身边界内,不会误触到浮动按钮或底部导航栏。
响应会回传被滚动的容器信息,便于确认滚对了地方。

### waitstable —— 等待界面稳定

```json
{"id":23,"cmd":"waitstable","timeoutMs":5000,"stableFrames":2}
```

连续 N 帧界面指纹相同即认为稳定,比固定 `wait` 可靠得多。**会如实报告是否真的稳定了**,
所以调用方可以区分"已就绪"和"到点了还在变"。

### colorat —— 单点取色

```json
{"id":24,"cmd":"colorat","x":540,"y":1200}
→ {"x":540,"y":1200,"color":"#FF5722","r":255,"g":87,"b":34,"alpha":255}
```

`findcolor` 的廉价版:取一个点只要一次截图 + 一次数组索引,而 `findcolor` 要扫全屏。
**适合轮询状态指示灯,不适合定位目标。**

### wake —— 唤醒屏幕

```json
{"id":25,"cmd":"wake"}
→ {"screenOn":true,"wasOn":false,"locked":true}
```

**只点亮屏幕,不解锁**,也不尝试解锁。

> 息屏时派发的手势会被系统**静默丢弃**——不报错、不回调。这是最难排查的一类失败,
> 所以操作前建议先 `wake`,并检查返回的 `locked`。

### clipboard —— 剪贴板

```json
{"cmd":"clipboard","action":"get"}
{"cmd":"clipboard","action":"set","text":"要复制的文本"}
{"cmd":"clipboard","action":"paste"}
```

`paste` 会把剪贴板内容注入当前焦点输入框(`ACTION_PASTE`)——当某个输入框
拒绝 `ACTION_SET_TEXT`(只认真实输入事件)时,这是唯一还能用的路径。

### deeplink —— 打开 URI

```json
{"cmd":"deeplink","uri":"weixin://","package":"com.tencent.mm"}
```

直达某个 App 的具体页面,省掉"启动 → 再点进去"的整段流程,也绕开了"应用打开后停在
开屏页/更新提示,模型找不到入口"这类常见失败。

### apps / stopapp —— 应用管理

```json
{"cmd":"apps","query":"微信","includeSystem":false}
{"cmd":"stopapp","package":"com.tencent.mm"}
```

`apps` 返回有启动入口的应用(标签 + 包名)。

`stopapp` 用 `killBackgroundProcesses`,**这是无 root 情况下能拿到的最强手段**:
它只回收**已在后台**的进程,前台应用不受影响。返回里的 `stillRunning` 和 `note` 会
如实说明这一点,而不是假装它是 force-stop。真正的前台强停需要 shell 权限。

### ocr —— 本地文字识别

在手机本地跑 OCR(**ML Kit bundled 版**:模型打包在 APK 内,**不需要 Google Play 服务**),所以国行无 GMS 的机器同样可用。

```json
{"id":13,"cmd":"ocr","region":[0,800,1080,1600]}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `region` | 全屏 | `[l,t,r,b]`,缩小范围可显著提速 |
| `minConfidence` | `0` | 预留 |

响应按 **block → line → element** 三级组织:

```json
{"id":13,"ok":true,"data":{
  "blockCount":1,"lineCount":2,"elementCount":6,
  "fullText":"账号登录\n忘记密码",
  "blocks":[{
    "text":"账号登录\n忘记密码","bounds":[100,900,700,1100],"center":[400,1000],
    "lines":[{"text":"账号登录","bounds":[100,900,500,960],"center":[300,930],
              "elements":[{"text":"账号","bounds":[...],"center":[...]}]}]
  }]
}}
```

**什么时候用它**:控件树看不到内容时才用——WebView/H5、游戏、Canvas、自绘控件、图片里的文字。**原生控件场景优先用 `find`**:快几百倍,而且带 viewId 和可点击性。

### findtext —— 按文本找控件 ⭐

把「看到字」变成「点得到」的那一步,也是 `find` 与 `ocr` 的融合层。

```json
{"id":14,"cmd":"findtext","text":"登录","tappable":true,"max":5}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `text` | 必填 | 要查找的文本 |
| `mode` | `contains` | `contains` 包含 / `exact` 完全相等 / `regex` 正则 |
| `source` | `auto` | `node` 只查控件树 / `ocr` 只 OCR / `auto` **先树后 OCR** |
| `tappable` | `true` | 是否解析出可点击目标 |
| `max` | `5` | 最多返回几个 |

**为什么必须有 `tapTarget`**:界面上「显示文字」的节点和「能点击」的节点**通常不是同一个**。文字常位于一个不可点击的 `TextView`,真正响应点击的是它的父 `LinearLayout`。OCR 只能给出文字矩形,`findtext` 会在该矩形中心做一次**命中测试**,返回这一点之下最深且最小的可点击节点:

```json
{"id":14,"ok":true,"data":{
  "count":1,"source":"ocr","query":"登录","mode":"contains",
  "matches":[{
    "text":"登录","from":"ocr",
    "bounds":[300,1180,780,1240],"center":[540,1210],
    "tapTarget":{"cls":"LinearLayout","bounds":[120,1150,960,1270],
                 "center":[540,1210],"clickable":true,"hitTest":true,"depth":9}
  }]
}}
```

**点 `tapTarget.center`,不要点文字自己的中心** —— 后者可能落在不可点击的文字节点上。

**分层策略**:`source` 默认 `auto`,先查控件树(毫秒级、带 viewId),只有没命中才回退到 OCR(数百毫秒)。这样绝大多数原生界面不必付 OCR 的代价,而 WebView、游戏这类盲区仍能兜住。

### findcolor —— 找色

用于无障碍树看不到内容的场景:游戏、Canvas、自绘控件、进度条、徽标。

```json
{"id":11,"cmd":"findcolor","color":"#FF5722","tolerance":16,"maxClusters":10}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `color` | 必填 | `#RRGGBB` 或 `#AARRGGBB` |
| `tolerance` | `16` | **每通道**允许偏差(0–255) |
| `region` | 全屏 | `[left, top, right, bottom]`,缩小范围可显著提速 |
| `maxClusters` | `20` | 返回多少个同色区域 |
| `clusterSize` | `24` | 聚类网格边长(px),越大合并得越狠 |

响应是**按像素数排序的聚类**,而不是一堆原始像素:

```json
{"id":11,"ok":true,"data":{
  "count":1842,"clusterCount":2,"region":[0,0,1080,2400],
  "clusters":[
    {"center":[540,1880],"bounds":[300,1860,780,1900],"pixels":1720},
    {"center":[120,240],"bounds":[110,230,130,250],"pixels":122}
  ]
}}
```

### findimage —— 找图(模板匹配)

```json
{"id":12,"cmd":"findimage","template":"<base64 PNG/JPEG>","threshold":0.85,"maxResults":3}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `template` | 必填 | base64 模板图,每边 ≤ 512px |
| `threshold` | `0.85` | 相似度阈值(1.0 = 完全相同) |
| `region` | 全屏 | 限定搜索区域 |
| `maxResults` | `5` | 最多返回几个匹配 |

> **实现说明**:朴素全分辨率匹配是 O(宽×高×模板面积)——1080×2400 配 100×100 模板约 2.2e10 次比较,要几十秒。这里先把图和模板降到模板长边约 28px 做一次穷举粗筛,再对前几个候选在原分辨率上小范围精修,因此能在百毫秒级返回。**缩小 `region` 是最有效的提速手段。**

```json
{"id":12,"ok":true,"data":{
  "count":1,"coarseScale":4,
  "matches":[{"center":[540,1200],"topLeft":[490,1150],
              "bounds":[490,1150,590,1250],"similarity":0.97}]
}}
```

---

## 拟人化改造了什么

`adb shell input` 的轨迹是**几何直线 + 恒定速度 + 固定事件间隔**。本 App 在无障碍手势通道上改写了三件事:

| 维度 | 传统自动化 | 本实现 |
|---|---|---|
| 路径 | 直线 | 二次贝塞尔弧线,控制点随机(约 ±2.2% 弦长),朝随机侧弯曲 |
| 速度 | 全程匀速 | 三段 ease-in-out:22% 距离用 34% 时间,56% 用 38%,尾段 22% 用 28% |
| 抖动 | 无 | 沿路径法线叠加 8–12 Hz 生理性震颤,两端收敛到 0(贴合按下/抬起) |
| 落点 | 几何中心 | 高斯散点(σ≈5.5 px) |
| 按压时长 | 固定 | 52–139 ms 随机 |

想对比效果,把 `human` 设为 `false` 再发同样的坐标即可。

> ⚠️ 仍未消除的差异:`dispatchGesture` 注入的事件由系统统一生成,**压力(`getPressure`)、接触面积(`getSize`)、来源设备 id(`getDeviceId`)无法自定义**。这些在应用层是可读的公开 API,是免 root 路线的固有天花板;要覆盖它们只有 `sendevent`(需 root)。

---

## 快速验证(Telnet / netcat)

```bash
# netcat
printf '{"id":1,"cmd":"ping"}\n' | nc 192.168.1.23 7912

# PowerShell
$c = [System.Net.Sockets.TcpClient]::new("192.168.1.23", 7912)
$s = $c.GetStream()
$w = [System.IO.StreamWriter]::new($s); $w.AutoFlush = $true
$r = [System.IO.StreamReader]::new($s)
$w.WriteLine('{"id":1,"cmd":"ping"}')
$r.ReadLine()
```

## 参考实现

`pc/pyclient/phone_client.py` 是一个约 200 行的参考客户端,可直接读它了解协议;不需要的话忽略即可。

## 安全边界

- 服务只监听局域网,不做端口映射或外网暴露。
- 未启用无障碍服务时,一切操作类命令都会被拒绝。

### 访问令牌

App 的「访问令牌」区块控制端口是否要求认证:

| 状态 | 行为 |
|---|---|
| **关闭**(默认) | 同一局域网内任意设备都能连接,**调用不需要带 token** |
| **开启** | 每次调用都必须带 `token`,否则返回 `code:"unauthorized"` |

```json
{"id":1,"cmd":"ping","token":"ae452e120c134a35"}
```

- 令牌值在 App 界面显示,点击即可复制
- **重启不更换**:存在 SharedPreferences,App 重启与手机重启都不变
- **更换须人工点击**,且有二次确认。更换会让正在使用旧令牌的脚本和插件**立即失效**,
  并持续返回 `unauthorized` 直到更新配置
- 首次启动会自动生成一个 16 位随机密钥,但**不会自动开启保护**
- 开关或更换令牌会**重启控制服务**(令牌在服务启动时读取一次,以换取请求热路径上
  不必每次查偏好设置)
