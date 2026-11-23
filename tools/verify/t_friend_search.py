"""好友 + 聊天记录搜索的接口测试。

只依赖 HTTP：加好友、候选列表、搜聊天记录。
搜索部分要先造几条消息，所以会调一次 WS 发消息（复用 im_client 的协议实现）。

运行前提：后端已启动，库里至少有 alice/bob 两个账号（reset.py + t_http.py 之后即可）。
"""

import json
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:8080"
MYSQL = r"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
PASS = 0
FAIL = 0


def reset_db():
    """每次都从空库开始。

    不做这步的话第二次跑会累积上一次的消息，搜索断言（"命中 3 条"）会随历史条数漂移，
    测试就变成"只在第一次跑通过"。清库 + 重建账号只要 1 秒，值得。
    """
    sql = (
        "SET FOREIGN_KEY_CHECKS=0;"
        "TRUNCATE im_message;"
        "TRUNCATE im_conversation_member;"
        "TRUNCATE im_conversation;"
        "TRUNCATE im_friend;"
        "TRUNCATE im_user;"
        "SET FOREIGN_KEY_CHECKS=1;"
    )
    rc = subprocess.run([MYSQL, "-uroot", "-p123456", "yujian_im", "-e", sql]).returncode
    assert rc == 0, f"mysql truncate 失败 rc={rc}"
    with socket.create_connection(("127.0.0.1", 6379), timeout=3) as s:
        s.sendall(b"FLUSHDB" + bytes([13, 10]))
        assert s.recv(64).decode().strip() == "+OK"


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"[PASS] {name}")
    else:
        FAIL += 1
        print(f"[FAIL] {name} {extra}")


def post(path, data, token=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(data).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def get(path, token):
    # path 必须是已经编码好的（search() 里用 urlencode 生成）。
    # 这里不要再 quote 一次 —— 对 %E5%8D%8A 这种已编码的串再 quote 会变成 %25E5%25…，
    # 服务端收到的是字面量 "%E5%8D%8A%"，搜不到任何东西。
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def search(token, keyword, conv_id=None, limit=50):
    q = {"keyword": keyword, "limit": str(limit)}
    if conv_id:
        q["conversationId"] = str(conv_id)
    return get(f"/api/search/messages?{urllib.parse.urlencode(q)}", token)


def delete(path, token):
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Bearer " + token},
                                 method="DELETE")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def ensure_user(username, nickname):
    """先登录，不行就注册。清库后直接跑也能过。"""
    try:
        r = post("/api/auth/login", {"username": username, "password": "123456"})
        if r.get("code") == 0:
            return r["data"]
    except urllib.error.HTTPError:
        pass
    post("/api/auth/register",
         {"username": username, "password": "123456", "nickname": nickname})
    return post("/api/auth/login",
                {"username": username, "password": "123456"})["data"]


