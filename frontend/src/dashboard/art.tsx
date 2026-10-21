import { useId } from 'react';

/*
 * Original, code-drawn artwork for the dashboard: a Kashmir evening (snow peaks, Dal Lake, houseboats, chinar
 * leaves) and small dish illustrations. Nothing is loaded from outside, so it works under the app's strict
 * content-security-policy and adds no image weight.
 */

const LEAF_PATH =
  'M12 1.5 13.7 6.1 17.4 4.4 16.4 9 21 8.4 18 12.2 22 14.2 16.4 14.8 17.2 19.4 13.1 17.2 12 22.5 10.9 17.2 6.8 19.4 7.6 14.8 2 14.2 6 12.2 3 8.4 7.6 9 6.6 4.4 10.3 6.1Z';

export function ChinarLeaf({ className, title }: { className?: string; title?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" role={title ? 'img' : undefined} aria-hidden={title ? undefined : true}>
      {title && <title>{title}</title>}
      <path d={LEAF_PATH} fill="currentColor" />
      <path d="M12 21.5V9M12 14l-3.6-3M12 14l3.6-3" stroke="rgba(0,0,0,.28)" strokeWidth=".7" fill="none" strokeLinecap="round" />
    </svg>
  );
}

/** The glowing brand leaf for the sidebar. */
export function BrandLeaf() {
  const id = useId();
  return (
    <svg className="kd-brand-leaf" viewBox="0 0 48 48" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-g`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#ff5a76" />
          <stop offset="1" stopColor="#c8002e" />
        </linearGradient>
        <filter id={`${id}-f`} x="-40%" y="-40%" width="180%" height="180%">
          <feGaussianBlur stdDeviation="2.2" />
        </filter>
      </defs>
      <g transform="scale(2)">
        <path d={LEAF_PATH} fill="#ff1744" opacity=".55" filter={`url(#${id}-f)`} />
        <path d={LEAF_PATH} fill={`url(#${id}-g)`} />
        <path d="M12 21.5V9M12 14l-3.6-3M12 14l3.6-3" stroke="rgba(255,255,255,.55)" strokeWidth=".6" fill="none" strokeLinecap="round" />
      </g>
    </svg>
  );
}

/** Low-opacity scattered leaves behind the sidebar (positions are in dashboard.css, `.kd-lp-1` to `.kd-lp-5`). */
export function LeafPattern() {
  return (
    <div className="kd-leaf-pattern" aria-hidden="true">
      {[1, 2, 3, 4, 5].map((n) => (
        <ChinarLeaf key={n} className={`kd-lp kd-lp-${n}`} />
      ))}
    </div>
  );
}

/* ---------- hero scenery ---------- */

const FALLING = [
  { x: 70, y: 40, s: 26, r: 25, c: '#e8352e', o: 0.9 },
  { x: 140, y: 120, s: 18, r: -40, c: '#ff8a3d', o: 0.8 },
  { x: 230, y: 30, s: 22, r: 70, c: '#c8102e', o: 0.85 },
  { x: 1380, y: 36, s: 30, r: -25, c: '#e8352e', o: 0.9 },
  { x: 1480, y: 110, s: 20, r: 50, c: '#ff9f43', o: 0.85 },
  { x: 1540, y: 40, s: 24, r: -70, c: '#d9142c', o: 0.9 },
  { x: 1300, y: 150, s: 16, r: 20, c: '#ff7043', o: 0.7 },
  { x: 40, y: 190, s: 14, r: 10, c: '#ff7043', o: 0.7 },
];

const HOUSES = [
  { x: 640, w: 70, h: 34 },
  { x: 726, w: 46, h: 24 },
  { x: 788, w: 86, h: 38 },
  { x: 890, w: 58, h: 28 },
  { x: 966, w: 40, h: 20 },
];

export function HeroArt() {
  const id = useId();
  const u = (n: string) => `${id}-${n}`;
  return (
    <svg
      className="kd-hero-svg"
      viewBox="0 0 1600 430"
      preserveAspectRatio="xMidYMin slice"
      role="img"
      aria-label="Snow-capped Himalayan peaks at sunset above Dal Lake, with lit houseboats and red chinar leaves"
    >
      <defs>
        <linearGradient id={u('sky')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#0a1030" />
          <stop offset=".38" stopColor="#2a1048" />
          <stop offset=".62" stopColor="#8a1a3c" />
          <stop offset=".8" stopColor="#e5472f" />
          <stop offset="1" stopColor="#ffa14a" />
        </linearGradient>
        <radialGradient id={u('sun')} cx=".5" cy="1" r=".6">
          <stop offset="0" stopColor="#ffd37a" stopOpacity=".95" />
          <stop offset=".35" stopColor="#ff8a3d" stopOpacity=".55" />
          <stop offset="1" stopColor="#ff4d4d" stopOpacity="0" />
        </radialGradient>
        <linearGradient id={u('snow')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#fff3f0" />
          <stop offset=".5" stopColor="#f6b6bf" />
          <stop offset="1" stopColor="#7c4a8c" />
        </linearGradient>
        <linearGradient id={u('far')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#6b3d78" />
          <stop offset="1" stopColor="#3a1744" />
        </linearGradient>
        <linearGradient id={u('near')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#2a0f2c" />
          <stop offset="1" stopColor="#12060f" />
        </linearGradient>
        <linearGradient id={u('lake')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#ff9a4a" stopOpacity=".85" />
          <stop offset=".35" stopColor="#a8264a" stopOpacity=".9" />
          <stop offset="1" stopColor="#10060f" />
        </linearGradient>
        <filter id={u('blur')} x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="3" />
        </filter>
        <filter id={u('soft')} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="1.4" />
        </filter>
      </defs>

      <rect width="1600" height="430" fill={`url(#${u('sky')})`} />
      <g transform="translate(0 -70)">
      <rect width="1600" height="340" fill={`url(#${u('sun')})`} />

      {/* stars */}
      {[120, 310, 480, 700, 910, 1130, 1260, 1450].map((x, i) => (
        <circle key={x} cx={x} cy={22 + ((i * 37) % 70)} r={i % 3 === 0 ? 1.4 : 0.9} fill="#fff" opacity={0.55} />
      ))}

      {/* distant range with snow caps */}
      <path
        d="M0 262 L90 210 L150 236 L250 150 L330 220 L410 176 L520 246 L640 132 L720 206 L790 170 L900 250 L1010 142 L1090 214 L1170 168 L1290 250 L1390 160 L1470 214 L1600 176 L1600 300 L0 300Z"
        fill={`url(#${u('far')})`}
      />
      <path
        d="M250 150 L292 190 L270 184 L252 204 L236 180 L212 186Z M640 132 L690 184 L664 176 L644 204 L620 172 L592 184Z M1010 142 L1056 190 L1032 182 L1012 208 L992 178 L964 188Z M1390 160 L1432 198 L1410 192 L1392 212 L1374 190 L1350 196Z"
        fill={`url(#${u('snow')})`}
        opacity=".95"
      />

      {/* nearer ridge */}
      <path
        d="M0 288 L110 246 L210 274 L330 226 L450 276 L560 250 L700 288 L860 258 L1000 292 L1140 252 L1290 290 L1420 244 L1520 276 L1600 258 L1600 330 L0 330Z"
        fill={`url(#${u('near')})`}
      />

      {/* shore line of tall trees */}
      {Array.from({ length: 46 }).map((_, i) => {
        const x = i * 36 + ((i * 7) % 13);
        const h = 26 + ((i * 11) % 20);
        return <path key={i} d={`M${x} 318 L${x + 7} ${318 - h} L${x + 14} 318Z`} fill="#0b040c" opacity=".92" />;
      })}

      {/* lakeside houses and houseboats, windows lit */}
      {HOUSES.map((h, i) => (
        <g key={h.x}>
          <path d={`M${h.x} 318 V${318 - h.h} L${h.x + h.w / 2} ${318 - h.h - 14} L${h.x + h.w} ${318 - h.h} V318Z`} fill="#150713" />
          {Array.from({ length: Math.max(2, Math.floor(h.w / 18)) }).map((_, k) => (
            <rect key={k} x={h.x + 7 + k * 16} y={318 - h.h + 7 + (i % 2) * 4} width="6" height="8" rx="1" fill="#ffc15a" opacity=".95" />
          ))}
        </g>
      ))}

      {/* lake */}
      <rect y="318" width="1600" height="260" fill={`url(#${u('lake')})`} />
      <g filter={`url(#${u('blur')})`} opacity=".8">
        <path d="M0 330 L1600 330" stroke="#ffb05a" strokeWidth="2" opacity=".5" />
        {HOUSES.map((h) => (
          <rect key={h.x} x={h.x} y={322} width={h.w} height={h.h * 0.8} fill="#ffc15a" opacity=".28" />
        ))}
      </g>
      {Array.from({ length: 14 }).map((_, i) => (
        <path key={i} d={`M${160 + i * 100} ${338 + (i % 5) * 14} h${60 + (i % 3) * 30}`} stroke="#ffd9a0" strokeWidth="1.2" opacity=".22" strokeLinecap="round" />
      ))}

      {/* a shikara on the water */}
      <g transform="translate(1110 352)">
        <path d="M0 6 Q40 18 86 6 L78 0 Q40 8 8 0Z" fill="#0d050c" />
        <path d="M10 0 Q40 -22 74 0Z" fill="#3a0f24" />
        <rect x="40" y="-24" width="1.6" height="24" fill="#0d050c" />
      </g>

      </g>

      {/* chinar leaves framing the corners */}
      {FALLING.map((l, i) => (
        <g key={i} transform={`translate(${l.x} ${l.y}) rotate(${l.r})`} opacity={l.o}>
          <g transform={`scale(${l.s / 24})`} filter={i % 3 === 0 ? `url(#${u('soft')})` : undefined}>
            <path d={LEAF_PATH} fill={l.c} />
          </g>
        </g>
      ))}
    </svg>
  );
}

/* ---------- dish illustrations (fallback when a dish has no photo) ---------- */

const DISH_HUES: [string, string][] = [
  ['#b3261e', '#ff8a3d'],
  ['#d9a441', '#fff2c4'],
  ['#8a3a1e', '#e9a05b'],
  ['#9c5a2a', '#f0c27a'],
  ['#7b2d4a', '#f08aa5'],
];

export function DishArt({ index, className }: { index: number; className?: string }) {
  const id = useId();
  const [a, b] = DISH_HUES[index % DISH_HUES.length];
  return (
    <svg className={className} viewBox="0 0 64 64" aria-hidden="true">
      <defs>
        <radialGradient id={`${id}-bg`} cx=".5" cy=".4" r=".8">
          <stop offset="0" stopColor={b} />
          <stop offset="1" stopColor={a} />
        </radialGradient>
      </defs>
      <rect width="64" height="64" fill="#1b0c16" />
      <ellipse cx="32" cy="36" rx="26" ry="9" fill="#000" opacity=".35" />
      <path d="M8 30 Q32 62 56 30Z" fill="#e9dccb" />
      <ellipse cx="32" cy="30" rx="24" ry="8" fill={`url(#${id}-bg)`} />
      <circle cx="22" cy="28" r="3" fill="#fff" opacity=".22" />
      <circle cx="40" cy="31" r="2.4" fill="#fff" opacity=".18" />
      <path d="M26 20 q2 -6 0 -9 M34 19 q2 -6 0 -9 M42 20 q2 -6 0 -9" stroke="#fff" strokeOpacity=".4" strokeWidth="1.4" fill="none" strokeLinecap="round" />
    </svg>
  );
}
