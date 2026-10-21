import { useMemo, useState } from 'react';
import type { MenuItem, OptionGroup } from '../../api/types';
import { price } from '../../lib/format';
import {
  blockedByStock,
  defaultSelection,
  deltaText,
  previewPrice,
  selectionProblem,
  toggleOption,
} from '../../lib/menuOptions';
import { CIcon } from './CustomerIcon';
import { QuantityStepper } from './QuantityStepper';
import { Sheet } from './Sheet';
import { VegMark } from './VegMark';

/**
 * Choose portion, add-ons and spice level for a dish, then add it. The total shown is a preview;
 * the server re-checks every choice and works out the real price from its own copy of the options.
 */
export function DishOptionsSheet({
  item,
  working,
  error,
  onClose,
  onConfirm,
}: {
  item: MenuItem;
  working: boolean;
  /** A refusal from the server (an option sold out since the menu loaded, say). */
  error: string | null;
  onClose: () => void;
  onConfirm: (optionIds: number[], quantity: number) => void;
}) {
  const groups = item.optionGroups ?? [];
  const [selected, setSelected] = useState<ReadonlySet<number>>(() => defaultSelection(groups));
  const [quantity, setQuantity] = useState(1);

  const unit = useMemo(() => previewPrice(item.price, groups, selected), [item.price, groups, selected]);
  const problem = selectionProblem(groups, selected);
  const blocked = blockedByStock(groups);
  const canAdd = !problem && !blocked && !working;

  return (
    <Sheet title={item.name} onClose={onClose}>
      <div className="km-opt-head">
        <VegMark veg={item.veg} />
        {item.description && <p className="km-dish-desc">{item.description}</p>}
      </div>

      {item.allergenInfoProvided && (item.allergens?.length ?? 0) > 0 && (
        <p className="km-allergens">Contains: {item.allergens!.map((a) => a.label).join(', ')}</p>
      )}

      {groups.map((group) => (
        <OptionGroupView
          key={group.id}
          group={group}
          selected={selected}
          onToggle={(optionId) => setSelected((prev) => toggleOption(group, optionId, prev))}
        />
      ))}

      <p className="km-sheet-note km-opt-note">
        Allergen details come from the restaurant. If you have an allergy, please tell your server.
      </p>

      <div className="km-opt-foot">
        <QuantityStepper
          name={item.name}
          quantity={quantity}
          disabled={working}
          working={false}
          onChange={(q) => setQuantity(Math.min(50, Math.max(1, q)))}
        />
        <button
          type="button"
          className="km-btn-fire km-opt-add"
          disabled={!canAdd}
          onClick={() => onConfirm([...selected], quantity)}
        >
          {working ? 'Adding…' : `Add · ${price(unit * quantity)}`}
        </button>
      </div>

      <div role="status" aria-live="polite" className="km-opt-hint">
        {blocked
          ? `${blocked} is sold out right now, so this dish cannot be ordered at the moment.`
          : (problem ?? '')}
      </div>
      {error && (
        <p className="km-join-error" role="alert">
          {error}
        </p>
      )}
    </Sheet>
  );
}

function OptionGroupView({
  group,
  selected,
  onToggle,
}: {
  group: OptionGroup;
  selected: ReadonlySet<number>;
  onToggle: (optionId: number) => void;
}) {
  const role = group.kind === 'SINGLE' ? 'radio' : 'checkbox';
  const rule =
    group.kind === 'SINGLE'
      ? group.required
        ? 'Required · choose 1'
        : 'Optional · choose 1'
      : group.maxSelect !== null
        ? `${group.required ? 'Required' : 'Optional'} · up to ${group.maxSelect}`
        : group.required
          ? 'Required · choose any'
          : 'Optional · choose any';

  return (
    <fieldset className="km-opt-group">
      <legend>
        <strong>{group.name}</strong>
        <small>{rule}</small>
      </legend>
      <div role={group.kind === 'SINGLE' ? 'radiogroup' : 'group'} aria-label={group.name} className="km-opt-list">
        {group.options.map((option) => {
          const checked = selected.has(option.id);
          const delta = deltaText(option.priceDelta, price);
          return (
            <button
              key={option.id}
              type="button"
              role={role}
              aria-checked={checked}
              disabled={!option.available}
              className={`km-opt${checked ? ' is-on' : ''}`}
              onClick={() => onToggle(option.id)}
            >
              <span className="km-opt-mark" aria-hidden="true">
                {checked && <CIcon name="check" size={14} />}
              </span>
              <span className="km-opt-name">{option.name}</span>
              <span className="km-opt-delta">{option.available ? delta : 'Sold out'}</span>
            </button>
          );
        })}
      </div>
    </fieldset>
  );
}
