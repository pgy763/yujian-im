<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, getToken, setToken } from './api'
import { CMD, ImSocket } from './ws'

// ---------------------------------------------------------------- 登录

const token = ref(getToken())
const me = ref(null)
const authMode = ref('login')
const authForm = ref({ username: '', password: '', nickname: '' })
const authBusy = ref(false)
const authError = ref('')

const WS_URL = `ws://${location.hostname}:9001/im`

// 演示账号：库里已经建好（密码统一 123456），点一下直接填进表单。
// 换成真实产品时这块删掉即可，就是个演示便利设施。
const DEMO_USERS = ['alice', 'billy', 'demo_zhang', 'demo_li']

function fillDemo(username) {
  authMode.value = 'login'
  authForm.value.username = username
  authForm.value.password = '123456'
  authError.value = ''
}

/**
 * 比较两个 ID 是否相同。
 *
 * 后端主键是雪花 ID，19 位数，远超 JS Number 能精确表示的 2^53。
 * 后端已经把所有 Long 序列化成字符串（见 JsonModules.java），
 * 但为了不被"某处漏配/某处还是数字"这种问题再坑一次，
 * 统一用 String() 归一化后比较，永远不受类型影响。
 *
 * 真实踩过的坑：曾经用 c.id === vo.conversationId 比较，
 * 一边是字符串一边被 JSON.parse 成 double，383197437298343936 变成 ...940，
 * 结果发消息报"你不在该会话中"。
 */
function sameId(a, b) {
  if (a === null || a === undefined || b === null || b === undefined) {
    return false
  }
  return String(a) === String(b)
}

async function doAuth() {
  authError.value = ''
  const { username, password, nickname } = authForm.value
  if (!username || !password) {
    authError.value = '用户名和密码都要填'
    return
  }
  authBusy.value = true
  try {
    const data =
      authMode.value === 'login'
        ? await api.login(username, password)
        : await api.register(username, password, nickname || username)
    setToken(data.token)
    token.value = data.token
    await boot()
  } catch (e) {
    authError.value = e.message
  } finally {
    authBusy.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  } catch {
    // 服务端失败不影响本地退出
  }
  socket?.close()
  socket = null
  setToken('')
  token.value = ''
  me.value = null
  conversations.value = []
  activeId.value = null
}

// ---------------------------------------------------------------- 连接与状态

let socket = null
const connected = ref(false)
const convLoaded = ref(false)

const conversations = ref([])
const activeId = ref(null)
const members = ref({})
const messages = ref([])
const loadingHistory = ref(false)
const hasMore = ref(true)
const draft = ref('')
const toast = ref('')
let toastTimer = null

function showToast(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2600)
}

const activeConv = computed(() => conversations.value.find((c) => sameId(c.id, activeId.value)) || null)

async function boot() {
  me.value = await api.me()
  await refreshConversations()
  await connect()
}

async function connect() {
  socket = new ImSocket({ url: WS_URL, token: token.value })
  socket.onReconnected = async () => {
    connected.value = true
    // 重连后先刷新会话列表，再把当前会话从断点续上。
    // 断点就是本地最大的 seq —— 服务端 PULL_OFFLINE 用它做 afterSeq。
    await refreshConversations()
    if (activeId.value) {
      await pullOffline(activeId.value)
    }
  }
  socket.on(CMD.PUSH_MESSAGE, onPush)
  socket.on(CMD.READ_SYNC_PUSH, onReadSync)
  // A 档新增的三个推送：撤回、已读回执、群成员变动
  socket.on(CMD.REVOKE_PUSH, onRevokePush)
  socket.on(CMD.READ_RECEIPT_PUSH, onReceiptPush)
  socket.on(CMD.GROUP_EVENT_PUSH, onGroupEvent)

  try {
    await socket.connect()
    connected.value = true
  } catch (e) {
    connected.value = false
    showToast(`连接失败：${e.message}`)
  }
}

function onPush(vo) {
  if (!vo || !vo.conversationId) {
    return
  }
  // 推送同时也会发给发送者的所有端（多端同步），
  // 所以这里必须按 clientMsgId / msgId 去重，否则自己发的消息会出现两条。
  const conv = conversations.value.find((c) => sameId(c.id, vo.conversationId))
  if (conv && sameId(vo.conversationId, activeId.value)) {
    if (messages.value.some((m) => sameId(m.msgId, vo.msgId))) {
      return
    }
    // 本地乐观插入的那条（同 clientMsgId）要换成服务端版本，拿到真正的 seq/msgId
    const idx = messages.value.findIndex((m) => m.clientMsgId && m.clientMsgId === vo.clientMsgId)
    if (idx >= 0) {
      messages.value.splice(idx, 1, vo)
    } else {
      messages.value.push(vo)
      // 用户正在往上翻历史，别强行把他拽回底部，只在按钮上记个数
      if (!atBottom.value) {
        unreadBelow.value += 1
      }
    }
    scrollToBottom()
    // 正在看这个会话，收到就算已读：上报 ack + 已读位点
    ackMessage(vo)
    api.markRead(vo.conversationId, vo.seq).catch(() => {})
    if (conv) {
      conv.unread = 0
    }
  } else if (conv) {
    conv.unread = (conv.unread || 0) + 1
    conv.lastPreview = previewOf(vo)
    conv.lastMsgAt = vo.createdAt
  }
  // 推送里没有的会话（别人第一次给你发消息）直接重拉列表
  if (!conv) {
    refreshConversations()
  }
}

function onReadSync(body) {
  // 同一个账号的另一端读了消息，把这边这份的未读也清掉
  const conv = conversations.value.find((c) => sameId(c.id, body.conversationId))
  if (conv) {
    conv.unread = 0
  }
}

// ---------------------------------------------------------------- 撤回 / 回执 / 群事件

/**
 * 消息被撤回（可能是别人撤的，也可能是我自己在另一台设备上撤的）。
 *
 * 服务端推过来的只是 { conversationId, msgId, seq, operatorId }，没有原文 ——
 * 客户端按 msgId 找到那个气泡改渲染即可。找不到（比如消息还没加载进来）就忽略：
 * 下次拉历史时服务端返回的那条本身就是 revoked 状态。
 */
