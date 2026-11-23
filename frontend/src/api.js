/**
 * HTTP 接口层。
 * 只做三件事：拼 URL、带 token、把后端统一响应体 { code, msg, data } 拆开。
 */

const BASE = ''

let token = localStorage.getItem('im_token') || ''

export function setToken(t) {
  token = t || ''
  if (t) {
    localStorage.setItem('im_token', t)
  } else {
    localStorage.removeItem('im_token')
  }
}

export function getToken() {
  return token
}

async function request(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const resp = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  // 401 意味着 token 过期或被登出，直接清掉让界面回到登录页
  if (resp.status === 401) {
    setToken('')
    throw new Error('登录已失效，请重新登录')
  }

  const json = await resp.json()
  if (json.code !== 0) {
    throw new Error(json.msg || json.message || '请求失败')
  }
  return json.data
}

export const api = {
  register: (username, password, nickname) =>
    request('/api/auth/register', { method: 'POST', body: { username, password, nickname } }),

  login: (username, password) =>
    request('/api/auth/login', { method: 'POST', body: { username, password } }),

  logout: () => request('/api/auth/logout', { method: 'POST' }),

  me: () => request('/api/users/me'),

  searchUsers: (keyword) => request(`/api/users/search?keyword=${encodeURIComponent(keyword)}`),

  onlineStats: () => request('/api/users/online-stats'),

  conversations: () => request('/api/conversations'),

  createSingle: (targetUserId) =>
    request('/api/conversations/single', { method: 'POST', body: { targetUserId } }),

  createGroup: (name, memberIds) =>
    request('/api/conversations/group', { method: 'POST', body: { name, memberIds } }),

  // 历史消息按 seq 倒序查（后端查完会 reverse 成正序返回），beforeSeq 用于向上翻页
  messages: (convId, beforeSeq, limit = 20) => {
    const q = new URLSearchParams({ limit: String(limit) })
    if (beforeSeq) {
      q.set('beforeSeq', String(beforeSeq))
    }
    return request(`/api/conversations/${convId}/messages?${q}`)
  },

  members: (convId) => request(`/api/conversations/${convId}/members`),

  // 成员详情（昵称/在线），信息面板用；比 members 多一次 JOIN，只在这类"打开就看一次"的场景用
  memberInfos: (convId) => request(`/api/conversations/${convId}/member-infos`),

  markRead: (convId, seq) =>
    request(`/api/conversations/${convId}/read?seq=${seq}`, { method: 'POST' }),

  // ---------- 好友 ----------
  friends: () => request('/api/friends'),

  // 还没加的人：SQL 里就排掉了自己和已加的人，前端直接渲染
  friendCandidates: (limit = 100) => request(`/api/friends/candidates?limit=${limit}`),

  addFriend: (targetId) =>
    request('/api/friends', { method: 'POST', body: { targetId } }),

  removeFriend: (targetId) =>
    request(`/api/friends?targetId=${targetId}`, { method: 'DELETE' }),

  // ---------- 搜索 ----------
  // convId 传 null 就是全局搜（跨我参与的所有会话）
  searchMessages: (keyword, convId = null, limit = 50) => {
    const q = new URLSearchParams({ keyword, limit: String(limit) })
    if (convId) {
      q.set('conversationId', String(convId))
    }
    return request(`/api/search/messages?${q}`)
  },

  // ---------- 群管理 ----------
  // 这些操作走 HTTP 而不是长连接：低频、需要明确的失败原因（"群主不能直接退群"），
  // HTTP 的状态码和错误体天然适合做这件事。长连接只留给高频的消息流。

  groupMembers: (convId) => request(`/api/conversations/${convId}/group-members`),

  addMembers: (convId, memberIds) =>
    request(`/api/conversations/${convId}/members`, { method: 'POST', body: { memberIds } }),

  leaveGroup: (convId) =>
    request(`/api/conversations/${convId}/members/me`, { method: 'DELETE' }),

  kickMember: (convId, targetId) =>
    request(`/api/conversations/${convId}/members/${targetId}`, { method: 'DELETE' }),

  transferOwner: (convId, targetId) =>
    request(`/api/conversations/${convId}/transfer?targetId=${targetId}`, { method: 'POST' }),
}
