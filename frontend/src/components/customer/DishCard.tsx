import { memo, type CSSProperties } from 'react';
import type { MenuItem } from '../../api/types';
import { price } from '../../lib/format';
import { hasOptions, startingPrice } from '../../lib/menuOptions';
import { CIcon } from './CustomerIcon';
import { DishImage } from './DishImage';
import { QuantityStepper } from './QuantityStepper';
import { VegMark } from './VegMark';

export interface DishCardProps {
  item: MenuItem;
  quantity: number;
  cartItemId: number | null;
  disabled: boolean;
  working: boolean;
  eagerImage: boolean;
  index: number;
  onAdd: (item: MenuItem) => void;
  onChangeQuantity: (cartItemId: number, quantity: number) => void;
}

export const DishCard = memo(function DishCard({
  item,
  quantity,
  cartItemId,
  disabled,
  working,
  eagerImage,
  index,
  onAdd,
  onChangeQuantity,
}: DishCardProps) {
  const unavailable = !item.available;
  const headingId = `dish-${item.id}`;
  const customisable = hasOptions(item);
  const from = customisable ? startingPrice(item.price, item.optionGroups ?? []) : item.price;
  const badges: string[] = [];
  if (item.todaysSpecial) badges.push("Today's special");
  if (item.chefPick) badges.push("Chef's pick");
  if (item.popular) badges.push('Popular');
  const allergens = item.allergens ?? [];
  return (
    <article
      className={`km-dish${unavailable ? ' is-off' : ''}${quantity > 0 ? ' is-in-cart' : ''}`}
      aria-labelledby={headingId}
      style={{ '--i': Math.min(index, 6) } as CSSProperties}
    >
      <DishImage src={item.imageUrl} eager={eagerImage} />
      <div className="km-dish-body">
        <div className="km-dish-top">
          <h3 id={headingId}>{item.name}</h3>
          <VegMark veg={item.veg} />
        </div>
        {badges.length > 0 && (
          <ul className="km-badges" aria-label="Highlights">
            {badges.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        )}
        {item.description && <p className="km-dish-desc">{item.description}</p>}
        {(item.dietaryLabels?.length ?? 0) > 0 && (
          <ul className="km-tags" aria-label="Dietary information">
            {item.dietaryLabels!.map((t) => (
              <li key={t.code}>{t.label}</li>
            ))}
          </ul>
        )}
        {item.allergenInfoProvided && (
          <p className="km-allergens">
            {allergens.length > 0 ? `Contains: ${allergens.map((a) => a.label).join(', ')}` : 'No allergens declared'}
          </p>
        )}
        <div className="km-dish-foot">
          <span className="km-price">
            {from < item.price && <small className="km-from">from </small>}
            {price(from)}
          </span>
          {unavailable ? (
            <span className="km-chip-off">Unavailable today</span>
          ) : customisable ? (
            // A dish with options is never a plain +/- stepper: each combination is its own line, which
            // the cart screen manages. This button opens the picker; the count shows what is already in.
            <div className="km-dish-actions">
              {quantity > 0 && <span className="km-in-order">{quantity} in order</span>}
              <button
                type="button"
                className={`km-btn-add${working ? ' is-working' : ''}`}
                disabled={disabled}
                onClick={() => onAdd(item)}
                aria-label={`${quantity > 0 ? 'Add another' : 'Choose options for'} ${item.name}`}
              >
                <CIcon name="plus" size={18} />
                {quantity > 0 ? 'Another' : 'Choose'}
              </button>
            </div>
          ) : quantity > 0 && cartItemId !== null ? (
            <QuantityStepper
              name={item.name}
              quantity={quantity}
              disabled={disabled}
              working={working}
              onChange={(q) => onChangeQuantity(cartItemId, q)}
            />
          ) : (
            <button
              type="button"
              className={`km-btn-add${working ? ' is-working' : ''}`}
              disabled={disabled}
              onClick={() => onAdd(item)}
              aria-label={`Add ${item.name} to your order`}
            >
              <CIcon name="plus" size={18} />
              Add
            </button>
          )}
        </div>
      </div>
    </article>
  );
});

export function DishCardSkeleton() {
  return (
    <div className="km-dish km-dish-skeleton" aria-hidden="true">
      <div className="km-dish-img km-shimmer" />
      <div className="km-dish-body">
        <div className="km-shimmer km-sk-line" style={{ width: '70%' }} />
        <div className="km-shimmer km-sk-line km-sk-thin" style={{ width: '90%' }} />
        <div className="km-shimmer km-sk-line km-sk-thin" style={{ width: '55%' }} />
        <div className="km-dish-foot">
          <div className="km-shimmer km-sk-line" style={{ width: '4rem' }} />
          <div className="km-shimmer km-sk-btn" />
        </div>
      </div>
    </div>
  );
}
