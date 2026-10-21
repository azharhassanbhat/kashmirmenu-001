import { describe, expect, it } from 'vitest';
import { guestStatusText, isOpenRequest, nextStep, REQUEST_CHOICES, waitText } from './serviceRequests';

describe('service request helpers', () => {
  it('walks staff through the lifecycle one step at a time', () => {
    expect(nextStep('PENDING')).toEqual({ to: 'ACKNOWLEDGED', label: 'Acknowledge' });
    expect(nextStep('ACKNOWLEDGED')?.to).toBe('IN_PROGRESS');
    expect(nextStep('IN_PROGRESS')?.to).toBe('COMPLETED');
  });

  it('offers no next step once a request is finished', () => {
    expect(nextStep('COMPLETED')).toBeNull();
    expect(nextStep('CANCELLED')).toBeNull();
  });

  it('knows which statuses are still open', () => {
    expect(isOpenRequest('PENDING')).toBe(true);
    expect(isOpenRequest('IN_PROGRESS')).toBe(true);
    expect(isOpenRequest('COMPLETED')).toBe(false);
    expect(isOpenRequest('CANCELLED')).toBe(false);
  });

  it('never tells a guest more than the server recorded', () => {
    expect(guestStatusText('PENDING')).toMatch(/waiting/i);
    expect(guestStatusText('PENDING')).not.toMatch(/on its way|done/i);
  });

  it('formats waits', () => {
    expect(waitText(20)).toBe('under 1 min');
    expect(waitText(5 * 60 + 10)).toBe('5 min');
    expect(waitText(65 * 60)).toBe('1h 05m');
    expect(waitText(-5)).toBe('under 1 min');
  });

  it('offers every type except the free-text one as a quick button', () => {
    const types = REQUEST_CHOICES.map((c) => c.type);
    expect(types).toContain('CALL_WAITER');
    expect(types).toContain('REQUEST_BILL');
    expect(types).not.toContain('OTHER');
    expect(new Set(types).size).toBe(types.length);
  });
});
