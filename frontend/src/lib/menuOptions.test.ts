import { describe, expect, it } from 'vitest';
import type { OptionGroup } from '../api/types';
import {
  blockedByStock,
  defaultSelection,
  deltaText,
  lineLabel,
  optionsSummary,
  previewPrice,
  selectionProblem,
  startingPrice,
  toggleOption,
} from './menuOptions';

const opt = (id: number, name: string, priceDelta: number, extra: Partial<OptionGroup['options'][0]> = {}) => ({
  id, name, priceDelta, available: true, defaultOption: false, ...extra,
});

const portion: OptionGroup = {
  id: 1, name: 'Portion', kind: 'SINGLE', required: true, maxSelect: null,
  options: [opt(10, 'Half', -80), opt(11, 'Full', 0, { defaultOption: true }), opt(12, 'Family', 250)],
};
const extras: OptionGroup = {
  id: 2, name: 'Extras', kind: 'MULTI', required: false, maxSelect: 2,
  options: [opt(20, 'Raita', 30), opt(21, 'Egg', 20), opt(22, 'Salan', 25), opt(23, 'Papad', 10, { available: false })],
};
const spice: OptionGroup = {
  id: 3, name: 'Spice', kind: 'SINGLE', required: false, maxSelect: null,
  options: [opt(30, 'Mild', 0), opt(31, 'Hot', 0)],
};
const groups = [portion, extras, spice];

describe('menu option helpers', () => {
  it('pre-selects the default option of a group', () => {
    expect([...defaultSelection(groups)]).toEqual([11]);
  });

  it('never pre-selects an option that is switched off', () => {
    const g: OptionGroup = { ...portion, options: [opt(1, 'Full', 0, { defaultOption: true, available: false })] };
    expect(defaultSelection([g]).size).toBe(0);
  });

  it('previews base plus every selected change', () => {
    expect(previewPrice(320, groups, new Set([12, 20, 21]))).toBe(620);
    expect(previewPrice(320, groups, new Set([10]))).toBe(240);
    expect(previewPrice(320, groups, new Set())).toBe(320);
  });

  it('asks for a required group before anything else', () => {
    expect(selectionProblem(groups, new Set([20]))).toBe('Please choose Portion.');
    expect(selectionProblem(groups, new Set([11]))).toBeNull();
  });

  it('flags too many in a group', () => {
    expect(selectionProblem(groups, new Set([11, 20, 21, 22]))).toBe('You can choose up to 2 for Extras.');
    expect(selectionProblem(groups, new Set([10, 11]))).toBe('Please choose only one for Portion.');
  });

  it('single choice replaces the previous pick', () => {
    expect([...toggleOption(portion, 12, new Set([11]))]).toEqual([12]);
  });

  it('a required single choice cannot be cleared, an optional one can', () => {
    expect([...toggleOption(portion, 11, new Set([11]))]).toEqual([11]);
    expect(toggleOption(spice, 30, new Set([30])).size).toBe(0);
  });

  it('multiple choice toggles and stops at its maximum', () => {
    let s = new Set<number>();
    s = toggleOption(extras, 20, s);
    s = toggleOption(extras, 21, s);
    s = toggleOption(extras, 22, s);
    expect([...s].sort()).toEqual([20, 21]);
    s = toggleOption(extras, 20, s);
    expect([...s]).toEqual([21]);
  });

  it('ignores a tap on an option that is sold out', () => {
    expect(toggleOption(extras, 23, new Set()).size).toBe(0);
  });

  it('writes price changes with a sign and nothing for free options', () => {
    const f = (n: number) => `Rs ${n}`;
    expect(deltaText(30, f)).toBe('+Rs 30');
    expect(deltaText(-80, f)).toBe('\u2212Rs 80');
    expect(deltaText(0, f)).toBe('');
  });

  it('summarises a line\'s choices', () => {
    expect(optionsSummary([{ groupName: 'Portion', optionName: 'Full', priceDelta: 0 }, { groupName: 'Extras', optionName: 'Raita', priceDelta: 30 }])).toBe('Full \u00b7 Raita');
    expect(optionsSummary(undefined)).toBe('');
  });

  it('spots a required group with everything sold out', () => {
    expect(blockedByStock([{ ...portion, options: portion.options.map((o) => ({ ...o, available: false })) }])).toBe('Portion');
    expect(blockedByStock(groups)).toBeNull();
  });

  it('advertises the cheapest mandatory choice, ignoring optional extras', () => {
    expect(startingPrice(320, groups)).toBe(240);
    expect(startingPrice(100, [extras, spice])).toBe(100);
    expect(startingPrice(100, [{ ...portion, options: portion.options.map((o) => ({ ...o, available: o.id !== 10 })) }])).toBe(100);
  });

  it('writes an order line as plain text for tables', () => {
    expect(lineLabel(2, 'Biryani', [{ groupName: 'P', optionName: 'Family', priceDelta: 250 }, { groupName: 'E', optionName: 'Raita', priceDelta: 30 }])).toBe('2 \u00d7 Biryani (Family \u00b7 Raita)');
    expect(lineLabel(1, 'Naan', undefined)).toBe('1 \u00d7 Naan');
  });
});
