import { describe, expect, it } from 'vitest';
import { addressLines, headingTone, quantityLine, receiptWhen, registrationLine, registrationProblem, tidyRegistration } from './receipt';

describe('tax registration number', () => {
  it('blank is fine: it is optional', () => {
    expect(registrationProblem('')).toBeNull();
    expect(registrationProblem('   ')).toBeNull();
  });

  it('tidies spaces and case, as the server does', () => {
    expect(tidyRegistration(' 29 abcde 1234 f1z5 ')).toBe('29ABCDE1234F1Z5');
    expect(registrationProblem('29abcde1234f1z5')).toBeNull();
  });

  it('is 5 to 30 characters of letters, digits, hyphen and slash', () => {
    expect(registrationProblem('ABCD')).toMatch(/5 to 30/);
    expect(registrationProblem('ABCDE')).toBeNull();
    expect(registrationProblem('A'.repeat(30))).toBeNull();
    expect(registrationProblem('A'.repeat(31))).toMatch(/5 to 30/);
    expect(registrationProblem('DE-123/45')).toBeNull();
  });

  it('refuses anything that could break a printout', () => {
    for (const bad of ['<script>1</script>', 'ABC12;DROP', 'ABC 12 & CO', 'GSTIN: 29ABCDE1234F1Z5', "ABC12'--", '=1+1+1']) {
      expect(registrationProblem(bad)).not.toBeNull();
    }
  });

  it('gives an example in the message', () => {
    expect(registrationProblem('no')).toMatch(/29ABCDE1234F1Z5/);
  });
});

describe('receipt wording', () => {
  it('prints the address as street then city and state, leaving out blanks', () => {
    expect(addressLines({ address: '12 Dal Lake Road', city: 'Srinagar', state: 'J&K' })).toEqual(['12 Dal Lake Road', 'Srinagar, J&K']);
    expect(addressLines({ address: '', city: 'Srinagar', state: null })).toEqual(['Srinagar']);
    expect(addressLines({ address: ' 12 Lane ', city: null, state: 'Delhi' })).toEqual(['12 Lane', 'Delhi']);
    expect(addressLines({})).toEqual([]);
  });

  it('labels the registration number with the tax name', () => {
    expect(registrationLine('GST', '29ABCDE1234F1Z5')).toBe('GST No: 29ABCDE1234F1Z5');
    expect(registrationLine(null, '29ABCDE1234F1Z5')).toBe('Tax No: 29ABCDE1234F1Z5');
    expect(registrationLine('  ', 'ABC12')).toBe('Tax No: ABC12');
    expect(registrationLine('GST', null)).toBeNull();
    expect(registrationLine('GST', '  ')).toBeNull();
  });

  it('writes the date in English whatever the device language', () => {
    expect(receiptWhen('2026-10-15T13:05:00+05:30')).toMatch(/Oct 2026/);
    expect(receiptWhen(null)).toBe('');
    expect(receiptWhen('not a date')).toBe('');
  });

  it('colours the heading by whether money is owed', () => {
    expect(headingTone('PAID')).toBe('ok');
    expect(headingTone('NO PAYMENT DUE')).toBe('ok');
    expect(headingTone('UNPAID')).toBe('owed');
    expect(headingTone('PART PAID')).toBe('owed');
    expect(headingTone('REFUNDED')).toBe('neutral');
  });

  it('writes a quantity line', () => {
    expect(quantityLine(2, '₹400.00')).toBe('2 × ₹400.00');
  });
});
