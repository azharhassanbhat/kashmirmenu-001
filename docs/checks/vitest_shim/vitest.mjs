// A small stand-in for vitest: describe / it / test / expect with the matchers the project's pure tests use.
import { isDeepStrictEqual } from 'node:util';

export const registry = [];
let prefix = [];
export function describe(name, fn) { prefix.push(name); try { fn(); } finally { prefix.pop(); } }
export function it(name, fn) { registry.push({ name: [...prefix, name].join(' > '), fn }); }
export const test = it;
export function beforeEach() {}
export function afterEach() {}

const show = (v) => { try { return typeof v === 'string' ? JSON.stringify(v) : JSON.stringify(v) ?? String(v); } catch { return String(v); } };
const norm = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

function matchers(actual, negate) {
  const check = (pass, msg) => {
    if (negate ? pass : !pass) throw new Error((negate ? 'NOT ' : '') + msg);
  };
  return {
    toBe: (e) => check(Object.is(actual, e), `expected ${show(actual)} to be ${show(e)}`),
    toEqual: (e) => check(isDeepStrictEqual(actual, e) || isDeepStrictEqual(norm(actual), norm(e)),
      `expected ${show(actual)} to equal ${show(e)}`),
    toBeNull: () => check(actual === null, `expected ${show(actual)} to be null`),
    toBeUndefined: () => check(actual === undefined, `expected ${show(actual)} to be undefined`),
    toMatch: (re) => check(typeof actual === 'string' && (re instanceof RegExp ? re.test(actual) : actual.includes(re)),
      `expected ${show(actual)} to match ${String(re)}`),
    toHaveLength: (n) => check(actual != null && actual.length === n, `expected length ${actual?.length} to be ${n}`),
    toContain: (x) => check(actual != null && (typeof actual === 'string' ? actual.includes(x) : [...actual].includes(x)),
      `expected ${show(actual)} to contain ${show(x)}`),
    toBeLessThan: (n) => check(actual < n, `expected ${actual} < ${n}`),
    toBeLessThanOrEqual: (n) => check(actual <= n, `expected ${actual} <= ${n}`),
    toBeTruthy: () => check(Boolean(actual), `expected ${show(actual)} to be truthy`),
    toBeFalsy: () => check(!actual, `expected ${show(actual)} to be falsy`),
    toBeDefined: () => check(actual !== undefined, `expected a defined value`),
    toBeNaN: () => check(Number.isNaN(actual), `expected ${show(actual)} to be NaN`),
    toStrictEqual: (e) => check(isDeepStrictEqual(actual, e), `expected ${show(actual)} to strictly equal ${show(e)}`),
    toHaveProperty: (key) => check(actual != null && Object.prototype.hasOwnProperty.call(actual, key), `expected property ${String(key)}`),
    toBeGreaterThan: (n) => check(actual > n, `expected ${actual} > ${n}`),
    toBeGreaterThanOrEqual: (n) => check(actual >= n, `expected ${actual} >= ${n}`),
    toBeCloseTo: (n, digits = 2) => check(Math.abs(actual - n) < Math.pow(10, -digits) / 2, `expected ${actual} to be close to ${n}`),
    toBeInstanceOf: (c) => check(actual instanceof c, `expected ${show(actual)} to be an instance of ${c?.name}`),
    toThrow: (arg) => {
      let threw = false, error;
      try { actual(); } catch (e) { threw = true; error = e; }
      let pass = threw;
      if (threw && arg !== undefined) {
        const m = String(error?.message ?? error);
        pass = arg instanceof RegExp ? arg.test(m) : typeof arg === 'string' ? m.includes(arg) : error instanceof arg;
      }
      check(pass, `expected function to throw${arg !== undefined ? ' ' + String(arg) : ''}`);
    },
  };
}
export function expect(actual) {
  return { ...matchers(actual, false), not: matchers(actual, true) };
}
