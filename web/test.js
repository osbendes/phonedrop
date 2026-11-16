// Checks the browser page's crypto against Node's own, and writes a QR code for tools/check_qr.swift.
// Usage: node web/test.js web/index.html /tmp/qr.txt
const fs = require('fs'), nc = require('crypto');
const html = fs.readFileSync(process.argv[2], 'utf8');
const core = html.split('/*CORE*/')[1].split('/*END CORE*/')[0];
const api = new Function('crypto', 'btoa', 'atob', core + '; return {x25519, BASE, sha256, hmac, hkdf, sealWith, seal, open, qrMatrix, b64u, unb64u};')(nc.webcrypto, btoa, atob);
const eq = (a, b) => Buffer.from(a).equals(Buffer.from(b));
let fails = 0; const check = (name, ok) => { if (!ok) { fails++; console.log('FAIL', name); } };
// X25519: RFC vector + random against node
check('x25519 rfc', Buffer.from(api.x25519(Buffer.from('a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4', 'hex'), Buffer.from('e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c', 'hex'))).toString('hex') === 'c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552');
for (let i = 0; i < 20; i++) {
  const a = nc.generateKeyPairSync('x25519'), b = nc.randomBytes(32);
  const aPub = a.publicKey.export({ type: 'spki', format: 'der' }).subarray(-32);
  const bPub = api.x25519(b, api.BASE);
  const bKey = nc.createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b656e032100', 'hex'), Buffer.from(bPub)]), format: 'der', type: 'spki' });
  check('x25519 rand', eq(nc.diffieHellman({ privateKey: a.privateKey, publicKey: bKey }), api.x25519(b, aPub)));
}
for (const n of [0, 1, 55, 56, 63, 64, 65, 1000, 100000]) { const m = nc.randomBytes(n); check('sha ' + n, eq(api.sha256(m), nc.createHash('sha256').update(m).digest())); }
for (let i = 0; i < 10; i++) { const k = nc.randomBytes(i * 13), m = nc.randomBytes(i * 31); check('hmac', eq(api.hmac(k, m), nc.createHmac('sha256', k).update(m).digest())); }
{ const ikm = nc.randomBytes(32), salt = nc.randomBytes(64); check('hkdf', eq(api.hkdf(ikm, salt, 'phonedrop web v3'), Buffer.from(nc.hkdfSync('sha256', ikm, salt, 'phonedrop web v3', 32)))); }
for (const n of [0, 1, 15, 16, 17, 63, 64, 65, 1000, 65536, (1 << 20) + 3]) for (const an of [0, 5, 16, 33]) {
  const key = nc.randomBytes(32), nonce = nc.randomBytes(12), plain = nc.randomBytes(n), aad = nc.randomBytes(an);
  const c = nc.createCipheriv('chacha20-poly1305', key, nonce, { authTagLength: 16 }); c.setAAD(aad);
  const want = Buffer.concat([nonce, c.update(plain), c.final(), c.getAuthTag()]);
  check('aead ' + n + '/' + an, eq(api.sealWith(key, nonce, plain, aad), want));
}
{ const key = nc.randomBytes(32), box = api.seal(key, Buffer.from('hello'), 'pd3 x'); check('open', Buffer.from(api.open(key, box, 'pd3 x')).toString() === 'hello');
  let threw = 0; try { api.open(key, box, 'pd3 y'); } catch (e) { threw++; } box[14] ^= 1; try { api.open(key, box, 'pd3 x'); } catch (e) { threw++; } check('tamper', threw === 2); }
{ const key = nc.randomBytes(32), nonce = nc.randomBytes(12), plain = nc.randomBytes(64 << 20), t = Date.now(); api.sealWith(key, nonce, plain, new Uint8Array(0)); console.log('speed', (64000 / (Date.now() - t)).toFixed(0), 'MiB/s'); }
const text = 'phonedrop://web?v=3&k=' + api.b64u(nc.randomBytes(32));
fs.writeFileSync(process.argv[3], text + '\n' + api.qrMatrix(text).map(r => r.join('')).join('\n') + '\n');
console.log(fails ? fails + ' FAILED' : 'all crypto checks passed');
