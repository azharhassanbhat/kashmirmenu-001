import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { couponApi } from '../../api';
import type { Coupon, DiscountType } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { EmptyState, ErrorMessage, Field, Modal, QueryState } from '../../components/ui';
import { currencySymbol, money, toLocalDateTime, toLocalInput } from '../../lib/format';

export default function CouponsPage() {
  const { user } = useAuth();
  const restaurantId = user?.restaurantId ?? null;
  const queryClient = useQueryClient();
  const coupons = useQuery({
    queryKey: ['coupons', restaurantId],
    queryFn: () => couponApi.list(restaurantId!),
    enabled: restaurantId !== null,
  });
  const [editing, setEditing] = useState<Coupon | 'new' | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['coupons'] });
  const remove = useMutation({ mutationFn: (c: Coupon) => couponApi.remove(c.id), onSuccess: refresh });

  if (restaurantId === null) return <div className="alert alert-info">Coupons belong to a restaurant account.</div>;

  return (
    <>
      <div className="page-head">
        <h2>Coupons</h2>
        <button className="btn btn-primary" onClick={() => setEditing('new')}>
          Add coupon
        </button>
      </div>
      <div className="alert alert-info">
        Diners enter these codes on their bill before ordering. A code is only counted as used when an order
        is placed, and a cancelled order gives the use back.
      </div>
      <ErrorMessage error={remove.error} />
      <QueryState isLoading={coupons.isLoading} error={coupons.error}>
        {coupons.data?.length === 0 ? (
          <EmptyState>No coupons yet.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Discount</th>
                  <th>Valid</th>
                  <th className="num">Used</th>
                  <th>Active</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {coupons.data?.map((c) => (
                  <tr key={c.id}>
                    <td data-label="Code">
                      <strong>{c.code}</strong>
                      <div className="muted">{c.name}</div>
                    </td>
                    <td data-label="Discount">
                      {c.discountType === 'PERCENTAGE' ? `${c.discountValue}%` : money(c.discountValue)}
                      {c.minimumOrderAmount > 0 && <div className="muted">min {money(c.minimumOrderAmount)}</div>}
                      {c.minimumItems > 0 && (
                        <div className="muted">
                          min {c.minimumItems} {c.minimumItems === 1 ? 'dish' : 'dishes'}
                        </div>
                      )}
                    </td>
                    <td className="muted" data-label="Valid">
                      {new Date(c.startDate).toLocaleDateString('en-IN')} – {new Date(c.expiryDate).toLocaleDateString('en-IN')}
                    </td>
                    <td className="num" data-label="Used">
                      {c.usedCount}/{c.usageLimit}
                    </td>
                    <td data-label="Active">
                      {c.active ? (
                        <span className="badge badge-green">Active</span>
                      ) : (
                        <span className="badge badge-gray">Off</span>
                      )}
                    </td>
                    <td className="num">
                      <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                        <button className="btn btn-sm" onClick={() => setEditing(c)}>
                          Edit
                        </button>
                        <button
                          className="btn btn-sm btn-danger"
                          disabled={remove.isPending}
                          onClick={() => window.confirm(`Delete coupon ${c.code}?`) && remove.mutate(c)}
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>

      {editing && (
        <CouponForm
          coupon={editing === 'new' ? null : editing}
          restaurantId={restaurantId}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void refresh();
          }}
        />
      )}
    </>
  );
}

function CouponForm({
  coupon,
  restaurantId,
  onClose,
  onSaved,
}: {
  coupon: Coupon | null;
  restaurantId: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState({
    code: coupon?.code ?? '',
    name: coupon?.name ?? '',
    description: coupon?.description ?? '',
    discountType: (coupon?.discountType ?? 'PERCENTAGE') as DiscountType,
    discountValue: String(coupon?.discountValue ?? ''),
    minimumOrderAmount: String(coupon?.minimumOrderAmount ?? 0),
    maximumDiscount: coupon?.maximumDiscount != null ? String(coupon.maximumDiscount) : '',
    minimumItems: String(coupon?.minimumItems ?? 0),
    usageLimit: String(coupon?.usageLimit ?? 100),
    startDate: toLocalInput(coupon?.startDate),
    expiryDate: toLocalInput(coupon?.expiryDate),
    active: coupon?.active ?? true,
  });

  const percentage = form.discountType === 'PERCENTAGE';
  const symbol = currencySymbol();
  const summary = describe(form.code, form.discountType, form.discountValue, form.minimumOrderAmount, form.maximumDiscount, form.minimumItems);

  const save = useMutation({
    mutationFn: () => {
      const { code, ...rest } = form;
      const body = {
        ...rest,
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        discountValue: Number(form.discountValue),
        minimumOrderAmount: Number(form.minimumOrderAmount),
        maximumDiscount: form.maximumDiscount ? Number(form.maximumDiscount) : null,
        minimumItems: Number(form.minimumItems),
        usageLimit: Number(form.usageLimit),
        startDate: toLocalDateTime(form.startDate),
        expiryDate: toLocalDateTime(form.expiryDate),
      };
      return coupon
        ? couponApi.update(coupon.id, body)
        : couponApi.create(restaurantId, { ...body, code: code.trim().toUpperCase() });
    },
    onSuccess: onSaved,
  });

  const input = (key: keyof typeof form) => ({
    value: form[key] as string,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [key]: e.target.value }),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  return (
    <Modal title={coupon ? `Edit ${coupon.code}` : 'Add coupon'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <Field label="Code">
            <input required disabled={!!coupon} maxLength={50} {...input('code')} />
          </Field>
          <Field label="Name">
            <input required {...input('name')} />
          </Field>
        </div>
        <Field label="Description">
          <input {...input('description')} />
        </Field>
        <div className="form-row">
          <Field label="Type">
            <select {...input('discountType')}>
              <option value="PERCENTAGE">Percentage</option>
              <option value="FIXED">Fixed amount</option>
            </select>
          </Field>
          {/* Every unit is spelled out in the label. "Minimum order" alone reads as a number of
              dishes, and a 2 typed there means two rupees, not two items. */}
          <Field
            label={percentage ? 'Discount percentage (%)' : `Discount amount (${symbol})`}
            hint={percentage ? 'Share taken off the bill.' : 'Flat amount taken off the bill.'}
          >
            <input
              type="number"
              required
              min="0"
              max={percentage ? 100 : undefined}
              step="0.01"
              {...input('discountValue')}
            />
          </Field>
          <Field
            label={`Minimum order value (${symbol})`}
            hint={`The bill must reach this amount in ${symbol}, not a number of dishes. 0 for no minimum.`}
          >
            <input type="number" required min="0" step="0.01" {...input('minimumOrderAmount')} />
          </Field>
          <Field
            label="Minimum number of dishes"
            hint="A count of dishes, not an amount. 0 for no requirement."
          >
            <input type="number" required min="0" step="1" {...input('minimumItems')} />
          </Field>
          <Field
            label={`Maximum discount (${symbol}, optional)`}
            hint={
              percentage
                ? 'Caps a percentage on a large bill. Leave empty for no cap.'
                : 'Not needed for a flat amount.'
            }
          >
            <input type="number" min="0" step="0.01" {...input('maximumDiscount')} />
          </Field>
          <Field label="Usage limit" hint="How many times the code can be used in total. 0 for unlimited.">
            <input type="number" required min="0" step="1" {...input('usageLimit')} />
          </Field>
        </div>
        {/* Reads the fields back in a sentence, so a value meant as "2 dishes" is obvious as "₹2"
            before the coupon is saved. No arithmetic here: the server owns that. */}
        <p className="alert alert-info coupon-summary">{summary}</p>
        <div className="form-row">
          <Field label="Starts">
            <input type="datetime-local" required {...input('startDate')} />
          </Field>
          <Field label="Expires">
            <input type="datetime-local" required {...input('expiryDate')} />
          </Field>
        </div>
        <label className="check">
          <input
            type="checkbox"
            checked={form.active}
            onChange={(e) => setForm({ ...form, active: e.target.checked })}
          />
          Active
        </label>
        <ErrorMessage error={save.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Says in words what the entered numbers mean. The amounts were being read as quantities - a
 * "minimum order" of 2 taken for two dishes rather than two rupees - so the form repeats itself in
 * a sentence before anything is saved.
 */
function describe(
  code: string,
  type: DiscountType,
  value: string,
  minimum: string,
  maximum: string,
  minimumItems: string,
): string {
  const name = code.trim() || 'This code';
  const amount = value.trim() === '' ? null : Number(value);
  const floor = minimum.trim() === '' ? 0 : Number(minimum);
  const cap = maximum.trim() === '' ? null : Number(maximum);
  const dishes = minimumItems.trim() === '' ? 0 : Number(minimumItems);

  if (amount == null || Number.isNaN(amount) || amount <= 0) {
    return 'Enter a discount value to see what this coupon will do.';
  }

  const takes = type === 'PERCENTAGE' ? `takes ${amount}% off the bill` : `takes ${money(amount)} off the bill`;
  const capped = type === 'PERCENTAGE' && cap != null && cap > 0 ? `, up to ${money(cap)}` : '';
  const hasFloor = !Number.isNaN(floor) && floor > 0;
  const hasDishes = !Number.isNaN(dishes) && dishes > 0;
  const dishWord = `${dishes} ${dishes === 1 ? 'dish' : 'dishes'}`;

  // Spelled out separately, because an amount and a count are different requirements.
  let needs = ' on any bill, however small';
  if (hasFloor && hasDishes) {
    needs = ` on bills of ${money(floor)} or more that include at least ${dishWord}`;
  } else if (hasFloor) {
    needs = ` on bills of ${money(floor)} or more`;
  } else if (hasDishes) {
    needs = ` on orders of at least ${dishWord}`;
  }

  return `${name} ${takes}${capped}${needs}.`;
}
