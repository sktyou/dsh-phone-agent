# DSH Phone Agent

用 **AccessibilityService** 控制一台已授权的 Android 手机。

**不 root,不依赖 adb,不需要 MediaProjection 授权弹窗。** 手机装一个 App,电脑通过局域网直连。

```powershell
# 手机端(MJPEG 实时画面)
http://<手机IP>:7913/

# 控制通道(一行一个 JSON)
<手机IP>:7912
```

---

## 为什么做这个

现有的 Android 自动化方案各有取舍:

| 方案 | 问题 |
|---|---|
| **adb input** | 需要连电脑、开 USB 调试,断线即失效 |
| **scrcpy** | 需要 adb 启动,只解决投屏不解决自动化 |
| **GKD / 李跳跳** | 只做跳过广告 |
| **Appium / ATX** | 重,依赖 adb 或 root |

这个项目想要的是:**装完就能用,断网也能用,脚本能挂机跑**。

代价是帧率——无障碍截图约 **2.5 fps**(见[性能](#性能与取舍)),这是这条通道的物理上限,不是没优化好。

---

## 功能

### 看
- **截图**:任意缩放 / 质量 / 区域
- **节点树**:完整控件树,含 viewId、文本、描述、状态
- **本地 OCR**:ML Kit 中文模型**打包在 APK 里**,无需 Google 服务
- **实时画面**:MJPEG 长连接推流

### 找
- **定位链 `locate`**:传一个词,内部按 `viewId → text → 包含 → desc → OCR → 颜色` 依次尝试,返回命中的策略与置信度
- **按选择器找控件**:viewId / text / desc / class / 状态组合
- **按文字找控件**:融合控件树与 OCR,自动解析到可点击的父节点
- **找色**:单点,按簇返回(而不是返回几千个像素)
- **多点找色**:锚点 + 偏移特征点,用来精确描述"某个图标"
- **找图**:模板匹配,支持全屏缩放粗搜 + 局部精搜
- **滚动识别(`sweep`)**:滚动 + OCR + 去重,一次调用扫完整屏长列表

### 做
- **执行前安全检查**:`tap` 先看目标位置有什么,返回「可点 / 可滚动 / 不可点 / 空」。**点击落在空白处不会报错** —— 这是自动化里最隐蔽的失败
- **拟人化手势**:贝塞尔弧线 + 生理性震颤 + 落点散布
- **滑动带刹车**:到达目标后静止 250–350ms 再抬手,**避免惯性冲过头**
- **动作连发 `sequence`**:多步一次调用不做网络往返,**实测比分开调用快 2.9 倍**
- **实测位移**:`swipemeasure` 告诉你内容到底滚了多远,而不是"手势被接受了"
- **执行事故跟踪 `incidents`**:失败动作开启记录,成功才关闭 —— 用它发现没关掉的弹窗
- 点击 / 双击 / 长按 / 甩动 / 缩放 / 输入 / 系统按键 / 音量 / 亮度 / 应用管理

### 接入
- **浏览器调试台**:七个 Tab,无构建步骤,同一局域网打开即用
- **MCP Server**:18 个工具,**零依赖**,Claude Code / Cursor / Codex / Windsurf 都能接
- **DSH 插件**:让 DeepSeek Harness 直接驱动手机

### 自动跳过广告
- **GKD 订阅格式**,可直接使用[社区维护的规则](https://github.com/topics/gkd-subscription)
- 总开关 + 每条规则独立开关
- 从浏览器调试台**点选节点直接生成规则**

---

## ⚠️ 手机 IP 会变

**这是最容易踩的坑,而且现象极具误导性。**

IP 变了之后连旧地址,会表现为:

- `ping` **通**(那个 IP 上有别的设备回 ARP)
- **TCP 永远建不了连接**(超时,而不是"连接被拒绝")

看起来像 **App 挂了或者死锁了**。**排查任何连接问题之前,先确认 IP。**

**权威来源是 App 首页显示的地址。** 缓存过 IP 的地方(PC 桥、MCP 配置、脚本)都会静默失效。

```powershell
# 用参数覆盖,别依赖默认值
node pc/mcp-server/index.mjs --host <App 首页显示的IP>
node tools/webui/server.mjs --host <App 首页显示的IP>
```

---

## 快速开始

### 1. 安装 App

从 [Releases](../../releases) 下载 APK 安装。首次启动后:

1. 打开**无障碍服务**(App 内点「打开设置」)
2. 授予**通知**权限(前台服务保活)
3. 可选:授予**修改系统设置**(用于调节亮度)

### 2. 打开浏览器调试台

同一局域网下访问 `http://<手机IP>:7913/`,IP 在 App 首页。

不需要装任何客户端。

### 3. (可选)接入 DSH

`pc/dsh-plugin/` 是一个 DeepSeek Harness 插件,装上后可以直接用自然语言驱动这台手机。

---

## 浏览器调试台

七个 Tab,左侧截屏始终可见:

| Tab | 用途 |
|---|---|
| **手势** | 起止坐标、时长、刹车、轨迹(按速度着色)、真实位移 |
| **找色** | 单击取色、单点找色、**多点特征编辑** |
| **找图** | Shift+拖动选区取模板,或选本地图片 |
| **识字** | OCR、按文字找控件、文字输入 |
| **节点** | 节点树、选中详情、对该节点执行、**生成跳过广告规则** |
| **设备** | 设备状态、**权限网格**、系统操作、音量亮度、滚动识别、应用 |
| **SKILL** | 完整操作说明,一键复制给 Agent |

**图像交互**:单击 = 起点+取色 · 双击 = 终点 · 拖动 = 滑动 · **Shift+拖动 = 选区域** · 滚轮 = 滚动

顶部**连接状态徽章**由真实流量推导:在线 / 迟滞 / 掉线。

---

## 设计要点

一些踩过坑之后才定下来的东西:

### 手势
- **`addStroke` 是多指,不是接续**。同一个 builder 里加三个 stroke 会变成三指手势,框架会丢弃大部分轨迹。实测效率只有 23%,合并成单 stroke 后 151%。
- **`StrokeDescription` 按路径长度均匀分配时间**,所以点距疏密**不影响速度**。想表达速度曲线只能拆成多段。
- **滑动精度取决于抬手速度**,不是距离。末尾速度快就会被判定为 fling 冲过头。解法是**到达后静止再抬手**:用 `continueStroke` 把"移动"和"刹车"串成一条连续手势。

### 稳定性
- **`takeScreenshot` 有速率限制**:同一时刻只允许一个请求,连续调用会失败。串行化 + 重试放在最底层封装里。
- **API 字段要先实测再写解析**。猜错 `findcolor` 返回的是 `clusters` 而不是 `matches`,表现为"功能完全没反应",比报错难查得多。

### 协议
- **长连接 MJPEG 而不是逐帧请求**:单帧截图 + 编码约 283ms,而局域网传 29KB 只要 0.2ms。**瓶颈是截图,不是网络**,所以换 WebSocket 没有意义。

---

## 性能与取舍

| 指标 | 值 |
|---|---|
| 实时画面帧率 | **约 2.5 fps** |
| 码率 | 71 KB/s |
| 推流内存 | 稳定 11.7 MB |
| 单次截图 | 283ms(scale 0.35) |

**2.5 fps 是这条通道的物理上限**,因为 `takeScreenshot` 本身就要 283ms。

想更快只有换捕获通道(MediaProjection + 硬件编码,30–60fps),**代价是每次会话一次授权弹窗**——这对"脚本半夜挂机"是致命的,所以没有采用。

> 参考:小米妙享桌面能做到流畅投屏,是因为 `com.xiaomi.mirror` 是 `PRIVILEGED` 系统应用,持有 `INJECT_EVENTS` 等签名级权限。**快的原因是有系统特权,不是没用 adb**,普通应用拿不到。

---

## 项目结构

```
.
├── android/                        手机端 App(Kotlin)
│   └── app/src/main/java/com/dsh/phoneagent/
│       ├── AgentAccessibilityService.kt  无障碍服务:UI 树 / 截图 / 手势
│       ├── GestureEngine.kt              拟人化轨迹引擎
│       ├── ControlServer.kt              TCP + JSON Lines 控制通道
│       ├── WebConsole.kt                 HTTP 控制台 + MJPEG 推流
│       ├── AdSkip{Activity,Engine,...}   广告跳过
│       ├── OcrEngine.kt                  本地 OCR(ML Kit bundled)
│       ├── ImageSearch.kt                找色 / 找图 / 多点找色
│       └── ...
├── tools/webui/                    浏览器调试台(单文件,无构建步骤)
├── pc/dsh-plugin/                  DSH 插件
├── skills/phone-agent/SKILL.md     能力说明书(构建时打包进 APK)
└── docs/
    ├── PROTOCOL.md                 协议说明
    └── help.html                   功能帮助(由 HelpContent.kt 生成)
```

---

## 构建

需要 JDK 17 和 Android SDK(platform 34 / build-tools 34.0.0)。

```bash
cd android
./gradlew assembleDebug
```

> **路径必须是纯 ASCII**。AGP 会拒绝非 ASCII 路径,`android.overridePathCheck` 也绕不过。

APK 约 51 MB,主要是中文 OCR 模型。

---

## 协议

完整说明见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。最简单的形式:

```json
→ {"id":1,"cmd":"tap","x":540,"y":1200}
← {"id":1,"ok":true,"data":{...},"elapsedMs":18}
```

---

## 许可

MIT
