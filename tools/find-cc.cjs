// find-cc.cjs — 在发件箱里找带抄送的邮件
const BASE = 'http://127.0.0.1:8789/api'
async function post(path, body, token) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: token } : {}) },
    body: JSON.stringify(body),
  })
  return res.json()
}
;(async () => {
  const login = await post('/login', { email: 'admin@example.com', password: '123456' })
  const token = login?.data?.token
  const get = async (p) => (await fetch(BASE + p, { headers: { Authorization: token } })).json()
  const r = await get('/email/list?type=0&accountId=2&mailbox=sent&page=1&size=20')
  const list = r?.data?.list ?? []
  for (const e of list) {
    if (!e.cc && !e.bcc) continue
    console.log(e.emailId, '|', e.subject, '| to', JSON.stringify(e.toEmail), '| cc', JSON.stringify(e.cc), '| bcc', JSON.stringify(e.bcc))
  }
  console.log('scanned', list.length)
})()
