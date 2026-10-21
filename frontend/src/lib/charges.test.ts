import { describe, expect, it } from 'vitest';
import { chargeRows, formatRate, includedTaxNote, previewCharges } from './charges';

const base = { subtotal: 800, discount: 0, total: 800 };

describe('bill rows', () => {
  it('shows only items when the restaurant charges nothing extra, as before', () => {
    expect(chargeRows(base, 'Items (2)').map((r) => r.key)).toEqual(['items']);
  });

  it('adds a discount row with the coupon code', () => {
    const rows = chargeRows({ ...base, discount: 80, couponCode: 'TEN' }, 'Items (2)');
    expect(rows.map((r) => r.label)).toEqual(['Items (2)', 'Discount (TEN)']);
    expect(rows[1].kind).toBe('discount');
  });

  it('names the tax and shows both rates', () => {
    const rows = chargeRows(
      { ...base, serviceCharge: 80, serviceChargeRate: 10, tax: 44, taxName: 'GST', taxRate: 5, total: 924 },
      'Items',
    );
    expect(rows.map((r) => r.label)).toEqual(['Items', 'Service charge (10%)', 'GST (5%)']);
    expect(rows.slice(1).every((r) => r.kind === 'charge')).toBe(true);
  });

  it('falls back to a plain word when the tax has no name', () => {
    expect(chargeRows({ ...base, tax: 40, taxName: ' ', taxRate: 5 }, 'Items')[1].label).toBe('Tax (5%)');
  });

  it('hides a row whose amount is zero', () => {
    const rows = chargeRows({ ...base, serviceCharge: 0, tax: 0, taxRate: 5 }, 'Items');
    expect(rows).toHaveLength(1);
  });

  it('writes rates without trailing zeros', () => {
    expect(formatRate(5)).toBe('5%');
    expect(formatRate(7.5)).toBe('7.5%');
    expect(formatRate(12.25)).toBe('12.25%');
  });

  it('says how much tax is inside inclusive prices, and nothing otherwise', () => {
    const f = (n: number) => `Rs ${n}`;
    expect(includedTaxNote({ ...base, pricesIncludeTax: true, taxIncluded: 38.1, taxName: 'GST' }, f)).toBe(
      'Includes GST of Rs 38.1',
    );
    expect(includedTaxNote({ ...base, pricesIncludeTax: false, taxIncluded: 38.1 }, f)).toBeNull();
    expect(includedTaxNote({ ...base, pricesIncludeTax: true, taxIncluded: 0 }, f)).toBeNull();
  });
});

describe('owner preview matches the server rules', () => {
  it('exclusive: service charge first, tax on both', () => {
    const p = previewCharges(1000, 0, 10, 5, false);
    expect([p.serviceCharge, p.tax, p.total]).toEqual([100, 55, 1155]);
  });

  it('a discount comes off before anything is worked out', () => {
    const p = previewCharges(1000, 200, 10, 5, false);
    expect([p.net, p.serviceCharge, p.tax, p.total]).toEqual([800, 80, 44, 924]);
  });

  it('inclusive: nothing added, tax shown inside', () => {
    const p = previewCharges(1050, 0, 0, 5, true);
    expect([p.total, p.tax, p.taxIncluded]).toEqual([1050, 0, 50]);
  });

  it('rounds half up like the server', () => {
    expect(previewCharges(333.33, 0, 0, 5, false).tax).toBe(16.67);
  });

  it('never goes below zero', () => {
    expect(previewCharges(100, 150, 10, 5, false).total).toBe(0);
  });

  it('with no rates the total is just the items less the discount', () => {
    expect(previewCharges(900, 100, 0, 0, false).total).toBe(800);
  });
});
