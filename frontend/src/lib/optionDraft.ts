import type { OptionGroup, OptionGroupInput, OptionKind } from '../api/types';

/** An option group as the editor holds it: text fields stay strings until they are saved. */
export interface DraftOption {
  key: string;
  id?: number;
  name: string;
  priceDelta: string;
  available: boolean;
  defaultOption: boolean;
}

export interface DraftGroup {
  key: string;
  id?: number;
  name: string;
  kind: OptionKind;
  required: boolean;
  maxSelect: string;
  options: DraftOption[];
}

let counter = 0;
export const newKey = () => `k${++counter}`;

export function blankOption(name = ''): DraftOption {
  return { key: newKey(), name, priceDelta: '0', available: true, defaultOption: false };
}

/** Starting points for the three things restaurants add most. Prices are left at 0 for the owner to set. */
export const TEMPLATES: Record<'portion' | 'addons' | 'spice', () => DraftGroup> = {
  portion: () => ({
    key: newKey(),
    name: 'Portion size',
    kind: 'SINGLE',
    required: true,
    maxSelect: '',
    options: [{ ...blankOption('Regular'), defaultOption: true }, blankOption('Large')],
  }),
  addons: () => ({
    key: newKey(),
    name: 'Add-ons',
    kind: 'MULTI',
    required: false,
    maxSelect: '',
    options: [blankOption('')],
  }),
  spice: () => ({
    key: newKey(),
    name: 'Spice level',
    kind: 'SINGLE',
    required: false,
    maxSelect: '',
    options: [blankOption('Mild'), blankOption('Medium'), blankOption('Hot')],
  }),
};

export function toDraft(groups: OptionGroup[]): DraftGroup[] {
  return groups.map((g) => ({
    key: newKey(),
    id: g.id,
    name: g.name,
    kind: g.kind,
    required: g.required,
    maxSelect: g.maxSelect === null ? '' : String(g.maxSelect),
    options: g.options.map((o) => ({
      key: newKey(),
      id: o.id,
      name: o.name,
      priceDelta: String(o.priceDelta),
      available: o.available,
      defaultOption: o.defaultOption,
    })),
  }));
}

export type DraftResult = { ok: true; groups: OptionGroupInput[] } | { ok: false; error: string };

/**
 * Turns the editor's drafts into the request, or says what to fix. These checks only catch typing
 * mistakes early; the server repeats and enforces every rule, including that the cheapest
 * combination still costs something.
 */
export function toInput(drafts: DraftGroup[]): DraftResult {
  const groups: OptionGroupInput[] = [];
  for (const g of drafts) {
    const name = g.name.trim();
    if (!name) return { ok: false, error: 'Every group needs a name.' };
    if (g.options.length === 0) return { ok: false, error: `"${name}" needs at least one option.` };

    let maxSelect: number | null = null;
    if (g.kind === 'MULTI' && g.maxSelect.trim() !== '') {
      maxSelect = Number(g.maxSelect);
      if (!Number.isInteger(maxSelect) || maxSelect < 1 || maxSelect > 20) {
        return { ok: false, error: `The maximum for "${name}" must be a whole number from 1 to 20.` };
      }
    }

    const options: OptionGroupInput['options'] = [];
    for (const o of g.options) {
      const optionName = o.name.trim();
      if (!optionName) return { ok: false, error: `Every option in "${name}" needs a name.` };
      const raw = o.priceDelta.trim() === '' ? '0' : o.priceDelta.trim();
      const delta = Number(raw);
      if (!Number.isFinite(delta) || Math.abs(delta) > 9999.99) {
        return { ok: false, error: `"${optionName}" needs a price change between -9999.99 and 9999.99.` };
      }
      options.push({
        id: o.id,
        name: optionName,
        priceDelta: Math.round(delta * 100) / 100,
        available: o.available,
        defaultOption: o.defaultOption,
      });
    }

    groups.push({ id: g.id, name, kind: g.kind, required: g.required, maxSelect, options });
  }
  return { ok: true, groups };
}

/** Moves item `from` to `to` in a list, returning a new list. Out-of-range moves change nothing. */
export function move<T>(list: T[], from: number, to: number): T[] {
  if (from < 0 || to < 0 || from >= list.length || to >= list.length) return list;
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}
