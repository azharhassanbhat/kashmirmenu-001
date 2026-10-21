import type { OptionGroup, SelectedOption } from '../api/types';

/*
 * The picker's preview and hints. The SERVER is the authority: it re-validates every choice and
 * works out the price from its own copy of the options, so nothing here can change what a guest is
 * charged. This only keeps the screen honest while they choose, so the total they see before tapping
 * Add is the total they will be quoted.
 */

/** Pre-selected options: the available default of each group (first one only in a single-choice group). */
export function defaultSelection(groups: OptionGroup[]): Set<number> {
  const picked = new Set<number>();
  for (const group of groups) {
    for (const option of group.options) {
      if (!option.defaultOption || !option.available) continue;
      picked.add(option.id);
      if (group.kind === 'SINGLE') break;
    }
  }
  return picked;
}

/** Base price plus the price change of every selected option that exists. */
export function previewPrice(base: number, groups: OptionGroup[], selected: ReadonlySet<number>): number {
  let total = base;
  for (const group of groups) {
    for (const option of group.options) {
      if (selected.has(option.id)) total += option.priceDelta;
    }
  }
  return Math.round(total * 100) / 100;
}

/** The first thing wrong with a selection, as a sentence for the guest, or null when it is fine. */
export function selectionProblem(groups: OptionGroup[], selected: ReadonlySet<number>): string | null {
  for (const group of groups) {
    const count = group.options.filter((o) => selected.has(o.id)).length;
    if (group.required && count === 0) return `Please choose ${group.name}.`;
    if (group.kind === 'SINGLE' && count > 1) return `Please choose only one for ${group.name}.`;
    if (group.kind === 'MULTI' && group.maxSelect !== null && count > group.maxSelect) {
      return `You can choose up to ${group.maxSelect} for ${group.name}.`;
    }
  }
  return null;
}

/**
 * Tapping an option. Single choice: picks it (and, when the group is optional, tapping the picked
 * one again clears it). Multiple choice: toggles it, but will not go past the group's maximum.
 */
export function toggleOption(group: OptionGroup, optionId: number, selected: ReadonlySet<number>): Set<number> {
  const next = new Set(selected);
  const option = group.options.find((o) => o.id === optionId);
  if (!option || !option.available) return next;

  if (group.kind === 'SINGLE') {
    const wasPicked = next.has(optionId);
    group.options.forEach((o) => next.delete(o.id));
    if (!(wasPicked && !group.required)) next.add(optionId);
    return next;
  }

  if (next.has(optionId)) {
    next.delete(optionId);
  } else {
    const count = group.options.filter((o) => next.has(o.id)).length;
    if (group.maxSelect === null || count < group.maxSelect) next.add(optionId);
  }
  return next;
}

/** "+₹30", "−₹80", or "" when the option costs nothing extra. */
export function deltaText(delta: number, format: (n: number) => string): string {
  if (!delta) return '';
  return `${delta > 0 ? '+' : '\u2212'}${format(Math.abs(delta))}`;
}

/** "Family · Raita · Egg": how a line's choices read under the dish name. Empty when there are none. */
export function optionsSummary(options: SelectedOption[] | undefined): string {
  return (options ?? []).map((o) => o.optionName).join(' \u00b7 ');
}

/** "2 × Biryani (Family · Raita)": one line of an order as plain text, for tables and summaries. */
export function lineLabel(quantity: number, name: string, options: SelectedOption[] | undefined): string {
  const summary = optionsSummary(options);
  return `${quantity} \u00d7 ${name}${summary ? ` (${summary})` : ''}`;
}

/** True when a dish must be configured before it can be added (it has any option group to show). */
export function hasOptions(item: { optionGroups?: OptionGroup[] }): boolean {
  return (item.optionGroups?.length ?? 0) > 0;
}

/** A required group whose every option is switched off: the dish cannot be ordered until staff fix it. */
export function blockedByStock(groups: OptionGroup[]): string | null {
  for (const group of groups) {
    if (group.required && group.options.every((o) => !o.available)) return group.name;
  }
  return null;
}

/**
 * What the dish costs at its cheapest mandatory choice: the base price plus the lowest available
 * price change of each REQUIRED group. Optional extras never lower or raise it. Shown as "from" on
 * the menu so a "Half portion" dish is not advertised at its full-portion price.
 */
export function startingPrice(base: number, groups: OptionGroup[]): number {
  let total = base;
  for (const group of groups) {
    if (!group.required) continue;
    const deltas = group.options.filter((o) => o.available).map((o) => o.priceDelta);
    if (deltas.length > 0) total += Math.min(...deltas);
  }
  return Math.round(total * 100) / 100;
}