function onRevokePush(body) {
  if (!body || !body.msgId) return
  const m = messages.value.find((x) => sameId(x.msgId, body.msgId))
  if (m) {
    m.revoked = true
    m.content = ''
    m.revokedAt = body.revokedAt
  }
  // 会话列表的摘要也要跟着变，不然左边还显示着原文
  const conv = conversations.value.find((c) => sameId(c.id, body.conversationId))
  if (conv && sameId(conv.id, activeId.value)) {
    conv.lastPreview = '[已撤回]'
  }
}

/**
 * 我发的某条消息被别人读了。
 *
 * body 里带的是"读到哪条消息"的完整回执（含已读/未读名单），
 * 直接按 msgId 覆盖到本地缓存即可 —— 服务端算的是全量，不是增量。
 */
function onReceiptPush(body) {
  if (!body || !body.msgId) return
  receipts.value[String(body.msgId)] = body
}

/**
 * 群成员变动。只做两件事：弹个提示 + 重拉成员列表。
 *
 * 为什么不在推送里带完整成员列表：那样每加一个人就要推一份全量快照给所有人，
 * 群大的时候是 O(n²) 的流量。推送只管"叫你一声"，数据让客户端走 HTTP 拉。
 */
function onGroupEvent(body) {
  if (!body) return
  const who = body.targetNickname || body.operatorNickname || '有人'
  const text = {
    join: `${who} 加入了群聊`,
    leave: `${who} 退出了群聊`,
    kick: `${who} 被移出群聊`,
    transfer: `群主转让给了 ${who}`,
  }[body.event] || '群成员有变动'
  showToast(`${text}（${body.memberCount} 人）`)
  if (sameId(body.conversationId, activeId.value)) {
    delete members.value[body.conversationId]
    api.members(body.conversationId)
      .then((list) => { members.value[body.conversationId] = list })
      .catch(() => {})
    if (groupMembersDlg.value?.open) {
      loadGroupMembers()
    }
  }
}

/**
 * 撤回自己发的消息。
 *
 * 为什么按钮上要先判时间窗：服务端一定会再校验一次（防改客户端），
 * 但本地先藏起来，用户就不会点了个必然失败的按钮。
 */
const REVOKE_WINDOW_MS = 120 * 1000

function canRevoke(m) {
  if (!m || !m.msgId || m.revoked || m.pending) return false
  if (!isMine(m)) return false
  const d = toDate(m.createdAt)
  if (!d) return false
  return Date.now() - d.getTime() <= REVOKE_WINDOW_MS
}

async function revokeMessage(m) {
  if (!canRevoke(m)) return
  try {
    const vo = await socket.request(CMD.REVOKE_REQ, {
      conversationId: activeId.value,
      msgId: m.msgId,
    })
    // 自己这台设备会同时收到 REVOKE_PUSH，但响应可能先到；
    // 两处都按 msgId 幂等处理，不存在"撤两次"的问题。
    onRevokePush(vo)
    showToast('已撤回')
  } catch (e) {
    showToast(`撤回失败：${e.message}`)
  }
}

// ---------------------------------------------------------------- 已读回执

// msgId -> 回执对象。按需拉取并缓存，不主动为每条消息都拉一次
// （一屏 20 条就是 20 次往返，绝大部分消息用户根本不关心谁读了）。
const receipts = ref({})

async function loadReceipt(m) {
  if (!m?.msgId) return
  try {
    const vo = await socket.request(CMD.READ_RECEIPT_REQ, {
      conversationId: activeId.value,
      msgId: m.msgId,
    })
    receipts.value[String(m.msgId)] = vo
  } catch (e) {
    showToast(`查已读失败：${e.message}`)
  }
}

function receiptText(m) {
  const r = receipts.value[String(m.msgId)]
  if (!r) return ''
  if (r.totalOthers <= 0) return ''
  const read = r.readUserIds?.length || 0
  if (r.totalOthers === 1) {
    return read >= 1 ? '已读' : '未读'
  }
  return `${read}/${r.totalOthers} 人已读`
}

// ---------------------------------------------------------------- 群管理

const groupMembersDlg = ref(null)
const groupMembers = ref([])
const groupOwnerId = ref(null)
const groupBusy = ref(false)
const pickerDlg = ref(null)
const pickerCandidates = ref([])
const pickerChecked = ref([])

async function openGroupMembers() {
  if (!activeConv.value || activeConv.value.type !== 2) return
  groupMembersDlg.value?.showModal()
  await loadGroupMembers()
}

async function loadGroupMembers() {
  const convId = activeId.value
  if (!convId) return
  groupBusy.value = true
  try {
    const list = await api.groupMembers(convId)
    groupMembers.value = list
    groupOwnerId.value = list.find((x) => x.role === 1)?.id ?? null
  } catch (e) {
    showToast(e.message)
  } finally {
    groupBusy.value = false
  }
}

const isGroupOwner = computed(() => sameId(groupOwnerId.value, me.value?.id))

async function openPicker() {
  pickerChecked.value = []
  try {
    const all = await api.friendCandidates(200)
    // 已在群里的人不该出现在候选里 —— 前端再过滤一遍，
    // 因为 friendCandidates 只排除了"已加好友"，不排除"已在群"。
    const inGroup = new Set(groupMembers.value.map((x) => String(x.id)))
    pickerCandidates.value = all.filter((u) => !inGroup.has(String(u.id)))
  } catch (e) {
    pickerCandidates.value = []
  }
  pickerDlg.value?.showModal()
}

function togglePicker(u) {
  const i = pickerChecked.value.findIndex((x) => sameId(x, u.id))
  if (i >= 0) {
    pickerChecked.value.splice(i, 1)
  } else {
    pickerChecked.value.push(u.id)
  }
}

function isPickerChecked(u) {
  return pickerChecked.value.some((x) => sameId(x, u.id))
}

async function confirmPicker() {
  if (!pickerChecked.value.length) {
    pickerDlg.value?.close()
    return
  }
  const convId = activeId.value
  try {
    const added = await api.addMembers(convId, pickerChecked.value)
    pickerDlg.value?.close()
    await loadGroupMembers()
    delete members.value[convId]
    showToast(added?.length ? `已拉入 ${added.length} 人` : '这些人都已经在群里了')
  } catch (e) {
    showToast(e.message)
  }
}

async function kickMember(u) {
  try {
    await api.kickMember(activeId.value, u.id)
    await loadGroupMembers()
    showToast(`已移出 ${u.nickname || u.username}`)
  } catch (e) {
    showToast(e.message)
  }
}

