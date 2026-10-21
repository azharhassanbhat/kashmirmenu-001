import { ChinarLeaf } from './ChinarLeaf';
import { CIcon } from './CustomerIcon';

/** KASHMIR MENU wordmark with the menu (More) and search controls either side. */
export function BrandHeader({ onOpenMore, onSearch }: { onOpenMore: () => void; onSearch?: () => void }) {
  return (
    <header className="km-brandbar">
      <button type="button" className="km-icon-btn" onClick={onOpenMore} aria-label="Open table menu" aria-haspopup="dialog">
        <CIcon name="burger" />
      </button>
      <div className="km-wordmark">
        <ChinarLeaf className="km-wordmark-leaf" size={34} />
        <span className="km-wordmark-name">
          <span className="km-cap">K</span>ashmir <span className="km-cap">M</span>enu
        </span>
        <span className="km-wordmark-tag">Taste our heritage</span>
      </div>
      {onSearch ? (
        <button type="button" className="km-icon-btn km-icon-btn-solid" onClick={onSearch} aria-label="Search dishes">
          <CIcon name="search" />
        </button>
      ) : (
        <span className="km-icon-btn-spacer" aria-hidden="true" />
      )}
    </header>
  );
}
