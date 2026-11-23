import json, time, uuid, struct
from im_client import ImClient, encode, CMD

tokens = json.load(open("tokens.json"))
A, B = tokens["alice"], tokens["bob"]
ta, tb = A["token"], B["token"]
ua, ub = A["userId"], B["userId"]

def ok(label, cond, extra=""):
    print(("  [PASS] " if cond else "  [FAIL] ") + label + ("" if cond else "  " + str(extra)))

# 1) 登录
ca = ImClient(); ca.login(ta, "pc")
cb = ImClient(); cb.login(tb, "phone")
print("== 1. 长连接登录 ==")
ok("alice/bob 登录成功", True)

# 2) 建会话（走 HTTP 已建过，这里直接复用 pair_key 逻辑：再建一次拿到同一 id）
import urllib.request
def http_post(path, data, token):
    req = urllib.request.Request("http://127.0.0.1:8080" + path, data=json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + token}, method="POST")
    return json.loads(urllib.request.urlopen(req, timeout=10).read())

conv = http_post("/api/conversations/single", {"targetUserId": ub}, ta)["data"]
conv_id = conv["id"]
print("== 2. 会话 ==", conv_id)

# 3) 单聊 + 推送
print("== 3. 单聊收发 ==")
rid = 1001
ca.chat(conv_id, "你好，我是 Alice", rid=rid)
resp = ca.expect("SINGLE_CHAT_RESP")
push = cb.expect("PUSH_MESSAGE")
ca.drain(0.3)   # 发送方自己也会收到多端同步的 PUSH，先收走
ok("发送方拿到 SINGLE_CHAT_RESP", resp["status"] == 0, resp)
ok("服务端回显 requestId", resp["requestId"] == rid, resp["requestId"])
ok("接收方收到 PUSH_MESSAGE", push["body"]["content"] == "你好，我是 Alice", push["body"])
ok("push 的 seq = 1", push["body"]["seq"] == 1, push["body"]["seq"])
seq1 = push["body"]["seq"]
msgid1 = push["body"]["msgId"]

# 4) 粘包：一口气发 3 条，服务端应解析出 3 帧
print("== 4. 粘包（一次 sendall 三帧） ==")
blob = b"".join(encode("SINGLE_CHAT_REQ", {"conversationId": conv_id, "type": 1,
             "content": f"粘包{i}", "clientMsgId": uuid.uuid4().hex}, 2000 + i) for i in range(3))
ca.send_raw(blob)
# 按 requestId 匹配，避免把发送方自己收到的 PUSH 混进来
got = [ca.expect("SINGLE_CHAT_RESP", request_id=2000 + i) for i in range(3)]
ok("收到 3 条独立响应", all(g["name"] == "SINGLE_CHAT_RESP" for g in got), [g["name"] for g in got])
ok("requestId 未串号", [g["requestId"] for g in got] == [2000, 2001, 2002], [g["requestId"] for g in got])
ok("seq 连续递增", [g["body"]["seq"] for g in got] == [seq1 + 1, seq1 + 2, seq1 + 3], [g["body"]["seq"] for g in got])
last_seq = got[-1]["body"]["seq"]

# 5) 半包：把一帧拆成两半发
print("== 5. 半包（拆两半，间隔 0.3s） ==")
ca.chat(conv_id, "半包测试", rid=3001, split=0.3)
resp = ca.expect("SINGLE_CHAT_RESP")
ok("半包被正确重组", resp["status"] == 0 and resp["body"]["content"] == "半包测试", resp)
ok("seq 继续递增", resp["body"]["seq"] == last_seq + 1, resp["body"]["seq"])
last_seq += 1

# 6) 幂等：同一个 clientMsgId 发两次
print("== 6. 幂等（同 clientMsgId 发两次） ==")
dup_id = uuid.uuid4().hex
ca.chat(conv_id, "只应入库一次", client_msg_id=dup_id, rid=4001)
r1 = ca.expect("SINGLE_CHAT_RESP")
ca.chat(conv_id, "只应入库一次", client_msg_id=dup_id, rid=4002)
r2 = ca.expect("SINGLE_CHAT_RESP")
ok("两次 msgId 相同", r1["body"]["msgId"] == r2["body"]["msgId"], (r1["body"]["msgId"], r2["body"]["msgId"]))
ok("第二次标记 duplicate", r2["body"].get("duplicate") is True, r2["body"])
ok("seq 没有被多消耗", r2["body"]["seq"] == r1["body"]["seq"], (r1["body"]["seq"], r2["body"]["seq"]))
seq_dup = r1["body"]["seq"]

# 7) 历史消息（HTTP）
print("== 7. 历史消息 ==")
req = urllib.request.Request(f"http://127.0.0.1:8080/api/conversations/{conv_id}/messages?limit=50",
                             headers={"Authorization": "Bearer " + ta})
