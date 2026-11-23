"""A 档功能回归：消息撤回 / 已读回执 / 群管理。

怎么跑：
    python reset.py            # 清库，保证断言里的条数不漂移
    python t_revoke_read_group.py

为什么这个脚本自己清库而不像 t_e2e_live.py 那样"随跑随过"：
    因为它要断言"群成员从 3 人变成 2 人"、"未读正好是 N"这类精确数字，
    脏数据会让断言从第一次跑就开始漂。清库是最省的确定性来源。
    （t_e2e_live.py 之所以不清库，是因为它只断言"有没有"，不断言"有几个"。）

依赖：pip install websocket-client
"""

import json
import subprocess
import time
import urllib.parse
import urllib.request

from im_ws import CMD, Client

BASE = "http://127.0.0.1:8080"
MYSQL = r"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"

ok = []
bad = []


def chk(cond, label, detail=""):
    (ok if cond else bad).append(label)
    suffix = ("  -> " + str(detail)) if detail else ""
    print(("  [PASS] " if cond else "  [FAIL] ") + label + suffix)


# ---------- HTTP 小工具 ----------

def req(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        return json.load(urllib.request.urlopen(r, timeout=10))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def post(path, body, token=None):
    return req("POST", path, body, token)


def get(path, token=None):
    return req("GET", path, None, token)


def delete(path, token=None):
    return req("DELETE", path, None, token)


def ensure_user(username, nickname):
    r = post("/api/auth/login", {"username": username, "password": "123456"})
    if r.get("code") == 0:
        return r["data"]
    post("/api/auth/register", {"username": username, "password": "123456", "nickname": nickname})
    r = post("/api/auth/login", {"username": username, "password": "123456"})
    assert r.get("code") == 0, r
    return r["data"]


def reset_db():
    sql = ("SET FOREIGN_KEY_CHECKS=0;"
           "TRUNCATE im_message;TRUNCATE im_conversation_member;"
           "TRUNCATE im_conversation;TRUNCATE im_friend;TRUNCATE im_user;"
           "SET FOREIGN_KEY_CHECKS=1;")
    # 沙箱下 subprocess 拿不到管道，这里直接让它继承 stdio。
    subprocess.run([MYSQL, "-uroot", "-p123456", "yujian_im", "-e", sql], check=True)
    import socket
    s = socket.create_connection(("127.0.0.1", 6379), timeout=3)
    s.sendall(b"FLUSHDB\r\n")
    s.recv(1024)
    s.close()


reset_db()

# ---------- 前置数据 ----------

print("== 0. 准备账号与会话 ==")
alice = ensure_user("alice", "Alice")
bob = ensure_user("bob", "Bob")
carol = ensure_user("carol", "Carol")
chk(True, "三个账号就绪", f"alice={alice['userId']} bob={bob['userId']} carol={carol['userId']}")

single = post("/api/conversations/single", {"targetUserId": bob["userId"]}, alice["token"])["data"]
single_id = single["id"]
chk(single["type"] == 1, "单聊会话创建成功", single_id)

group = post("/api/conversations/group",
             {"name": "测试群", "memberIds": [bob["userId"], carol["userId"]]},
             alice["token"])["data"]
group_id = group["id"]
chk(group["type"] == 2, "群聊会话创建成功", group_id)

a = Client(alice["token"], "alice-pc")
a.connect()
b = Client(bob["token"], "bob-pc")
b.connect()
c = Client(carol["token"], "carol-pc")
c.connect()
print("   三个长连接已建")

# ============================================================
print("\n== 1. 消息撤回：正常路径 ==")
# ============================================================

r = a.request(CMD["SINGLE_CHAT_REQ"], {
    "conversationId": single_id, "type": 1,
    "content": "这条要撤回", "clientMsgId": "revoke-case-1",
})
vo = r["body"]
msg_id = vo["msgId"]
seq1 = vo["seq"]
chk(vo.get("revoked") in (None, False), "刚发出的消息 revoked 为假", f"msgId={msg_id}")

pushed = b.expect(CMD["PUSH_MESSAGE"])
chk(pushed["body"]["content"] == "这条要撤回", "对方收到原文", pushed["body"]["content"])

r = a.request(CMD["REVOKE_REQ"], {"conversationId": single_id, "msgId": msg_id})
rv = r["body"]
chk(rv["revoked"] is True, "撤回响应 revoked=true", rv)
chk(rv["content"] == "", "撤回响应里 content 已清空（不是靠前端隐藏）", repr(rv["content"]))

rp = b.expect(CMD["REVOKE_PUSH"])
chk(str(rp["body"]["msgId"]) == str(msg_id), "对方收到 REVOKE_PUSH", rp["body"]["msgId"])
chk(rp["body"]["seq"] == seq1, "撤回推送带原 seq（不重排）", rp["body"]["seq"])

# 撤回者自己也应该收到一条（多端同步：另一台设备要改渲染）
rp_self = a.expect(CMD["REVOKE_PUSH"])
chk(str(rp_self["body"]["msgId"]) == str(msg_id), "自己（多端）也收到 REVOKE_PUSH")

# ============================================================
print("\n== 2. 消息撤回：历史与预览 ==")
# ============================================================

h = get(f"/api/conversations/{single_id}/messages", alice["token"])["data"]
target = [m for m in h if str(m["msgId"]) == str(msg_id)]
chk(len(target) == 1, "历史里这条还在（软删，不是硬删）", f"条数={len(target)}")
chk(target[0]["revoked"] is True, "历史里 revoked=true")
chk(target[0]["content"] == "", "历史里 content 为空", repr(target[0]["content"]))
chk(target[0]["seq"] == seq1, "历史里 seq 保持不变（没留空洞）", target[0]["seq"])

convs = get("/api/conversations", alice["token"])["data"]
mine = [x for x in convs if str(x["id"]) == str(single_id)][0]
chk(mine["lastPreview"] in ("[已撤回]", "还没有消息"),
    "会话预览被重算（不残留原文）", mine["lastPreview"])

# ============================================================
print("\n== 3. 消息撤回：幂等与权限 ==")
# ============================================================

rid = a.send(CMD["REVOKE_REQ"], {"conversationId": single_id, "msgId": msg_id})
r = a.expect(CMD["REVOKE_RESP"], request_id=rid)
chk(r["body"]["revoked"] is True, "重复撤回仍返回成功（幂等）")

# 重复撤回不该再广播一次
b.drain(0.6)
dup = [f for f in b.backlog if f.get("cmd") == CMD["REVOKE_PUSH"]]
chk(len(dup) == 0, "重复撤回不再广播（副作用链幂等）", f"多出 {len(dup)} 条")

# 撤别人发的消息
r2 = a.request(CMD["SINGLE_CHAT_REQ"], {
    "conversationId": single_id, "type": 1,
    "content": "Alice 的消息", "clientMsgId": "revoke-case-2",
})
alice_msg = r2["body"]["msgId"]
b.expect(CMD["PUSH_MESSAGE"])

rid = b.send(CMD["REVOKE_REQ"], {"conversationId": single_id, "msgId": alice_msg})
r = b.expect(CMD["ERROR"], request_id=rid)
chk("只能撤回自己" in r["body"]["msg"], "撤别人的消息被拒", r["body"]["msg"])

# 跨会话撤回：拿单聊的 msgId 去群聊撤回
rid = a.send(CMD["REVOKE_REQ"], {"conversationId": group_id, "msgId": alice_msg})
r = a.expect(CMD["ERROR"], request_id=rid)
chk("不属于该会话" in r["body"]["msg"] or "只能撤回自己" in r["body"]["msg"],
    "跨会话撤回被拒（防越权）", r["body"]["msg"])

# ============================================================
print("\n== 4. 已读回执：单聊 ==")
# ============================================================

r = a.request(CMD["SINGLE_CHAT_REQ"], {
    "conversationId": single_id, "type": 1,
    "content": "看得到这条吗", "clientMsgId": "receipt-case-1",
})
m2 = r["body"]
b.expect(CMD["PUSH_MESSAGE"])

# 查回执：对方还没读
r = a.request(CMD["READ_RECEIPT_REQ"], {"conversationId": single_id, "msgId": m2["msgId"]})
rv = r["body"]
chk(rv["totalOthers"] == 1, "单聊 totalOthers=1", rv["totalOthers"])
chk(len(rv["unreadUserIds"]) == 1, "对方未读", rv["unreadUserIds"])
chk(rv["readUserIds"] == [], "已读列表为空", rv["readUserIds"])

# Bob 读了这个位点
b.post(CMD["READ_SYNC_REQ"], {"conversationId": single_id, "seq": m2["seq"]})
time.sleep(0.4)

# Alice 应该收到「你的消息被读了」的推送
rp = a.expect(CMD["READ_RECEIPT_PUSH"])
chk(str(rp["body"]["msgId"]) == str(m2["msgId"]), "发送者收到已读回执推送", rp["body"]["msgId"])
chk(str(bob["userId"]) in [str(x) for x in rp["body"]["readUserIds"]],
    "回执里包含读者的 id")

# 再查一次，应该变成已读
r = a.request(CMD["READ_RECEIPT_REQ"], {"conversationId": single_id, "msgId": m2["msgId"]})
rv = r["body"]
chk(len(rv["readUserIds"]) == 1, "再查已读人数=1", rv["readUserIds"])
chk(rv["unreadUserIds"] == [], "未读列表清空", rv["unreadUserIds"])
chk(rv["readUserIds"] != [] and all(isinstance(x, str) for x in rv["readUserIds"]),
    "回执里的用户 id 是字符串（防 JS 精度丢失）", rv["readUserIds"][:2])

# 重复上报同一个位点，不该再推回执
before = len([f for f in a.backlog if f.get("cmd") == CMD["READ_RECEIPT_PUSH"]])
b.post(CMD["READ_SYNC_REQ"], {"conversationId": single_id, "seq": m2["seq"]})
a.drain(0.5)
after = len([f for f in a.backlog if f.get("cmd") == CMD["READ_RECEIPT_PUSH"]])
chk(after == before, "重复上报不重复推回执（位点只前进）", f"{before} -> {after}")

# ============================================================
print("\n== 5. 已读回执：群聊 N 人 ==")
# ============================================================

r = a.request(CMD["GROUP_CHAT_REQ"], {
    "conversationId": group_id, "type": 1,
    "content": "群里问一句", "clientMsgId": "group-receipt-1",
})
gm = r["body"]
b.expect(CMD["PUSH_MESSAGE"])
c.expect(CMD["PUSH_MESSAGE"])

r = a.request(CMD["READ_RECEIPT_REQ"], {"conversationId": group_id, "msgId": gm["msgId"]})
rv = r["body"]
chk(rv["totalOthers"] == 2, "群里 totalOthers=2（除自己）", rv["totalOthers"])
chk(len(rv["unreadUserIds"]) == 2, "两个人都未读", rv["unreadUserIds"])

# 只有 bob 读
b.post(CMD["READ_SYNC_REQ"], {"conversationId": group_id, "seq": gm["seq"]})
time.sleep(0.5)

r = a.request(CMD["READ_RECEIPT_REQ"], {"conversationId": group_id, "msgId": gm["msgId"]})
rv = r["body"]
chk(len(rv["readUserIds"]) == 1, "一人已读", rv["readUserIds"])
chk(len(rv["unreadUserIds"]) == 1, "一人未读", rv["unreadUserIds"])

# carol 也读
c.post(CMD["READ_SYNC_REQ"], {"conversationId": group_id, "seq": gm["seq"]})
time.sleep(0.5)

r = a.request(CMD["READ_RECEIPT_REQ"], {"conversationId": group_id, "msgId": gm["msgId"]})
rv = r["body"]
chk(len(rv["readUserIds"]) == 2, "两人都读了", rv["readUserIds"])
chk(rv["unreadUserIds"] == [], "未读列表空", rv["unreadUserIds"])

# ============================================================
print("\n== 6. 群管理：拉人 ==")
# ============================================================

dave = ensure_user("dave", "Dave")
gm_list = get(f"/api/conversations/{group_id}/group-members", alice["token"])["data"]
chk(len(gm_list) == 3, "初始 3 人", len(gm_list))
chk(any(x["role"] == 1 for x in gm_list), "有一个群主")

r = post(f"/api/conversations/{group_id}/members",
         {"memberIds": [dave["userId"]]}, alice["token"])
chk(r.get("code") == 0, "拉人成功", r)
added = r.get("data") or []
chk(len(added) == 1, "返回实际新加入 1 人", added)

gm_list = get(f"/api/conversations/{group_id}/group-members", alice["token"])["data"]
chk(len(gm_list) == 4, "群成员变 4 人", len(gm_list))

# 重复拉，应该返回空
r = post(f"/api/conversations/{group_id}/members",
         {"memberIds": [dave["userId"]]}, alice["token"])
chk(r.get("data") == [], "重复拉人返回空（幂等）", r.get("data"))

# 老成员应该收到 GROUP_EVENT_PUSH
ev = b.expect(CMD["GROUP_EVENT_PUSH"])
chk(ev["body"]["event"] == "join", "成员收到 join 事件", ev["body"]["event"])
chk(ev["body"]["memberCount"] == 4, "事件里带最新人数", ev["body"]["memberCount"])

# ============================================================
print("\n== 7. 群管理：退群 ==")
# ============================================================

b.close()  # 先让 Bob 下线，验证退群后不再收推送

r = delete(f"/api/conversations/{group_id}/members/me", carol["token"])
chk(r.get("code") == 0, "Carol 退群成功", r)

gm_list = get(f"/api/conversations/{group_id}/group-members", alice["token"])["data"]
ids = [str(x["id"]) for x in gm_list]
chk(str(carol["userId"]) not in ids, "退群后不在成员列表", ids)
chk(len(gm_list) == 3, "人数回到 3", len(gm_list))

# 退群的人不能再发消息
c2 = Client(carol["token"], "carol-again")
c2.connect()
rid = c2.send(CMD["GROUP_CHAT_REQ"], {
    "conversationId": group_id, "type": 1,
    "content": "我还能说话吗", "clientMsgId": "after-leave-1",
})
r = c2.expect(CMD["ERROR"], request_id=rid)
chk("不在该会话" in r["body"]["msg"], "退群后发消息被拒", r["body"]["msg"])
c2.close()

# 退群的人也不能再查历史
r = get(f"/api/conversations/{group_id}/messages", carol["token"])
chk(r.get("code") == 403, "退群后不能读历史", r)

# ============================================================
print("\n== 8. 群管理：踢人与转让 ==")
# ============================================================

# 非群主踢人被拒（Carol 已经退群了，用 Bob 这个普通成员来试）
r = delete(f"/api/conversations/{group_id}/members/{dave['userId']}", bob["token"])
chk(r.get("code") != 0, "非群主踢人被拒", r.get("msg"))

r = delete(f"/api/conversations/{group_id}/members/{dave['userId']}", alice["token"])
chk(r.get("code") == 0, "群主踢人成功", r)

gm_list = get(f"/api/conversations/{group_id}/group-members", alice["token"])["data"]
ids = [str(x["id"]) for x in gm_list]
chk(str(dave["userId"]) not in ids, "被踢的人不在成员列表", ids)

# 群主不能直接退
r = delete(f"/api/conversations/{group_id}/members/me", alice["token"])
chk(r.get("code") != 0 and "转让" in (r.get("msg") or ""),
    "群主直接退群被拒并提示转让", r.get("msg"))

# 转让给 Bob
r = post(f"/api/conversations/{group_id}/transfer?targetId={bob['userId']}", {}, alice["token"])
chk(r.get("code") == 0, "转让群主成功", r)

gm_list = get(f"/api/conversations/{group_id}/group-members", alice["token"])["data"]
owner = [x for x in gm_list if x["role"] == 1]
chk(len(owner) == 1 and str(owner[0]["id"]) == str(bob["userId"]),
    "群主变成 Bob", owner)

# 现在 Alice 已经被降为普通成员，不能再踢人
r = delete(f"/api/conversations/{group_id}/members/{alice['userId']}", alice["token"])
chk(r.get("code") != 0, "转让后老群主失去踢人权限", r.get("msg"))

gm_list = get(f"/api/conversations/{group_id}/group-members", bob["token"])["data"]
print("   当前群成员:", [(x["nickname"], x["role"]) for x in gm_list])

# ============================================================
print("\n== 9. 撤回/回执的权限边界 ==")
# ============================================================

# Bob 的连接在退群测试里被 close 了，这里重连一个，
# 否则下面发帧会撞 "socket is already closed"，看起来像服务端 bug 其实是脚本状态没管好。
b = Client(bob["token"], "bob-pc2")
b.connect()

rid = b.send(CMD["READ_RECEIPT_REQ"], {"conversationId": single_id, "msgId": "999999999"})
r = b.expect(CMD["ERROR"], request_id=rid)
chk(r["body"]["code"] != 0, "查不存在的消息被拒", r["body"]["msg"])

rid = a.send(CMD["READ_RECEIPT_REQ"], {"conversationId": group_id, "msgId": "1"})
r = a.expect(CMD["ERROR"], request_id=rid)
chk(r["body"]["code"] != 0, "查不存在的群消息被拒", r["body"]["msg"])

# 漏参数的容错
rid = a.send(CMD["REVOKE_REQ"], {"conversationId": single_id})
r = a.expect(CMD["ERROR"], request_id=rid)
chk(r["body"]["code"] == 400 or "msgId" in (r["body"]["msg"] or ""),
    "缺 msgId 时返回明确错误", r["body"]["msg"])

for cli in (a, b, c):
    cli.close()

print("\n" + "=" * 50)
print(f"PASS={len(ok)} FAIL={len(bad)}")
if bad:
    print("失败的项:")
    for x in bad:
        print("  - " + x)
print("=" * 50)
raise SystemExit(0 if not bad else 1)
