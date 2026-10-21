import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { imageApi, paymentSettingsApi } from '../../api';
import { ApiError } from '../../api/client';
import type { PaymentSettingsInput } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ErrorMessage, Field, QueryState } from '../../components/ui';
import { isValidUpiId } from '../../lib/payment';

const DEFAULTS: PaymentSettingsInput = {
  cashEnabled: true,
  upiEnabled: false,
  cardEnabled: false,
  walletEnabled: false,
  upiId: null,
  merchantName: null,
  upiQrUrl: null,
};

const MAX_QR_BYTES = 10 * 1024 * 1024;

export default function PaymentSettingsPage() {
  const { user } = useAuth();
  const restaurantId = user?.restaurantId ?? null;
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ['payment-settings', restaurantId],
    queryFn: async () => {
      try {
        return await paymentSettingsApi.get(restaurantId!);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: restaurantId !== null,
  });

  const [form, setForm] = useState<PaymentSettingsInput>(DEFAULTS);
  const [saved, setSaved] = useState(false);
  const [qrProblem, setQrProblem] = useState<string | null>(null);

  useEffect(() => {
    if (settings.data) {
      const { id: _id, ...rest } = settings.data;
      setForm({ ...DEFAULTS, ...rest });
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: () => {
      const body: PaymentSettingsInput = {
        ...form,
        upiId: form.upiId?.trim() || null,
        merchantName: form.merchantName?.trim() || null,
        upiQrUrl: form.upiQrUrl?.trim() || null,
      };
      return settings.data
        ? paymentSettingsApi.update(restaurantId!, body)
        : paymentSettingsApi.create(restaurantId!, body);
    },
    onSuccess: () => {
      setSaved(true);
      void queryClient.invalidateQueries({ queryKey: ['payment-settings'] });
    },
  });

  // The server checks and re-encodes the picture; what comes back is our own address for it. It only
  // becomes the restaurant's QR when the form is saved.
  const upload = useMutation({
    mutationFn: (file: File) => imageApi.upload(file),
    onSuccess: (uploaded) => {
      setSaved(false);
      setForm((f) => ({ ...f, upiQrUrl: uploaded.url }));
    },
  });

  if (restaurantId === null) {
    return <div className="alert alert-info">Payment settings belong to a restaurant account.</div>;
  }

  const toggle = (key: 'cashEnabled' | 'upiEnabled' | 'cardEnabled' | 'walletEnabled', label: string) => (
    <label className="check">
      <input
        type="checkbox"
        checked={form[key]}
        onChange={(e) => {
          setSaved(false);
          setForm({ ...form, [key]: e.target.checked });
        }}
      />
      {label}
    </label>
  );

  function submit(e: FormEvent) {
    e.preventDefault();
    setSaved(false);
    save.mutate();
  }

  function onQrFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setQrProblem(null);
    if (file.type !== 'image/jpeg' && file.type !== 'image/png') {
      setQrProblem('Please choose a JPEG or PNG picture of your UPI QR code.');
      return;
    }
    if (file.size > MAX_QR_BYTES) {
      setQrProblem('That picture is larger than 10 MB. Please choose a smaller one.');
      return;
    }
    upload.mutate(file);
  }

  const upiId = form.upiId?.trim() ?? '';
  const upiIdProblem = upiId !== '' && !isValidUpiId(upiId);
  const customersCanUseUpi = form.upiEnabled && upiId !== '' && !upiIdProblem && Boolean(form.upiQrUrl);

  return (
    <>
      <div className="page-head">
        <h2>Payment settings</h2>
      </div>
      <QueryState isLoading={settings.isLoading} error={settings.error}>
        <form className="card" style={{ maxWidth: 520 }} onSubmit={submit}>
          <div className="alert alert-info" style={{ marginTop: 0 }}>
            These choose which payment methods diners can select. There is no payment gateway: staff confirm each
            payment manually from the Orders page.
          </div>
          {toggle('cashEnabled', 'Cash')}
          {toggle('upiEnabled', 'UPI')}
          {toggle('cardEnabled', 'Card')}
          {toggle('walletEnabled', 'Wallet')}
          <Field label="UPI ID" hint="For example name@bank. Required to turn UPI on.">
            <input
              value={form.upiId ?? ''}
              aria-invalid={upiIdProblem}
              onChange={(e) => {
                setSaved(false);
                setForm({ ...form, upiId: e.target.value });
              }}
            />
          </Field>
          {upiIdProblem && (
            <div className="alert alert-error" role="alert">
              That does not look like a UPI ID. It should look like name@bank.
            </div>
          )}
          <Field label="Recipient name" hint="Shown to customers next to your QR code. Optional.">
            <input
              value={form.merchantName ?? ''}
              onChange={(e) => {
                setSaved(false);
                setForm({ ...form, merchantName: e.target.value });
              }}
            />
          </Field>

          <fieldset className="upi-qr-settings">
            <legend>UPI payment QR code</legend>
            <p className="muted">
              Upload the QR code from your UPI app. Customers who choose UPI at checkout see exactly this picture,
              on a white background.
            </p>
            {form.upiQrUrl ? (
              <div className="upi-qr-preview">
                <img src={form.upiQrUrl} alt="Your uploaded UPI QR code" width={180} height={180} />
              </div>
            ) : (
              <p className="muted">No QR code uploaded yet.</p>
            )}
            <div className="actions">
              <label className="btn btn-sm">
                {upload.isPending ? 'Uploading…' : form.upiQrUrl ? 'Replace QR code' : 'Upload QR code'}
                <input
                  type="file"
                  accept="image/jpeg,image/png"
                  hidden
                  disabled={upload.isPending}
                  onChange={onQrFile}
                  aria-label="Upload your UPI QR code image"
                />
              </label>
              {form.upiQrUrl && (
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => {
                    setSaved(false);
                    setForm({ ...form, upiQrUrl: null });
                  }}
                >
                  Remove
                </button>
              )}
            </div>
            {qrProblem && (
              <div className="alert alert-error" role="alert">
                {qrProblem}
              </div>
            )}
            <ErrorMessage error={upload.error} />
            <p className="muted">The picture is used once you press Save.</p>
          </fieldset>

          {form.upiEnabled && !customersCanUseUpi && (
            <div className="alert alert-info" role="status">
              Customers will not see UPI until it is on and you have saved a valid UPI ID and an uploaded QR code.
            </div>
          )}
          <ErrorMessage error={save.error} />
          {saved && <div className="alert alert-ok">Saved.</div>}
          <div className="actions">
            <button className="btn btn-primary" disabled={save.isPending || upload.isPending || upiIdProblem}>
              {save.isPending ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      </QueryState>
    </>
  );
}