hist = json.loads(urllib.request.urlopen(req, timeout=10).read())["data"]
ok("历史条数 = 6", len(hist) == 6, len(hist))
ok("历史正序", [m["seq"] for m in hist] == sorted(m["seq"] for m in hist), [m["seq"] for m in hist])
ok("幂等消息只出现一次", sum(1 for m in hist if m["seq"] == seq_dup) == 1)

# 8) 离线补偿：Bob 断开 → Alice 发消息 → Bob 重连拉取
print("== 8. 离线消息补偿 ==")
cb.send_raw(encode("ACK_REQ", {"conversationId": conv_id, "seq": last_seq, "msgId": 0}))
time.sleep(0.3)
cb.close()
time.sleep(0.5)
ca.chat(conv_id, "你不在的时候我说的", rid=5001)
r = ca.expect("SINGLE_CHAT_RESP")
offline_seq = r["body"]["seq"]
time.sleep(0.3)

cb2 = ImClient(); cb2.login(tb, "phone")
cb2.send_raw(encode("PULL_OFFLINE_REQ", {"conversationId": conv_id, "afterSeq": last_seq, "limit": 100}, 6001))
pull = cb2.expect("PULL_OFFLINE_RESP")
ok("重新登录后拉到离线消息", len(pull["body"]) >= 1, pull["body"])
ok("离线消息内容正确", any(m["content"] == "你不在的时候我说的" for m in pull["body"]), pull["body"])
ok("离线消息 seq 正确", any(m["seq"] == offline_seq for m in pull["body"]), offline_seq)

# 9) 未读数
print("== 9. 未读数 ==")
req = urllib.request.Request("http://127.0.0.1:8080/api/conversations", headers={"Authorization": "Bearer " + tb})
convs = json.loads(urllib.request.urlopen(req, timeout=10).read())["data"]
c1 = [c for c in convs if c["id"] == conv_id][0]
ok("bob 对该会话有未读", c1["unread"] > 0, c1)

# 10) 已读：先取会话当前最大 seq，再把它标成已读
req = urllib.request.Request("http://127.0.0.1:8080/api/conversations", headers={"Authorization": "Bearer " + tb})
cur = [c for c in json.loads(urllib.request.urlopen(req, timeout=10).read())["data"] if c["id"] == conv_id][0]
req = urllib.request.Request(f"http://127.0.0.1:8080/api/conversations/{conv_id}/read?seq={cur['seq']}",
                             data=b"", headers={"Authorization": "Bearer " + tb}, method="POST")
json.loads(urllib.request.urlopen(req, timeout=10).read())
req = urllib.request.Request("http://127.0.0.1:8080/api/conversations", headers={"Authorization": "Bearer " + tb})
c1 = [c for c in json.loads(urllib.request.urlopen(req, timeout=10).read())["data"] if c["id"] == conv_id][0]
ok("已读后未读清零", c1["unread"] == 0, c1)

# 11) 群聊
print("== 11. 群聊 ==")
cc = ImClient(); cc.login(tokens["carol"]["token"], "web")
grp = http_post("/api/conversations/group", {"name": "考试周自习室", "memberIds": [ub, tokens["carol"]["userId"]]}, ta)["data"]
ca.chat(grp["id"], "今晚八点图书馆", cmd="GROUP_CHAT_REQ", rid=7001)
gr = ca.expect("GROUP_CHAT_RESP")
pb = cb2.expect("PUSH_MESSAGE", timeout=5)
pc = cc.expect("PUSH_MESSAGE", timeout=5)
ok("群消息发送成功", gr["status"] == 0, gr)
ok("群内两个成员都收到", pb["body"]["content"] == "今晚八点图书馆" and pc["body"]["content"] == "今晚八点图书馆")
ok("群会话 seq 独立", gr["body"]["seq"] == 1, gr["body"]["seq"])

# 12) 非成员越权
print("== 12. 越权校验：往不存在的会话发消息 ==")
ca.chat(999999, "打给不存在的会话", rid=8001)
er = ca.expect("ERROR")
ok("非成员发消息被拒", er["status"] != 0, er)

# 13) 心跳
print("== 13. 心跳 ==")
ca.send_raw(encode("HEARTBEAT_REQ", {}, 9001))
hb = ca.expect("HEARTBEAT_RESP")
ok("心跳回包 requestId 一致", hb["requestId"] == 9001, hb)

# 14) 未登录就发消息
print("== 14. 未鉴权拦截 ==")
cn = ImClient()
cn.send_raw(encode("SINGLE_CHAT_REQ", {"conversationId": conv_id, "type": 1, "content": "x", "clientMsgId": uuid.uuid4().hex}, 1))
try:
    f = cn._read_one()
    ok("未登录被拒", f["name"] == "ERROR", f)
except Exception as e:
    ok("未登录被拒(连接被关)", isinstance(e, ConnectionError), e)

for c in (ca, cb2, cc, cn):
    c.close()
print("\n全部用例执行完毕")
