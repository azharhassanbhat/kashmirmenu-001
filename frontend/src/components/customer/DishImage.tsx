import { useState } from 'react';
import { ChinarLeaf } from './ChinarLeaf';

/**
 * Dish photo with fixed intrinsic size (no layout shift) and a designed placeholder when the
 * restaurant has not uploaded one, or the URL fails to load.
 */
export function DishImage({
  src,
  eager = false,
  className = '',
  size = 320,
}: {
  src: string | null;
  eager?: boolean;
  className?: string;
  size?: number;
}) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) {
    return (
      <div className={`km-dish-img km-dish-placeholder ${className}`}>
        <ChinarLeaf tone="current" size="42%" veins={false} />
        <span>No photo yet</span>
      </div>
    );
  }
  return (
    <img
      className={`km-dish-img ${className}`}
      src={src}
      alt=""
      width={size}
      height={size}
      loading={eager ? 'eager' : 'lazy'}
      fetchPriority={eager ? 'high' : 'auto'}
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}
