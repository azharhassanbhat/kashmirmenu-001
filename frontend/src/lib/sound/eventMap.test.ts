import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  EVENT_RULES,
  RESTAURANT_STATUS_TYPES,
  roleHears,
  ruleFor,
  ruleForEvent,
  serviceKindFor,
  serviceKindOf,
  type EventInfo,
} from './eventMap';
import { SOUND_DEFS, SOUND_KINDS, themedNotes } from './soundDefs';

describe('event map', () => {
  const audible: [string, string, string][] = [
    ['ORDER_PLACED', 'NEW_ORDER', 'newOrder'],
    ['PAYMENT_SUCCESS', 'SUCCESS', 'payments'],
    ['PAYMENT_FAILED', 'WARNING', 'payments'],
    ['PAYMENT_REFUNDED', 'MESSAGE', 'payments'],
    ['ORDER_CANCELLED', 'ORDER_CANCELLED', 'cancellations'],
    ['FEEDBACK_RECEIVED', 'MESSAGE', 'messages'],
    ['SERVICE_REQUEST_CREATED', 'MESSAGE', 'messages'],
    ['SERVICE_REQUEST_UPDATED', 'MESSAGE', 'general'],
    ['ORDER_READY', 'ORDER_READY', 'orderReady'],
    ['RESTAURANT_APPROVED', 'SUCCESS', 'general'],
    ['RESTAURANT_REACTIVATED', 'SUCCESS', 'general'],
    ['RESTAURANT_REJECTED', 'CRITICAL', 'critical'],
    ['RESTAURANT_SUSPENDED', 'CRITICAL', 'critical'],
    ['PLATFORM_RESTAURANT_REGISTERED', 'MESSAGE', 'general'],
  ];

  it.each(audible)('%s plays %s under %s', (type, kind, group) => {
    const rule = ruleFor(type);
    expect(rule.sound).toBe(kind);
    expect(rule.group).toBe(group);
    expect(rule.silent).toBeFalsy();
    expect(SOUND_KINDS).toContain(rule.sound);
  });

  it.each(['ORDER_CONFIRMED', 'ORDER_PREPARING', 'ORDER_SERVED', 'COUPON_APPLIED'])('%s is visual only', (type) => {
    const rule = ruleFor(type);
    expect(rule.silent).toBe(true);
    expect(rule.sound).toBeNull();
  });

  it('treats unknown types as visual only', () => {
    for (const type of ['SOMETHING_NEW', '', 'constructor', '__proto__', 'toString']) {
      const rule = ruleFor(type);
      expect(rule.silent).toBe(true);
      expect(rule.sound).toBeNull();
    }
  });

  it('ranks a new order above everything and service requests above general sounds', () => {
    const priorities = audible.map(([type]) => ruleFor(type).priority);
    expect(ruleFor('ORDER_PLACED').priority).toBe(Math.max(...priorities));
    expect(ruleFor('SERVICE_REQUEST_CREATED').priority).toBeGreaterThan(ruleFor('SERVICE_REQUEST_UPDATED').priority);
    expect(ruleFor('SERVICE_REQUEST_CREATED').priority).toBeGreaterThan(ruleFor('ORDER_READY').priority);
  });

  it('styles failures as errors', () => {
    expect(ruleFor('PAYMENT_FAILED').tone).toBe('error');
    expect(ruleFor('RESTAURANT_SUSPENDED').tone).toBe('error');
  });

  it('lists the restaurant decision types', () => {
    expect(RESTAURANT_STATUS_TYPES).toEqual([
      'RESTAURANT_APPROVED',
      'RESTAURANT_REJECTED',
      'RESTAURANT_SUSPENDED',
      'RESTAURANT_REACTIVATED',
    ]);
  });

  // The backend is the source of the types; one added there without a decision here would silently be
  // treated as "visual only". Skipped where the backend source is not next to the frontend (a frontend-only build).
  const enumFile = resolve(process.cwd(), '../src/main/java/com/kashmirmenu/app/enums/NotificationType.java');
  it.skipIf(!existsSync(enumFile))('has a rule for every notification type the backend can send', () => {
    const source = readFileSync(enumFile, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const body = source.slice(source.indexOf('{') + 1, source.lastIndexOf('}'));
    const types = body.split(',').map((t) => t.trim()).filter((t) => /^[A-Z_]+$/.test(t));
    expect(types.length).toBeGreaterThan(10);
    for (const type of types) expect(Object.keys(EVENT_RULES), type).toContain(type);
  });

  describe('waiter call, bill and new order', () => {
    it('gives a new order, a waiter call and a bill request three different sounds', () => {
      const order = ruleFor('ORDER_PLACED').sound;
      const waiter = ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' }).sound;
      const bill = ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind: 'BILL_REQUEST' }).sound;
      expect(new Set([order, waiter, bill]).size).toBe(3);
      expect(waiter).toBe('WAITER_CALL');
      expect(bill).toBe('BILL_REQUEST');
    });

    it('keeps other table requests on the ordinary message ping', () => {
      expect(ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind: 'TABLE_REQUEST' }).sound).toBe('MESSAGE');
      expect(ruleForEvent('SERVICE_REQUEST_CREATED', {}).sound).toBe('MESSAGE');
    });

    it('recognises the request kind from the label prefix only', () => {
      expect(serviceKindOf('Call waiter - please hurry')).toBe('WAITER_CALL');
      expect(serviceKindOf('  call waiter')).toBe('WAITER_CALL');
      expect(serviceKindOf('Assistance')).toBe('WAITER_CALL');
      expect(serviceKindOf('Bring the bill')).toBe('BILL_REQUEST');
      expect(serviceKindOf('Other request - bring the bill')).toBe('OTHER');
    });

    it('decides by the structured kind, whatever the text says', () => {
      // A reworded (or translated) message must not change the sound.
      expect(serviceKindFor({ eventKind: 'WAITER_CALL', text: 'Someone needs you at table 4' })).toBe('WAITER_CALL');
      expect(serviceKindFor({ eventKind: 'BILL_REQUEST', text: 'Call waiter' })).toBe('BILL_REQUEST');
      expect(serviceKindFor({ eventKind: 'TABLE_REQUEST', text: 'Call waiter' })).toBe('OTHER');
    });

    it('falls back to the text for an older server, and for a kind this app does not know', () => {
      expect(serviceKindFor({ text: 'Call waiter' })).toBe('WAITER_CALL');
      expect(serviceKindFor({ eventKind: null, text: 'Bring the bill' })).toBe('BILL_REQUEST');
      expect(serviceKindFor({ eventKind: 'SOMETHING_NEW', text: 'Bring the bill' })).toBe('BILL_REQUEST');
      expect(serviceKindFor({ eventKind: '__proto__', text: 'hello' })).toBe('OTHER');
      expect(serviceKindFor(undefined)).toBe('OTHER');
      expect(ruleForEvent('SERVICE_REQUEST_CREATED', { text: 'Call waiter' }).sound).toBe('WAITER_CALL');
    });

    it('ranks a new order above a waiter call above a bill request', () => {
      const p = (type: string, info?: EventInfo) => ruleForEvent(type, info).priority;
      expect(p('ORDER_PLACED')).toBeGreaterThan(p('SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' }));
      expect(p('SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' })).toBeGreaterThan(p('SERVICE_REQUEST_CREATED', { eventKind: 'BILL_REQUEST' }));
    });
  });

  describe('every sound is distinct', () => {
    const signature = (kind: (typeof SOUND_KINDS)[number]) =>
      JSON.stringify(SOUND_DEFS[kind].notes.map((n) => [n.at, n.freq, n.wave]));

    it('has no two kinds with the same notes', () => {
      expect(new Set(SOUND_KINDS.map(signature)).size).toBe(SOUND_KINDS.length);
    });

    it('keeps every sound short enough for a notification', () => {
      for (const kind of SOUND_KINDS) expect(SOUND_DEFS[kind].durationMs, kind).toBeLessThanOrEqual(1100);
    });

    it('themes change pitch but never timing, and the default theme is untouched', () => {
      for (const kind of SOUND_KINDS) {
        expect(themedNotes(kind, 'bell')).toBe(SOUND_DEFS[kind].notes);
        for (const theme of ['crystal', 'warm'] as const) {
          const themed = themedNotes(kind, theme);
          expect(themed.map((n) => n.at)).toEqual(SOUND_DEFS[kind].notes.map((n) => n.at));
          expect(themed[0].freq).not.toBe(SOUND_DEFS[kind].notes[0].freq);
        }
      }
    });
  });

  describe('who hears what', () => {
    it('lets managers hear everything', () => {
      for (const role of ['ADMIN', 'RESTAURANT_OWNER', 'MANAGER'] as const) {
        expect(roleHears(role, 'ORDER_PLACED')).toBe(true);
        expect(roleHears(role, 'SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' })).toBe(true);
        expect(roleHears(role, 'PAYMENT_SUCCESS')).toBe(true);
      }
    });

    it('lets the kitchen hear orders and cancellations only', () => {
      expect(roleHears('CHEF', 'ORDER_PLACED')).toBe(true);
      expect(roleHears('CHEF', 'ORDER_CANCELLED')).toBe(true);
      expect(roleHears('CHEF', 'SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' })).toBe(false);
      expect(roleHears('CHEF', 'PAYMENT_SUCCESS')).toBe(false);
    });

    it('lets a waiter hear table requests and ready orders, not new orders', () => {
      expect(roleHears('WAITER', 'SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' })).toBe(true);
      expect(roleHears('WAITER', 'SERVICE_REQUEST_CREATED', { eventKind: 'BILL_REQUEST' })).toBe(true);
      expect(roleHears('WAITER', 'ORDER_READY')).toBe(true);
      expect(roleHears('WAITER', 'ORDER_PLACED')).toBe(false);
    });

    it('lets a cashier hear bills and payments only', () => {
      expect(roleHears('CASHIER', 'SERVICE_REQUEST_CREATED', { eventKind: 'BILL_REQUEST' })).toBe(true);
      expect(roleHears('CASHIER', 'SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' })).toBe(false);
      expect(roleHears('CASHIER', 'PAYMENT_FAILED')).toBe(true);
      expect(roleHears('CASHIER', 'ORDER_PLACED')).toBe(false);
    });

    it('lets every staff role hear a decision about their restaurant', () => {
      for (const role of ['CHEF', 'WAITER', 'CASHIER'] as const) {
        expect(roleHears(role, 'RESTAURANT_SUSPENDED')).toBe(true);
      }
    });

    it('lets a customer hear nothing on the staff feed', () => {
      expect(roleHears('CUSTOMER', 'ORDER_PLACED')).toBe(false);
    });
  });
});
