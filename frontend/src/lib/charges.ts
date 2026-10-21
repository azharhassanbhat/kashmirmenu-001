/*
 * How a bill reads, and an owner-facing preview of how a rate will behave. The server works out every
 * real amount (and rounds half up to 2 places); the preview below repeats its rules only so the
 * settings screen can show an example, and it is labelled as an example there.
 */

export interface ChargeSource {
  subtotal: number;
  discount: number;
  couponCode?: string | null;
  serviceCharge?: number;
  serviceChargeRate?: number;
  tax?: number;
  taxName?: string | null;
  taxRate?: number;
  taxIncluded?: number;
  pricesIncludeTax?: boolean;
  total: number;
}

export interface BillRow {
  key: string;
  label: string;
  /** Always positive; `kind` says whether it is taken off or added on. */
  amount: number;
  kind: 'item' | 'discount' | 'charge';
}

/** "5%", "7.5%", "12.25%": a rate without trailing zeros. */
export function formatRate(rate: number): string {
  return `${Number(rate.toFixed(2))}%`;
}

/**
 * The rows between the items and the total. A row appears only when there is something to show, so a
 * restaurant that charges no tax or service charge shows a bill of just items and total, as before.
 */
export function chargeRows(b: ChargeSource, itemsLabel: string): BillRow[] {
  const rows: BillRow[] = [{ key: 'items', label: itemsLabel, amount: b.subtotal, kind: 'item' }];

  if (b.discount > 0) {
    rows.push({
      key: 'discount',
      label: `Discount${b.couponCode ? ` (${b.couponCode})` : ''}`,
      amount: b.discount,
      kind: 'discount',
    });
  }
  if ((b.serviceCharge ?? 0) > 0) {
    const rate = b.serviceChargeRate ? ` (${formatRate(b.serviceChargeRate)})` : '';
    rows.push({ key: 'service', label: `Service charge${rate}`, amount: b.serviceCharge!, kind: 'charge' });
  }
  if ((b.tax ?? 0) > 0) {
    const rate = b.taxRate ? ` (${formatRate(b.taxRate)})` : '';
    rows.push({ key: 'tax', label: `${b.taxName?.trim() || 'Tax'}${rate}`, amount: b.tax!, kind: 'charge' });
  }
  return rows;
}

/** "Includes GST of ₹38.10" for inclusive pricing; null when no tax is inside the prices. */
export function includedTaxNote(b: ChargeSource, format: (n: number) => string): string | null {
  if (!b.pricesIncludeTax || !((b.taxIncluded ?? 0) > 0)) return null;
  return `Includes ${b.taxName?.trim() || 'tax'} of ${format(b.taxIncluded!)}`;
}

const roundHalfUp = (n: number) => Math.floor(n * 100 + 0.5 + 1e-9) / 100;

export interface Preview {
  net: number;
  serviceCharge: number;
  tax: number;
  taxIncluded: number;
  total: number;
}

/** The server's rules, for the owner's example: discount first, then service charge, then tax. */
export function previewCharges(
  subtotal: number,
  discount: number,
  serviceRate: number,
  taxRate: number,
  pricesIncludeTax: boolean,
): Preview {
  const net = Math.max(0, roundHalfUp(subtotal - discount));
  const serviceCharge = roundHalfUp((net * serviceRate) / 100);
  let tax = 0;
  let taxIncluded = 0;
  if (pricesIncludeTax) {
    taxIncluded = roundHalfUp((net * taxRate) / (100 + taxRate));
  } else {
    tax = roundHalfUp(((net + serviceCharge) * taxRate) / 100);
  }
  return { net, serviceCharge, tax, taxIncluded, total: roundHalfUp(net + serviceCharge + tax) };
}
