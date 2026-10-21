import type { ReactElement } from 'react';
import type { LandingActionKey } from '../../api/types';

/** Small decorative SVG illustrations shown on the right edge of each landing action card. Purely visual. */
function Stars() {
  return (
    <g fill="#ffc94d">
      {[0, 1, 2, 3, 4].map((i) => (
        <path key={i} transform={`translate(${24 + i * 17} 56) scale(.62)`} d="M12 1.5l3 6.4 7 .9-5.1 4.8 1.3 6.9L12 17.1 5.800 20.500l1.300-6.900L2 8.800l7-.9z" />
      ))}
    </g>
  );
}

const ART: Record<LandingActionKey, ReactElement> = {
  MENU: (
    <svg viewBox="0 0 120 80" aria-hidden="true">
      <g fill="none" stroke="#fff" strokeOpacity=".35" strokeWidth="2" strokeLinecap="round">
        <path d="M52 22c-4-5 4-8 0-13M66 22c-4-5 4-8 0-13M80 22c-4-5 4-8 0-13" />
      </g>
      <ellipse cx="66" cy="34" rx="38" ry="9" fill="#c2561a" />
      <ellipse cx="66" cy="33" rx="32" ry="6.5" fill="#8f2f12" />
      <circle cx="52" cy="31" r="3" fill="#ffb15a" />
      <circle cx="68" cy="30" r="3.5" fill="#e8742c" />
      <circle cx="82" cy="32" r="2.6" fill="#ffc45c" />
      <path d="M28 36h76c-2 22-14 34-38 34S30 58 28 36z" fill="url(#bowl)" />
      <path d="M40 48h52M44 57h44" stroke="#ffd48a" strokeOpacity=".5" strokeWidth="1.5" />
      <defs>
        <linearGradient id="bowl" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#d9923a" />
          <stop offset="1" stopColor="#6d3414" />
        </linearGradient>
      </defs>
    </svg>
  ),
  ASSISTANCE: (
    <svg viewBox="0 0 120 80" aria-hidden="true">
      <ellipse cx="66" cy="70" rx="40" ry="6" fill="#000" fillOpacity=".35" />
      <path d="M22 66c0-3 4-5 8-5h72c4 0 8 2 8 5H22z" fill="#c98a34" />
      <path d="M36 61c0-20 12-32 30-32s30 12 30 32z" fill="url(#bell)" />
      <path d="M48 56c0-12 6-20 14-23" stroke="#fff" strokeOpacity=".5" strokeWidth="3" strokeLinecap="round" fill="none" />
      <rect x="62" y="22" width="8" height="8" rx="3" fill="#ffd27a" />
      <defs>
        <linearGradient id="bell" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#ffd98a" />
          <stop offset="1" stopColor="#9a5a14" />
        </linearGradient>
      </defs>
    </svg>
  ),
  REVIEW: (
    <svg viewBox="0 0 120 80" aria-hidden="true">
      <g transform="translate(46 8) scale(1.3)">
        <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.4h6.5a5.6 5.6 0 0 1-2.4 3.7v3h3.9c2.3-2.1 3.5-5.200 3.5-8.800z" />
        <path fill="#34A853" d="M12 24c3.200 0 6-1.100 8-2.900l-3.900-3c-1.100.7-2.500 1.200-4.100 1.200-3.100 0-5.800-2.100-6.700-5H1.300v3.100A12 12 0 0 0 12 24z" />
        <path fill="#FBBC05" d="M5.300 14.300a7.200 7.200 0 0 1 0-4.600V6.600H1.300a12 12 0 0 0 0 10.800z" />
        <path fill="#EA4335" d="M12 4.800c1.800 0 3.300.6 4.600 1.800l3.400-3.400A12 12 0 0 0 1.300 6.600l4 3.100c.9-2.800 3.600-4.900 6.700-4.900z" />
      </g>
      <Stars />
    </svg>
  ),
  WIFI: (
    <svg viewBox="0 0 120 80" aria-hidden="true">
      <g fill="none" stroke="#53dfff" strokeLinecap="round" strokeWidth="5">
        <path d="M30 34a42 42 0 0 1 72 0" strokeOpacity=".45" />
        <path d="M42 46a27 27 0 0 1 48 0" strokeOpacity=".75" />
        <path d="M54 58a12 12 0 0 1 24 0" />
      </g>
      <circle cx="66" cy="68" r="4" fill="#53dfff" />
      <path d="M0 70c20-14 40 6 60-6s40-6 60-14" fill="none" stroke="#53dfff" strokeOpacity=".25" strokeWidth="2" />
    </svg>
  ),
  REWARDS: (
    <svg viewBox="0 0 120 80" aria-hidden="true">
      <rect x="40" y="36" width="62" height="38" rx="3" fill="#b3202f" />
      <rect x="36" y="26" width="70" height="14" rx="3" fill="#d72c3d" />
      <rect x="66" y="26" width="10" height="48" fill="#ffc45c" />
      <path d="M71 26c-12-2-20-10-14-15 6-4 13 4 14 15zM71 26c12-2 20-10 14-15-6-4-13 4-14 15z" fill="none" stroke="#ffc45c" strokeWidth="4" />
      <path d="M52 52l3 6 6.500.9-4.700 4.600 1.100 6.500-5.900-3.100-5.900 3.100 1.100-6.500-4.700-4.600 6.500-.9z" transform="translate(-4 -6) scale(.9)" fill="#ffc45c" />
    </svg>
  ),
};

export function CardArt({ action }: { action: LandingActionKey }) {
  return <span className="km-l-art">{ART[action]}</span>;
}

/** Brand glyphs for the social pills (simplified marks, decorative; the link text carries the name). */
export function SocialIcon({ label }: { label: string }) {
  switch (label) {
    case 'Instagram':
      return (
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <defs>
            <linearGradient id="ig" x1="0" y1="1" x2="1" y2="0">
              <stop offset="0" stopColor="#feda75" />
              <stop offset=".35" stopColor="#fa7e1e" />
              <stop offset=".65" stopColor="#d62976" />
              <stop offset="1" stopColor="#4f5bd5" />
            </linearGradient>
          </defs>
          <rect x="1.500" y="1.500" width="21" height="21" rx="6" fill="url(#ig)" />
          <circle cx="12" cy="12" r="4.600" fill="none" stroke="#fff" strokeWidth="2" />
          <circle cx="17.700" cy="6.300" r="1.300" fill="#fff" />
        </svg>
      );
    case 'YouTube':
      return (
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <rect x="1" y="4.500" width="22" height="15" rx="4.500" fill="#ff1f1f" />
          <path d="M10 9l5.500 3-5.500 3z" fill="#fff" />
        </svg>
      );
    default:
      return (
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <circle cx="12" cy="12" r="11" fill="#1877f2" />
          <path d="M13.200 22v-7.500h2.500l.4-3h-2.900V9.600c0-.9.300-1.500 1.500-1.500h1.500V5.400c-.3 0-1.200-.1-2.200-.1-2.200 0-3.700 1.300-3.700 3.800v2.400H8v3h2.300V22z" fill="#fff" />
        </svg>
      );
  }
}
