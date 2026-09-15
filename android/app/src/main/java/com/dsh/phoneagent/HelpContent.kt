package com.dsh.phoneagent

/**
 * The capability reference shown on the Help screen.
 *
 * This is the single source of truth for "what can this thing do". **Every new
 * command must be added here**, otherwise the help page starts lying — and a help
 * page that lags reality is worse than no help page, because it is trusted.
 *
 * Kept as Kotlin data rather than a JSON asset deliberately: a typed list fails
 * the build when malformed, while a resource file fails silently at runtime on
 * the one screen a user opens precisely because they are already stuck.
 */
object HelpContent {

    data class Entry(
        val name: String,
        val summary: String,
        val detail: String,
        val example: String? = null,
        val params: List<Pair<String, String>> = emptyList(),
    )

    data class Category(
        val title: String,
        val blurb: String,
        val entries: List<Entry>,
    )

    val categories: List<Category> = listOf(
        Category(
            title = "如何调用",
            blurb = "三种接入方式,任选一种",
            entries = listOf(
                Entry(
                    name = "方式一:DSH 插件",
                    summary = "让大模型直接调用(推荐)",
                    detail = "插件装在 DSH 的 web profile 里,工具名 **phone_lan**。" +
                        "模型自己会挑 action,你只需要用自然语言描述目标。\n\n" +
                        "地址在插件包自己的 cordis.patch.yml 里配置(host / port / toolName)," +
                        "改完要重启 DSH——新增或改配置都不会热加载。",
                    example = """// 对模型说
「看看手机现在在哪个界面」
「在手机上打开微信,找到文件传输助手」

// 模型实际调用
action = "device"                      // 看设备状态
action = "observe"                     // 截图 + 界面树
action = "findtext", text = "登录"      // 按文本找控件
action = "tap", x = 540, y = 1200      // 拟人点击
action = "send"                        // 提交输入""",
                ),
                Entry(
                    name = "方式二:原始 TCP",
                    summary = "一行一个 JSON,任何语言都能接",
                    detail = "不依赖任何 SDK。协议就是 **一行一个 JSON**,UTF-8," +
                        "`\\n` 分隔,默认端口 **7912**。\n\n" +
                        "响应也是一行:成功是 `{\"ok\":true,\"data\":{...}}`," +
                        "失败是 `{\"ok\":false,\"error\":\"...\",\"code\":\"...\"}`。" +
                        "单条失败**不会断开连接**,可以继续发。",
                    example = """// 用 netcat 先试一下(最省事)
printf '{"id":1,"cmd":"ping"}\\n' | nc 192.168.12.138 7912

// PowerShell
${'$'}c = [Net.Sockets.TcpClient]::new("192.168.12.138", 7912)
${'$'}s = ${'$'}c.GetStream()
${'$'}w = [IO.StreamWriter]::new(${'$'}s); ${'$'}w.AutoFlush = ${'$'}true
${'$'}r = [IO.StreamReader]::new(${'$'}s)
${'$'}w.WriteLine('{"id":1,"cmd":"ping"}')
${'$'}r.ReadLine()""",
                ),
                Entry(
                    name = "方式三:Python 客户端",
                    summary = "现成的参考实现",
                    detail = "`pc/pyclient/phone_client.py`,约 200 行,直接读它就能理解协议。" +
                        "不需要的话可以在脚本里更简单地自己写一个。",
                    example = """from phone_client import PhoneClient

with PhoneClient("192.168.12.138") as phone:
    print(phone.info())          # 设备信息
    frame = phone.observe()      # 截图 + 界面树
    phone.tap(540, 1200)         # 拟人点击
    phone.swipe(540, 1700, 540, 900)   # 拟人滑动""",
                ),
                Entry(
                    name = "频率与限流",
                    summary = "高频发送会怎样,以及正确的写法",
                    detail = "服务端**串行执行**所有操作:同一时刻只处理一个,后到的排队。\n\n" +
                        "所以**不要不等响应就连续发**。一次 tap 要「手势 + 截图」约 300–800ms," +
                        "连发 100 个的话,第 100 个要等近一分钟才回,看起来就是卡死。\n\n" +
                        "App 侧做了两道保护:\n\n" +
                        "1. **点击节流**——两次点击之间强制最小间隔,默认 110ms" +
                        "(大致是人手最快连点速度)。可用 `minIntervalMs` 调整,设 0 关闭。\n" +
                        "2. **并发上限**——同时有 4 个操作在跑时,新请求直接返回 " +
                        "`busy` 错误而不是排队。\n\n" +
                        "**滑动和长按不受节流约束**:它们本身就是长动作,强行间隔没有物理依据。\n\n" +
                        "正确写法是**串行等待**:发一个 → 等响应 → 再发下一个。",
                    example = """// ❌ 错误:不等响应连发
for x in points:
    send({"cmd": "tap", "x": x, "y": 1200})

// ✅ 正确:等每一条的响应
for x in points:
    reply = send_and_wait({"cmd": "tap", "x": x, "y": 1200})
    if not reply["ok"]:
        handle(reply["code"])     // bad_request 不必重试

// ✅ 大量点击时关掉自动截图,最后统一观察一次
{"cmd": "tap", "x": 540, "y": 1200, "observe": false}
{"cmd": "observe"}                // 全部点完再看结果""",
                ),
                Entry(
                    name = "错误码",
                    summary = "区分该重试还是该改参数",
                    detail = "失败响应的 `code` 字段告诉你该怎么办:\n\n" +
                        "• `bad_request` — 参数不合法 → **不要重试**,改参数\n" +
                        "• `not_ready` — 界面或服务尚未就绪 → 稍等再试\n" +
                        "• `timeout` — 超时 → 可重试一次\n" +
                        "• `busy` — 操作积压 → 等一会儿再发\n" +
                        "• `unauthorized` — 令牌不匹配 → 检查 token\n" +
                        "• `io` — 读写失败 → 重连\n\n" +
                        "**`bad_request` 重复发送必然重复失败**,这是最容易浪费请求的一种。",
                    example = """{"id":7,"ok":false,
 "error":"(1200,3000) is outside 1080x2400",
 "code":"bad_request"}

{"id":8,"ok":false,
 "error":"no focused editable field",
 "code":"not_ready"}

{"id":9,"ok":false,
 "error":"busy: 4 operations already in flight; retry shortly",
 "code":"busy"}""",
                ),
            ),
        ),

        Category(
            title = "连接与状态",
            blurb = "确认链路是否正常,以及手机当前是什么状态",
            entries = listOf(
                Entry(
                    name = "ping",
                    summary = "连通性与无障碍状态",
                    detail = "最轻的一次往返。用来区分「连不上」「连上了但无障碍没开」" +
                        "「一切正常」三种情况。accessibility 为 false 时,除 ping / info / " +
                        "device 外的命令都会失败。",
                    example = """{"id":1,"cmd":"ping"}

{"id":1,"ok":true,"data":{
  "pong":true,"ts":1757654321000,
  "accessibility":true,"serving":true
}}""",
                ),
                Entry(
                    name = "info",
                    summary = "设备与屏幕基本信息",
                    detail = "型号、系统版本、屏幕尺寸、旋转角、亮灭屏、锁屏状态、前台包名。" +
                        "screenWidth/Height 是「全屏」尺寸(含导航栏),与截图坐标系一致。",
                    example = """{"id":2,"cmd":"info"}

{"id":2,"ok":true,"data":{
  "model":"Redmi K30","android":"12","sdk":31,
  "screenWidth":1080,"screenHeight":2400,
  "rotation":0,"screenOn":true,"locked":false,
  "foregroundPackage":"com.android.settings",
  "accessibility":true
}}""",
                ),
                Entry(
                    name = "device",
                    summary = "完整设备状态",
                    detail = "一次拿全:身份、屏幕、电量(百分比/充电/温度/健康度)、亮灭屏、" +
                        "锁屏、音量(5 种流)、亮度、网络、存储、内存,以及本服务自身的运行状态。" +
                        "不需要无障碍权限,所以服务没绑定时也能用它排查。取不到的值返回 null " +
                        "或 -1,不会编造一个看起来合理的数。",
                    example = """{"cmd":"device"}

{
  "battery":{"percent":47,"charging":true,"plugged":"usb"},
  "volume":{"music":{"current":8,"max":15},"mode":"normal"},
  "brightness":{"level":128,"percent":50,"writable":false},
  "network":{"type":"wifi","online":true},
  "storage":{"usedPercent":62},"memory":{"usedPercent":71},
  "service":{"running":true,"port":7912,"uptimeMs":2520000,"clients":1}
}""",
                ),
                Entry(
                    name = "wake",
                    summary = "唤醒屏幕",
                    detail = "只点亮屏幕,不解锁,也不尝试解锁。**息屏时派发的手势会被系统静默" +
                        "丢弃**——不报错也不回调,是最难排查的一类失败,所以操作前先 wake。" +
                        "返回里带 locked,锁屏状态下手势同样到不了应用。",
                    example = """{"cmd":"wake"}

{"screenOn":true,"wasOn":false,"locked":true}""",
                ),
            ),
        ),

        Category(
            title = "看屏幕",
            blurb = "按成本从低到高排列,能用低成本的就不用高成本的",
            entries = listOf(
                Entry(
                    name = "uitree",
                    summary = "界面节点树",
                    detail = "无障碍节点树。interactiveOnly 为 true 时只回可操作节点,并且是" +
                        "扁平列表而非层级——列表类页面能缩到 1/10,而且调用方想知道的多半就是" +
                        "「这屏能点哪些」。",
                    example = """{"cmd":"uitree","maxDepth":8,"maxNodes":300}

{"cmd":"uitree","interactiveOnly":true}""",
                    params = listOf(
                        "maxDepth" to "最大深度,默认 30",
                        "maxNodes" to "最大节点数,默认 2000",
                        "interactiveOnly" to "只回可操作节点,默认 false",
                    ),
                ),
                Entry(
                    name = "observe",
                    summary = "截图 + 界面树(一次往返)",
                    detail = "最常用的一条。一次往返同时拿到画面和节点树,避免截图与树不同步。" +
                        "支持缩放和区域裁剪,响应里会给出全屏尺寸与缩放系数。",
                    example = """{"cmd":"observe","includeUi":true}          // 截图 + 界面树

{"cmd":"observe","scale":0.5}                 // 半尺寸截图,省带宽

{"cmd":"observe","interactiveOnly":true}      // 界面树只回可点节点

{"cmd":"observe","region":[0,800,1080,1600]}  // 只截中间一块

{"screenWidth":1080,"screenHeight":2400,"scale":0.5,
 "imageWidth":540,"imageHeight":1200,
 "foregroundPackage":"com.android.settings",
 "image":"<base64 图片>","ui":{...}}""",
                    params = listOf(
                        "includeUi" to "是否附带界面树,默认 true",
                        "scale" to "0.05–1,缩小截图。边长减半省约 4 倍带宽",
                        "region" to "[left,top,right,bottom],限定范围",
                        "interactiveOnly" to "界面树只回可操作节点",
                        "quality" to "JPEG 质量 1–100,默认 82",
                    ),
                ),
                Entry(
                    name = "screenshot",
                    summary = "仅截图",
                    detail = "只取画面,不取节点树。同样支持 scale 与 region。",
                    example = """{"cmd":"screenshot","scale":0.4,"format":"jpeg","quality":70}""",
                ),
                Entry(
                    name = "find",
                    summary = "控件选择器",
                    detail = "按条件在节点树里筛选,**只返回命中项**。这是省 token 的关键:" +
                        "聊天列表或 WebView 动辄几千节点,全量传给模型又贵又慢,而手机本地筛完" +
                        "只要几毫秒。返回的 center 可直接用于 tap。",
                    example = """{"cmd":"find","selector":{
  "textContains":"登录","clickable":true
},"max":5}                                    // 文本包含「登录」且可点击

{"cmd":"find","viewId":"btn_login"}           // 按资源 ID(后缀匹配)
{"cmd":"find","cls":"Button"}                 // 按控件类型
{"cmd":"find","selector":{"editable":true}}   // 找输入框
{"cmd":"find","textRegex":"^\d{6}$"}          // 6 位数字(验证码框)

{"count":1,"scanned":137,
 "matches":[{"cls":"Button","text":"登录",
             "center":[400,260],"clickable":true}]}""",
                    params = listOf(
                        "text" to "文本完全相等",
                        "textContains" to "文本包含",
                        "textRegex" to "文本正则(Java 语法)",
                        "desc / descContains" to "contentDescription",
                        "viewId" to "资源 ID,**按后缀匹配**(传 btn_login 即可)",
                        "viewIdContains" to "资源 ID 包含",
                        "cls" to "类名短名,如 TextView、Button",
                        "clickable 等" to "clickable/longClickable/scrollable/editable/enabled/checked/selected/focused",
                        "depthMin / depthMax" to "深度范围",
                    ),
                ),
                Entry(
                    name = "findtext",
                    summary = "按文本找控件 ⭐",
                    detail = "融合节点树与 OCR,并把命中点解析成**真正可点击的控件**。\n\n" +
                        "关键坑:显示文字的节点和能点击的节点**通常不是同一个**。文字常在一个" +
                        "不可点击的 TextView 里,真正响应点击的是它的父 LinearLayout。" +
                        "findtext 会对命中点做命中测试,返回这一点之下「最深且最小」的可点击节点。\n\n" +
                        "**点 tapTarget.center,不要点文字自己的 center。**",
                    example = """{"cmd":"findtext","text":"登录","tappable":true}  // 找「登录」并解析可点目标

{"cmd":"findtext","text":"登录","source":"ocr"}   // 强制走 OCR(WebView/游戏)
{"cmd":"findtext","text":"登录","source":"node"}  // 只查控件树(更快)
{"cmd":"findtext","text":"^\d{6}$","mode":"regex"} // 正则匹配

{"count":1,"source":"ocr","matches":[{
  "text":"登录","from":"ocr","center":[540,1210],
  "tapTarget":{"cls":"LinearLayout","center":[540,1210],
               "clickable":true,"depth":9}        // ← 点这个
}]}""",
                    params = listOf(
                        "text" to "要查找的文本",
                        "mode" to "contains(默认)/ exact / regex",
                        "source" to "auto(默认,先树后 OCR)/ node / ocr",
                        "tappable" to "是否解析可点击目标,默认 true",
                    ),
                ),
                Entry(
                    name = "ocr",
                    summary = "本地文字识别",
                    detail = "在手机本地跑 OCR(ML Kit bundled 版,模型在 APK 内," +
                        "**不需要 Google Play 服务**,国行无 GMS 也能用)。\n\n" +
                        "用于节点树看不到内容的场景:WebView/H5、游戏、Canvas、自绘控件、" +
                        "图片里的文字。**原生控件场景优先用 find**,快几百倍且带 viewId。\n\n" +
                        "返回按 block → line → element 三级组织。",
                    example = """{"cmd":"ocr","region":[0,800,1080,1600]}

{"blockCount":1,"lineCount":2,
 "fullText":"账号登录\\n忘记密码",
 "blocks":[{"text":"账号登录","bounds":[100,900,500,960],
            "center":[300,930]}]}""",
                ),
                Entry(
                    name = "colorat",
                    summary = "单点取色",
                    detail = "取屏幕上某一个像素的颜色。findcolor 的廉价版:取一个点只要一次" +
                        "截图加一次数组索引,而 findcolor 要扫全屏。**适合轮询状态指示灯," +
                        "不适合定位目标。**",
                    example = """{"cmd":"colorat","x":540,"y":1200}

{"x":540,"y":1200,"color":"#FF5722",
 "r":255,"g":87,"b":34,"alpha":255}""",
                ),
                Entry(
                    name = "findmulti",
                    summary = "多点找色(锚点+偏移)",
                    detail = "**单个颜色在真实屏幕上几乎没有辨识度** —— 任何一个色调都有成千上万" +
                        "像素命中,所以单点找色只能告诉你\"这个颜色在哪\",不能告诉你\"那个东西在哪\"。\n\n" +
                        "多点找色用**一个锚点色 + 若干固定偏移点**来描述目标:必须**锚点命中," +
                        "且每个偏移点也各自命中自己的颜色**,才算一处匹配。这样才能在几百个图标里" +
                        "锁定特定那一个。\n\n" +
                        "`points` 是 `[dx, dy, 颜色]` 的数组,坐标**相对锚点**。\n\n" +
                        "**采样要点**:\n" +
                        "• 锚点选**稀有、稳定**的像素(纯色背景块最差,满屏都是)\n" +
                        "• 偏移点选**纹理丰富**的位置 —— 边缘、文字、图标细节\n" +
                        "• 偏移量**越大越精确**,但也越不耐缩放\n" +
                        "• 3–6 个偏移点通常就够,超过 48 会被拒\n\n" +
                        "响应里的 `anchorsTried` 能帮你判断锚点选得好不好:**这个数越大,说明" +
                        "锚点色越普通**,匹配会越慢。",
                    example = """// 描述"某个图标":锚点取图标主色,偏移点取它的特征像素
{"cmd":"findmulti",
 "anchor":"#1E88E5",
 "points":[[14,0,"#FFFFFF"],[0,18,"#FFC107"],[26,22,"#212121"]],
 "tolerance":12,"max":20}
→ {"count":1,"scanned":82848,"anchorsTried":2440,
   "matches":[{"center":[648,1204],"bounds":[634,1186,660,1226]}]}
// anchorsTried 很小 = 锚点选得好
""",
                    params = listOf(
                        "anchor" to "锚点颜色 #RRGGBB",
                        "points" to "[[dx,dy,\"#RRGGBB\"], ...] 相对锚点的偏移",
                        "tolerance" to "每个通道的容差 0–255,默认 16",
                        "region" to "限定搜索范围",
                        "max" to "最多返回几处,默认 20",
                    ),
                ),
                Entry(
                    name = "findcolor",
                    summary = "单点找色(按簇返回)",
                    detail = "按颜色查找,返回**按像素数排序的聚类区域**,而不是一堆原始像素点。" +
                        "用于节点树看不到内容、但颜色稳定的目标:徽标、进度条、游戏元素。" +
                        "region 能显著提速。",
                    example = """{"cmd":"findcolor","color":"#FF5722",
  "tolerance":16,"maxClusters":10}

{"count":1842,"clusterCount":2,
 "clusters":[{"center":[540,1880],
              "bounds":[300,1860,780,1900],"pixels":1720}]}""",
                    params = listOf(
                        "color" to "#RRGGBB 或 #AARRGGBB",
                        "tolerance" to "每通道容差 0–255,默认 16",
                        "region" to "限定搜索范围",
                        "clusterSize" to "聚类网格边长,默认 24",
                    ),
                ),
                Entry(
                    name = "findimage",
                    summary = "找图(模板匹配)",
                    detail = "朴素全分辨率匹配是 O(宽×高×模板面积)——1080×2400 配 100×100 " +
                        "模板约 2.2×10¹⁰ 次比较,要几十秒。这里先把图和模板降到模板长边约 28px " +
                        "做穷举粗筛,再对前几个候选在原分辨率上小范围精修,因此能在百毫秒级返回。\n\n" +
                        "**缩小 region 是最有效的提速手段。**",
                    example = """{"cmd":"findimage","template":"<base64 PNG>",
  "threshold":0.85,"region":[0,800,1080,1600]}

{"count":1,"coarseScale":4,
 "matches":[{"center":[540,1200],
             "bounds":[490,1150,590,1250],
             "similarity":0.97}]}""",
                ),
                Entry(
                    name = "waitstable",
                    summary = "等待界面稳定",
                    detail = "连续 N 帧界面指纹相同即认为稳定,比固定 sleep 可靠得多。" +
                        "**会如实报告是否真的稳定了**,所以调用方能区分「已就绪」和" +
                        "「到点了还在变」。",
                    example = """{"cmd":"waitstable","timeoutMs":5000,"stableFrames":2}

{"stable":true,"samples":6,"elapsedMs":1080}""",
                ),
            ),
        ),

        Category(
            title = "操作屏幕",
            blurb = "全部默认拟人——手指不可能每次都落在同一个像素上",
            entries = listOf(
                Entry(
                    name = "tap",
                    summary = "点击(拟人)",
                    detail = "落点带高斯散点(σ≈5.5px)、亚像素级微滑、52–139ms 随机按压时长。\n\n" +
                        "**这不是可选项**:human:false 只是留给 A/B 对照的开关,正常使用不应打开。",
                    example = """{"cmd":"tap","x":540,"y":1200}                   // 基本点击
{"cmd":"tap","x":540,"y":1200,"retry":3}          // 被取消时最多重试 3 次
{"cmd":"tap","x":540,"y":1200,"minIntervalMs":0}  // 关闭点击节流(不推荐)""",
                    params = listOf(
                        "x / y" to "全屏截图像素坐标",
                        "retry" to "被取消时重试 1–5 次,默认 1",
                        "minIntervalMs" to "两次点击最小间隔,默认 110ms;设 0 关闭",
                        "human" to "默认 true。false 仅用于对照",
                    ),
                ),
                Entry(
                    name = "doubletap",
                    summary = "双击(拟人)",
                    detail = "两次接触的**间隔(88–209ms)和第二次落点都随机**。\n\n" +
                        "实现细节:两次接触放在**同一条 GestureDescription 的两个 stroke** 里," +
                        "而不是发两次请求——框架正是靠「同一输入序列内的两次接触」识别双击的," +
                        "分两次往返反而不成立。",
                    example = """{"cmd":"doubletap","x":540,"y":1200}   // 双击(如点赞)

{"completed":true,"x":540,"y":1200}""",
                ),
                Entry(
                    name = "longpress",
                    summary = "长按(拟人)",
                    detail = "按住期间**分 3 段微动**——真人手指不会完全静止。原来的实现是" +
                        "一根静止的直线,特征明显。",
                    example = """{"cmd":"longpress","x":540,"y":1200,"holdMs":650}  // 长按

{"cmd":"longpress","x":540,"y":1200,"holdMs":2000} // 长按 2 秒(菜单/拖拽)

{"completed":true,"holdMs":650}""",
                    params = listOf(
                        "holdMs" to "按住时长,300–5000ms,默认 650",
                        "retry" to "被取消时重试次数",
                    ),
                ),
                Entry(
                    name = "swipe",
                    summary = "滑动(到达后停留再抬手)",
                    detail = "二次贝塞尔弧线(控制点随机、朝随机侧弯曲约 ±2.2% 弦长)、" +
                        "沿法线叠加的生理性震颤(两端收敛为 0),速度剖面为" +
                        "**加速 → 匀速 → 减速 → 到达后停留约 200ms 再抬手**。\n\n" +
                        "**为什么要有那个停留**:系统在手指离开后按**抬手瞬间的速度**" +
                        "决定要不要继续惯性滚动。末尾速度快就会\"甩\"出去、落点超出坐标;" +
                        "速度接近 0 才会正好停在目标。\n\n" +
                        "只靠减速曲线做不到这件事:300ms 内既要走完 1000px、又要在最后" +
                        "100ms 爬行,数学上不可能。**停留 200ms 绕开了这道算术** —— 最后" +
                        "那段采样点几乎重合,速度必然是 0,无论滑得多快。\n\n" +
                        "这也正是真人的做法:快速划过去,**停住**,再抬手。" +
                        "停留只有 200ms,不增加可见的耗时。\n\n" +
                        "**实践建议**:\n" +
                        "• 需要**落在指定位置** → 用 600–1200ms\n" +
                        "• 需要**快速翻页** → 用 200–350ms,现在同样能刹住\n" +
                        "• 要**读完整列表** → 用 `sweep`,它自己处理滚动",
                    example = """// 慢滑到位
{"cmd":"swipe","x1":540,"y1":1900,"x2":540,"y2":700,"durationMs":1000}

// 快滑 —— 同样能精确停下(靠末尾 200ms 停留)
{"cmd":"swipe","x1":540,"y1":1900,"x2":540,"y2":700,"durationMs":300}

// 想知道实际滚了多少
{"cmd":"swipemeasure","x1":540,"y1":1900,"x2":540,"y2":700,
 "durationMs":300,"settleMs":1000}
→ {"fingerDistance":1200,"contentShift":1180,"efficiency":0.98}

// 读长列表:一次调用扫完
{"cmd":"sweep","scrolls":40,"distance":1100,"perCapture":2,
 "region":[280,400,1080,2100]}""",
                    params = listOf(
                        "durationMs" to "60–4000,默认 320。末尾另有约 200ms 停留",
                        "human" to "默认 true。false 为机械直线,仅用于对照",
                        "trace" to "true 时返回 [x,y,timeMs] 采样点,用于画速度曲线",
                    ),
                ),
                Entry(
                    name = "swipemeasure",
                    summary = "滑动并测量真实位移",
                    detail = "滑动,**并告诉你内容实际滚了多远**。\n\n" +
                        "`swipe` 返回 `completed:true` 只说明手势被系统接受了," +
                        "**完全不代表内容滚动了相应的距离** —— 列表到边界就不再跟手、" +
                        "惯性会让它继续走、页面也可能根本不滚。想知道实情只能量。\n\n" +
                        "做法是滑动前后各截一屏,用归一化互相关找垂直偏移。\n\n" +
                        "**读法**:\n" +
                        "• `fingerDistance` 手指走了多远\n" +
                        "• `contentShift` 内容实际滚了多少(**-1 表示无法测量**,不是 0)\n" +
                        "• `efficiency` = 位移 ÷ 手指距离。**大于 1 说明触发了惯性**\n" +
                        "• `matchScore` 匹配相关系数,偏低说明两次截图没对齐(页面变了)",
                    example = """{"cmd":"swipemeasure","x1":540,"y1":1900,
 "x2":540,"y2":700,"durationMs":300,"settleMs":1000}

→ {"fingerDistance":1200,"contentShift":1810,
   "efficiency":1.51,          // >1 = 甩出去了
   "matchScore":0.98,"matchConfident":true,
   "path":[[540,1900,0], ...]}  // 带时间戳的采样点""",
                    params = listOf(
                        "durationMs" to "滑动时长,同上",
                        "settleMs" to "滑动后等待多久再截图,默认 800。惯性大的要留足",
                    ),
                ),
                Entry(
                    name = "flick",
                    summary = "惯性甩动",
                    detail = "短而快的滑动,让内容带着惯性继续滚。\n\n" +
                        "**和 swipe 是两种操作**:同样距离慢慢拖是拖拽(内容精确跟随手指)," +
                        "快速甩是抛出(内容继续滚一段),应用对两者的响应确实不同。",
                    example = """{"cmd":"flick","x1":540,"y1":1700,
         "x2":540,"y2":900,"durationMs":90}   // 快速上甩

{"cmd":"flick","x1":540,"y1":1700,
         "x2":540,"y2":1400,"durationMs":60}  // 短促甩动

{"completed":true,"durationMs":90}""",
                ),
                Entry(
                    name = "pinch",
                    summary = "双指缩放",
                    detail = "两根手指放在**同一条 GestureDescription** 里,所以是同时落下——" +
                        "框架正是靠这个区分「捏合」和「两次独立滑动」。\n\n" +
                        "endSpread 大于 startSpread 是放大,反之缩小。地图、图片查看器、" +
                        "部分游戏没有替代方案。",
                    example = """{"cmd":"pinch","x":540,"y":1200,
         "startSpread":200,"endSpread":600}   // 放大(两指张开)

{"cmd":"pinch","x":540,"y":1200,
         "startSpread":600,"endSpread":200}   // 缩小(两指捏合)

{"completed":true,"startSpread":200,"endSpread":600}""",
                ),
                Entry(
                    name = "scroll",
                    summary = "按容器滚动一屏",
                    detail = "找到第 index 个可滚动容器,对它下发 ACTION_SCROLL_FORWARD / " +
                        "BACKWARD。\n\n" +
                        "比「盲滑屏幕」可靠:手势锚定在容器自身边界内,不会误触到浮动按钮或" +
                        "底部导航栏。响应会回传被滚动的容器信息。",
                    example = """{"cmd":"scroll","direction":"forward","index":0}   // 往下滚一屏
{"cmd":"scroll","direction":"backward","index":0}  // 往上滚一屏
{"cmd":"scroll","direction":"forward","index":1}   // 第 2 个滚动容器

{"scrolled":true,"direction":"forward",
 "container":{"cls":"RecyclerView",
              "bounds":[0,300,1080,2000]}}""",
                    params = listOf(
                        "direction" to "forward(默认)/ backward",
                        "index" to "第几个可滚动容器,默认 0",
                    ),
                ),
                Entry(
                    name = "key",
                    summary = "系统按键(8 个)",
                    detail = "全局系统动作。响应会回传中文名 label,便于日志和展示。\n\n" +
                        "• `BACK` — 返回\n" +
                        "• `HOME` — 回到桌面\n" +
                        "• `RECENTS` / `APPSWITCH` — 最近任务\n" +
                        "• `NOTIFICATIONS` — 下拉通知栏\n" +
                        "• `QUICK_SETTINGS` — 快捷设置面板\n" +
                        "• `POWER` — 电源菜单\n" +
                        "• `LOCK` — 锁屏\n" +
                        "• `SCREENSHOT` — 截屏\n\n" +
                        "⚠️ 这里只有**全局动作**,没有 ENTER / 退格——无障碍服务注入不了按键事件。\n" +
                        "需要提交输入用 `send`,需要回删用 `delete`。",
                    example = """{"cmd":"key","key":"BACK"}           // 返回
{"cmd":"key","key":"HOME"}           // 回到桌面
{"cmd":"key","key":"RECENTS"}        // 最近任务
{"cmd":"key","key":"NOTIFICATIONS"}  // 下拉通知栏
{"cmd":"key","key":"QUICK_SETTINGS"} // 快捷设置面板
{"cmd":"key","key":"LOCK"}           // 锁屏

// 响应
{"performed":true,"key":"BACK","label":"返回"}""",
                ),
            ),
        ),

        Category(
            title = "输入文字",
            blurb = "不依赖输入法,也不需要剪贴板中转",
            entries = listOf(
                Entry(
                    name = "text",
                    summary = "向焦点输入框写文本",
                    detail = "走 ACTION_SET_TEXT,所以**中文和 emoji 直接可用,不需要输入法," +
                        "也不走剪贴板**——这正是 adb 方案要额外跑 scrcpy 才能做到的事。\n\n" +
                        "typing:\"natural\" 会逐字符提交并插入随机间隔。**整段文字在一帧里出现" +
                        "本身就是个信号**,没有人类是那样打字的。代价是慢,只在输入框会观察" +
                        "输入时序时才值得。",
                    example = """{"cmd":"text","text":"你好,世界"}                  // 写入

{"cmd":"text","text":"追加内容","mode":"append"}  // 追加到已有内容后

{"cmd":"text","text":"","mode":"clear"}          // 清空(等价于 clear 命令)

{"cmd":"text","text":"逐字输入","typing":"natural"} // 逐字符 + 随机间隔

{"inserted":true,"length":5,"mode":"replace","typing":"instant"}""",
                    params = listOf(
                        "text" to "要写入的文本",
                        "mode" to "replace(默认)/ append / clear",
                        "typing" to "instant(默认)/ natural",
                    ),
                ),
                Entry(
                    name = "send",
                    summary = "提交输入(发送 / 搜索 / 完成)",
                    detail = "让输入法执行它自己的编辑器动作:聊天框里是「发送」,搜索框里是" +
                        "「搜索」,表单里是「完成」——和你按键盘右下角那个键**是同一个动作**," +
                        "所以应用对两者的处理完全一致。\n\n" +
                        "⚠️ 这**不是**合成的 Enter 按键。无障碍服务根本无法注入按键事件" +
                        "(那需要 INJECT_EVENTS,只有 shell/系统权限才有),所以只能走输入法这条路。\n\n" +
                        "字段若返回 sent:false,说明它没有声明编辑器动作,此时改用点击发送按钮。",
                    example = """{"cmd":"tap","x":540,"y":900}     // 先点输入框
{"cmd":"text","text":"你好"}
{"cmd":"send"}

{"sent":true,"note":"IME action performed"}""",
                ),
                Entry(
                    name = "delete",
                    summary = "回删(退格)",
                    detail = "从焦点输入框末尾删除 N 个字符,默认 1。\n\n" +
                        "⚠️ **实现方式的诚实说明**:无障碍没有任何「发送退格键」的动作" +
                        "(同样要 INJECT_EVENTS)。这里的做法是读出当前文本、写回去掉末尾的内容。\n\n" +
                        "**内容上完全等价**,区别在于它是一次原子写入——如果某个输入框会检查按键" +
                        "节奏,是能看出差别的。这是免 root 路线的硬限制,不是实现疏漏。",
                    example = """{"cmd":"delete"}          // 删 1 个
{"cmd":"delete","count":5}   // 删 5 个

{"deleted":1,"remaining":4,"applied":true}""",
                    params = listOf("count" to "删除字符数,1–500,默认 1"),
                ),
                Entry(
                    name = "clear",
                    summary = "清空输入框",
                    detail = "把焦点输入框清空,返回清空前的内容长度。\n\n" +
                        "比 text 的 mode:\"clear\" 更直观,单独成一个命令是因为「清空重填」是" +
                        "很常见的一步,值得少写一个参数。",
                    example = """{"cmd":"clear"}

{"cleared":true,"clearedLength":12}""",
                ),
                Entry(
                    name = "clipboard",
                    summary = "剪贴板读 / 写 / 粘贴",
                    detail = "get 读取、set 写入、paste 注入到当前焦点输入框。\n\n" +
                        "**paste 是救命路径**:某些输入框拒绝 ACTION_SET_TEXT(只认真实输入" +
                        "事件),此时只能先把文本写进剪贴板再粘贴。",
                    example = """{"cmd":"clipboard","action":"get"}

{"cmd":"clipboard","action":"set","text":"要复制的文本"}

{"cmd":"clipboard","action":"paste"}""",
                ),
            ),
        ),

        Category(
            title = "应用与系统",
            blurb = "启动、跳转、结束,以及音量亮度",
            entries = listOf(
                Entry(
                    name = "launch",
                    summary = "启动应用",
                    detail = "按包名启动。需要先知道包名,可以用 apps 查。",
                    example = """{"cmd":"launch","package":"com.android.settings"}""",
                ),
                Entry(
                    name = "deeplink",
                    summary = "打开 URI(直达页面)",
                    detail = "直达某个 App 的具体页面,省掉「启动 → 再点进去」的整段流程。" +
                        "也绕开了「应用打开后停在开屏页或更新提示,模型找不到入口」这类常见失败。",
                    example = """{"cmd":"deeplink","uri":"weixin://","package":"com.tencent.mm"}""",
                ),
                Entry(
                    name = "apps",
                    summary = "已安装应用列表",
                    detail = "返回有启动入口的应用(标签 + 包名),可按名称或包名过滤。" +
                        "默认不含系统预装应用。",
                    example = """{"cmd":"apps","query":"微信","includeSystem":false}""",
                ),
                Entry(
                    name = "stopapp",
                    summary = "结束后台应用",
                    detail = "用 killBackgroundProcesses,**这是无 root 情况下能拿到的最强手段**:\n\n" +
                        "它只回收**已在后台**的进程,前台应用不受影响。返回里的 stillRunning " +
                        "和 note 会如实说明这一点,而不是假装它是 force-stop——真正的前台强停" +
                        "需要 shell 权限。",
                    example = """{"cmd":"stopapp","package":"com.tencent.mm"}

{"backgroundKilled":true,"stillRunning":false,
 "note":"background processes reclaimed"}""",
                ),
                Entry(
                    name = "volume",
                    summary = "调节音量",
                    detail = "走 AudioManager 而不是合成按键事件——**息屏时也能用**," +
                        "而且不会把焦点抢给别的应用。",
                    example = """{"cmd":"volume","stream":"music","action":"set","level":6}

{"cmd":"volume","stream":"ring","action":"mute"}""",
                    params = listOf(
                        "stream" to "music / ring / alarm / notification / voice",
                        "action" to "up / down / mute / unmute / set",
                    ),
                ),
                Entry(
                    name = "brightness",
                    summary = "调节亮度",
                    detail = "需要 **WRITE_SETTINGS 特殊权限**——这个权限**不能通过运行时对话框" +
                        "授予**,必须去「设置 → 应用 → 本 App → 修改系统设置」手动打开。" +
                        "先查 device 返回的 brightness.writable 可以避免无谓失败。\n\n" +
                        "写入时会**同时把亮度模式置为手动**,否则自动亮度机型会立刻覆盖回去。",
                    example = """{"cmd":"brightness","percent":40}

{"cmd":"brightness","level":102}""",
                ),
            ),
        ),

        Category(
            title = "概念与约定",
            blurb = "理解这几条能少踩很多坑",
            entries = listOf(
                Entry(
                    name = "坐标约定",
                    summary = "一切坐标都是全屏截图像素",
                    detail = "原点是屏幕左上角,尺寸与截图一致(含导航栏)。\n\n" +
                        "⚠️ 别用 Android 的 resources.displayMetrics——那是「应用可用区域」," +
                        "在手势导航机型上会少掉导航栏高度(实测 2261 vs 2400)。据此换算会让" +
                        "坐标整体偏移。\n\n" +
                        "截图有缩放时,换算规则:\n触摸坐标 = 图像坐标 ÷ scale + region 左上角",
                    example = """响应会同时给出:
{"screenWidth":1080,"screenHeight":2400,
 "scale":0.5,"imageWidth":540,"imageHeight":1200}""",
                ),
                Entry(
                    name = "两个独立的授权",
                    summary = "常驻通知 ≠ 无障碍",
                    detail = "**常驻通知(前台服务)**:让进程活着,端口继续监听。" +
                        "掉了的表现是「手机从电脑视野里消失」。\n\n" +
                        "**无障碍服务**:允许读界面、截图、注入手势。" +
                        "掉了的表现是「端口还在,但所有操作类命令失败」。\n\n" +
                        "这两个是独立状态,排查时先看掉的是哪一个。",
                    example = """{"cmd":"ping"}
→ {"accessibility":true,"serving":true}

重装 App 会清空无障碍授权(Android 12 的受限设置),
但**不影响前台服务**。""",
                ),
                Entry(
                    name = "分层选择策略",
                    summary = "按成本递进,别一上来就烧算力",
                    detail = "找目标时按这个顺序,绝大多数情况在第一层就解决了:\n\n" +
                        "1. find 控件树 —— 毫秒级,覆盖原生控件(大多数界面)\n" +
                        "2. findtext + OCR —— 数百毫秒,覆盖 WebView、游戏、图片文字\n" +
                        "3. findcolor / findimage —— 百毫秒级,无文字但颜色图形稳定\n" +
                        "4. observe 交给视觉模型 —— 秒级 + token,前三层都失败时兜底",
                    example = """// 先试最便宜的
{"cmd":"find","selector":{"textContains":"立即购买"}}

// 没命中再上 OCR
{"cmd":"findtext","text":"立即购买","source":"ocr"}

// 还不行才截图给模型看
{"cmd":"observe","scale":0.5}""",
                ),
                Entry(
                    name = "安全边界",
                    summary = "免 root 的固有天花板",
                    detail = "dispatchGesture 注入的事件由系统统一生成,**压力(getPressure)、" +
                        "接触面积(getSize)、来源设备 id(getDeviceId)无法自定义**。这三项在应用层" +
                        "都是可读的公开 API,属于免 root 路线的硬上限;要覆盖它们只有 sendevent " +
                        "直接写 /dev/input/eventX,那需要 root。\n\n" +
                        "好消息:InputEvent.isTainted() 是 @hide 的内部标志,普通 App 读不到。",
                    example = """轨迹可以伪造:
  路径 = 贝塞尔弧线 + 随机控制点
  速度 = 三段 ease-in-out
  抖动 = 沿法线的高斯噪声

事件属性不能伪造:
  压力 / 接触面积 / 来源设备 id

→ 前者是特征推断,后者是确定性证据。""",
                ),
                Entry(
                    name = "访问令牌",
                    summary = "可选的端口认证",
                    detail = "App 首页「访问令牌」区块控制端口是否要求认证。\n\n" +
                        "• **关闭**(默认)—— 同一局域网内任意设备都能连,**调用不用带令牌**\n" +
                        "• **开启** —— 每次调用都必须带 `token`,否则返回 `code:\"unauthorized\"`\n\n" +
                        "几点设计取舍:\n" +
                        "• 令牌与开关**分开存**,关掉保护不会丢密钥\n" +
                        "• **重启保持** —— App 重启、手机重启都不变,只有人工点击才会更换\n" +
                        "• **更换有二次确认** —— 因为它会让所有正在用旧令牌的脚本和插件**立刻失效**\n" +
                        "• 首次启动**自动生成**一个 16 位密钥,但**不会自动开启**保护\n" +
                        "• 开关或更换会**重启控制服务**(令牌在服务启动时读一次,换来请求热路径\n" +
                        "  上不必每次查偏好设置)",
                    example = """// 关闭时:直接调用
{"id":1,"cmd":"ping"}
→ {"ok":true,"data":{"pong":true}}

// 开启后:必须带令牌
{"id":1,"cmd":"ping","token":"ae452e120c134a35"}
→ {"ok":true,"data":{"pong":true}}

{"id":1,"cmd":"ping"}
→ {"ok":false,"error":"unauthorized","code":"unauthorized"}

{"id":1,"cmd":"ping","token":"错的"}
→ {"ok":false,"error":"unauthorized","code":"unauthorized"}""",
                ),
            ),
        ),
    )

    /** Total number of documented commands, shown on the help header. */
    val totalEntries: Int get() = categories.sumOf { it.entries.size }
}
