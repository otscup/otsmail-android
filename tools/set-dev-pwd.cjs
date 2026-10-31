// 本地 dev 管理员口令重置为 123456：按 mail-worker/src/utils/crypto-utils.js 的 PBKDF2 格式生成 SQL
const { webcrypto } = require('node:crypto');
const fs = require('node:fs');

const PBKDF2_ITERATIONS = 210000;
const email = 'admin@example.com';
const password = '123456';

(async () => {
  const saltBytes = new Uint8Array(16);
  webcrypto.getRandomValues(saltBytes);
  const salt = Buffer.from(saltBytes).toString('base64');
  const key = await webcrypto.subtle.importKey('raw', Buffer.from(password), { name: 'PBKDF2' }, false, ['deriveBits']);
  const bits = await webcrypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: Buffer.from(salt), iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    key,
    256,
  );
  const hash = `pbkdf2$${PBKDF2_ITERATIONS}$${Buffer.from(bits).toString('base64')}`;
  const sql = `UPDATE user SET password='${hash}', salt='${salt}' WHERE email='${email}';\n`;
  const out = process.argv[2] || 'dev/_tmp/set-dev-pwd.sql';
  fs.writeFileSync(out, sql, 'utf8');
  console.log(`wrote ${out}`);
})();
