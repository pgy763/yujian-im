import json, urllib.request, time, sys
import websocket

def post(p,b,tok=None):
    h={"Content-Type":"application/json"}
    if tok: h["Authorization"]="Bearer "+tok
    r=urllib.request.Request("http://127.0.0.1:8080"+p,data=json.dumps(b).encode(),headers=h)
    return json.load(urllib.request.urlopen(r,timeout=8))
def get(p,tok=None):
    h={}
    if tok: h["Authorization"]="Bearer "+tok
    return json.load(urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8080"+p,headers=h),timeout=8))

CMD={"LOGIN_REQ":1,"LOGIN_RESP":2,"HEARTBEAT_REQ":3,"HEARTBEAT_RESP":4,
 "SINGLE_CHAT_REQ":5,"SINGLE_CHAT_RESP":6,"GROUP_CHAT_REQ":7,"GROUP_CHAT_RESP":8,
 "PUSH_MESSAGE":9,"ACK_REQ":10,"ACK_RESP":11,"PULL_OFFLINE_REQ":12,"PULL_OFFLINE_RESP":13,
 "READ_SYNC_PUSH":14,"KICK_PUSH":15,"READ_SYNC_REQ":16,"ERROR":0x7F}

class C:
    def __init__(self,name,token):
        self.name=name; self.rid=1000; self.pushes=[]
        self.ws=websocket.create_connection("ws://127.0.0.1:9001/im",timeout=5)
        r=self.request("LOGIN_REQ",{"token":token,"device":"pc"})
        assert r["body"]["success"], r
        self.uid=r["body"]["userId"]
    def send(self,cmd,body):
        self.rid+=1
        self.ws.send(json.dumps({"cmd":CMD[cmd],"status":0,"requestId":self.rid,"body":body}))
        return self.rid
    def recv(self):
        return json.loads(self.ws.recv())
    def request(self,cmd,body,timeout=5):
        rid=self.send(cmd,body); end=time.time()+timeout
        while time.time()<end:
            f=self.recv()
            if f.get("requestId")==rid: return f
            self.pushes.append(f)
        raise TimeoutError(cmd)
    def wait_push(self,cmd,timeout=4):
        want=CMD[cmd]; end=time.time()+timeout
        while time.time()<end:
            for i,f in enumerate(self.pushes):
                if f.get("cmd")==want: return self.pushes.pop(i)
            try: self.pushes.append(self.recv())
            except Exception: return None
        return None
    def close(self):
        try: self.ws.close()
        except Exception: pass

ok=[]; bad=[]
def chk(cond,label,detail=""):
    (ok if cond else bad).append(label)
    print(("  [PASS] " if cond else "  [FAIL] ")+label+(("  -> "+str(detail)) if detail else ""))

def ensure_user(username, nickname):
    """登录；账号不存在就先注册。

    为什么不直接假设账号存在：reset.py 清库之后 demo 账号就没了，
    这时候脚本应该自己把前置条件补上，而不是抛 KeyError 让人手工去建。
    注册接口本身就是"注册并登录"，成功时返回的 data 和登录接口一致。
    """
    r = post("/api/auth/login", {"username": username, "password": "123456"})
    if r.get("code") == 0:
        return r["data"]
    post("/api/auth/register",
         {"username": username, "password": "123456", "nickname": nickname})
    r = post("/api/auth/login", {"username": username, "password": "123456"})
    assert r.get("code") == 0, f"账号 {username} 既登录不上也注册不了: {r}"
    return r["data"]


za=ensure_user("demo_zhang","张三")
li=ensure_user("demo_li","李四")

print("== A. 建连 ==")
c_za=C("zhang-pc",za["token"])
c_za2=C("zhang-phone",za["token"])     # 同账号第二端
c_li=C("li-pc",li["token"])
c_li2=C("li-phone",li["token"])        # 李四第二端
chk(all([c_za.uid,c_za2.uid,c_li.uid,c_li2.uid]),"四端全部登录成功")
chk(isinstance(c_za.uid,str) and c_za.uid.isdigit(),"userId 是字符串（雪花 ID 精度防线）",c_za.uid)

conv=post("/api/conversations/single",{"targetUserId":li["userId"]},za["token"])["data"]
cid=conv["id"]
chk(isinstance(cid,str) and cid.isdigit(),"conversationId 是字符串",cid)
chk(isinstance(conv["seq"],int),"seq 是数字",conv["seq"])

print("== B. 发消息 + 推送 ==")
cmsg="live-%d"%int(time.time()*1000)
r1=c_za.request("SINGLE_CHAT_REQ",{"conversationId":cid,"type":1,"content":"你好，这是端到端验证","clientMsgId":cmsg})["body"]
chk(isinstance(r1["msgId"],str) and r1["msgId"].isdigit(),"msgId 是字符串",r1["msgId"])
chk(isinstance(r1["seq"],int) and r1["seq"]>0,"seq 是数字且 >0",r1["seq"])
chk(r1["duplicate"] is False,"首次发送 duplicate=false")

p=c_li.wait_push("PUSH_MESSAGE")
chk(p is not None and p["body"]["content"]=="你好，这是端到端验证","对方(李四)收到实时推送")
chk(p is not None and p["body"].get("seq")==r1["seq"],"推送里的 seq 与响应一致")
p2=c_za2.wait_push("PUSH_MESSAGE")
chk(p2 is not None,"发送方另一台设备也收到（多端同步）")

print("== C. 幂等 ==")
r2=c_za.request("SINGLE_CHAT_REQ",{"conversationId":cid,"type":1,"content":"你好，这是端到端验证","clientMsgId":cmsg})["body"]
chk(r2["msgId"]==r1["msgId"],"重发返回同一个 msgId")
chk(r2["duplicate"] is True,"重发 duplicate=true")
chk(r2["seq"]==r1["seq"],"重发不消耗新 seq")
time.sleep(0.3); c_li.pushes.clear()
p3=c_li.wait_push("PUSH_MESSAGE",timeout=1.5)
chk(p3 is None,"重发不会给对方推第二条")

print("== D. 未读数 ==")
convs=get("/api/conversations",li["token"])["data"]
tgt=[c for c in convs if c["id"]==cid]
chk(bool(tgt) and tgt[0]["unread"]>0,"李四未读数 > 0",tgt[0]["unread"] if tgt else None)

print("== E. 多端已读同步 ==")
c_li.send("READ_SYNC_REQ",{"conversationId":cid,"seq":r1["seq"]})   # 无响应帧
pz=c_li2.wait_push("READ_SYNC_PUSH")
chk(pz is not None,"李四另一台设备收到已读同步")
chk(pz is not None and pz["body"]["lastReadSeq"]==r1["seq"],"同步的 lastReadSeq 正确",pz["body"] if pz else None)
chk(pz is not None and isinstance(pz["body"]["lastReadSeq"],int),"lastReadSeq 是数字不是字符串")
pn=c_za.wait_push("READ_SYNC_PUSH",timeout=1.2)
chk(pn is None,"不推给对方（不做已读回执）")

time.sleep(0.4)
convs=get("/api/conversations",li["token"])["data"]
tgt=[c for c in convs if c["id"]==cid]
chk(bool(tgt) and tgt[0]["unread"]==0,"已读后未读清零",tgt[0]["unread"] if tgt else None)

print("== F. ACK 无下行帧 ==")
c_li.send("ACK_REQ",{"conversationId":cid,"seq":r1["seq"],"msgId":r1["msgId"]})
c_li.pushes.clear()
chk(c_li.wait_push("ACK_RESP",timeout=1.2) is None,"ACK 不产生回包")

print("== G. 离线补偿 ==")
c_li.close(); c_li2.close(); time.sleep(0.5)
cmsg2="offline-%d"%int(time.time()*1000)
r3=c_za.request("SINGLE_CHAT_REQ",{"conversationId":cid,"type":1,"content":"这条你不在线","clientMsgId":cmsg2})["body"]
chk(r3["seq"]==r1["seq"]+1,"离线期间序列号连续递增",r3["seq"])
c_li3=C("li-pc-reconnect",li["token"])
pull=c_li3.request("PULL_OFFLINE_REQ",{"conversationId":cid,"afterSeq":r1["seq"],"limit":50})["body"]
lst=pull if isinstance(pull,list) else pull.get("messages",[])
chk(len(lst)>=1,"重连后补拉到离线消息",len(lst))
chk(any(m["content"]=="这条你不在线" for m in lst),"补拉内容正确")

print("== H. 心跳 requestId ==")
h=c_li3.request("HEARTBEAT_REQ",{"ts":int(time.time()*1000)})
chk(isinstance(h["requestId"],int) and h["requestId"]>0,"心跳回包 requestId 正确关联",h["requestId"])

print("== I. 鉴权 ==")
import websocket as wsl
raw=wsl.create_connection("ws://127.0.0.1:9001/im",timeout=5)
raw.send(json.dumps({"cmd":CMD["SINGLE_CHAT_REQ"],"status":0,"requestId":9,"body":{"conversationId":cid,"type":1,"content":"x","clientMsgId":"nope"}}))
f=json.loads(raw.recv()); raw.close()
chk(f.get("cmd")==CMD["ERROR"] or f.get("status")!=0,"未登录发消息被拒",f)

print("== J. 历史消息 ==")
hist=get("/api/conversations/%s/messages?limit=20"%cid,li["token"])["data"]
seqs=[m["seq"] for m in hist]
chk(seqs==sorted(seqs),"历史按 seq 正序")
chk(len([m for m in hist if m["clientMsgId"]==cmsg])==1,"幂等的那条只有一条")

for c in [c_za,c_za2,c_li3]: c.close()

print("\n"+"="*48)
print("PASS=%d  FAIL=%d"%(len(ok),len(bad)))
if bad:
    print("失败项:")
    for b in bad: print("  -",b)
    sys.exit(1)
print("全部通过")
