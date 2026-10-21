import { useState } from 'react';
import { ChinarLeaf } from './ChinarLeaf';

/**
 * Shown when a guest opens a table's QR code and has no session yet. A name is all it asks for: no
 * account, no password. The name is how the restaurant tells guests at one table apart; their cart
 * and orders are theirs alone.
 */
export function JoinTable({
  restaurantName,
  tableName,
  busy,
  error,
  onJoin,
}: {
  restaurantName?: string;
  tableName?: string;
  busy: boolean;
  error: string | null;
  onJoin: (name: string) => void;
}) {
  const [name, setName] = useState('');
  const trimmed = name.trim();

  return (
    <div className="km">
      <main className="km-state">
        <ChinarLeaf size={64} />
        <h1>{restaurantName ? `Welcome to ${restaurantName}` : 'Welcome'}</h1>
        <p>
          {tableName ? `${tableName}. ` : ''}
          Tell us your name so your order is yours. There is nothing to sign up for.
        </p>
        <form
          className="km-join-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (trimmed && !busy) onJoin(trimmed);
          }}
        >
          <label className="km-join-label" htmlFor="km-join-name">
            Your name
          </label>
          <input
            id="km-join-name"
            className="km-join-input"
            type="text"
            value={name}
            maxLength={40}
            autoComplete="given-name"
            onChange={(event) => setName(event.target.value)}
          />
          {error && (
            <p className="km-join-error" role="alert">
              {error}
            </p>
          )}
          <button type="submit" className="km-btn-fire" disabled={busy || !trimmed}>
            {busy ? 'Joining…' : 'Start ordering'}
          </button>
        </form>
      </main>
    </div>
  );
}
