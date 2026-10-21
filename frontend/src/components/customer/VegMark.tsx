/** FSSAI-style diet mark: square with a dot for vegetarian, a triangle for non-vegetarian. */
export function VegMark({ veg }: { veg: boolean }) {
  const label = veg ? 'Vegetarian' : 'Non-vegetarian';
  return (
    <span className={`km-veg ${veg ? 'is-veg' : 'is-nonveg'}`} role="img" aria-label={label} title={label}>
      <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <rect x="1" y="1" width="14" height="14" rx="2.5" />
        {veg ? <circle cx="8" cy="8" r="3.6" /> : <path d="M8 4.2 12 11.4H4z" />}
      </svg>
    </span>
  );
}
