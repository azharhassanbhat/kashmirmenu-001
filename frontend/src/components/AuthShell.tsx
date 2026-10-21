import type { ReactNode } from 'react';
import { ChinarLeaf } from './customer/ChinarLeaf';
import { DalLakeScene } from './customer/DalLakeScene';
import { BrandMark, Icon } from './ui';

const POINTS = [
  'Live kitchen display & order tracking',
  'QR menus that update instantly',
  'Inventory, coupons and payments in one place',
];

/** Split-screen frame for sign in / register: brand panel on the left, form on the right (stacked on phones). */
export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <aside className="auth-aside">
        <DalLakeScene className="auth-scene" />
        <ChinarLeaf className="auth-leaf" size={110} />
        <div className="brand">
          <BrandMark />
          <span>
            Kashmir Menu
            <span className="brand-tag">Taste our heritage</span>
          </span>
        </div>
        <div>
          <h1>Run your dining room beautifully.</h1>
          <p>The calm, fast way to take orders from every table.</p>
          <ul>
            {POINTS.map((p) => (
              <li key={p}>
                <Icon name="check" />
                {p}
              </li>
            ))}
          </ul>
        </div>
      </aside>
      <main className="auth-main">{children}</main>
    </div>
  );
}
