/**
 * WebSocket 长连接客户端。
 *
 * 服务端在 9001 端口上跑 WebSocket，路径 /im，帧格式是 JSON 信封：
 *   { cmd: 数字, status: 数字, requestId: 数字, body: 任意 }
 *
 * 为什么不直接在浏览器里连 TCP：浏览器没有裸 TCP API，只有 WebSocket，
 * 所以服务端的 TCP(9000) 和 WS(9001) 两条通道共用同一套业务 Handler，
 * 只是编解码不同 —— 这一点在 backend/src/main/java/com/yujian/im/netty/WsCodec.java 里有注释说明。
 */

// 命令字，必须和后端 protocol/Command.java 完全一致。
// 写死在这里而不是从后端拉，是因为协议一旦变了前端本来就要改，多一次网络请求没意义。
export const CMD = {
  LOGIN_REQ: 0x01,
  LOGIN_RESP: 0x02,
  HEARTBEAT_REQ: 0x03,
  HEARTBEAT_RESP: 0x04,
  SINGLE_CHAT_REQ: 0x05,
  SINGLE_CHAT_RESP: 0x06,
  GROUP_CHAT_REQ: 0x07,
  GROUP_CHAT_RESP: 0x08,
  PUSH_MESSAGE: 0x09,
  ACK_REQ: 0x0a,
  ACK_RESP: 0x0b,
  PULL_OFFLINE_REQ: 0x0c,
  PULL_OFFLINE_RESP: 0x0d,
  READ_SYNC_PUSH: 0x0e,
  KICK_PUSH: 0x0f,
  READ_SYNC_REQ: 0x10,
  // 消息撤回：REQ/RESP 是"我撤回"这一问一答；PUSH 是"这条消息被撤回了"的通知，
  // 发给会话里所有人（包括撤回者自己的其他端），所以它不带 requestId。
  REVOKE_REQ: 0x11,
  REVOKE_RESP: 0x12,
  REVOKE_PUSH: 0x13,
  // 已读回执：REQ/RESP 是查询某条消息谁读了；PUSH 是别人读了你的消息时推给你。
  READ_RECEIPT_REQ: 0x14,
  READ_RECEIPT_RESP: 0x15,
  READ_RECEIPT_PUSH: 0x16,
  // 群成员变动（加入/退出/被踢/转让群主）。只推事件，详情让客户端走 HTTP 拉。
  GROUP_EVENT_PUSH: 0x17,
  ERROR: 0x7f,
}

export class ImSocket {
  constructor({ url, token, device = 'web' }) {
    this.url = url
    this.token = token
    this.device = device
    this.ws = null
    this.seq = 0
    this.pending = new Map() // requestId -> { resolve, reject, timer }
    this.handlers = new Map() // cmd -> fn(body)
    this.heartbeatTimer = null
    this.reconnectTimer = null
    this.closedByUser = false
    this.retry = 0
  }

  connect() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url)
      this.ws = ws

      ws.onopen = async () => {
        try {
          // 连上第一件事就是登录：服务端在 ImDispatcher 里对非 LOGIN/HEARTBEAT 的命令一律拒绝，
          // 所以这一步失败的话后面什么也做不了。
          const resp = await this.request(CMD.LOGIN_REQ, { token: this.token, device: this.device })
          if (!resp.success) {
            reject(new Error(resp.message || '登录失败'))
            ws.close()
            return
          }
          this.retry = 0
          this.startHeartbeat()
          resolve(resp)
        } catch (e) {
          reject(e)
        }
      }

      ws.onmessage = (ev) => {
        let frame
        try {
          frame = JSON.parse(ev.data)
        } catch {
          return
        }
        this.dispatch(frame)
      }

      ws.onclose = () => {
        this.stopHeartbeat()
        this.failAllPending(new Error('连接已断开'))
        if (!this.closedByUser) {
          this.scheduleReconnect()
        }
      }

      ws.onerror = () => {
        // 具体错误信息浏览器不会给，onclose 里统一处理
      }
    })
  }

  dispatch(frame) {
    const { cmd, status, requestId, body } = frame

    // 有 requestId 的是"请求-响应"，先还给等待中的 Promise
    if (requestId && this.pending.has(requestId)) {
      const p = this.pending.get(requestId)
      this.pending.delete(requestId)
      clearTimeout(p.timer)
      if (status === 0) {
        p.resolve(body)
      } else {
        p.reject(new Error(body?.message || body?.msg || `服务端返回错误 status=${status}`))
      }
      return
    }

    // 服务端主动推的帧（PUSH_MESSAGE / READ_SYNC_PUSH 等）走事件回调
    const fn = this.handlers.get(cmd)
    if (fn) {
      fn(body)
    }
  }

  /** 发一个请求并等它的响应。requestId 单调递增，用来把响应和请求对上号。 */
  request(cmd, body, timeoutMs = 10000) {
    return new Promise((resolve, reject) => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        reject(new Error('连接未就绪'))
        return
      }
      const requestId = ++this.seq
      const timer = setTimeout(() => {
        this.pending.delete(requestId)
        reject(new Error(`请求超时 cmd=${cmd}`))
      }, timeoutMs)
      this.pending.set(requestId, { resolve, reject, timer })
      this.ws.send(JSON.stringify({ cmd, requestId, body }))
    })
  }

  /** 发一个不需要响应的帧（比如 ACK）。 */
  post(cmd, body) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ cmd, body }))
    }
  }

  on(cmd, fn) {
    this.handlers.set(cmd, fn)
  }

  startHeartbeat() {
    this.stopHeartbeat()
    // 服务端 IdleStateHandler 的读空闲是 90 秒，30 秒一次心跳留了三次容错。
    // 心跳还兼做"确认连接活着"：很多网络设备会悄悄掐掉长时间没流量的连接。
    this.heartbeatTimer = setInterval(() => {
      this.request(CMD.HEARTBEAT_REQ, {}, 5000).catch(() => {
        // 心跳失败就当连接坏了，主动关，交给 onclose 去重连
        this.ws?.close()
      })
    }, 30000)
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  scheduleReconnect() {
    if (this.reconnectTimer) {
      return
    }
    // 指数退避 + 上限：1s, 2s, 4s, 8s, 最多 15s。
    // 不用固定间隔，是为了避免服务端刚重启、所有客户端同时重连把它再打死。
    const delay = Math.min(1000 * 2 ** this.retry, 15000)
    this.retry += 1
    this.reconnectTimer = setTimeout(async () => {
      this.reconnectTimer = null
      try {
        await this.connect()
        this.onReconnected?.()
      } catch {
        this.scheduleReconnect()
      }
    }, delay)
  }

  failAllPending(err) {
    for (const [, p] of this.pending) {
      clearTimeout(p.timer)
      p.reject(err)
    }
    this.pending.clear()
  }

  close() {
    this.closedByUser = true
    this.stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
  }
}
