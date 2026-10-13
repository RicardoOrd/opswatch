#!/usr/bin/env node
// Regenerates src/main/resources/identity/common-passwords.txt, the list of common passwords that
// registration and password changes reject (OW-039, docs/security/security-architecture.md#contraseñas).
//
// Usage (from the repository root):
//   node scripts/common-passwords.mjs
//
// Source: the top 1,000,000 of the xato.net list of 10 million leaked passwords, from SecLists (MIT),
// pinned by commit and checked against its SHA-256. Keeps only the entries that PasswordRules accepts
// (12 code points to 72 bytes in UTF-8): shorter ones are already rejected by the length rule.
// Lowercases them (the application compares in lowercase), removes duplicates and sorts them, so the
// same commit always gives the same file. Changing the source means updating common-passwords.NOTICE.
import crypto from 'node:crypto';
import fs from 'node:fs';

const COMMIT = '97e8798de8031310faa8093e3c72391f784707e7';
const PATH = 'Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt';
const SHA256 = '424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51';
const OUTPUT = 'src/main/resources/identity/common-passwords.txt';

// Same limits as PasswordRules (identity.domain)
const MIN_CHARACTERS = 12;
const MAX_UTF8_BYTES = 72;

const url = `https://raw.githubusercontent.com/danielmiessler/SecLists/${COMMIT}/${PATH}`;
const response = await fetch(url);
if (!response.ok) {
  throw new Error(`${url} answered ${response.status}`);
}
const source = Buffer.from(await response.arrayBuffer());
const digest = crypto.createHash('sha256').update(source).digest('hex');
if (digest !== SHA256) {
  throw new Error(`Unexpected SHA-256 of ${PATH}: ${digest}`);
}

const accepted = (password) =>
  [...password].length >= MIN_CHARACTERS && Buffer.byteLength(password, 'utf8') <= MAX_UTF8_BYTES;

const passwords = new Set();
for (const line of source.toString('utf8').split('\n')) {
  const password = line.endsWith('\r') ? line.slice(0, -1) : line;
  if (accepted(password)) {
    passwords.add(password.toLowerCase());
  }
}
// Code unit order: the same in every locale
const sorted = [...passwords].sort();
fs.writeFileSync(OUTPUT, sorted.join('\n') + '\n');
console.log(`${OUTPUT}: ${sorted.length} passwords, ${fs.statSync(OUTPUT).size} bytes`);
