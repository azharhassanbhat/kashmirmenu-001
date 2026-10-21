// The floor under load: many tables, guests joining, ordering and paying at once, with staff confirming
// orders and payments and closing tables behind them.
//
// This is the scenario the table-ordering flow was built for, and the one that exercises its locking:
// guests at one table ordering together, rival payers, orders and payments overlapping with staff reads
// and closes. Run it against a real MySQL (never H2, never production).
//
// What it asserts, beyond latency: NO server error at all (km_server_errors must stay at zero, because a
// lock or isolation problem surfaces as a 5xx), and that guests' orders get confirmed and paid in time.
// A 409 from two guests racing to pay for a table is correct and is not counted as a failure.
//
//   ENVIRONMENT=staging BASE_URL=https://staging.example.com LOAD_VUS=60 k6 run k6/tables.js
//   (k6 2.x treats K6_VUS and K6_DURATION as its own settings and then ignores the scenarios below, so use
//   LOAD_VUS and LOAD_DURATION; the K6_ names still work on older k6.)
//
// A visit admits TABLE_SESSION_MAX_DINERS guests (default 30). The staff loop closes paid-up tables so
// visits turn over, but for a long or heavy run raise the cap on the server under test, or joins will
// start being refused by design.
import http from 'k6/http';
import { sleep } from 'k6';
import { assertLoadTestingAllowed, thresholdsFor, THRESHOLDS } from './lib/config.js';
import { seedFloor, guestJourney, staffPass } from './lib/floor.js';

// A refusal because two guests raced to pay for a table is the system working; it must not count as a failed request.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 404, 409));

const GUESTS = Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 30);
const HOLD = (__ENV.LOAD_DURATION || __ENV.K6_DURATION) || '3m';

export const options = {
  scenarios: {
    guests: {
      executor: 'ramping-vus',
      exec: 'guests',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '30s', target: GUESTS },
        { duration: HOLD, target: GUESTS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '60s',
    },
    staff: {
      executor: 'constant-vus',
      exec: 'staff',
      vus: Number(__ENV.STAFF_VUS || 2),
      // Must cover the guests' whole run (ramp up, hold, ramp down) plus a little.
      duration: __ENV.STAFF_DURATION || '5m',
    },
  },
  thresholds: thresholdsFor({
    // The one that matters most: a lock or isolation problem is a 5xx.
    km_server_errors: ['count==0'],
    checks: ['rate>0.98'],
    'http_req_duration{name:floor:join}': [`p(95)<${THRESHOLDS.cartWrite}`],
    'http_req_duration{name:floor:cart-add}': [`p(95)<${THRESHOLDS.cartWrite}`],
    'http_req_duration{name:floor:order-place}': [`p(95)<${THRESHOLDS.orderPlace}`],
    'http_req_duration{name:floor:settle}': [`p(95)<${THRESHOLDS.orderPlace}`],
    'http_req_duration{name:staff:live-tables}': [`p(95)<${THRESHOLDS.staffRead}`],
    km_confirm_wait: [`p(95)<${__ENV.THRESHOLD_CONFIRM_WAIT_S || 15}`],
    km_pay_wait: [`p(95)<${__ENV.THRESHOLD_PAY_WAIT_S || 15}`],
  }),
};

export function setup() {
  assertLoadTestingAllowed('tables');
  return seedFloor(Number(__ENV.TABLES || 12));
}

export function guests(shop) {
  if (!shop) return;
  guestJourney(shop);
  sleep(Number(__ENV.THINK_TIME || 2));
}

export function staff(shop) {
  if (!shop) return;
  staffPass(shop);
}
