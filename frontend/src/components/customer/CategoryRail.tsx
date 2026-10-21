import { useEffect, useRef, type ReactNode } from 'react';
import { ChinarLeaf } from './ChinarLeaf';
import { CIcon, iconForCategory } from './CustomerIcon';

export type CategoryFilter = number | 'all';

export function CategoryRail({
  categories,
  active,
  onChange,
}: {
  categories: { id: number; name: string }[];
  active: CategoryFilter;
  onChange: (next: CategoryFilter) => void;
}) {
  const railRef = useRef<HTMLDivElement>(null);

  // Keep the selected chip in view when it changes (e.g. via "See all"). Horizontal only, so
  // the page itself never jumps.
  useEffect(() => {
    const track = railRef.current;
    const el = track?.querySelector<HTMLElement>('[aria-pressed="true"]');
    if (!track || !el) return;
    const left = el.offsetLeft; // the track is position: relative, so this is track-relative
    const right = left + el.offsetWidth;
    if (left < track.scrollLeft || right > track.scrollLeft + track.clientWidth) {
      track.scrollTo({ left: Math.max(0, left - 16), behavior: 'smooth' });
    }
  }, [active]);

  const chip = (id: CategoryFilter, label: string, icon: ReactNode) => (
    <button
      key={id}
      type="button"
      className="km-cat"
      aria-pressed={active === id}
      onClick={() => onChange(id)}
    >
      <span className="km-cat-icon">{icon}</span>
      <span className="km-cat-label">{label}</span>
    </button>
  );

  return (
    <nav className="km-rail" aria-label="Filter by category">
      <div className="km-rail-track" ref={railRef}>
        {chip('all', 'All', <ChinarLeaf tone="current" size={24} veins={false} />)}
        {categories.map((c) => chip(c.id, c.name, <CIcon name={iconForCategory(c.name)} size={24} />))}
      </div>
    </nav>
  );
}