async function transferTo(u) {
  if (!confirm(`把群主转让给 ${u.nickname || u.username}？转让后你将变成普通成员。`)) return
  try {
    await api.transferOwner(activeId.value, u.id)
    await loadGroupMembers()
    showToast('已转让群主')
  } catch (e) {
    showToast(e.message)
  }
}

async function leaveGroup() {
  if (!confirm('确定退出这个群？退出后将看不到后续消息。')) return
  try {
    await api.leaveGroup(activeId.value)
    groupMembersDlg.value?.close()
    // activeConv 是 computed（从 activeId 派生），不能直接赋值；
    // 把 activeId 清空它自然就变 null 了。
    activeId.value = null
    messages.value = []
    await refreshConversations()
    showToast('已退出群聊')
  } catch (e) {
    showToast(e.message)
  }
}

function ackMessage(vo) {
  // ACK 是单向确认，服务端不回帧：它只推进 delivered_seq，
  // 这个位点就是下次重连 PULL_OFFLINE 的起点。
  socket?.post(CMD.ACK_REQ, { conversationId: vo.conversationId, seq: vo.seq, msgId: vo.msgId })
}

async function pullOffline(convId) {
  const localMax = messages.value
    .filter((m) => sameId(m.conversationId, convId))
    .reduce((max, m) => Math.max(max, m.seq || 0), 0)
  try {
    const list = await socket.request(CMD.PULL_OFFLINE_REQ, {
      conversationId: convId,
      afterSeq: localMax,
      limit: 200,
    })
    if (!Array.isArray(list) || list.length === 0) {
      return
    }
    for (const vo of list) {
      if (!messages.value.some((m) => sameId(m.msgId, vo.msgId))) {
        messages.value.push(vo)
      }
      ackMessage(vo)
    }
    messages.value.sort((a, b) => (a.seq || 0) - (b.seq || 0))
    scrollToBottom()
    showToast(`补到 ${list.length} 条离线消息`)
  } catch (e) {
    console.warn('离线补拉失败', e)
  }
}

// ---------------------------------------------------------------- 会话列表

async function refreshConversations() {
  try {
    conversations.value = await api.conversations()
    convLoaded.value = true
  } catch (e) {
    showToast(e.message)
  }
}

function previewOf(vo) {
  if (!vo) return ''
  if (vo.type === 2) return '[图片]'
  return vo.content || ''
}

async function openConversation(conv) {
  activeId.value = conv.id
  messages.value = []
  hasMore.value = true
  unreadBelow.value = 0
  atBottom.value = true
  if (!members.value[conv.id]) {
    try {
      members.value[conv.id] = await api.members(conv.id)
    } catch {
      members.value[conv.id] = []
    }
  }
  await loadHistory()
  conv.unread = 0
  const maxSeq = messages.value.reduce((m, x) => Math.max(m, x.seq || 0), conv.seq || 0)
  api.markRead(conv.id, maxSeq).catch(() => {})
  // HTTP 标记已读之后还要走一次长连接，让服务端推 READ_SYNC 给同账号其他端
  socket?.post(CMD.READ_SYNC_REQ, { conversationId: conv.id, seq: maxSeq })
  await pullOffline(conv.id)
  scrollToBottom()
}

async function loadHistory() {
  if (!activeId.value || loadingHistory.value) {
    return
  }
  loadingHistory.value = true
  try {
    const oldest = messages.value[0]?.seq
    const list = await api.messages(activeId.value, oldest, 20)
    if (list.length < 20) {
      hasMore.value = false
    }
    if (list.length) {
      messages.value = [...list, ...messages.value]
      for (const m of list) {
        if (!sameId(m.fromUserId, me.value?.id)) {
          ackMessage(m)
        }
      }
    }
  } catch (e) {
    showToast(e.message)
  } finally {
    loadingHistory.value = false
  }
}

// ---------------------------------------------------------------- 发消息

const sending = ref(false)

