// Shared configuration and safety guard for every k6 script.
//
// Everything is parameterised by environment variable so no URL, credential or workload is baked
// into the repository.

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/+$/, '');
/**
 * Whether ENVIRONMENT was set deliberately. The default below is only for display and for the smoke
 * test; the load guard requires an explicit value, so an unset variable cannot authorise anything.
 */
export const ENVIRONMENT_SET = Boolean(__ENV.ENVIRONMENT);
export const ENVIRONMENT = __ENV.ENVIRONMENT || 'local';

/** Environments where load beyond a smoke test is allowed to run. */
const LOAD_SAFE = ['local', 'dev', 'development', 'test', 'staging', 'perf'];

/**
 * Refuses to run anything heavier than a smoke test outside a known test environment.
 *
 * The default outcome of a careless `k6 run` against a production URL must be a refusal, not a
 * stress test. ENVIRONMENT has to be set deliberately to a recognised non-production value, so
 * forgetting it fails closed rather than open.
 *
 * @param {string} testName  used only in the message
 */
export function assertLoadTestingAllowed(testName) {
  // An unset ENVIRONMENT must refuse. Falling back to 'local' here would mean that forgetting the
  // variable authorises a load test against whatever BASE_URL happens to point at.
  if (!ENVIRONMENT_SET) {
    throw new Error(
      `Refusing to run "${testName}": ENVIRONMENT is not set.\n` +
        `Set it explicitly to one of: ${LOAD_SAFE.join(', ')} — and only if you are certain ` +
        `BASE_URL (${BASE_URL}) is not production.`,
    );
  }
  if (!LOAD_SAFE.includes(ENVIRONMENT.toLowerCase())) {
    throw new Error(
      `Refusing to run "${testName}" with ENVIRONMENT="${ENVIRONMENT}".\n` +
        `Load, stress, spike and soak tests may only run against an authorised test environment.\n` +
        `Set ENVIRONMENT to one of: ${LOAD_SAFE.join(', ')} — and only if you are certain ` +
        `BASE_URL (${BASE_URL}) is not production.`,
    );
  }
  if (/\b(prod|production)\b/i.test(BASE_URL)) {
    throw new Error(
      `Refusing to run "${testName}": BASE_URL (${BASE_URL}) looks like production.`,
    );
  }
}

/**
 * Provisional thresholds.
 *
 * These are starting hypotheses, not measured SLOs: the application's real latency profile is
 * unknown until a baseline run has been done against representative data. Recalibrate from the
 * observed P95 after the first baseline — see PERFORMANCE_TESTING_GUIDE.md. Override any of them
 * from the environment rather than editing this file.
 */
export const THRESHOLDS = {
  // Reads a diner waits on. Above this the QR experience starts to feel slow.
  menuRead: __ENV.THRESHOLD_MENU_MS || 400,
  // A write plus a cart recalculation, over a phone network.
  cartWrite: __ENV.THRESHOLD_CART_MS || 600,
  // The heaviest write in the application: locks the order, the number counter, stock and the coupon.
  orderPlace: __ENV.THRESHOLD_ORDER_MS || 1200,
  // Aggregates over a date range; a manager at a desk tolerates more than a diner standing up.
  staffRead: __ENV.THRESHOLD_STAFF_MS || 1500,
  errorRate: __ENV.THRESHOLD_ERROR_RATE || 0.01,
};

export function thresholdsFor(scenarioThresholds) {
  return {
    http_req_failed: [`rate<${THRESHOLDS.errorRate}`],
    ...scenarioThresholds,
  };
}

export const jsonHeaders = { 'Content-Type': 'application/json' };

export function authHeaders(token) {
  return { ...jsonHeaders, Authorization: `Bearer ${token}` };
}
