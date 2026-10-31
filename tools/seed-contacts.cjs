// seed-contacts.cjs — 本地 D1（8789）里给 admin 塞几个联系人，用来验证联系人页 A–Z 索引
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
  if (!token) return console.log('LOGIN FAILED', JSON.stringify(login).slice(0, 200))

  const people = [
    ['Alice Chen', 'alice@example.com'],
    ['Bob Liu', 'bob@example.com'],
    ['Deng Wei', 'deng@example.com'],
    ['Emma Zhao', 'emma@example.com'],
    ['Frank Xu', 'frank@example.com'],
    ['Grace Sun', 'grace@example.com'],
    ['Henry Wu', 'henry@example.com'],
    ['Ivan Ma', 'ivan@example.com'],
    ['Judy Lin', 'judy@example.com'],
    ['Kelly He', 'kelly@example.com'],
    ['张伟', 'zhangwei@example.com'],
    ['李娜', 'lina@example.com'],
  ]
  for (const [name, email] of people) {
    const r = await post('/contact/add', { name, email, groupName: '默认', remark: '' }, token)
    console.log(name, r.code ?? r.message)
  }
  // 分组轮换，让列表里能看到「邮箱 · 分组」
  const groups = ['客户', '工作', '家人', '默认']
  const list = await fetch(BASE + '/contact/list?page=1&size=100', { headers: { Authorization: token } }).then((r) => r.json())
  for (const [i, c] of (list?.data?.list ?? []).entries()) {
    const r = await post('/contact/set', {
      contactId: c.contactId, name: c.name, email: c.email,
      groupName: groups[i % groups.length], remark: c.remark || '',
    }, token)
    if (r.code !== 200) console.log('set FAILED', c.name, JSON.stringify(r).slice(0, 120))
  }
  const all = await fetch(BASE + '/contact/list?page=1&size=100', { headers: { Authorization: token } }).then((r) => r.json())
  console.log('contact/list total =', all?.data?.total ?? JSON.stringify(all).slice(0, 160))
})()
