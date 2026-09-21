# DSH Phone Agent — MCP Server

把手机暴露成 [Model Context Protocol](https://modelcontextprotocol.io/) 工具,让**任何 MCP 客户端**都能驱动真机 —— Claude Code、Cursor、Codex、Windsurf、Antigravity 等。

**零依赖**,只用 Node 内置模块。传输走 stdio(JSON-RPC 2.0),这是 MCP 本地服务器的标准方式。

---

## 前置条件

1. 手机装好 [DSH Phone Agent APK](../../README.md),无障碍服务已开启
2. 电脑和手机在同一局域网
3. Node.js 18+

确认手机可达:浏览器打开 `http://<手机IP>:7913/`,能看到控制台就说明通了。

---

## 配置

把下面这段加到客户端的 MCP 配置里,替换 `--host` 为你的手机 IP。

### Claude Code

`~/.claude.json` 或项目根目录的 `.mcp.json`:

```json
{
  "mcpServers": {
    "phone": {
      "command": "node",
      "args": ["E:/DSH-Phone-Agent/pc/mcp-server/index.mjs", "--host", "192.168.12.138"]
    }
  }
}
```

### Codex

`~/.codex/config.toml`:

```toml
[mcp_servers.phone]
command = "node"
args = ["E:/DSH-Phone-Agent/pc/mcp-server/index.mjs", "--host", "192.168.12.138"]
```

### Cursor

`~/.cursor/mcp.json`(或项目 `.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "phone": {
      "command": "node",
      "args": ["E:/DSH-Phone-Agent/pc/mcp-server/index.mjs", "--host", "192.168.12.138"]
    }
  }
}
```

### Windsurf / 其他

同样是 `command` + `args` 两个字段,填法一致。

---

## 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--host` | `192.168.12.138` | 手机 IP(也可用环境变量 `DSH_PHONE_HOST`) |
| `--port` | `7912` | 控制端口 |
| `--token` | 空 | 若 App 里启用了访问令牌,填这里(或 `DSH_PHONE_TOKEN`) |
| `--timeout` | `120000` | 单次调用超时(毫秒) |

---

## 工具列表

| 工具 | 用途 |
|---|---|
| `phone_status` | **设备状态 + 12 项权限自检** —— 连接后先调这个 |
| `phone_screenshot` | 截图(返回图片,模型能直接看到) |
| `phone_uitree` | 界面节点树 |
| **`phone_locate`** | **定位链**:viewId → text → 包含 → desc → OCR → 颜色,自动尝试并报告用了哪种 |
| `phone_tap` | 点击(**带执行前安全检查**) |
| `phone_swipe` | 滑动(末尾自动刹车,落点准) |
| `phone_swipe_measure` | 滑动 + **测量内容实际滚了多远** |
| `phone_text` / `phone_key` | 输入 / 系统键 |
| `phone_find_text` | 按文字找控件(树 + OCR 融合) |
| `phone_ocr` | 本地中文 OCR |
| `phone_sweep` | **滚动识别**,一次读完整个长列表 |
| **`phone_sequence`** | **动作连发**,处理会消失的瞬时控件 |
| **`phone_incidents`** | **查询未解决的执行事故** |
| `phone_launch` / `phone_apps` | 应用管理 |
| `phone_find_image` | 模板找图 |
| `phone_raw` | 执行任意命令(兜底) |

---

## 几个设计上的取舍

**① 为什么工具贴着底层命令,而不是做成"打开设置页并读电量"这种任务级工具**

调用方通常是有能力的模型。把原语藏到任务级封装后面,**在happy path之外就拿不回控制权了** —— 而自动化遇到的全是 happy path 之外。

**② 为什么 `phone_tap` 返回里带 `safety` 字段**

点击落在空白处或不可点的容器上,**不会报错** —— 手势被接受,框架回 success,脚本继续跑。这是自动化里最糟的失败模式,因为它是隐藏的。所以每次点击都会报告那个位置实际有什么。

**③ 为什么有 `phone_swipe_measure` 而不只有 `phone_swipe`**

`completed: true` 只说明手势被系统接受了,**完全不代表内容滚动了相应距离**。想知道实情只能量。

**④ 为什么 `phone_sequence` 存在**

有些 UI 只短暂存在:toast、一秒钟后淡出的控制栏、自动关闭的弹窗。一步一次网络往返会输掉这场竞速。连发把间隔从"往返延迟"降到"微秒"。

**⑤ 为什么失败返回 `isError` 内容而不是 JSON-RPC 错误**

模型需要看到"手机说不行"然后调整。JSON-RPC 层的错误会被客户端当成协议故障吞掉或抛出,模型看不到。

---

## 手动测试

不接 IDE 也能验证服务本身:

```bash
# 列出工具
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | node pc/mcp-server/index.mjs

# 调一次设备状态
echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"phone_status","arguments":{}}}' \
  | node pc/mcp-server/index.mjs
```

第二行会打印手机的型号、屏幕、电量和 12 项能力检查结果 —— **能打出来就说明链路是通的**。
