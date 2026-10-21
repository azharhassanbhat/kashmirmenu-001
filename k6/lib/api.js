// Thin helpers over the application's API, so the scenario scripts read as journeys rather than
// as a wall of http.post calls.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, jsonHeaders, authHeaders } from './config.js';

/**
 * Registers a throwaway restaurant with an owner, a category, a dish and a table.
 *
 * Tests create their own data and never touch a real account. The email is unique per call so
 * parallel VUs do not collide on the unique-email constraint.
 */
export function seedRestaurant(suffix) {
  const id = `k6-${suffix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

  const register = http.post(
    `${BASE_URL}/api/v1/auth/register-owner`,
    JSON.stringify({
      restaurantName: `Load Test ${id}`,
      restaurantEmail: `r-${id}@k6.invalid`,
      restaurantPhone: '9999999999',
      ownerName: 'Load Test Owner',
      ownerEmail: `owner-${id}@k6.invalid`,
      password: 'LoadTest123!',
    }),
    { headers: jsonHeaders, tags: { name: 'seed:register-owner' } },
  );
  if (register.status !== 200 && register.status !== 201) {
    return null;
  }
  const token = register.json('accessToken');
  const auth = { headers: authHeaders(token) };

  const category = http.post(`${BASE_URL}/api/categories`,
    JSON.stringify({ name: 'Mains' }), { ...auth, tags: { name: 'seed:category' } });
  const categoryId = category.json('data.id');

  const item = http.post(`${BASE_URL}/api/menu-items`,
    JSON.stringify({ name: `Dish ${id}`, price: 250.0, categoryId }),
    { ...auth, tags: { name: 'seed:menu-item' } });

  const table = http.post(`${BASE_URL}/api/v1/tables`,
    JSON.stringify({ tableCode: 'T1', tableName: 'Table 1', capacity: 4 }),
    { ...auth, tags: { name: 'seed:table' } });

  // The registration response carries no restaurant id (it used to be read from there, which produced
  // /restaurants//payment-settings, a 401, and a restaurant with no payment settings at all, so every
  // payment in the load test was refused). The id comes from /me. UPI stays off: it now needs a UPI ID
  // and an uploaded QR, which a throwaway restaurant does not have.
  const me = http.get(`${BASE_URL}/api/v1/me`, { ...auth, tags: { name: 'seed:me' } });
  http.post(`${BASE_URL}/api/v1/restaurants/${me.json('restaurantId')}/payment-settings`,
    JSON.stringify({ cashEnabled: true, upiEnabled: false, cardEnabled: true, walletEnabled: true }),
    { ...auth, tags: { name: 'seed:payment-settings' } });

  return {
    token,
    ownerEmail: `owner-${id}@k6.invalid`,
    password: 'LoadTest123!',
    menuItemId: item.json('data.id'),
    qr: table.json('qrIdentifier'),
  };
}

export function login(email, password) {
  const res = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: jsonHeaders, tags: { name: 'auth:login' } });
  check(res, { 'login succeeded': (r) => r.status === 200 });
  return res.status === 200 ? res.json('accessToken') : null;
}

/**
 * The diner journey: join the table, read the menu, build a cart, place an order, then track it.
 * Every virtual user joins as a new guest, so each has their own cart, as real diners do.
 *
 * A table visit admits at most TABLE_SESSION_MAX_DINERS guests (default 30). Load tests that send
 * many virtual users to few tables must raise it on the server under test, or joins past the cap
 * fail by design.
 */
export function dinerJourney(qr, menuItemId) {
  const tagged = (name) => ({ headers: jsonHeaders, tags: { name } });

  const table = http.get(`${BASE_URL}/api/public/tables/${qr}`, tagged('public:table'));
  check(table, { 'table resolved': (r) => r.status === 200 });

  const menu = http.get(`${BASE_URL}/api/public/tables/${qr}/menu`, tagged('public:menu'));
  check(menu, { 'menu loaded': (r) => r.status === 200 });

  const join = http.post(`${BASE_URL}/api/public/tables/${qr}/join`,
    JSON.stringify({ name: `Guest ${__VU}` }), tagged('public:join'));
  // A failure here under sustained load is usually the visit's guest cap, not a fault: see PERFORMANCE_TESTING_GUIDE.md.
  const joined = check(join, { 'table joined (if this fails under load, raise TABLE_SESSION_MAX_DINERS)': (r) => r.status === 201 });
  if (!joined) return join;

  // Cart and order calls act as the guest who just joined.
  const asGuest = (name) => ({
    headers: { ...jsonHeaders, 'X-Diner-Token': join.json('dinerToken') },
    tags: { name },
  });

  const addItem = http.post(`${BASE_URL}/api/public/tables/${qr}/cart/items`,
    JSON.stringify({ menuItemId, quantity: 2 }), asGuest('public:cart-add'));
  check(addItem, { 'item added': (r) => r.status === 200 });

  const order = http.post(`${BASE_URL}/api/public/tables/${qr}/orders`,
    JSON.stringify({ notes: 'k6 load test' }), asGuest('public:order-place'));
  const placed = check(order, { 'order placed': (r) => r.status === 200 || r.status === 201 });

  if (placed) {
    // Tracking is keyed by the order's public token, never by its sequential number.
    const token = order.json('publicToken');
    const track = http.get(`${BASE_URL}/api/public/orders/by-token/${token}`,
      tagged('public:order-track'));
    check(track, { 'order tracked': (r) => r.status === 200 });
  }
  return order;
}

/** The staff journey: look at the kitchen board and the day's orders. */
export function staffJourney(token) {
  const auth = { headers: authHeaders(token) };

  const active = http.get(`${BASE_URL}/api/v1/kitchen/orders/active`,
    { ...auth, tags: { name: 'staff:kitchen-active' } });
  check(active, { 'kitchen board loaded': (r) => r.status === 200 });

  const dashboard = http.get(`${BASE_URL}/api/v1/dashboard/summary`,
    { ...auth, tags: { name: 'staff:dashboard' } });
  check(dashboard, { 'dashboard loaded': (r) => r.status === 200 });

  const analytics = http.get(`${BASE_URL}/api/v1/payment-analytics/overview?period=LAST_7_DAYS`,
    { ...auth, tags: { name: 'staff:payment-analytics' } });
  check(analytics, { 'analytics loaded': (r) => r.status === 200 });
}
