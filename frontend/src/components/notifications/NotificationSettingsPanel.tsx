import { useState } from 'react';
import { SOUND_THEMES, isSoundTheme, type SoundKind } from '../../lib/sound/soundDefs';
import { REMINDER_CHOICES, type PrefGroup } from '../../lib/sound/soundPrefs';
import { useNotificationCenter, type TestSoundResult } from './NotificationCenter';

const RESULT_TEXT: Record<TestSoundResult, string> = {
  played: 'Played',
  blocked: 'Your browser blocked audio. Click Enable Notification Sounds, then try again.',
  unsupported: 'This browser cannot play notification sounds.',
  'volume-zero': 'The volume is at 0%, so nothing can be heard. Raise it and try again.',
};

const GROUPS: { key: PrefGroup; label: string; hint: string; preview: SoundKind }[] = [
  { key: 'newOrder', label: 'New Order', hint: 'A chime when a new order arrives.', preview: 'NEW_ORDER' },
  { key: 'waiterCalls', label: 'Waiter Call', hint: 'A guest calls the waiter (a double service bell).', preview: 'WAITER_CALL' },
  { key: 'billRequests', label: 'Bill Request', hint: 'A guest asks for the bill (a soft, polite tone).', preview: 'BILL_REQUEST' },
  { key: 'orderReady', label: 'Order Ready', hint: 'The kitchen marks an order ready to serve.', preview: 'ORDER_READY' },
  { key: 'payments', label: 'Payment', hint: 'Payments received, failed or refunded.', preview: 'SUCCESS' },
  { key: 'cancellations', label: 'Cancellation', hint: 'An order is cancelled.', preview: 'ORDER_CANCELLED' },
  { key: 'messages', label: 'Message', hint: 'Other table requests and customer feedback.', preview: 'MESSAGE' },
  { key: 'general', label: 'General Notification', hint: 'Request updates and similar.', preview: 'MESSAGE' },
];

/**
 * "Notification & Sound Settings". Every change is saved at once, on this device, for the signed-in
 * account. Order confirmed, preparing and served stay visual only: they follow staff's own taps.
 */
export function NotificationSettingsPanel({ variant }: { variant?: 'restaurant' | 'platform' }) {
  const { scope, prefs, setPrefs, engineState, testSound } = useNotificationCenter();
  const mode = variant ?? (scope === 'platform' ? 'platform' : 'restaurant');
  const [result, setResult] = useState<TestSoundResult | null>(null);
  const [busy, setBusy] = useState(false);
  const off = !prefs.master;

  async function run(kind: SoundKind) {
    setBusy(true);
    setResult(null);
    try {
      setResult(await testSound(kind));
    } finally {
      setBusy(false);
    }
  }

  const rows =
    mode === 'platform'
      ? [
          {
            key: 'general' as PrefGroup,
            label: 'Platform notifications',
            hint: 'A restaurant registers and is waiting for approval.',
            preview: 'MESSAGE' as SoundKind,
          },
        ]
      : GROUPS;

  return (
    <div className="notif-settings">
      <label className="check">
        <input
          type="checkbox"
          checked={prefs.master}
          onChange={(e) => {
            setPrefs({ master: e.target.checked });
            // Switching sounds on is a click, so it is also the moment the browser lets audio start.
            if (e.target.checked) void testSound('MESSAGE').then(setResult);
          }}
        />
        <span>
          <strong>Enable / Disable All Sounds</strong>
        </span>
      </label>

      <p className="muted notif-engine" role="status">
        {engineState === 'ready' && 'Your browser currently allows notification sounds.'}
        {engineState === 'locked' &&
          'Your browser is not allowing audio yet. It starts after you click Enable Notification Sounds (or any button on this page).'}
        {engineState === 'unsupported' && 'This browser cannot play notification sounds. Visual notifications still work.'}
      </p>

      <fieldset className={`notif-groups${off ? ' is-off' : ''}`} disabled={off}>
        <legend className="muted">
          What makes a sound{off ? ' (all sounds are off)' : ''}
        </legend>
        {rows.map((g) => (
          <div className="notif-row" key={g.key}>
            <label className="check">
              <input type="checkbox" checked={prefs[g.key]} onChange={(e) => setPrefs({ [g.key]: e.target.checked })} />
              <span>
                {g.label}
                <small className="muted notif-hint">{g.hint}</small>
              </span>
            </label>
            <button
              type="button"
              className="btn btn-sm btn-ghost"
              disabled={busy}
              aria-label={`Preview the ${g.label} sound`}
              onClick={() => void run(g.preview)}
            >
              Preview
            </button>
          </div>
        ))}

        <label className="field">
          <span>Sound theme</span>
          <select
            value={prefs.theme}
            aria-label="Sound theme"
            onChange={(e) => {
              if (isSoundTheme(e.target.value)) setPrefs({ theme: e.target.value });
            }}
          >
            {SOUND_THEMES.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label} - {t.hint}
              </option>
            ))}
          </select>
        </label>

        {mode === 'restaurant' && (
          <label className="field">
            <span>Remind me about unanswered waiter calls, bill requests and new orders</span>
            <select
              value={prefs.reminderSec}
              aria-label="Reminder interval"
              onChange={(e) => setPrefs({ reminderSec: Number(e.target.value) })}
            >
              {REMINDER_CHOICES.map((sec) => (
                <option key={sec} value={sec}>
                  {sec === 0 ? 'Never' : sec < 60 ? `Every ${sec} seconds` : `Every ${sec / 60} minute${sec === 60 ? '' : 's'}`}
                </option>
              ))}
            </select>
          </label>
        )}

        <label className="field notif-volume">
          <span>
            Volume <output htmlFor="notif-volume-input">{prefs.volume}%</output>
          </span>
          <input
            id="notif-volume-input"
            type="range"
            min={0}
            max={100}
            step={1}
            value={prefs.volume}
            aria-label="Notification volume"
            aria-valuetext={`${prefs.volume} percent`}
            onChange={(e) => setPrefs({ volume: Number(e.target.value) })}
          />
        </label>
      </fieldset>

      {mode === 'restaurant' && (
        <p className="muted notif-hint">
          Critical alerts (a restaurant suspended or rejected) follow the master switch only. You only hear the events
          that concern your role. Reminders repeat up to 5 times and stop as soon as the request is answered.
          Confirmed, preparing and served updates are shown to staff but never make a sound.
        </p>
      )}

      <div className="actions" style={{ justifyContent: 'flex-start' }}>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void run('NEW_ORDER')}>
          Test Notification Sound
        </button>
        {engineState === 'locked' && (
          <button
            type="button"
            className="btn"
            disabled={busy}
            data-sound-activator
            onClick={() => void run('SUCCESS')}
          >
            Enable Notification Sounds
          </button>
        )}
      </div>
      <div aria-live="polite" className="notif-result">
        {result && (
          <div className={`alert ${result === 'played' ? 'alert-ok' : 'alert-error'}`}>{RESULT_TEXT[result]}</div>
        )}
      </div>
    </div>
  );
}
