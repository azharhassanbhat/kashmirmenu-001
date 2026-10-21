import { CIcon } from './CustomerIcon';

export function QuantityStepper({
  name,
  quantity,
  disabled,
  working,
  onChange,
  size = 'md',
}: {
  name: string;
  quantity: number;
  disabled?: boolean;
  working?: boolean;
  onChange: (next: number) => void;
  size?: 'md' | 'sm';
}) {
  return (
    <div className={`km-stepper km-stepper-${size}${working ? ' is-working' : ''}`} role="group" aria-label={`Quantity of ${name}`}>
      <button type="button" aria-label={`Remove one ${name}`} disabled={disabled} onClick={() => onChange(quantity - 1)}>
        <CIcon name="minus" size={18} />
      </button>
      <output aria-live="polite">{quantity}</output>
      <button type="button" aria-label={`Add one ${name}`} disabled={disabled} onClick={() => onChange(quantity + 1)}>
        <CIcon name="plus" size={18} />
      </button>
    </div>
  );
}
