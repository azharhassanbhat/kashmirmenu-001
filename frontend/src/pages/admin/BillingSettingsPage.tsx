import { useEffect, useState, type FormEvent } from 'react';
import { registrationProblem } from '../../lib/receipt';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { billingSettingsApi } from '../../api';
import { ErrorMessage, Field, QueryState } from '../../components/ui';
import { formatRate, previewCharges } from '../../lib/charges';
import { money } from '../../lib/format';

/** The example order the preview is worked out on. */
const EXAMPLE_SUBTOTAL = 1000;

/**
 * Service charge and tax. Nothing is assumed and every rate starts at 0, so a restaurant that never
 * opens this page charges exactly the menu prices. Which taxes apply to a restaurant, and whether its
 * prices should include them, is for the restaurant and its accountant: Kashmir Menu only applies the
 * rates it is given.
 */
export default function BillingSettingsPage() {
  const queryClient = useQueryClient();
  const stored = useQuery({ queryKey: ['billing-settings'], queryFn: billingSettingsApi.get });

  const [taxName, setTaxName] = useState('Tax');
  const [taxRate, setTaxRate] = useState('0');
  const [serviceRate, setServiceRate] = useState('0');
  const [inclusive, setInclusive] = useState(false);
  const [registration, setRegistration] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!stored.data) return;
    setTaxName(stored.data.taxName);
    setTaxRate(String(stored.data.taxRate));
    setServiceRate(String(stored.data.serviceChargeRate));
    setInclusive(stored.data.pricesIncludeTax);
    setRegistration(stored.data.taxRegistrationNumber ?? '');
  }, [stored.data]);

  const save = useMutation({
    mutationFn: () =>
      billingSettingsApi.save({
        taxName: taxName.trim(),
        taxRate: Number(taxRate),
        serviceChargeRate: Number(serviceRate),
        pricesIncludeTax: inclusive,
        taxRegistrationNumber: registration.trim() || null,
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(['billing-settings'], data);
      setSaved(true);
    },
  });

  const tax = Number(taxRate);
  const service = Number(serviceRate);
  const valid = Number.isFinite(tax) && Number.isFinite(service) && tax >= 0 && tax <= 100 && service >= 0 && service <= 100;
  const preview = valid ? previewCharges(EXAMPLE_SUBTOTAL, 0, service, tax, inclusive) : null;

  const registrationIssue = registrationProblem(registration);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (registrationIssue) return;
    setSaved(false);
    save.mutate();
  }

  const clear = () => setSaved(false);

  return (
    <>
      <div className="page-head">
        <h2>Taxes &amp; service charge</h2>
      </div>

      <div className="alert alert-info">
        Kashmir Menu does not decide which taxes apply to your restaurant. Enter the rates your accountant gives
        you. Leave both at 0 to charge exactly your menu prices.
      </div>

      <QueryState isLoading={stored.isLoading} error={stored.error}>
        <form onSubmit={submit} className="card">
          <div className="form-row">
            <Field label="Tax name">
              <input required maxLength={30} value={taxName} onChange={(e) => { setTaxName(e.target.value); clear(); }} />
            </Field>
            <Field label="Tax rate (%)">
              <input
                type="number"
                required
                min="0"
                max="100"
                step="0.01"
                value={taxRate}
                onChange={(e) => { setTaxRate(e.target.value); clear(); }}
              />
            </Field>
            <Field label="Service charge (%)">
              <input
                type="number"
                required
                min="0"
                max="100"
                step="0.01"
                value={serviceRate}
                onChange={(e) => { setServiceRate(e.target.value); clear(); }}
              />
            </Field>
          </div>

          <label className="field">
            <span>Tax registration number (printed on receipts, optional)</span>
            <input
              maxLength={60}
              autoComplete="off"
              spellCheck={false}
              placeholder="for example 29ABCDE1234F1Z5"
              value={registration}
              onChange={(e) => { setRegistration(e.target.value); clear(); }}
              aria-invalid={registrationIssue ? true : undefined}
            />
            {registrationIssue ? (
              <small className="field-error" role="alert">{registrationIssue}</small>
            ) : (
              <small className="muted">
                Your GSTIN or VAT number. It is printed exactly as you enter it; it is not checked against any
                tax records, so please make sure it is right.
              </small>
            )}
          </label>

          <div className="field">
            <span>Your menu prices</span>
            <label className="check">
              <input type="radio" name="pricing" checked={!inclusive} onChange={() => { setInclusive(false); clear(); }} />{' '}
              do not include tax. Tax is added on the bill.
            </label>
            <label className="check">
              <input type="radio" name="pricing" checked={inclusive} onChange={() => { setInclusive(true); clear(); }} />{' '}
              already include tax. Nothing is added; the bill shows how much tax is inside.
            </label>
            {inclusive && service > 0 && (
              <small className="muted">
                With prices that include tax, the service charge is added without tax on top of it. If your service
                charge is taxable, choose &ldquo;do not include tax&rdquo;.
              </small>
            )}
          </div>

          {preview && (
            <div className="card" aria-live="polite">
              <h3>Example: a {money(EXAMPLE_SUBTOTAL)} order</h3>
              <dl className="kv">
                <div><dt>Items</dt><dd>{money(EXAMPLE_SUBTOTAL)}</dd></div>
                {preview.serviceCharge > 0 && (
                  <div><dt>Service charge ({formatRate(service)})</dt><dd>{money(preview.serviceCharge)}</dd></div>
                )}
                {preview.tax > 0 && (
                  <div><dt>{taxName.trim() || 'Tax'} ({formatRate(tax)})</dt><dd>{money(preview.tax)}</dd></div>
                )}
                <div><dt><strong>Guest pays</strong></dt><dd><strong>{money(preview.total)}</strong></dd></div>
              </dl>
              {inclusive && preview.taxIncluded > 0 && (
                <p className="muted">
                  Includes {taxName.trim() || 'tax'} of {money(preview.taxIncluded)}.
                </p>
              )}
              <p className="muted">
                An example only. A coupon is taken off first, and every amount is rounded to the nearest paisa.
              </p>
            </div>
          )}

          <ErrorMessage error={save.error} />
          {saved && <div className="alert alert-ok" role="status">Saved. New carts use these rates straight away.</div>}
          <p className="muted">
            Orders already placed keep the rates they were priced with, and a guest already building a cart is
            charged the rates they were shown.
          </p>
          <div className="actions">
            <button className="btn btn-primary" disabled={save.isPending || !valid}>
              {save.isPending ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      </QueryState>
    </>
  );
}
