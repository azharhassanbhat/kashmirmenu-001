import { useEffect, useState, type ChangeEvent, type FormEvent, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { imageApi, landingAdminApi, tableApi } from '../../api';
import { ApiError } from '../../api/client';
import type { AdminLanding, AdminLandingInput, LandingActionKey } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ErrorMessage, Field, QueryState } from '../../components/ui';
import { ACCEPTED_TYPES, fileProblem, photoLinkProblem } from '../../lib/imageUpload';
import { ACTION_TEXT, knownActions } from '../../lib/landing';

type Item = { key: LandingActionKey; on: boolean };

/** Visible cards first, in their saved order, then the hidden ones. */
function toItems(visibleRaw: string[], allRaw: string[]): Item[] {
  const visible = knownActions(visibleRaw);
  const all = knownActions(allRaw);
  return [...visible.map((key) => ({ key, on: true })), ...all.filter((k) => !visible.includes(k)).map((key) => ({ key, on: false }))];
}

function Check({ checked, onChange, children }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode }) {
  return (
    <label style={{ display: 'flex', gap: '0.6rem', alignItems: 'center', minHeight: 44 }}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>{children}</span>
    </label>
  );
}

export default function LandingSettingsPage() {
  const { user } = useAuth();
  const restaurantId = user?.restaurantId ?? null;
  const queryClient = useQueryClient();

  const settings = useQuery({ queryKey: ['landing-admin'], queryFn: landingAdminApi.get, enabled: restaurantId !== null });
  const tables = useQuery({ queryKey: ['tables'], queryFn: tableApi.list, enabled: restaurantId !== null });
  const sampleQr = tables.data?.find((t) => t.active)?.qrIdentifier ?? null;

  const [form, setForm] = useState<AdminLanding | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [wifiPassword, setWifiPassword] = useState('');
  const [saved, setSaved] = useState(false);
  const [photo, setPhoto] = useState<{ busy: boolean; error: string | null }>({ busy: false, error: null });

  useEffect(() => {
    if (settings.data) {
      setForm(settings.data);
      setItems(toItems(settings.data.actions, settings.data.allActions));
      setWifiPassword('');
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: (f: AdminLanding) => {
      const { wifiPasswordSet: _set, allActions: _all, ...rest } = f;
      void _set;
      void _all;
      const input: AdminLandingInput = {
        ...rest,
        // Anonymous feedback is no longer offered; saving switches it off so the server stops advertising it.
        feedbackEnabled: false,
        actions: items.filter((i) => i.on).map((i) => i.key),
        wifiPassword: wifiPassword === '' ? null : wifiPassword,
      };
      return landingAdminApi.save(input);
    },
    onSuccess: (data) => {
      setSaved(true);
      queryClient.setQueryData(['landing-admin'], data);
      void queryClient.invalidateQueries({ queryKey: ['landing'] });
      void queryClient.invalidateQueries({ queryKey: ['public-table'] });
    },
  });

  if (restaurantId === null) return <div className="alert alert-info">The landing page belongs to a restaurant account.</div>;
  if (settings.error instanceof ApiError && settings.error.status === 403) {
    return <div className="alert alert-info">Only managers and owners can change the landing page.</div>;
  }

  const set = <K extends keyof AdminLanding>(key: K, value: AdminLanding[K]) => {
    setSaved(false);
    setForm((f) => (f ? { ...f, [key]: value } : f));
  };
  const text = (key: keyof AdminLanding, placeholder?: string, type = 'text') => (
    <input
      type={type}
      value={(form?.[key] as string | null) ?? ''}
      placeholder={placeholder}
      autoComplete="off"
      spellCheck={false}
      onChange={(e) => set(key, (e.target.value === '' ? null : e.target.value) as never)}
    />
  );
  const move = (i: number, d: -1 | 1) => {
    setSaved(false);
    setItems((list) => {
      const j = i + d;
      if (j < 0 || j >= list.length) return list;
      const next = [...list];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
  };

  async function onCover(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ''; // so choosing the same file again still fires
    if (!file) return;
    const problem = fileProblem(file);
    if (problem) {
      setPhoto({ busy: false, error: problem });
      return;
    }
    setPhoto({ busy: true, error: null });
    try {
      const uploaded = await imageApi.upload(file);
      set('coverImageUrl', uploaded.url);
      setPhoto({ busy: false, error: null });
    } catch (error) {
      setPhoto({ busy: false, error: error instanceof Error ? error.message : 'The upload failed. Please try again.' });
    }
  }

  const coverProblem = photoLinkProblem(form?.coverImageUrl ?? '');

  function submit(e: FormEvent) {
    e.preventDefault();
    if (coverProblem || photo.busy) return;
    if (form) {
      setSaved(false);
      save.mutate(form);
    }
  }

  return (
    <>
      <div className="page-head">
        <h2>QR landing page</h2>
        {sampleQr && (
          <a className="btn btn-sm" href={`/menu/${sampleQr}/home`} target="_blank" rel="noopener noreferrer">
            Preview
          </a>
        )}
      </div>
      <QueryState isLoading={settings.isLoading} error={settings.error}>
        {form && (
          <form className="card" onSubmit={submit}>
            <p className="muted">
              What guests see after scanning a table's QR code. The printed codes do not change: the same address shows this
              page first when it is switched on. Anything left empty is hidden from guests.
            </p>
            <Check checked={form.landingEnabled} onChange={(v) => set('landingEnabled', v)}>
              Show the landing page when a QR code is scanned
            </Check>

            <h3>Your page</h3>
            <Field label="Tagline" hint="Shown under your name.">
              {text('tagline', 'Authentic Kashmiri flavours')}
            </Field>
            <Field label="Cover image" hint="Upload a photo (JPEG or PNG) or paste an https link. Leave empty for the illustrated Dal Lake scene.">
              {text('coverImageUrl', 'https://…')}
            </Field>
            <input type="file" accept={ACCEPTED_TYPES.join(',')} onChange={onCover} disabled={photo.busy} aria-label="Upload a cover photo" />
            {photo.busy && <p className="muted">Uploading…</p>}
            {(photo.error || coverProblem) && <div className="alert alert-error" role="alert">{photo.error ?? coverProblem}</div>}
            <Field label="Announcement or special offer" hint="One short notice at the top of the page.">
              {text('announcement', 'Free kahwa with every Wazwan platter this week')}
            </Field>
            <Field
              label="Opening hours"
              hint={'One line per day or range, 24-hour time. For example "Mon-Fri 09:00-22:00", "Sat,Sun 10:00-23:30" or "Wed closed". Empty hides the hours.'}
            >
              <textarea
                rows={4}
                value={form.openingHours ?? ''}
                onChange={(e) => set('openingHours', e.target.value === '' ? null : e.target.value)}
              />
            </Field>

            <h3>Links</h3>
            <Field
              label="Google review link"
              hint="Copy it from your Google Business Profile (Get more reviews), for example https://g.page/r/…/review. No Place ID needed."
            >
              {text('googleReviewUrl', 'https://g.page/r/…/review')}
            </Field>
            {form.googleReviewUrl === null && (
              <p className="muted">Without a review link the Google review button is hidden from guests.</p>
            )}
            <Field label="Instagram">{text('instagramUrl', 'https://www.instagram.com/…')}</Field>
            <Field label="YouTube">{text('youtubeUrl', 'https://www.youtube.com/@…')}</Field>
            <Field label="Facebook">{text('facebookUrl', 'https://www.facebook.com/…')}</Field>
            <Field label="Website">{text('websiteUrl', 'https://…')}</Field>
            <Field label="Map link" hint="Optional. Without it, directions use your restaurant's address.">
              {text('mapUrl', 'https://maps.app.goo.gl/…')}
            </Field>

            <h3>Guest Wi-Fi</h3>
            <Check checked={form.wifiEnabled} onChange={(v) => set('wifiEnabled', v)}>
              Offer guest Wi-Fi
            </Check>
            {form.wifiEnabled && (
              <>
                <Field label="Network name">{text('wifiSsid', 'Kashmir Cafe Guest')}</Field>
                <Field label="Security">
                  <select value={form.wifiSecurity} onChange={(e) => set('wifiSecurity', e.target.value)}>
                    <option value="WPA">WPA / WPA2 / WPA3</option>
                    <option value="WEP">WEP</option>
                    <option value="NONE">Open (no password)</option>
                  </select>
                </Field>
                {form.wifiSecurity !== 'NONE' && (
                  <Field
                    label="Password"
                    hint={form.wifiPasswordSet ? 'A password is saved. Leave empty to keep it, or type a new one.' : 'Guests are shown this password.'}
                  >
                    <input
                      type="password"
                      value={wifiPassword}
                      autoComplete="new-password"
                      onChange={(e) => {
                        setSaved(false);
                        setWifiPassword(e.target.value);
                      }}
                    />
                  </Field>
                )}
              </>
            )}

            <h3>Service</h3>
            <Check checked={form.assistanceEnabled} onChange={(v) => set('assistanceEnabled', v)}>
              Call a waiter and table requests
            </Check>

            <h3>Rewards</h3>
            <Check checked={form.rewardsEnabled} onChange={(v) => set('rewardsEnabled', v)}>
              Offer rewards
            </Check>
            {form.rewardsEnabled && (
              <>
                <Field label="Spend per point" hint="A guest earns one point for each of this amount on a paid, served order.">
                  <input
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={form.rewardsSpendPerPoint}
                    onChange={(e) => set('rewardsSpendPerPoint', Number(e.target.value))}
                  />
                </Field>
                <Field label="Points for one reward">
                  <input
                    type="number"
                    min="1"
                    step="1"
                    value={form.rewardsRedeemPoints}
                    onChange={(e) => set('rewardsRedeemPoints', Math.trunc(Number(e.target.value)))}
                  />
                </Field>
                <Field label="The reward">{text('rewardsRewardText', 'A free kahwa')}</Field>
                <Field label="Terms (optional)">{text('rewardsTerms', 'One reward per visit. Not valid with other offers.')}</Field>
                <p className="muted">
                  Points are added only for orders that are served and paid in full, once per order. Opening the page or
                  tapping the review button never earns points. Google asks that reviews are not rewarded.
                </p>
              </>
            )}

            <h3>Order of the buttons</h3>
            <p className="muted">Untick a button to hide it. A button also stays hidden if its feature is off or not set up.</p>
            <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
              {items.map((item, i) => (
                <li key={item.key} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <Check
                    checked={item.on}
                    onChange={(v) => {
                      setSaved(false);
                      setItems((list) => list.map((x) => (x.key === item.key ? { ...x, on: v } : x)));
                    }}
                  >
                    {ACTION_TEXT[item.key].title}
                  </Check>
                  <button type="button" className="btn btn-sm" onClick={() => move(i, -1)} disabled={i === 0} aria-label={`Move ${ACTION_TEXT[item.key].title} up`}>
                    Up
                  </button>
                  <button type="button" className="btn btn-sm" onClick={() => move(i, 1)} disabled={i === items.length - 1} aria-label={`Move ${ACTION_TEXT[item.key].title} down`}>
                    Down
                  </button>
                </li>
              ))}
            </ul>

            <ErrorMessage error={save.error} />
            {saved && <div className="alert alert-ok">Saved. Guests see the changes the next time they scan.</div>}
            <div className="actions">
              <button className="btn btn-primary" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : 'Save'}
              </button>
            </div>
          </form>
        )}
      </QueryState>

      {settings.data?.rewardsEnabled && <RedeemCard />}
    </>
  );
}

function RedeemCard() {
  const [code, setCode] = useState('');
  const lookup = useMutation({ mutationFn: () => landingAdminApi.member(code) });
  const redeem = useMutation({
    mutationFn: (memberCode: string) => landingAdminApi.redeem(memberCode),
    // After a redemption the looked-up balance is out of date, so clear it rather than show a stale figure.
    onSuccess: () => lookup.reset(),
  });
  const member = lookup.data;

  function find(e: FormEvent) {
    e.preventDefault();
    redeem.reset();
    lookup.mutate();
  }

  return (
    <div className="card">
      <h3>Redeem a reward</h3>
      <p className="muted">Enter the code the guest reads out from their rewards screen, check the balance, then redeem.</p>
      <form onSubmit={find}>
        <Field label="Member code">
          <input
            value={code}
            onChange={(e) => {
              setCode(e.target.value);
              lookup.reset();
            }}
            maxLength={20}
            autoComplete="off"
            spellCheck={false}
          />
        </Field>
        <div className="actions">
          <button className="btn" disabled={lookup.isPending || code.trim() === ''}>
            {lookup.isPending ? 'Looking…' : 'Check balance'}
          </button>
        </div>
      </form>
      <ErrorMessage error={lookup.error ?? redeem.error} />
      {member && (
        <div className="alert alert-info" role="status">
          <strong>{member.memberCode}</strong> has {member.balance} points.{' '}
          {member.canRedeem
            ? `Enough for ${member.rewardText ?? 'a reward'} (costs ${member.redeemPoints}).`
            : `${member.pointsToGo} more needed for ${member.rewardText ?? 'a reward'}.`}
          <div className="actions">
            <button
              type="button"
              className="btn btn-primary"
              disabled={!member.canRedeem || redeem.isPending}
              onClick={() => redeem.mutate(member.memberCode)}
            >
              {redeem.isPending ? 'Redeeming…' : `Redeem ${member.redeemPoints} points`}
            </button>
          </div>
        </div>
      )}
      {redeem.data && (
        <div className="alert alert-ok" role="status">
          Redeemed {redeem.data.rewardText ?? 'a reward'} for {redeem.data.memberCode}. {redeem.data.newBalance} points left.
        </div>
      )}
    </div>
  );
}
