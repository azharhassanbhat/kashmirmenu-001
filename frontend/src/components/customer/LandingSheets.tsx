import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { landingApi } from '../../api';
import type { PublicLanding, RewardsInfo } from '../../api/types';
import {
  claimableOrders,
  hasRewardSecret,
  rewardSecretFor,
} from '../../lib/landing';
import { rememberedOrders } from '../../lib/orderHistory';
import { CIcon } from './CustomerIcon';
import { Sheet } from './Sheet';

function Problem({ text }: { text: string | null }) {
  return text ? (
    <p className="km-l-error" role="alert">
      {text}
    </p>
  ) : null;
}

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Please try again.');

// ------------------------------------------------------------------ Wi-Fi

/** The network name and password with a copy button, plus the camera-join QR where the phone supports it. */
export function WifiSheet({ qr, onClose }: { qr: string; onClose: () => void }) {
  // Never reused from a cache: the password is only held while this sheet is open.
  const wifi = useQuery({ queryKey: ['landing-wifi', qr], queryFn: () => landingApi.wifi(qr), retry: false, gcTime: 0 });
  const [qrUrl, setQrUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState<'password' | 'name' | null>(null);

  useEffect(() => {
    let live = true;
    let url: string | null = null;
    landingApi
      .wifiQr(qr)
      .then((blob) => {
        if (!live) return;
        url = URL.createObjectURL(blob);
        setQrUrl(url);
      })
      .catch(() => undefined); // the name and password below still work without the picture
    return () => {
      live = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [qr]);

  async function copy(what: 'password' | 'name', value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(what);
      window.setTimeout(() => setCopied(null), 2000);
    } catch {
      setCopied(null); // clipboard blocked: the text is on screen to type
    }
  }

  const d = wifi.data;
  return (
    <Sheet title="Guest Wi-Fi" onClose={onClose}>
      {wifi.isLoading && <p>One moment…</p>}
      {wifi.error != null && <Problem text="Guest Wi-Fi is not available right now. Please ask a member of staff." />}
      {d && (
        <div className="km-l-wifi">
          <dl>
            <dt>Network</dt>
            <dd>
              <code>{d.ssid}</code>
              <button type="button" className="km-l-mini" onClick={() => copy('name', d.ssid)}>
                <CIcon name="copy" size={16} />
                {copied === 'name' ? 'Copied' : 'Copy'}
              </button>
            </dd>
            {d.password && (
              <>
                <dt>Password</dt>
                <dd>
                  <code>{d.password}</code>
                  <button type="button" className="km-l-mini" onClick={() => copy('password', d.password!)}>
                    <CIcon name="copy" size={16} />
                    {copied === 'password' ? 'Copied' : 'Copy'}
                  </button>
                </dd>
              </>
            )}
            {!d.password && (
              <>
                <dt>Security</dt>
                <dd>Open network, no password</dd>
              </>
            )}
          </dl>
          <p aria-live="polite" className="sr-only">
            {copied ? 'Copied to the clipboard' : ''}
          </p>
          {qrUrl && (
            <figure className="km-l-wifi-qr">
              <img src={qrUrl} width={200} height={200} alt={`QR code to join ${d.ssid}`} />
              <figcaption>Point your phone camera here to join automatically.</figcaption>
            </figure>
          )}
        </div>
      )}
    </Sheet>
  );
}

// ------------------------------------------------------------------ rewards

export function RewardsSheet({
  qr,
  landing,
  onClose,
}: {
  qr: string;
  landing: PublicLanding;
  onClose: () => void;
}) {
  const restaurantId = landing.restaurantId;
  const queryClient = useQueryClient();
  const key = ['landing-rewards', qr];
  // A secret is made only when the guest chooses to join; just looking creates nothing.
  const [secret, setSecret] = useState<string | null>(() => (hasRewardSecret(restaurantId) ? rewardSecretFor(restaurantId) : null));
  const orders = useMemo(() => claimableOrders(rememberedOrders(qr)), [qr]);

  const info = useQuery({
    queryKey: [...key, secret !== null],
    queryFn: () => landingApi.rewards(qr, secret ?? ''),
    retry: false,
  });
  const put = (data: RewardsInfo) => queryClient.setQueryData([...key, true], data);

  const join = useMutation({
    mutationFn: () => landingApi.joinRewards(qr, rewardSecretFor(restaurantId)),
    // The result is stored under the "joined" key BEFORE the screen switches to it, so a refetch that started
    // earlier cannot come back as "no member" and overwrite it.
    onSuccess: (data) => {
      put(data);
      setSecret(rewardSecretFor(restaurantId));
    },
  });
  const claim = useMutation({
    mutationFn: (token: string) => landingApi.claimRewards(qr, secret ?? '', token),
    onSuccess: (r) => put(r.rewards),
  });

  const rules = info.data ?? null;
  const member = rules?.member ?? null;
  const reward = landing.rewards?.rewardText ?? rules?.rewardText ?? 'a reward';
  const cost = rules?.redeemPoints ?? landing.rewards?.redeemPoints ?? 0;
  const progress = member && cost > 0 ? Math.min(100, Math.round((member.balance / cost) * 100)) : 0;

  return (
    <Sheet title="Rewards" onClose={onClose}>
      <div className="km-l-rewards">
        <p>
          Earn 1 point for every {landing.rewards?.spendPerPoint} you spend on a paid order. Collect {cost} points for{' '}
          <strong>{reward}</strong>.
        </p>

        {!member && (
          <>
            <button type="button" className="km-btn-fire" disabled={join.isPending} onClick={() => join.mutate()}>
              {join.isPending ? 'Joining…' : 'Start earning rewards'}
            </button>
            <p className="km-l-note">
              No account needed. Your membership stays on this phone, so clearing your browser data starts over.
            </p>
          </>
        )}

        {member && (
          <>
            <div className="km-l-balance">
              <span className="km-l-balance-n">{member.balance}</span>
              <span>points</span>
              <div className="km-l-bar" role="progressbar" aria-valuemin={0} aria-valuemax={cost} aria-valuenow={Math.min(member.balance, cost)} aria-label="Progress to your reward">
                <span style={{ width: `${progress}%` }} />
              </div>
              <p>
                {member.canRedeem
                  ? `You can claim ${reward}! Show this code to the staff.`
                  : `${member.pointsToGo} more points to ${reward}.`}
              </p>
              <p className="km-l-code">
                Your code <strong>{member.code}</strong>
              </p>
            </div>

            <h3>Add points for a meal</h3>
            {orders.length === 0 ? (
              <p className="km-l-note">Orders you place from this phone will appear here once they are served and paid.</p>
            ) : (
              <ul className="km-l-orders">
                {orders.map((o) => (
                  <li key={o.token}>
                    <span>{o.orderNumber}</span>
                    <button
                      type="button"
                      className="km-l-mini"
                      disabled={claim.isPending}
                      onClick={() => claim.mutate(o.token)}
                    >
                      Add points
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {claim.isSuccess && (
              <p role="status" className="km-l-ok">
                {claim.data.pointsAdded} points added.
              </p>
            )}
            <Problem text={claim.error ? messageOf(claim.error) : null} />
          </>
        )}

        <Problem text={join.error ? messageOf(join.error) : info.error ? messageOf(info.error) : null} />
        {landing.rewards?.terms && <p className="km-l-note">{landing.rewards.terms}</p>}
      </div>
    </Sheet>
  );
}
