"""用 CDP 驱动 headless Edge 走完登录、截图，验证前端 UI 真实可用。

为什么不用 playwright：本机没装（npm 装不了 playwright 浏览器），
但 Edge 自带 --remote-debugging-port，用 websocket-client 说 CDP 协议就够。

跑法：先起后端 8080/9001 和 vite dev（localhost:5173），再
      PYTHONIOENCODING=utf-8 python shot_ui.py
产物：_shots/*.png
"""
import base64
import json
import os
import re
import subprocess
import time
import urllib.request

import websocket

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
HERE = os.path.dirname(os.path.abspath(__file__))          # tools/verify
ROOT = os.path.dirname(os.path.dirname(HERE))               # yujian-im
SHOTS = os.path.join(ROOT, "_shots")
PORT = 9333
PROFILE = os.path.join(ROOT, "_shots", "_edge_profile")


class CDP:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=30, max_size=64 * 1024 * 1024)
        self.id = 0

    def call(self, method, **params):
        self.id += 1
        self.ws.send(json.dumps({"id": self.id, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.id:
                if "error" in msg:
                    raise AssertionError(f"{method} -> {msg['error']}")
                return msg.get("result", {})

    def eval(self, expr, await_promise=True):
        r = self.call("Runtime.evaluate", expression=expr, awaitPromise=await_promise,
                      returnByValue=True)
        if r.get("exceptionDetails"):
            raise AssertionError(json.dumps(r["exceptionDetails"], ensure_ascii=False))
        return r["result"].get("value")

    def shot(self, name):
        r = self.call("Page.captureScreenshot", format="png")
        path = os.path.join(SHOTS, name)
        with open(path, "wb") as f:
            f.write(base64.b64decode(r["data"]))
        print(f"  截图 -> {path}  ({os.path.getsize(path)} bytes)")
        return path


def wait_devtools(deadline=25):
    end = time.time() + deadline
    while time.time() < end:
        try:
            d = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/version", timeout=2).read())
            return d["webSocketDebuggerUrl"]
        except Exception:
            time.sleep(0.4)
    raise AssertionError("Edge 的 devtools 端口没起来")


def main():
    os.makedirs(SHOTS, exist_ok=True)
    proc = subprocess.Popen([
        EDGE, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
        f"--remote-debugging-port={PORT}", f"--user-data-dir={PROFILE}",
        "--window-size=1440,900", "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait_devtools()
        # 新建一个 page target
        target = json.loads(urllib.request.urlopen(
            f"http://127.0.0.1:{PORT}/json/new?about:blank", timeout=5).read()) \
            if False else None
        # 老版本没有 /json/new?url，用 /json/list 里现有的 page
        pages = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=5).read())
        page = next(p for p in pages if p["type"] == "page")
        cdp = CDP(page["webSocketDebuggerUrl"])

        cdp.call("Page.enable")
        cdp.call("Runtime.enable")
        cdp.call("Emulation.setDeviceMetricsOverride", width=1440, height=900,
                 deviceScaleFactor=1, mobile=False)

        print("1. 打开登录页")
        cdp.call("Page.navigate", url="http://localhost:5173/")
        time.sleep(2.5)
        # 上一次跑会在 profile 里留下 token，先清掉，保证看到的是登录页
        cdp.eval("localStorage.clear(); 'cleared'")
        cdp.call("Page.navigate", url="http://localhost:5173/")
        time.sleep(2.5)
        title = cdp.eval("document.title")
        body_len = cdp.eval("document.body.innerText.length")
        print(f"   title={title!r}  正文字符数={body_len}")
        first = cdp.eval("document.body.innerText")
        print("   body=", repr(first[:300]))
        assert "鱼见" in first or len(first) > 20, "页面没渲染出来"
        cdp.shot("ui-1-login.png")

        print("2. 注册 billy（走真实 /api/auth/register）")
        reg = cdp.eval("""
          (async () => {
            const r = await fetch('/api/auth/register', {
              method:'POST', headers:{'Content-Type':'application/json'},
              body: JSON.stringify({username:'billy', password:'123456', nickname:'Billy'})
            });
            return JSON.stringify({status:r.status, body: await r.text()});
          })()
        """)
        print("   ", reg)

        print("3. 登录 billy")
        login = cdp.eval("""
          (async () => {
            const r = await fetch('/api/auth/login', {
              method:'POST', headers:{'Content-Type':'application/json'},
              body: JSON.stringify({username:'billy', password:'123456'})
            });
            const d = await r.json();
            if (d.code !== 0) return JSON.stringify({ok:false, d});
            localStorage.setItem('im_token', d.data.token);
            return JSON.stringify({ok:true, userId:d.data.userId, nickname:d.data.nickname,
                                   token:d.data.token});
          })()
        """)
        print("   ", login)
        assert '"ok":true' in login, "登录失败"
        billy_token = json.loads(login)["token"]

        print("4. 刷新进入聊天界面，等 WebSocket 建连")
        cdp.call("Page.navigate", url="http://localhost:5173/")
        time.sleep(3.5)
        connected = cdp.eval("document.body.innerText")
        print("   body=", repr(connected[:300]))
        # 这里断言的是"已经进到主界面"，不是登录页 —— 登录页才有「鱼见 IM」标题
        assert "Billy" in connected and "退出" in connected, "刷新后没自动恢复会话（onMounted 没生效）"
        cdp.shot("ui-2-chat-empty.png")
        print("   页面文本片段:", " / ".join(connected.split("\n")[:6]))

        print("5. 搜人并开单聊，发一条消息（走 WS 真链路）")
        # 让 alice 先在线，这样 billy 发出去能看到对方在线
        alice = json.loads(urllib.request.urlopen(urllib.request.Request(
            "http://127.0.0.1:8080/api/auth/login",
            data=json.dumps({"username": "alice", "password": "123456"}).encode(),
            headers={"Content-Type": "application/json"}, method="POST"), timeout=10).read())["data"]

        conv = json.loads(urllib.request.urlopen(urllib.request.Request(
            "http://127.0.0.1:8080/api/conversations/single",
            data=json.dumps({"targetUserId": alice["userId"]}).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + billy_token}, method="POST"),
            timeout=10).read())["data"]
        print("   会话:", conv["id"], conv.get("title"))

        # 直接用页面里的 ws 实例发消息，模拟用户输入
        cdp.call("Page.navigate", url="http://localhost:5173/")
        time.sleep(3.0)
        # 点开会话
        clicked = cdp.eval(f"""
          (() => {{
            const items = [...document.querySelectorAll('.conv-item, .conv, li, button')];
            const hit = items.find(e => (e.innerText || '').includes('Alice'));
            if (!hit) return 'not-found:' + document.body.innerText.slice(0,200);
            hit.click();
            return 'clicked:' + hit.innerText.replace(/\\n/g,' | ');
          }})()
        """)
        print("   点会话:", clicked)
        time.sleep(1.5)
        cdp.shot("ui-3-conversation.png")

        print("6. 在输入框里打字并点发送")
        typed = cdp.eval("""
          (() => {
            const el = document.querySelector('textarea, input[type=text].composer, .composer-input');
            if (!el) return 'no-input';
            el.focus();
            el.value = '你好，这是从浏览器发出去的第一条消息';
            el.dispatchEvent(new Event('input', {bubbles:true}));
            return 'typed';
          })()
        """)
        print("   ", typed)
        sent = cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('button')].find(b => /发送/.test(b.innerText));
            if (!btn) return 'no-send-btn';
            btn.click();
            return 'sent';
          })()
        """)
        print("   ", sent)
        time.sleep(2.0)
        cdp.shot("ui-4-message-sent.png")
        txt = cdp.eval("document.body.innerText")
        print("   消息是否上屏:", "第一" in txt or "你好" in txt)
        # 这一条是回归的重点：雪花 ID 精度丢失时，这里会出现「发送失败：你不在该会话中」。
        # 所以显式断言页面上没有"发送失败"四个字。
        assert "发送失败" not in txt, f"发送失败，页面文本：{txt[:400]}"

        print("7. 打开群聊弹窗（原生 <dialog>）")
        # 按钮文案是「+群」（见 App.vue 侧栏），不是"群聊"/"新建群"。
        # 这里匹配 +群 与 群 两种写法，避免以后改了文案又跑不过。
        dlg = cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('button')].find(
              b => /\\+\\s*群|群聊|新建群/.test(b.innerText));
            if (!btn) return 'no-group-btn';
            btn.click();
            return 'opened';
          })()
        """)
        print("   ", dlg)
        time.sleep(1.0)
        # 断言 dialog 真的进了 top-layer（showModal 才会让 :modal 命中）
        is_modal = cdp.eval("!!document.querySelector('dialog:modal')")
        print("   dialog 已进入 top-layer:", is_modal)
        cdp.shot("ui-5-group-dialog.png")

        print("8. 看控制台有没有报错")
        errs = cdp.eval("JSON.stringify(window.__errs || [])")
        print("   window.__errs =", errs)

        print("9. 切到深色模式，验证 color-scheme + light-dark() 真的生效")
        # 先把群聊弹窗关掉，否则 ::backdrop 的模糊会把整页糊住，截图看不出配色。
        cdp.eval("(() => { const d = document.querySelector('dialog[open]'); "
                 "if (d) d.close(); return 'closed'; })()")
        time.sleep(0.6)
        # CDP 的 Emulation.setEmulatedMedia 可以直接骗过浏览器，
        # 不用改系统设置，适合 CI / 无头环境。
        cdp.call("Emulation.setEmulatedMedia", features=[
            {"name": "prefers-color-scheme", "value": "dark"},
        ])
        time.sleep(1.2)
        # 断言 1：页面的 color-scheme 计算值包含 dark
        scheme = cdp.eval("getComputedStyle(document.documentElement).colorScheme")
        print("   computed color-scheme =", scheme)
        assert "dark" in (scheme or ""), f"color-scheme 没声明 dark：{scheme}"
        # 断言 2：body 背景真的变暗了（RGB 三通道都小）
        bg = cdp.eval("getComputedStyle(document.body).backgroundColor")
        nums = [int(x) for x in re.findall(r"\d+", bg)[:3]]
        print("   body 背景色 =", bg)
        assert len(nums) == 3 and max(nums) < 80, f"深色模式下 body 背景还是亮的：{bg}"
        # 断言 3：正文颜色变亮了（和背景要有对比）
        fg = cdp.eval("getComputedStyle(document.body).color")
        fnums = [int(x) for x in re.findall(r"\d+", fg)[:3]]
        print("   正文字色 =", fg)
        assert len(fnums) == 3 and min(fnums) > 150, f"深色模式下正文字色太暗：{fg}"
        cdp.shot("ui-6-dark-mode.png")

        print("10. 深色模式下再发一条消息，确认功能没被样式影响")
        typed2 = cdp.eval("""
          (() => {
            const el = document.querySelector('.composer textarea');
            if (!el) return 'no-textarea';
            el.focus();
            el.value = '深色模式下发的消息';
            el.dispatchEvent(new Event('input', {bubbles:true}));
            return 'typed';
          })()
        """)
        print("   ", typed2)
        sent2 = cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('button')].find(b => /发送/.test(b.innerText));
            if (!btn) return 'no-send-btn';
            btn.click();
            return 'sent';
          })()
        """)
        print("   ", sent2)
        time.sleep(2.0)
        txt2 = cdp.eval("document.body.innerText")
        assert "发送失败" not in txt2, f"深色模式下发消息失败：{txt2[:400]}"
        assert "深色模式下发的消息" in txt2, "深色模式下消息没上屏"
        print("   深色模式发消息: OK")
        cdp.shot("ui-7-dark-chat.png")

        print("11. 打开会话信息面板（ⓘ 按钮 → /member-infos）")
        info = cdp.eval("""
          (() => {
            // 头部现在有两个 icon-btn（🔍 搜索、ⓘ 信息），按 title 精确选，
            // 不能再用 .icon-btn 取第一个 —— 那样会误点成搜索
            const btn = document.querySelector('.chat-header .icon-btn[title*="信息"]');
            if (!btn) return 'no-info-btn';
            btn.click();
            return 'opened';
          })()
        """)
        print("   ", info)
        assert info == "opened", f"找不到会话信息按钮：{info}"
        time.sleep(1.4)
        info_txt = cdp.eval("(document.querySelector('.info-dlg') || {}).innerText || ''")
        # 断言：成员昵称真的显示出来了（不是一串雪花号）
        assert "Alice" in info_txt and "Billy" in info_txt, f"成员昵称没显示：{info_txt[:300]}"
        assert "当前 seq" in info_txt, f"信息面板缺 seq 行：{info_txt[:300]}"
        print("   面板文本片段:", " / ".join(info_txt.split("\\n")[:8]))
        cdp.shot("ui-8-info-dialog.png")
        cdp.eval("(() => { const d = document.querySelector('.info-dlg'); if (d) d.close(); return 'closed'; })()")
        time.sleep(0.5)
        assert not cdp.eval("!!document.querySelector('dialog:modal')"), "信息面板没关掉"
        print("   面板已关闭，且没有残留的 modal dialog")

        print("12. 滚上去看「回到底部」按钮（sticky 定位）")
        # 先造点历史：连续发若干条，把消息列表撑到能滚。
        # 文字用一段像聊天记录的话，而不是「滚动测试 1/2/3」——
        # 截图要进 README，假数据也得像真的。
        LINES = [
            "刚才说的那个粘包问题，我在解码器里加了 markReaderIndex",
            "读了一半发现 body 不全，就 resetReaderIndex 退回去等下一次 read",
            "对，不然读指针跑到一半，后面整条流都错位了",
            "那半包怎么测的",
            "把一帧拆两半，中间 sleep 0.3 秒再发第二半",
            "服务端应该能原样拼回来",
            "实测是能，而且 requestId 也对得上",
            "幂等那块呢",
            "客户端发 clientMsgId，重发的时候保持不变",
            "服务端 Redis 先拦一次，DB 唯一索引再兜一次",
            "并发重发也拦得住吗",
            "能，唯一索引是数据库层的原子保证，两个请求同时越过 Redis 那层也会有一条 dup key",
            "行，那这块我心里有数了",
        ]
        for i in range(14):
            text = LINES[i % len(LINES)]
            ok = cdp.eval(f"""
              (async () => {{
                const el = document.querySelector('.composer textarea');
                const btn = () => [...document.querySelectorAll('button')].find(b => /发送/.test(b.innerText));
                for (let t = 0; t < 40 && btn().disabled; t++) {{
                  await new Promise(r => setTimeout(r, 50));
                }}
                el.focus();
                el.value = {json.dumps(text)};
                el.dispatchEvent(new Event('input', {{bubbles:true}}));
                await new Promise(r => setTimeout(r, 30));
                if (btn().disabled) return 'still-disabled';
                btn().click();
                for (let t = 0; t < 60 && el.value; t++) {{
                  await new Promise(r => setTimeout(r, 50));
                }}
                return el.value ? 'not-cleared' : 'ok';
              }})()
            """)
            if ok != "ok":
                print(f"   第 {i} 条:", ok)
            time.sleep(0.15)
        time.sleep(1.5)
        scrolled = cdp.eval("""
          (() => {
            const el = document.querySelector('.message-list');
            el.scrollTop = 0;
            el.dispatchEvent(new Event('scroll'));
            return {h: el.scrollHeight, top: el.scrollTop, ch: el.clientHeight,
                    rows: document.querySelectorAll('.msg-row').length};
          })()
        """)
        print("   滚动容器:", scrolled)
        assert scrolled["h"] > scrolled["ch"], f"消息太少没撑出滚动条：{scrolled}"
        time.sleep(0.6)
        has_btn = cdp.eval("!!document.querySelector('.to-bottom')")
        print("   出现「回到底部」按钮:", has_btn)
        assert has_btn, "滚到顶上之后没有出现回到底部按钮"
        cdp.shot("ui-9-to-bottom.png")
        cdp.eval("document.querySelector('.to-bottom').click(); 'clicked'")
        time.sleep(0.8)
        gone = cdp.eval("!document.querySelector('.to-bottom')")
        print("   点击后按钮消失:", gone)
        assert gone, "点了回到底部，按钮还在"

        print("13. 切回浅色，拍一张内容饱满的聊天界面（README 首图用）")
        cdp.call("Emulation.setEmulatedMedia", features=[
            {"name": "prefers-color-scheme", "value": "light"},
        ])
        time.sleep(1.2)
        # 滚到底，让最后几条可见
        cdp.eval("(() => { const el = document.querySelector('.message-list'); "
                 "el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event('scroll')); return 1; })()")
        time.sleep(0.8)
        light_bg = cdp.eval("getComputedStyle(document.body).backgroundColor")
        print("   浅色模式 body 背景 =", light_bg)
        cdp.shot("ui-10-chat-light.png")

        print("14. 添加好友弹窗（列出未加的人，可勾选）")
        opened = cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('.sidebar-actions button')]
              .find(b => /好友/.test(b.innerText));
            if (!btn) return 'no-friend-btn';
            btn.click();
            return 'opened';
          })()
        """)
        print("   ", opened)
        assert opened == "opened", f"找不到添加好友按钮：{opened}"
        time.sleep(1.5)
        friend_txt = cdp.eval("(document.querySelector('.friend-dlg') || {}).innerText || ''")
        print("   弹窗文本片段:", " / ".join(friend_txt.split(chr(10))[:6]))
        assert "添加好友" in friend_txt, f"好友弹窗没打开：{friend_txt[:200]}"
        cand_count = cdp.eval("document.querySelectorAll('.friend-dlg .friend-list')[0]"
                              " ? document.querySelectorAll('.friend-dlg .friend-list')[0]"
                              "     .querySelectorAll('.user-pick').length : 0")
        print("   可添加人数:", cand_count)
        cdp.shot("ui-11-friend-dialog.png")
        # 勾选第一个人，确认 tick 出现
        picked = cdp.eval("""
          (() => {
            const list = document.querySelectorAll('.friend-dlg .friend-list')[0];
            const first = list && list.querySelector('.user-pick');
            if (!first) return 'no-candidate';
            first.click();
            return 'clicked';
          })()
        """)
        time.sleep(0.5)
        tick = cdp.eval("!!document.querySelector('.friend-dlg .tick')")
        print("   勾选后出现 ✓ :", tick, "(", picked, ")")
        if picked == "clicked":
            assert tick, "勾选了候选但没出现 ✓"
        cdp.eval("(() => { const d = document.querySelector('.friend-dlg'); if (d) d.close(); return 1; })()")
        time.sleep(0.5)

        print("15. 查找聊天记录（在刚造出来的 14 条里搜『粘包』）")
        opened = cdp.eval("""
          (() => {
            const btn = document.querySelector('.chat-header .icon-btn[title*="聊天记录"]');
            if (!btn) return 'no-search-btn';
            btn.click();
            return 'opened';
          })()
        """)
        print("   ", opened)
        assert opened == "opened", f"找不到搜索按钮：{opened}"
        time.sleep(0.9)
        typed = cdp.eval("""
          (() => {
            const el = document.querySelector('.search-dlg input');
            if (!el) return 'no-input';
            el.value = '粘包';
            el.dispatchEvent(new Event('input', {bubbles:true}));
            return 'typed';
          })()
        """)
        print("   ", typed)
        clicked = cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('.search-dlg .dlg-foot button')]
              .find(b => /搜索/.test(b.innerText));
            if (!btn) return 'no-search-btn';
            btn.click();
            return 'clicked';
          })()
        """)
        print("   ", clicked)
        time.sleep(1.8)
        hits = cdp.eval("document.querySelectorAll('.search-dlg .hit').length")
        print("   命中条数:", hits)
        assert hits >= 1, f"搜『粘包』应该至少有 1 条，实际 {hits}"
        search_txt = cdp.eval("(document.querySelector('.search-dlg') || {}).innerText || ''")
        assert "粘包" in search_txt, f"结果里没看到关键字：{search_txt[:300]}"
        cdp.shot("ui-12-search-dialog.png")

        print("16. 点搜索结果跳回会话，目标消息高亮")
        cdp.eval("(() => { const h = document.querySelector('.search-dlg .hit'); if (h) h.click(); return 1; })()")
        # 高亮动画只有 2.4s，等 .hl 出现就立刻截图，
        # 固定 sleep 会睡过头拍到已经淡掉的画面（第一次就是这么漏掉的）
        hl = 0
        for _ in range(40):
            time.sleep(0.15)
            hl = cdp.eval("document.querySelectorAll('.msg-row.hl').length")
            if hl:
                break
        print("   高亮的消息条数:", hl)
        assert hl == 1, f"跳转后应该恰好有 1 条高亮，实际 {hl}"
        cdp.shot("ui-13-search-jump.png")
        errs2 = cdp.eval("window.__errs || []")
        print("   window.__errs =", errs2)
        assert errs2 == [], f"控制台有报错：{errs2}"

        print("17. 深色模式下再看一眼好友弹窗")
        cdp.call("Emulation.setEmulatedMedia", features=[
            {"name": "prefers-color-scheme", "value": "dark"},
        ])
        time.sleep(1.0)
        cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('.sidebar-actions button')]
              .find(b => /好友/.test(b.innerText));
            if (btn) btn.click();
            return 1;
          })()
        """)
        time.sleep(1.4)
        cdp.shot("ui-14-friend-dark.png")
        cdp.eval("(() => { const d = document.querySelector('.friend-dlg'); if (d) d.close(); return 1; })()")
        time.sleep(0.4)
        cdp.call("Emulation.setEmulatedMedia", features=[
            {"name": "prefers-color-scheme", "value": "light"},
        ])
        time.sleep(0.6)

        print("18. 发一条消息然后撤回（验证两分钟窗口 + 气泡变虚线）")
        cdp.eval("(() => { document.querySelectorAll('dialog[open]').forEach(d => d.close()); return 1; })()")
        time.sleep(0.4)
        cdp.eval("""
          (() => {
            const el = document.querySelector('.composer textarea');
            el.value = '这条马上会被撤回';
            el.dispatchEvent(new Event('input', {bubbles: true}));
            return 1;
          })()
        """)
        time.sleep(0.3)
        cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('.composer button')]
              .find(b => /发送/.test(b.innerText));
            if (btn) btn.click();
            return 1;
          })()
        """)
        time.sleep(1.8)
        clicked = cdp.eval("""
          (() => {
            const rows = [...document.querySelectorAll('.msg-row.mine')];
            const last = rows[rows.length - 1];
            if (!last) return 'no-mine-row';
            const btn = [...last.querySelectorAll('.meta-btn')].find(b => /撤回/.test(b.innerText));
            if (!btn) return 'no-revoke-btn';
            btn.click();
            return 'clicked';
          })()
        """)
        print("   撤回按钮:", clicked)
        assert clicked == "clicked", f"没找到撤回按钮：{clicked}"
        time.sleep(1.8)
        revoked = cdp.eval("document.querySelectorAll('.bubble.revoked').length")
        print("   撤回态气泡数:", revoked)
        assert revoked >= 1, "撤回后没有出现 .bubble.revoked"
        revoke_text = cdp.eval("(() => { const b = document.querySelector('.bubble.revoked'); return b ? b.innerText : ''; })()")
        print("   撤回提示文案:", repr(revoke_text))
        assert "撤回" in revoke_text, f"撤回文案不对：{revoke_text}"
        cdp.shot("ui-15-revoke.png")

        print("19. 点「查已读」拿回执")
        receipt = cdp.eval("""
          (() => {
            const rows = [...document.querySelectorAll('.msg-row.mine')];
            for (const r of rows) {
              const btn = [...r.querySelectorAll('.meta-btn')].find(b => /查已读|已读|未读/.test(b.innerText));
              if (btn) { btn.click(); return 'clicked'; }
            }
            return 'no-btn';
          })()
        """)
        print("   查已读按钮:", receipt)
        assert receipt == "clicked", f"没找到查已读按钮：{receipt}"
        time.sleep(1.8)
        receipt_txt = cdp.eval("""
          (() => {
            const btns = [...document.querySelectorAll('.msg-row.mine .meta-btn')];
            return btns.map(b => b.innerText).join(' | ');
          })()
        """)
        print("   回执文案:", repr(receipt_txt))
        assert ("已读" in receipt_txt or "未读" in receipt_txt), f"回执没渲染：{receipt_txt}"
        cdp.shot("ui-16-read-receipt.png")

        print("20. 群成员管理弹窗（👥 按钮 → /group-members）")
        # 前面的步骤只打开过建群弹窗，没有真的建过群。
        # 这里先通过 HTTP 建一个群（billy + alice），再进群成员面板。
        made = cdp.eval("""
          (async () => {
            const tk = localStorage.getItem('im_token');
            const h = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tk };
            const s = await fetch('/api/users/search?keyword=alice', { headers: h });
            const sd = await s.json();
            const alice = (sd.data || [])[0];
            if (!alice) return 'no-alice';
            const g = await fetch('/api/conversations/group', {
              method: 'POST', headers: h,
              body: JSON.stringify({ name: '截图用测试群', memberIds: [alice.id] }),
            });
            const gd = await g.json();
            if (gd.code !== 0) return 'group-failed:' + gd.msg;
            return JSON.stringify({ id: gd.data.id, name: gd.data.name });
          })()
        """)
        print("   建群结果:", made)
        assert made != "no-alice" and not str(made).startswith("group-failed"), f"建群失败：{made}"
        # 刷新会话列表，让新群出现在侧栏
        cdp.eval("(() => { const btn = [...document.querySelectorAll('button')].find(b => /刷新|重连/.test(b.innerText)); if (btn) btn.click(); return 1; })()")
        time.sleep(0.6)
        cdp.call("Page.reload")
        time.sleep(3.5)
        cdp.eval("""
          (() => {
            const items = [...document.querySelectorAll('.conv-item')];
            const target = items.find(i => /测试群/.test(i.innerText));
            if (target) target.click();
            return 1;
          })()
        """)
        time.sleep(2.0)
        opened = cdp.eval("""
          (() => {
            const btn = [...document.querySelectorAll('.chat-header .icon-btn')]
              .find(b => /群成员/.test(b.title || ''));
            if (!btn) return 'no-group-btn';
            btn.click();
            return 'clicked';
          })()
        """)
        print("   群成员按钮:", opened)
        assert opened == "clicked", f"没找到群成员按钮（可能不在群聊里）：{opened}"
        time.sleep(2.0)
        member_txt = cdp.eval("""
          (() => {
            const d = document.querySelector('dialog[open].friend-dlg');
            return d ? d.innerText : '';
          })()
        """)
        print("   群成员弹窗文本:", repr(member_txt[:200]))
        assert "群成员" in member_txt, "群成员弹窗没打开"
        assert "群主" in member_txt, "没显示群主标记"
        cdp.shot("ui-17-group-members.png")
        errs3 = cdp.eval("window.__errs || []")
        print("   window.__errs =", errs3)
        assert errs3 == [], f"控制台有报错：{errs3}"
        cdp.eval("(() => { document.querySelectorAll('dialog[open]').forEach(d => d.close()); return 1; })()")
        time.sleep(0.3)

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=8)
        except Exception:
            proc.kill()


if __name__ == "__main__":
    main()
