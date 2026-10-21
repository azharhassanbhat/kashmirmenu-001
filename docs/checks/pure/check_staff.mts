import { assignableRoles, generatePassword, passwordProblem, roleLabel } from '../../../frontend/src/lib/staff.ts';
import assert from 'node:assert/strict';
assert.deepEqual(assignableRoles('RESTAURANT_OWNER'),['MANAGER','CHEF','WAITER','CASHIER']); assert.deepEqual(assignableRoles('ADMIN'),['MANAGER','CHEF','WAITER','CASHIER']); assert.deepEqual(assignableRoles('MANAGER'),['CHEF','WAITER','CASHIER']);
for (const r of ['CHEF','WAITER','CASHIER','CUSTOMER','PLATFORM_OWNER'] as const) assert.deepEqual(assignableRoles(r),[]); assert.deepEqual(assignableRoles(undefined),[]);
for (const a of ['RESTAURANT_OWNER','ADMIN','MANAGER'] as const) for (const f of ['RESTAURANT_OWNER','ADMIN','PLATFORM_OWNER','CUSTOMER']) assert.ok(!(assignableRoles(a) as string[]).includes(f));
assert.equal(passwordProblem('Waiter2026','w@x.com','Sam'),null); assert.match(passwordProblem('','w@x.com','Sam')!,/enter a password/i); assert.match(passwordProblem('abc12','w@x.com','Sam')!,/8/); assert.match(passwordProblem('12345678','w@x.com','Sam')!,/letter/); assert.match(passwordProblem('abcdefgh','w@x.com','Sam')!,/number/); assert.match(passwordProblem('waiter12','waiter12@x.com','Sam')!,/email/); assert.match(passwordProblem('sam12345','w@x.com','Sam12345')!,/name/);
for (let i=0;i<500;i++){ const p=generatePassword(); assert.equal(p.length,12); assert.equal(passwordProblem(p,'a@b.com','Name'),null); assert.ok(!/[0O1lI]/.test(p)); }
assert.equal(new Set(Array.from({length:50},()=>generatePassword())).size,50);
assert.equal(roleLabel('RESTAURANT_OWNER' as any),'Owner'); assert.equal(roleLabel('CASHIER' as any),'Cashier');
console.log('STAFF HELPERS PASSED (500 generated passwords all valid)');
