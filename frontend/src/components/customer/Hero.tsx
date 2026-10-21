import type { ReactNode } from 'react';
import { ChinarLeaf, OrnamentDivider } from './ChinarLeaf';
import { DalLakeScene } from './DalLakeScene';

/**
 * Cinematic opener. Without `imageSrc` it shows the illustrated Dal Lake scene; pass a licensed
 * photograph to replace it (the overlay keeps the headline readable either way).
 */
export function Hero({
  header,
  imageSrc,
  footer,
  afterCopy,
}: {
  header: ReactNode;
  imageSrc?: string;
  footer?: ReactNode;
  /** Shown under the footer line, e.g. the review actions. */
  afterCopy?: ReactNode;
}) {
  return (
    <section className="km-hero" aria-labelledby="km-hero-title">
      <div className="km-hero-art">
        {imageSrc ? <img src={imageSrc} alt="" fetchPriority="high" /> : <DalLakeScene />}
      </div>
      <div className="km-hero-leaves" aria-hidden="true">
        <ChinarLeaf className="km-leaf km-leaf-a" />
        <ChinarLeaf className="km-leaf km-leaf-b" tone="orange" veins />
        <ChinarLeaf className="km-leaf km-leaf-c" />
        <ChinarLeaf className="km-leaf km-leaf-d" tone="crimson" veins />
        <ChinarLeaf className="km-leaf km-leaf-e" tone="orange" />
      </div>
      {header}
      <div className="km-hero-copy">
        <h1 id="km-hero-title">Authentic Kashmiri Flavours</h1>
        <p>A culinary journey from the valleys of Kashmir</p>
        <OrnamentDivider />
        {footer}
        {afterCopy}
      </div>
    </section>
  );
}
