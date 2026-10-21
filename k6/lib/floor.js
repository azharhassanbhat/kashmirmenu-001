// The table-ordering journeys: a guest joining a table, ordering and paying, and the staff who confirm
// orders and payments and close tables. Kept apart from api.js so the older scripts are unchanged.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, jsonHeaders, authHeaders } from './config.js';
import { seedRestaurant } from './api.js';

/** Any 5xx at all. The thresholds require this to stay at zero: a lock problem shows up here first. */
export const serverErrors = new Counter('km_server_errors');
/** Guests whose table was closed under them mid-journey. Expected when staff close tables; not a failure. */
export const sessionsEnded = new Counter('km_sessions_ended');
/** Two guests tried to pay for the whole table and one was told it was already being paid. Expected. */
export const combinedConflicts = new Counter('km_combined_conflicts');
export const settlementsStarted = new Counter('km_settlements_started');
export const tablesClosed = new Counter('km_tables_closed');
/** Seconds from placing an order until staff confirmed it. */
export const confirmWait = new Trend('km_confirm_wait', true);
/** Seconds from starting a payment until staff confirmed it. */
export const payWait = new Trend('km_pay_wait', true);

const TOKEN_HEADER = 'X-Diner-Token';

function tagged(name, extra) {
  return { headers: { ...jsonHeaders, ...(extra || {}) }, tags: { name } };
}

/** Counts a response if it is a server error. Returns the response so calls can be chained. */
function watch(res) {
  if (res.status >= 500) serverErrors.add(1);
  return res;
}

/**
 * Registers a throwaway restaurant with several tables, so guests spread out the way they do in a real
 * dining room instead of all sitting at one.
 */
export function seedFloor(tableCount) {
  const shop = seedRestaurant('floor');
  if (!shop) return null;

  const auth = { headers: authHeaders(shop.token) };
  const tables = [shop.qr];
  for (let i = 2; i <= tableCount; i++) {
    const res = http.post(`${BASE_URL}/api/v1/tables`,
      JSON.stringify({ tableCode: `T${i}`, tableName: `Table ${i}`, capacity: 30 }),
      { ...auth, tags: { name: 'seed:table' } });
    if (res.status === 200 || res.status === 201) tables.push(res.json('qrIdentifier'));
  }
  return { token: shop.token, menuItemId: shop.menuItemId, tables };
}

/**
 * One guest's visit: join, order, wait for the kitchen, then pay (their own bill, or one in five
 * the whole table), wait for staff to confirm, and read the receipt.
 */
