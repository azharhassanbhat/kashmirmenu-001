import { describe, expect, it } from 'vitest';
import type { OptionGroup } from '../api/types';
import { blankOption, move, TEMPLATES, toDraft, toInput, type DraftGroup } from './optionDraft';

const draft = (over: Partial<DraftGroup> = {}): DraftGroup => ({
  key: 'g', name: 'Portion', kind: 'SINGLE', required: true, maxSelect: '',
  options: [{ ...blankOption('Half'), priceDelta: '-80' }, blankOption('Full')], ...over,
});

describe('option editor drafts', () => {
  it('turns drafts into the request, parsing prices and trimming names', () => {
    const r = toInput([draft({ name: '  Portion ' })]);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.groups[0].name).toBe('Portion');
      expect(r.groups[0].options[0].priceDelta).toBe(-80);
      expect(r.groups[0].options[1].priceDelta).toBe(0);
      expect(r.groups[0].maxSelect).toBeNull();
    }
  });

  it('keeps ids so saving does not recreate what already exists', () => {
    const stored: OptionGroup[] = [{ id: 7, name: 'Extras', kind: 'MULTI', required: false, maxSelect: 2,
      options: [{ id: 70, name: 'Raita', priceDelta: 30, available: true, defaultOption: false }] }];
    const r = toInput(toDraft(stored));
    expect(r.ok && r.groups[0].id).toBe(7);
    expect(r.ok && r.groups[0].options[0].id).toBe(70);
    expect(r.ok && r.groups[0].maxSelect).toBe(2);
  });

  it('treats a blank price change as no change', () => {
    const r = toInput([draft({ options: [{ ...blankOption('Plain'), priceDelta: '  ' }] })]);
    expect(r.ok && r.groups[0].options[0].priceDelta).toBe(0);
  });

  it('rounds to two decimal places', () => {
    const r = toInput([draft({ options: [{ ...blankOption('X'), priceDelta: '12.345' }] })]);
    expect(r.ok && r.groups[0].options[0].priceDelta).toBe(12.35);
  });

  it('says what to fix instead of sending nonsense', () => {
    expect(toInput([draft({ name: ' ' })])).toEqual({ ok: false, error: 'Every group needs a name.' });
    expect(toInput([draft({ options: [] })]).ok).toBe(false);
    expect(toInput([draft({ options: [blankOption('')] })]).ok).toBe(false);
    expect(toInput([draft({ options: [{ ...blankOption('X'), priceDelta: 'abc' }] })]).ok).toBe(false);
    expect(toInput([draft({ options: [{ ...blankOption('X'), priceDelta: '99999' }] })]).ok).toBe(false);
  });

  it('only a multiple-choice group has a maximum, and it must be 1 to 20', () => {
    expect(toInput([draft({ kind: 'SINGLE', maxSelect: '5' })]).ok && true).toBe(true);
    const single = toInput([draft({ kind: 'SINGLE', maxSelect: '5' })]);
    expect(single.ok && single.groups[0].maxSelect).toBeNull();
    expect(toInput([draft({ kind: 'MULTI', maxSelect: '0' })]).ok).toBe(false);
    expect(toInput([draft({ kind: 'MULTI', maxSelect: '2.5' })]).ok).toBe(false);
    expect(toInput([draft({ kind: 'MULTI', maxSelect: '21' })]).ok).toBe(false);
    const multi = toInput([draft({ kind: 'MULTI', maxSelect: '3' })]);
    expect(multi.ok && multi.groups[0].maxSelect).toBe(3);
  });

  it('offers templates for portion, add-ons and spice level', () => {
    expect(TEMPLATES.portion().required).toBe(true);
    expect(TEMPLATES.addons().kind).toBe('MULTI');
    expect(TEMPLATES.spice().options.map((o) => o.name)).toEqual(['Mild', 'Medium', 'Hot']);
  });

  it('reorders without mutating and ignores impossible moves', () => {
    const list = ['a', 'b', 'c'];
    expect(move(list, 0, 2)).toEqual(['b', 'c', 'a']);
    expect(list).toEqual(['a', 'b', 'c']);
    expect(move(list, 0, -1)).toBe(list);
    expect(move(list, 2, 3)).toBe(list);
  });
});