def main():
    print("=== 0. 清库并准备账号 ===")
    reset_db()
    alice = ensure_user("alice", "Alice")
    bob = ensure_user("bob", "Bob")
    carol = ensure_user("carol", "Carol")
    ta, tb = alice["token"], bob["token"]
    print("   alice =", alice["userId"], " bob =", bob["userId"])

    # ---------- 好友 ----------
    print("\n=== 1. 候选好友列表 ===")
    # 先清掉 alice 的全部好友，保证候选里能看到 bob
    for f in get("/api/friends", ta)["data"]:
        delete(f"/api/friends?targetId={f['id']}", ta)
    cands = get("/api/friends/candidates", ta)["data"]
    ids = [c["id"] for c in cands]
    check("候选里有 bob", bob["userId"] in ids, ids)
    check("候选里没有自己", alice["userId"] not in ids, ids)
    check("候选带 online 字段", "online" in cands[0])

    print("\n=== 2. 加好友 ===")
    r = post("/api/friends", {"targetId": bob["userId"]}, ta)
    check("加好友返回 code=0", r["code"] == 0, r)
    friends = get("/api/friends", ta)["data"]
    check("好友列表里出现 bob", any(f["id"] == bob["userId"] for f in friends), friends)
    # 双向：bob 那边也该有 alice
    friends_b = get("/api/friends", tb)["data"]
    check("双向生效（bob 也有 alice）", any(f["id"] == alice["userId"] for f in friends_b),
          friends_b)

    print("\n=== 3. 幂等：重复加同一个人 ===")
    r2 = post("/api/friends", {"targetId": bob["userId"]}, ta)
    check("重复添加不报错", r2["code"] == 0, r2)
    friends2 = get("/api/friends", ta)["data"]
    check("重复添加不会多一条", len(friends2) == len(friends), (len(friends), len(friends2)))

    print("\n=== 4. 加过的人从候选里消失 ===")
    cands2 = get("/api/friends/candidates", ta)["data"]
    check("候选里不再有 bob", all(c["id"] != bob["userId"] for c in cands2), cands2)

    print("\n=== 5. 不能加自己 ===")
    try:
        rr = post("/api/friends", {"targetId": alice["userId"]}, ta)
        check("加自己被拒绝", rr["code"] != 0, rr)
    except urllib.error.HTTPError as e:
        check("加自己被拒绝(HTTP)", e.code >= 400, e.code)

    # ---------- 搜索 ----------
    print("\n=== 6. 造数据：发几条带关键词的消息 ===")
    conv = post("/api/conversations/single", {"targetUserId": bob["userId"]}, ta)["data"]
    conv_id = conv["id"]
    print("   conv =", conv_id)

    # 直接用 HTTP 发不了消息（消息走长连接），这里用 WS 最小客户端
    import im_ws

    ca = im_ws.Client(ta, device="search-a")
    cb = im_ws.Client(tb, device="search-b")
    ca.connect()
    cb.connect()

    texts = [
        "粘包和半包是两回事，粘包是多个帧粘在一起",
        "半包要等下一次 read 才能拼出完整帧",
        "markReaderIndex 和 resetReaderIndex 就是用来处理半包的",
        "requestId 让响应能和请求对上号",
        "幂等靠 clientMsgId，服务端只认这一个键",
    ]
    for t in texts:
        ca.request(im_ws.CMD["SINGLE_CHAT_REQ"], {
            "conversationId": conv_id, "type": 1, "content": t,
            "clientMsgId": str(uuid.uuid4()),
        })
    time.sleep(0.6)

    print("\n=== 7. 会话内搜索（多字，走全文索引）===")
    r = search(ta, "半包", conv_id)["data"]
    check("搜『半包』命中 3 条", len(r) == 3, [x["content"] for x in r])
    check("命中带会话标题", all(x["conversationTitle"] == "Bob" for x in r),
          [x.get("conversationTitle") for x in r])
    check("结果按 seq 倒序", all(r[i]["seq"] > r[i + 1]["seq"] for i in range(len(r) - 1)),
          [x["seq"] for x in r])
    check("msgId 是字符串（雪花精度）", isinstance(r[0]["msgId"], str), r[0]["msgId"])
    check("seq 是数字", isinstance(r[0]["seq"], int), r[0]["seq"])

    print("\n=== 8. 短语匹配（不是任一词命中）===")
    r = search(ta, "粘包和半包", conv_id)["data"]
    check("『粘包和半包』只命中同时含这两段的那条", len(r) == 1,
          [x["content"] for x in r])

    print("\n=== 9. 单字退回 LIKE ===")
    r = search(ta, "包", conv_id)["data"]
    check("单字『包』命中 3 条（ngram 不索引单字，走 LIKE 兜底）", len(r) == 3,
          [x["content"] for x in r])

    print("\n=== 10. 搜不到时返回空数组 ===")
    r = search(ta, "这个词库里绝对没有", conv_id)["data"]
    check("无结果返回空数组", r == [], r)

    print("\n=== 11. 权限：不在会话里不能搜该会话 ===")
    try:
        rr = search(carol["token"], "半包", conv_id)
        check("非成员搜别人的会话被拒", rr["code"] != 0, rr)
    except urllib.error.HTTPError as e:
        check("非成员搜别人的会话被拒(HTTP)", e.code >= 400, e.code)

    print("\n=== 12. 全局搜索（跨我会话）===")
    r = search(ta, "半包")["data"]
    check("全局搜能命中 3 条", len(r) == 3, r)
    r_carol = search(carol["token"], "半包")["data"]
    check("别人的会话搜不到", r_carol == [], r_carol)

    print("\n=== 13. 空关键字被拒 ===")
    try:
        rr = search(ta, "")
        check("空关键字被拒", rr["code"] != 0, rr)
    except urllib.error.HTTPError as e:
        check("空关键字被拒(HTTP)", e.code >= 400, e.code)

    print("\n=== 14. 特殊字符不会把搜索搞崩 ===")
    r = search(ta, '"+-~>', conv_id)
    check("引号/加减号等 BOOLEAN 语法字符被包进短语，不报错", r["code"] == 0, r)

    ca.close()
    cb.close()

    print(f"\n===== 好友/搜索：PASS={PASS} FAIL={FAIL} =====")
    raise SystemExit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