export function guestJourney(shop) {
  const qr = shop.tables[(__VU + __ITER) % shop.tables.length];
  const asGuest = (name, token) => tagged(name, { [TOKEN_HEADER]: token });
  const ended = (res) => {
    if (res.status === 404) {
      sessionsEnded.add(1);
      return true;
    }
    return false;
  };

  const join = watch(http.post(`${BASE_URL}/api/public/tables/${qr}/join`,
    JSON.stringify({ name: `Guest${__VU}x${__ITER}` }), tagged('floor:join')));
  if (!check(join, { 'joined the table': (r) => r.status === 201 })) return;
  const token = join.json('dinerToken');

  for (let i = 0, dishes = 1 + Math.floor(Math.random() * 3); i < dishes; i++) {
    const add = watch(http.post(`${BASE_URL}/api/public/tables/${qr}/cart/items`,
      JSON.stringify({ menuItemId: shop.menuItemId, quantity: 1 }), asGuest('floor:cart-add', token)));
    if (ended(add)) return;
    check(add, { 'dish added': (r) => r.status === 200 });
  }

  const placed = watch(http.post(`${BASE_URL}/api/public/tables/${qr}/orders`,
    JSON.stringify({ notes: 'k6 floor test' }), asGuest('floor:order-place', token)));
  if (ended(placed)) return;
  if (!check(placed, { 'order placed': (r) => r.status === 201 })) return;
  const orderedAt = Date.now();

  // Staff confirm orders on their own loop; the bill shows something payable once they have.
  let due = 0;
  for (let waited = 0; waited < Number(__ENV.CONFIRM_TIMEOUT_S || 30) && due <= 0; waited++) {
    sleep(1);
    const bill = watch(http.get(`${BASE_URL}/api/public/tables/${qr}/bill`, asGuest('floor:bill', token)));
    if (ended(bill)) return;
    if (bill.status === 200) due = bill.json('due');
  }
  if (!check(null, { 'order confirmed by staff in time': () => due > 0 })) return;
  confirmWait.add((Date.now() - orderedAt) / 1000);

  const tableBill = watch(http.get(`${BASE_URL}/api/public/tables/${qr}/bill/table`, asGuest('floor:table-bill', token)));
  if (ended(tableBill)) return;
  check(tableBill, { 'table bill read': (r) => r.status === 200 });

  const kind = Math.random() < 0.2 ? 'COMBINED' : 'SEPARATE';
  const key = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const settle = watch(http.post(`${BASE_URL}/api/public/tables/${qr}/settlements`,
    JSON.stringify({ kind, paymentMethod: 'CASH' }),
    tagged('floor:settle', { [TOKEN_HEADER]: token, 'Idempotency-Key': key })));
  if (ended(settle)) return;

  if (settle.status === 409) {
    // Someone else was already paying for what this would have covered. Correct, not a failure.
    combinedConflicts.add(1);
    return;
  }
  if (!check(settle, { 'payment started': (r) => r.status === 201 })) return;
  settlementsStarted.add(1);
  const code = settle.json('code');
  const startedAt = Date.now();

  let status = 'PENDING';
  let receipt = null;
  for (let waited = 0; waited < Number(__ENV.PAY_TIMEOUT_S || 60) && status === 'PENDING'; waited++) {
    sleep(1);
    const poll = watch(http.get(`${BASE_URL}/api/public/tables/${qr}/settlements/${code}`,
      asGuest('floor:settlement-poll', token)));
    if (ended(poll)) return;
    if (poll.status === 200) {
      status = poll.json('status');
      receipt = poll;
    }
  }
  if (!check(null, { 'payment confirmed by staff in time': () => status === 'SUCCESS' })) return;
  payWait.add((Date.now() - startedAt) / 1000);
  check(receipt, {
    'receipt says when it was confirmed': (r) => r.json('confirmedAt') !== null,
    'receipt has an amount': (r) => r.json('confirmed') > 0,
  });
}

/**
 * One pass of the floor staff: confirm orders, confirm payments, and every few passes close the tables
 * that are fully paid and have gone quiet. Run it on a couple of VUs, looping.
 */
export function staffPass(shop) {
  const auth = (name) => ({ headers: authHeaders(shop.token), tags: { name } });

  const orders = watch(http.get(`${BASE_URL}/api/v1/orders/status/PENDING?page=0&size=100`, auth('staff:pending-orders')));
  if (orders.status === 200) {
    for (const o of orders.json('items') || []) {
      watch(http.put(`${BASE_URL}/api/v1/orders/${o.id}/status?status=CONFIRMED`, null, auth('staff:confirm-order')));
    }
  }

  const payments = watch(http.get(`${BASE_URL}/api/v1/settlements/pending`, auth('staff:pending-payments')));
  if (payments.status === 200) {
    for (const p of payments.json() || []) {
      watch(http.put(`${BASE_URL}/api/v1/settlements/${p.id}/status`,
        JSON.stringify({ paymentStatus: 'SUCCESS', transactionId: 'k6' }), auth('staff:confirm-payment')));
    }
  }

  if (__ITER % 5 === 0) {
    const active = watch(http.get(`${BASE_URL}/api/v1/table-sessions/active`, auth('staff:live-tables')));
    if (active.status === 200) {
      for (const t of active.json() || []) {
        const quietFor = Date.now() - Date.parse(t.lastActivityAt);
        // Only a table that is paid up and has gone quiet: closing one under a guest mid-order is not what staff do.
        if (t.payState === 'PAID' && quietFor > Number(__ENV.CLOSE_AFTER_QUIET_MS || 15000)) {
          const closed = watch(http.post(`${BASE_URL}/api/v1/table-sessions/${t.id}/close`, null, auth('staff:close-table')));
          if (closed.status === 200) tablesClosed.add(1);
        }
      }
    }
  }
  sleep(1);
}
