// Usage: node --experimental-strip-types --import ./register.mjs run.mjs <file.test.ts>
import { pathToFileURL } from 'node:url';
import path from 'node:path';
const file = path.resolve(process.argv[2]);
const shim = await import('./vitest.mjs');
await import(pathToFileURL(file).href);
let passed = 0; const failures = [];
for (const t of shim.registry) {
  try { await t.fn(); passed++; } catch (e) { failures.push(`${t.name}: ${e.message}`); }
}
for (const f of failures) console.log('FAILED ' + f);
console.log(`RESULT ${passed} passed, ${failures.length} failed`);
process.exit(failures.length ? 1 : 0);
