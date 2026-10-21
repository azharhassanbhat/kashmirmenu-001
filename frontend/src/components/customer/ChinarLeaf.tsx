import { useId } from 'react';

/** Outline of a Chinar (Platanus orientalis) leaf: five broad, serrated lobes on a curved petiole. */
export const LEAF_PATH =
  'M46.6 67.4 Q38.4 71.8 45.2 66.9 L41.3 70.1 L39.8 69.4 L36.3 70.7 L34.6 69.7 L31.3 70.2 L29.8 69.6 Q26.8 70.2 25.5 69.4 Q25.8 68.0 28.1 66.1 L28.6 64.5 L31.1 62.2 L31.4 60.4 L34.7 58.4 L35.2 56.9 L40.1 55.9 Q40.3 57.4 35.3 60.0 L28.5 58.0 L28.2 55.6 L24.0 52.3 L24.0 49.4 L21.0 45.7 L20.7 43.3 Q18.1 39.5 17.8 37.9 Q19.4 37.4 23.9 38.1 L26.2 37.3 L30.9 38.4 L33.5 37.1 L38.3 39.5 L40.6 38.7 L45.4 43.9 Q43.4 46.0 38.5 44.2 L36.2 35.9 L38.5 34.1 L39.2 27.6 L42.2 25.8 L44.0 20.3 L46.3 18.4 Q48.8 13.4 50.0 12.0 Q51.2 13.4 53.7 18.4 L56.0 20.3 L57.8 25.8 L60.8 27.6 L61.5 34.1 L63.8 35.9 L61.5 44.2 Q58.0 43.5 54.6 43.9 L59.4 38.7 L61.7 39.5 L66.5 37.1 L69.1 38.4 L73.8 37.3 L76.1 38.1 Q80.6 37.4 82.2 37.9 Q81.9 39.5 79.3 43.3 L79.0 45.7 L76.0 49.4 L76.0 52.3 L71.8 55.6 L71.5 58.0 L64.7 60.0 Q63.7 57.2 59.9 55.9 L64.8 56.9 L65.3 58.4 L68.6 60.4 L68.9 62.2 L71.4 64.5 L71.9 66.1 Q74.2 68.0 74.5 69.4 Q73.2 70.2 70.2 69.6 L68.7 70.2 L65.4 69.7 L63.7 70.7 L60.2 69.4 L58.7 70.1 L54.8 66.9 Q61.6 71.8 53.4 67.4 Q50.0 64.0 46.6 67.4 Z';
const VEINS = 'M50 58 L28.5 68 M50 58 L21.6 40.3 M50 58 L50 17.5 M50 58 L78.4 40.3 M50 58 L71.5 68';
const STEM = 'M50 58 C50 67 52 78 56 88';

type Tone = 'fire' | 'crimson' | 'orange' | 'saffron' | 'current';

const FILLS: Record<Exclude<Tone, 'fire'>, string> = {
  crimson: '#D51F32',
  orange: '#F47B30',
  saffron: '#FFC45C',
  current: 'currentColor',
};

/**
 * Decorative leaf. "fire" renders the full autumn gradient with veins; other tones render a flat
 * silhouette that stays crisp down to icon size.
 */
export function ChinarLeaf({
  tone = 'fire',
  size,
  className,
  veins = tone === 'fire',
}: {
  tone?: Tone;
  size?: number | string;
  className?: string;
  veins?: boolean;
}) {
  const id = useId();
  const fill = tone === 'fire' ? `url(#${id})` : FILLS[tone];
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="10 8 80 84"
      aria-hidden="true"
      focusable="false"
    >
      {tone === 'fire' && (
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#FF4A24" />
            <stop offset="0.55" stopColor="#D51F32" />
            <stop offset="1" stopColor="#8E1424" />
          </linearGradient>
        </defs>
      )}
      <path d={LEAF_PATH} fill={fill} />
      {veins && <path d={VEINS} stroke="#4A0A14" strokeWidth="1.1" fill="none" strokeLinecap="round" opacity="0.5" />}
      <path d={STEM} stroke={tone === 'fire' ? '#A5182A' : fill} strokeWidth="2.4" fill="none" strokeLinecap="round" />
    </svg>
  );
}

/** Fine rule with a centred leaf between two diamonds, as used under headlines. */
export function OrnamentDivider({ className = '' }: { className?: string }) {
  return (
    <span className={`km-ornament ${className}`} aria-hidden="true">
      <span className="km-ornament-line" />
      <span className="km-ornament-diamond" />
      <ChinarLeaf tone="orange" size={18} veins={false} />
      <span className="km-ornament-diamond" />
      <span className="km-ornament-line" />
    </span>
  );
}
