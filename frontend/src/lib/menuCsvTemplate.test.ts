import { describe, expect, it } from 'vitest';
import { CSV_COLUMNS, fileSize, MAX_CSV_BYTES, MENU_CSV_TEMPLATE } from './menuCsvTemplate';

describe('menu CSV template', () => {
  it('starts with the header the server expects', () => {
    expect(MENU_CSV_TEMPLATE.split('\n')[0]).toBe('name,category,price,veg,description,available,prep_time');
  });

  it('shows both veg and non-veg, so nobody copies a default', () => {
    expect(MENU_CSV_TEMPLATE).toMatch(/,veg,/);
    expect(MENU_CSV_TEMPLATE).toMatch(/,non-veg,/);
  });

  it('ends with a newline', () => {
    expect(MENU_CSV_TEMPLATE.endsWith('\n')).toBe(true);
  });

  it('documents every column the template uses, and marks the four required ones', () => {
    const header = MENU_CSV_TEMPLATE.split('\n')[0].split(',');
    expect(CSV_COLUMNS.map((c) => c.name)).toEqual(header);
    expect(CSV_COLUMNS.filter((c) => c.required).map((c) => c.name)).toEqual(['name', 'category', 'price', 'veg']);
  });

  it('is far below the size limit', () => {
    expect(MENU_CSV_TEMPLATE.length).toBeLessThan(MAX_CSV_BYTES / 100);
  });

  it('writes sizes for people', () => {
    expect(fileSize(500)).toBe('500 B');
    expect(fileSize(12_345)).toBe('12 KB');
    expect(fileSize(1_500_000)).toBe('1.5 MB');
  });
});