async function send() {
  const content = draft.value.trim()
  if (!content || !activeId.value || sending.value) {
    return
  }
  const conv = activeConv.value
  const type = conv?.type === 1 ? CMD.SINGLE_CHAT_REQ : CMD.GROUP_CHAT_REQ
  const clientMsgId = crypto.randomUUID()

  draft.value = ''
  sending.value = true

  // 乐观渲染：先本地插一条"待确认"的消息，网络回来后再换成服务端版本。
  // 这样点发送到看到气泡之间没有延迟感；如果失败就把它标红删掉。
  const optimistic = {
    msgId: null,
    conversationId: activeId.value,
    seq: null,
    fromUserId: me.value?.id,
    fromNickname: me.value?.nickname,
    type: 1,
    content,
    clientMsgId,
    createdAt: new Date().toISOString(),
    pending: true,
  }
  messages.value.push(optimistic)
  scrollToBottom()

  try {
    const resp = await socket.request(type, {
      conversationId: activeId.value,
      type: 1,
      content,
      clientMsgId,
    })
    // 服务端会同时回响应和推一份 PUSH 给自己；
    // onPush 可能先到，所以这里要按 clientMsgId 找，找不到就说明已经被替换了。
    const idx = messages.value.findIndex((m) => m.clientMsgId === clientMsgId && !m.msgId)
    if (idx >= 0) {
      messages.value.splice(idx, 1, resp)
    }
    ackMessage(resp)
    if (conv) {
      conv.lastPreview = content
      conv.seq = resp.seq
      conv.lastMsgAt = resp.createdAt
    }
    // 把会话提到列表最上面
    const i = conversations.value.findIndex((c) => sameId(c.id, conv?.id))
    if (i > 0) {
      conversations.value.unshift(conversations.value.splice(i, 1)[0])
    }
  } catch (e) {
    const idx = messages.value.findIndex((m) => m.clientMsgId === clientMsgId)
    if (idx >= 0) {
      messages.value.splice(idx, 1)
    }
    draft.value = content // 失败把内容还给用户，别让他重打一遍
    showToast(`发送失败：${e.message}`)
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

// IME 安全的回车发送。
// 中文输入法里回车是"确认候选词"，如果直接发送会发出半截拼音。
// 查过 modern-web-guidance 的 ime-safe-enter-submit：
// 要同时判 event.isComposing 和 keyCode === 229（后者兜 Safari 的事件顺序 bug）。
function onKeydown(e) {
  if (e.key !== 'Enter' || e.shiftKey) {
    return
  }
  e.preventDefault()
  if (e.isComposing || e.keyCode === 229) {
    return
  }
  send()
}

// ---------------------------------------------------------------- 滚动

const listEl = ref(null)
const atBottom = ref(true)
// 不在底部时新到的消息数：显示在"回到底部"按钮上的小角标，
// 让用户知道"上面这段不是我漏掉的，是下面又来了 N 条"。
const unreadBelow = ref(0)

function onListScroll() {
  const el = listEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
  if (atBottom.value) {
    unreadBelow.value = 0
  }
  if (el.scrollTop < 40 && hasMore.value && !loadingHistory.value) {
    loadHistory()
  }
}

function jumpToBottom() {
  atBottom.value = true
  unreadBelow.value = 0
  nextTick(() => {
    const el = listEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function scrollToBottom() {
  if (!atBottom.value) {
    return
  }
  nextTick(() => {
    const el = listEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

watch(activeId, () => {
  atBottom.value = true
})

// ---------------------------------------------------------------- 会话信息面板

const infoDlg = ref(null)
const memberInfos = ref([])
const infoBusy = ref(false)

/**
 * 打开"成员与连接信息"面板。
 *
 * 成员详情单独请求 /member-infos，而不是复用会话列表里的 members id 数组：
 * 那边只有 id，没有昵称和在线状态，前端拿 id 拼名字会拼出一串雪花号。
 */
async function openInfoDlg() {
  const conv = activeConv.value
  if (!conv) {
    return
  }
  memberInfos.value = []
  infoBusy.value = true
  infoDlg.value?.showModal()
  try {
    memberInfos.value = await api.memberInfos(conv.id)
  } catch (e) {
    showToast(e.message)
  } finally {
    infoBusy.value = false
  }
}

// ---------------------------------------------------------------- 新建会话

const groupDlg = ref(null)
const searchKeyword = ref('')
const searchResult = ref([])
const groupName = ref('')
const picked = ref([])
const newConvBusy = ref(false)

async function doSearch() {
  const kw = searchKeyword.value.trim()
  if (!kw) {
    searchResult.value = []
    return
  }
  try {
    searchResult.value = await api.searchUsers(kw)
  } catch (e) {
    showToast(e.message)
  }
}

async function startSingle(user) {
  if (newConvBusy.value) return
  newConvBusy.value = true
  try {
    const conv = await api.createSingle(user.id)
    await refreshConversations()
    const found = conversations.value.find((c) => sameId(c.id, conv.id))
    await openConversation(found || { ...conv, unread: 0 })
    searchResult.value = []
    searchKeyword.value = ''
  } catch (e) {
    showToast(e.message)
  } finally {
    newConvBusy.value = false
  }
}

function openGroupDlg() {
  groupName.value = ''
  picked.value = []
  searchResult.value = []
  searchKeyword.value = ''
  groupDlg.value.showModal()
}

function togglePick(u) {
  const i = picked.value.findIndex((x) => sameId(x.id, u.id))
  if (i >= 0) {
    picked.value.splice(i, 1)
  } else {
    picked.value.push(u)
  }
}

async function createGroup() {
  if (!groupName.value.trim()) {
    showToast('群名不能为空')
    return
  }
  if (picked.value.length === 0) {
    showToast('至少选一个成员')
    return
  }
  try {
    const conv = await api.createGroup(
      groupName.value.trim(),
      picked.value.map((u) => u.id),
    )
    groupDlg.value.close()
    await refreshConversations()
    const found = conversations.value.find((c) => sameId(c.id, conv.id))
    await openConversation(found || { ...conv, unread: 0 })
  } catch (e) {
    showToast(e.message)
  }
}

// ---------------------------------------------------------------- 好友

const friendDlg = ref(null)
const friendCandidates = ref([])
const friendPicked = ref([])
const friendBusy = ref(false)
const myFriends = ref([])

/**
 * 打开"添加好友"：把<b>还没加过的人</b>一次列出来，让用户自己勾。
 *
 * 为什么不在前端做差集：候选清单由后端 SQL 里 NOT EXISTS 直接算出来，
 * 前端拉到的就是可选项；这样好友上万时也不会先把全表拉到浏览器再过滤。
 */
async function openFriendDlg() {
  friendPicked.value = []
  friendCandidates.value = []
  friendBusy.value = true
  friendDlg.value?.showModal()
  try {
    const [cands, friends] = await Promise.all([api.friendCandidates(), api.friends()])
    friendCandidates.value = cands || []
    myFriends.value = friends || []
  } catch (e) {
    showToast(e.message)
  } finally {
    friendBusy.value = false
  }
}

function toggleFriendPick(u) {
  const i = friendPicked.value.findIndex((x) => sameId(x.id, u.id))
  if (i >= 0) {
    friendPicked.value.splice(i, 1)
  } else {
    friendPicked.value.push(u)
  }
}

function isFriendPicked(u) {
  return friendPicked.value.some((x) => sameId(x.id, u.id))
}

async function addPickedFriends() {
  if (!friendPicked.value.length) {
    showToast('先选一个要加的人')
    return
  }
  const pickedNow = [...friendPicked.value]
  friendBusy.value = true
  try {
    // 后端 addFriend 是幂等的：重复加同一个人不会报错，所以这里串行发也安全
    for (const u of pickedNow) {
      await api.addFriend(u.id)
    }
    showToast(`已添加 ${pickedNow.length} 位好友`)
    const added = new Set(pickedNow.map((u) => String(u.id)))
    friendCandidates.value = friendCandidates.value.filter((u) => !added.has(String(u.id)))
    friendPicked.value = []
    myFriends.value = await api.friends()
  } catch (e) {
    showToast(e.message)
  } finally {
    friendBusy.value = false
  }
}

async function removeFriend(u) {
  try {
    await api.removeFriend(u.id)
    myFriends.value = myFriends.value.filter((x) => !sameId(x.id, u.id))
    // 删掉的人会重新出现在候选里，顺手补一份最新候选，省得用户关掉再开
    friendCandidates.value = await api.friendCandidates()
    showToast(`已移除 ${u.nickname || u.username}`)
  } catch (e) {
    showToast(e.message)
  }
}

async function chatWithFriend(u) {
  friendDlg.value?.close()
  await startSingle(u)
}

// ---------------------------------------------------------------- 搜聊天记录

const searchDlg = ref(null)
const msgKeyword = ref('')
const msgScope = ref('conv')
const searchHits = ref([])
const searchBusy = ref(false)
const highlightId = ref(null)

function openSearchDlg() {
  msgKeyword.value = ''
  searchHits.value = []
  // 没进任何会话时只能全局搜，否则默认只搜当前会话（范围小、命中更准）
  msgScope.value = activeId.value ? 'conv' : 'all'
  searchDlg.value?.showModal()
}

async function runMessageSearch() {
  const kw = msgKeyword.value.trim()
  if (!kw) {
    showToast('输入要搜的关键字')
    return
  }
  searchBusy.value = true
  try {
    searchHits.value = await api.searchMessages(
      kw,
      msgScope.value === 'conv' ? activeId.value : null,
    )
    if (!searchHits.value.length) {
      showToast('没搜到相关聊天记录')
    }
  } catch (e) {
    showToast(e.message)
  } finally {
    searchBusy.value = false
  }
}

/**
 * 跳到某条搜索结果。
 *
 * 目标消息可能在更早的分页里，所以进来先正常打开会话（默认最新一页），
 * 再看列表最旧一条的 seq 是不是还比目标大 —— 是就继续向上翻，最多 5 页。
 * 找不到不假装成功，直接告诉用户去手动翻，避免"点了没反应"这种更糟的体验。
 */
async function jumpToHit(hit) {
  searchDlg.value?.close()
  const conv = conversations.value.find((c) => sameId(c.id, hit.conversationId))
  if (!conv) {
    showToast('这个会话不在你的列表里')
    return
  }
  await openConversation(conv)
  let tries = 0
  while (
    tries < 5 &&
    messages.value.length &&
    (messages.value[0].seq || 0) > hit.seq
  ) {
    const before = messages.value.length
    await loadHistory()
    tries += 1
    if (messages.value.length === before) {
      break
    }
  }
  await nextTick()
  const target = messages.value.find((m) => sameId(m.msgId, hit.msgId))
  if (!target) {
    showToast('这条消息在更早的历史里，请手动向上翻')
    return
  }
  highlightId.value = target.msgId
  await nextTick()
  document
    .querySelector(`[data-msg-id="${target.msgId}"]`)
    ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  setTimeout(() => {
    highlightId.value = null
  }, 2400)
}

// ---------------------------------------------------------------- 展示工具

function initial(name) {
  return (name || '?').slice(0, 1).toUpperCase()
}

// 头像底色：由名字 hash 出一个固定色相，同一个人在任何界面永远是同一个颜色。
// 群聊里十几个人同时出现时，靠颜色比靠首字母更快分辨出是谁。
// 只挑了几个在浅色和深色背景下都够对比度的色相，避免黄绿那种发灰的中间色。
const AVATAR_HUES = [212, 258, 190, 330, 24, 158, 280, 348]

function avatarStyle(name) {
  const s = String(name || '?')
  let h = 0
  for (let i = 0; i < s.length; i += 1) {
    h = (h * 31 + s.charCodeAt(i)) % 9973
  }
  return { '--av-hue': AVATAR_HUES[h % AVATAR_HUES.length] }
}

function fmtTime(t) {
  if (!t) return ''
  // 后端 LocalDateTime 被 Jackson 序列化成 "2026-11-23T09:41:02.123"，
  // 末尾没有时区，Safari 解析 ISO 字符串对格式很挑，所以自己拼。
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(t).slice(11, 16)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return sameDay ? `${hh}:${mm}` : `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`
}

function isMine(m) {
  return sameId(m.fromUserId, me.value?.id)
}

// ---------------------------------------------------------------- 渲染元信息

// 后端 LocalDateTime 是 "2026-11-23T09:41:02.123"，没有时区后缀，
// 有些浏览器解析 ISO 字符串很挑，统一先规整再交给 Date。
function toDate(t) {
  if (!t) return null
  const d = new Date(String(t).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

const DAY_MS = 24 * 60 * 60 * 1000

function dayLabel(t) {
  const d = toDate(t)
  if (!d) return ''
  const today = new Date()
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  const diffDays = Math.floor((startOfToday - new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()) / DAY_MS)
  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (d.getFullYear() === today.getFullYear()) return `${d.getMonth() + 1} 月 ${d.getDate()} 日`
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

// 把消息预先算成"带渲染元信息"的数组：
//   showDay   —— 这一天第一条消息上方要插日期分隔条
//   grouped   —— 和上一条是同一人且 5 分钟内，省略头像、收紧间距
// 放在 computed 里而不是模板表达式里，模板保持只读，同一份数据也只算一次。
const GROUP_WINDOW_MS = 5 * 60 * 1000

const renderedMessages = computed(() => {
  const out = []
  let lastDay = null
  let lastSender = null
  let lastTs = 0
  for (const m of messages.value) {
    const d = toDate(m.createdAt)
    const day = d ? new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() : null
    const showDay = day !== null && day !== lastDay
    // 系统消息自成一行，不参与分组
    const grouped =
      !showDay && m.type !== 3 && lastSender === String(m.fromUserId) && d && lastTs && d.getTime() - lastTs < GROUP_WINDOW_MS
    out.push({ m, showDay, dayText: showDay ? dayLabel(m.createdAt) : '', grouped: Boolean(grouped) })
    if (day !== null) lastDay = day
    lastSender = String(m.fromUserId)
    lastTs = d ? d.getTime() : 0
  }
  return out
})

onBeforeUnmount(() => {
  socket?.close()
  clearTimeout(toastTimer)
})

// 刷新页面后 localStorage 里还有 token，但 me 是 null。
// 不补这段的话用户一刷新就被踢回登录页（体验上像掉线），
// 所以带着 token 自动 boot 一次；失败说明 token 过期，清掉即可。
onMounted(async () => {
  if (!token.value) return
  try {
    await boot()
  } catch {
    setToken('')
    token.value = ''
    me.value = null
  }
})
</script>

<template>
  <!-- ======================= 登录 ======================= -->
  <div v-if="!token || !me" class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">鱼</div>
        <div>
          <h1>鱼见 IM</h1>
          <p class="sub">Netty 长连接 · 自研二进制协议 · 消息不丢不重</p>
        </div>
      </div>
      <label for="a-username">用户名</label>
      <input
        id="a-username"
        v-model="authForm.username"
        autocomplete="username"
        placeholder="3~32 位，字母数字下划线"
        @keyup.enter="doAuth"
      />
      <label for="a-password">密码</label>
      <input
        id="a-password"
        v-model="authForm.password"
        type="password"
        :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'"
        placeholder="至少 6 位"
        @keyup.enter="doAuth"
      />
      <template v-if="authMode === 'register'">
        <label for="a-nick">昵称（可选）</label>
        <input id="a-nick" v-model="authForm.nickname" placeholder="不填就用用户名" />
      </template>

      <div class="actions">
        <button class="primary" :disabled="authBusy" @click="doAuth">
          {{ authBusy ? '请稍候…' : authMode === 'login' ? '登录' : '注册并登录' }}
        </button>
        <button
          @click="authMode = authMode === 'login' ? 'register' : 'login'; authError = ''"
        >
          {{ authMode === 'login' ? '注册' : '返回登录' }}
        </button>
      </div>
      <div class="form-error">{{ authError }}</div>

      <!-- 一键填账号：纯前端便利功能，省得演示时每次手打 -->
      <div class="demo-row">
        <span>试一下：</span>
        <button
          v-for="u in DEMO_USERS"
          :key="u"
          type="button"
          class="chip"
          @click="fillDemo(u)"
        >
          {{ u }}
        </button>
      </div>
    </div>
  </div>

  <!-- ======================= 主界面 ======================= -->
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="me-bar">
        <div class="avatar" :style="avatarStyle(me.nickname || me.username)">
          {{ initial(me.nickname || me.username) }}
        </div>
        <div>
          <div class="name">{{ me.nickname || me.username }}</div>
          <div class="handle">@{{ me.username }}</div>
        </div>
        <span class="conn" :class="{ on: connected }">
          {{ connected ? '在线' : '重连中' }}
        </span>
      </div>

      <div class="sidebar-actions">
        <input
          v-model="searchKeyword"
          placeholder="搜用户名/昵称找人"
          @keyup.enter="doSearch"
        />
        <button class="ghost" title="新建群聊" @click="openGroupDlg">+群</button>
        <button class="ghost" title="添加好友" @click="openFriendDlg">+好友</button>
        <button class="ghost" title="查找聊天记录" @click="openSearchDlg">🔍</button>
        <button class="ghost" title="退出登录" @click="logout">退出</button>
      </div>

      <div v-if="searchResult.length" style="padding: 0 8px 10px">
        <div
          v-for="u in searchResult"
          :key="u.id"
          class="user-pick"
          @click="startSingle(u)"
        >
          <div class="avatar sm" :style="avatarStyle(u.nickname || u.username)">
            {{ initial(u.nickname || u.username) }}
          </div>
          <div class="uname">
            {{ u.nickname || u.username }}
            <div class="uhandle">@{{ u.username }}</div>
          </div>
          <span v-if="u.online" class="dot-online" title="在线"></span>
        </div>
      </div>

      <div class="conv-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: sameId(c.id, activeId) }"
          @click="openConversation(c)"
        >
          <div class="avatar" :class="{ group: c.type === 2 }" :style="avatarStyle(c.title)">
            {{ c.type === 2 ? '群' : initial(c.title) }}
          </div>
          <div class="meta">
            <div class="title">
              <span>{{ c.title }}</span>
              <span class="time">{{ fmtTime(c.lastMsgAt) }}</span>
            </div>
            <div class="preview">{{ c.lastPreview || '还没有消息' }}</div>
          </div>
          <span v-if="c.unread > 0" class="badge">{{ c.unread > 99 ? '99+' : c.unread }}</span>
        </div>
        <div v-if="convLoaded && !conversations.length" class="empty-hint">
          还没有会话。<br />用上面的搜索框找个人，或者建个群。
        </div>
      </div>
    </aside>

    <section class="chat">
      <template v-if="activeConv">
        <header class="chat-header">
          <div class="avatar" :class="{ group: activeConv.type === 2 }" :style="avatarStyle(activeConv.title)">
            {{ activeConv.type === 2 ? '群' : initial(activeConv.title) }}
          </div>
          <div class="hinfo">
            <div class="title">{{ activeConv.title }}</div>
            <div class="sub">
              <span>
                {{ activeConv.type === 2 ? `群聊 · ${(members[activeConv.id] || []).length} 人` : '单聊' }}
              </span>
              <span class="seq-chip" title="这个会话的消息序号，全局递增不重复">seq {{ activeConv.seq }}</span>
            </div>
          </div>
          <button class="icon-btn" title="查找聊天记录" aria-label="查找聊天记录" @click="openSearchDlg">
            🔍
          </button>
          <!-- 群成员管理只在群聊里出现：单聊没有"成员"这个概念 -->
          <button
            v-if="activeConv.type === 2"
            class="icon-btn"
            title="群成员管理"
            aria-label="群成员管理"
            @click="openGroupMembers"
          >
            👥
          </button>
          <button class="icon-btn" title="成员与连接信息" aria-label="成员与连接信息" @click="openInfoDlg">
            ⓘ
          </button>
        </header>

        <div class="msg-area">
          <div ref="listEl" class="message-list" @scroll="onListScroll">
            <button
              v-if="hasMore"
              class="load-more ghost"
              :disabled="loadingHistory"
              @click="loadHistory"
            >
              {{ loadingHistory ? '加载中…' : '加载更早的消息' }}
            </button>
            <template v-for="row in renderedMessages" :key="row.m.msgId || row.m.clientMsgId">
              <div v-if="row.showDay" class="day-sep"><span>{{ row.dayText }}</span></div>
              <div
                class="msg-row"
                :class="{
                  mine: isMine(row.m),
                  system: row.m.type === 3,
                  grouped: row.grouped,
                  hl: sameId(row.m.msgId, highlightId),
                }"
                :data-msg-id="row.m.msgId || ''"
              >
                <div v-if="row.m.type !== 3" class="avatar sm" :style="avatarStyle(row.m.fromNickname)">
                  {{ initial(row.m.fromNickname) }}
                </div>
                <div class="msg-body">
                  <template v-if="row.m.type !== 3">
                    <div class="sender" v-if="activeConv.type === 2 && !isMine(row.m) && !row.grouped">
                      {{ row.m.fromNickname }}
                    </div>
                    <!-- 撤回态：不渲染原文。服务端下发的 content 已经是空串，
                         这里再显示一行说明，用户才知道"这里曾经有条消息"。 -->
                    <div v-if="row.m.revoked" class="bubble revoked">
                      {{ isMine(row.m) ? '你撤回了一条消息' : `${row.m.fromNickname} 撤回了一条消息` }}
                    </div>
                    <div v-else class="bubble">{{ row.m.content }}</div>
                    <div class="msg-meta">
                      <span v-if="row.m.pending" class="pending">发送中…</span>
                      <template v-else>
                        #{{ row.m.seq }} · {{ fmtTime(row.m.createdAt) }}
                        <span v-if="row.m.duplicate" class="dup">（幂等命中，未重复入库）</span>
                        <!-- 已读回执只对"我发的、且不是撤回的"消息有意义 -->
                        <button
                          v-if="isMine(row.m) && !row.m.revoked"
                          class="meta-btn"
                          :title="receipts[row.m.msgId] ? '已读详情' : '点一下查谁读了'"
                          @click="loadReceipt(row.m)"
                        >
                          {{ receiptText(row.m) || '查已读' }}
                        </button>
                        <button
                          v-if="canRevoke(row.m)"
                          class="meta-btn danger"
                          title="两分钟内可以撤回"
                          @click="revokeMessage(row.m)"
                        >
                          撤回
                        </button>
                      </template>
                    </div>
                  </template>
                  <div v-else class="bubble">{{ row.m.content }}</div>
                </div>
              </div>
            </template>
          </div>

          <!-- 往上翻历史时冒出来的"回到底部"，只在真的离底部够远时才出现 -->
          <button v-if="!atBottom" class="to-bottom" @click="jumpToBottom">
            回到底部
            <span v-if="unreadBelow > 0" class="to-bottom-badge">{{ unreadBelow > 99 ? '99+' : unreadBelow }}</span>
          </button>
        </div>

        <div class="composer">
          <div style="flex: 1">
            <textarea
              v-model="draft"
              placeholder="输入消息，Enter 发送，Shift+Enter 换行"
              aria-label="消息输入框"
              @keydown="onKeydown"
            ></textarea>
            <div class="hint">Enter 发送 / Shift+Enter 换行（中文输入法下回车确认候选词不会误发）</div>
          </div>
          <button class="primary" :disabled="!draft.trim() || sending" @click="send">发送</button>
        </div>
      </template>

      <div v-else class="chat-empty">选一个会话开始聊天</div>
    </section>
  </div>

  <!-- ======================= 会话信息 ======================= -->
  <dialog ref="infoDlg" class="info-dlg">
    <div class="dlg-head">会话信息</div>
    <div class="dlg-body" v-if="activeConv">
      <div class="info-hero">
        <div
          class="avatar lg"
          :class="{ group: activeConv.type === 2 }"
          :style="avatarStyle(activeConv.title)"
        >
          {{ activeConv.type === 2 ? '群' : initial(activeConv.title) }}
        </div>
        <div class="info-title">{{ activeConv.title }}</div>
        <div class="info-sub">
          {{ activeConv.type === 2 ? '群聊' : '单聊' }} ·
          {{ memberInfos.length || (members[activeConv.id] || []).length }} 人
        </div>
      </div>

      <div class="info-kv">
        <div class="k">会话 ID</div>
        <div class="v mono">{{ activeConv.id }}</div>
      </div>
      <div class="info-kv">
        <div class="k">当前 seq</div>
        <div class="v mono">
          {{ activeConv.seq }}
          <span class="muted">（会话内单调递增，断线补偿靠它定位）</span>
        </div>
      </div>
      <div class="info-kv">
        <div class="k">连接</div>
        <div class="v mono">{{ connected ? '已连接' : '重连中' }} · {{ WS_URL }}</div>
      </div>
      <div class="info-kv">
        <div class="k">心跳</div>
        <div class="v">30s 一次（服务端 90s 读空闲即断开）</div>
      </div>

      <div class="info-section">成员</div>
      <div class="member-grid">
        <div v-for="u in memberInfos" :key="u.id" class="member" :title="`@${u.username || u.id}`">
          <div class="avatar sm" :style="avatarStyle(u.nickname || u.username || u.id)">
            {{ initial(u.nickname || u.username || '?') }}
          </div>
          <div class="member-name">{{ u.nickname || u.username || '未知用户' }}</div>
          <span v-if="u.online" class="dot-online" title="在线"></span>
        </div>
        <div v-if="infoBusy" class="muted">加载中…</div>
        <div v-else-if="!memberInfos.length" class="muted">成员信息加载失败</div>
      </div>
    </div>
    <div class="dlg-foot">
      <button class="primary" @click="infoDlg.close()">关闭</button>
    </div>
  </dialog>

  <!-- ======================= 新建群聊 ======================= -->
  <dialog ref="groupDlg">
    <div class="dlg-head">新建群聊</div>
    <div class="dlg-body">
      <label for="g-name">群名称</label>
      <input id="g-name" v-model="groupName" placeholder="给群起个名字" />
      <label for="g-search">搜索并选择成员（已选 {{ picked.length }} 人）</label>
      <input
        id="g-search"
        v-model="searchKeyword"
        placeholder="输入用户名或昵称，回车搜索"
        @keyup.enter="doSearch"
      />
      <div
        v-for="u in searchResult"
        :key="u.id"
        class="user-pick"
        :class="{ picked: picked.some((p) => sameId(p.id, u.id)) }"
        @click="togglePick(u)"
      >
        <div class="avatar sm" :style="avatarStyle(u.nickname || u.username)">
          {{ initial(u.nickname || u.username) }}
        </div>
        <div class="uname">
          {{ u.nickname || u.username }}
          <div class="uhandle">@{{ u.username }}</div>
        </div>
        <span v-if="u.online" class="dot-online" title="在线"></span>
        <span v-if="picked.some((p) => sameId(p.id, u.id))" class="tick">✓</span>
      </div>
    </div>
    <div class="dlg-foot">
      <button @click="groupDlg.close()">取消</button>
      <button class="primary" @click="createGroup">创建</button>
    </div>
  </dialog>

  <!-- ======================= 添加好友 ======================= -->
  <dialog ref="friendDlg" class="friend-dlg">
    <div class="dlg-head">添加好友</div>
    <div class="dlg-body">
      <div class="dlg-tip">
        下面是<b>还没加过的人</b>（后端 SQL 里就排掉了你自己和已经是好友的人）。勾选后点"添加"。
      </div>

      <label>可添加（勾选 {{ friendPicked.length }} 人）</label>
      <div class="friend-list">
        <div
          v-for="u in friendCandidates"
          :key="u.id"
          class="user-pick"
          :class="{ picked: isFriendPicked(u) }"
          @click="toggleFriendPick(u)"
        >
          <div class="avatar sm" :style="avatarStyle(u.nickname || u.username)">
            {{ initial(u.nickname || u.username) }}
          </div>
          <div class="uname">
            {{ u.nickname || u.username }}
            <div class="uhandle">@{{ u.username }}</div>
          </div>
          <span v-if="u.online" class="dot-online" title="在线"></span>
          <span v-if="isFriendPicked(u)" class="tick">✓</span>
        </div>
        <div v-if="friendBusy" class="muted">加载中…</div>
        <div v-else-if="!friendCandidates.length" class="muted">所有人都已经是你的好友了</div>
      </div>

      <template v-if="myFriends.length">
        <label>我的好友（{{ myFriends.length }} 人）</label>
        <div class="friend-list">
          <div v-for="u in myFriends" :key="`f-${u.id}`" class="user-pick">
            <div class="avatar sm" :style="avatarStyle(u.nickname || u.username)">
              {{ initial(u.nickname || u.username) }}
            </div>
            <div class="uname">
              {{ u.nickname || u.username }}
              <div class="uhandle">@{{ u.username }}</div>
            </div>
            <span v-if="u.online" class="dot-online" title="在线"></span>
            <button class="mini" @click.stop="chatWithFriend(u)">聊天</button>
            <button class="mini danger" @click.stop="removeFriend(u)">移除</button>
          </div>
        </div>
      </template>
    </div>
    <div class="dlg-foot">
      <button @click="friendDlg.close()">取消</button>
      <button class="primary" :disabled="friendBusy || !friendPicked.length" @click="addPickedFriends">
        添加{{ friendPicked.length ? ` (${friendPicked.length})` : '' }}
      </button>
    </div>
  </dialog>

  <!-- ======================= 查找聊天记录 ======================= -->
  <dialog ref="searchDlg" class="search-dlg">
    <div class="dlg-head">查找聊天记录</div>
    <div class="dlg-body">
      <div class="seg">
        <button
          class="ghost"
          :class="{ on: msgScope === 'conv' }"
          :disabled="!activeId"
          @click="msgScope = 'conv'"
        >
          当前会话
        </button>
        <button class="ghost" :class="{ on: msgScope === 'all' }" @click="msgScope = 'all'">
          全部会话
        </button>
      </div>
      <input
        v-model="msgKeyword"
        placeholder="输入关键字，回车搜索（1 个字走 LIKE，2 个字以上走全文索引）"
        aria-label="聊天记录关键字"
        @keyup.enter="runMessageSearch"
      />
      <div class="dlg-tip muted">
        {{ msgKeyword.trim().length === 1 ? '只搜 1 个字时用的是 LIKE，因为 ngram 分词器不索引单字' : '2 个字以上走 FULLTEXT + ngram，能命中"粘包"这类中文二元组' }}
      </div>

      <div v-if="searchBusy" class="muted">搜索中…</div>
      <div v-else class="hit-list">
        <div v-for="h in searchHits" :key="h.msgId" class="hit" @click="jumpToHit(h)">
          <div class="hit-top">
            <span class="hit-conv">{{ h.conversationTitle }}</span>
            <span class="hit-time">{{ fmtTime(h.createdAt) }}</span>
          </div>
          <div class="hit-body">{{ h.content }}</div>
          <div class="hit-meta">
            {{ h.fromNickname || '未知' }} · #{{ h.seq }}
          </div>
        </div>
        <div v-if="!searchHits.length && !searchBusy" class="muted">
          还没有搜索。输入关键字后回车。
        </div>
      </div>
    </div>
    <div class="dlg-foot">
      <button @click="searchDlg.close()">关闭</button>
      <button class="primary" :disabled="searchBusy" @click="runMessageSearch">搜索</button>
    </div>
  </dialog>

  <!-- 群成员管理：拉人、踢人、转让群主、退群 -->
  <dialog ref="groupMembersDlg" class="friend-dlg">
    <div class="dlg-head">群成员</div>
    <div class="dlg-body">
      <div class="dlg-tip muted">
        {{ groupBusy ? '加载中…' : `共 ${groupMembers.length} 人（上限 200）` }}
      </div>
      <div class="friend-list">
        <div v-for="u in groupMembers" :key="u.id" class="user-pick picked-off">
          <div class="avatar sm" :style="avatarStyle(u.nickname || u.username)">
            {{ initial(u.nickname || u.username) }}
          </div>
          <div class="uinfo">
            <div class="name">
              {{ u.nickname || u.username }}
              <span v-if="u.role === 1" class="owner-tag">群主</span>
            </div>
            <div class="uhandle">@{{ u.username }}</div>
          </div>
          <div class="acts" v-if="isGroupOwner && !sameId(u.id, me?.id) && u.role !== 1">
            <button class="mini" @click="transferTo(u)">转让</button>
            <button class="mini danger" @click="kickMember(u)">移出</button>
          </div>
        </div>
      </div>
    </div>
    <div class="dlg-foot">
      <button class="ghost danger-text" @click="leaveGroup">退出群聊</button>
      <button class="ghost" @click="groupMembersDlg.close()">关闭</button>
      <button class="primary" @click="openPicker">拉人入群</button>
    </div>
  </dialog>

  <!-- 拉人：从好友里挑，已在群里的不在候选里 -->
  <dialog ref="pickerDlg" class="friend-dlg">
    <div class="dlg-head">拉人入群</div>
    <div class="dlg-body">
      <div class="dlg-tip muted">下面是可以拉进群的人（已在群里的已经排掉了）</div>
      <div class="friend-list">
        <div
          v-for="u in pickerCandidates"
          :key="u.id"
          class="user-pick"
          :class="{ picked: isPickerChecked(u) }"
          @click="togglePicker(u)"
        >
          <div class="avatar sm" :style="avatarStyle(u.nickname || u.username)">
            {{ initial(u.nickname || u.username) }}
          </div>
          <div class="uinfo">
            <div class="name">{{ u.nickname || u.username }}</div>
            <div class="uhandle">@{{ u.username }}</div>
          </div>
          <span v-if="isPickerChecked(u)" class="tick">✓</span>
        </div>
        <div v-if="!pickerCandidates.length" class="muted">没有可拉的人了。</div>
      </div>
    </div>
    <div class="dlg-foot">
      <button @click="pickerDlg.close()">取消</button>
      <button class="primary" @click="confirmPicker">
        加入{{ pickerChecked.length ? `（${pickerChecked.length}）` : '' }}
      </button>
    </div>
  </dialog>

  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
