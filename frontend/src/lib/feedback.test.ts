import { describe, expect, it } from 'vitest';
import { averageText, barPercent, feedbackProblem, MAX_COMMENT, ratingsText, ratingWord, stars } from './feedback';

describe('feedback form checks', () => {
  it('needs a whole rating from 1 to 5', () => {
    for (const bad of [null, 0, 6, -1, 2.5, Number.NaN]) {
      expect(feedbackProblem(bad, '')).toBe('Please choose a rating from 1 to 5.');
    }
    for (const good of [1, 2, 3, 4, 5]) expect(feedbackProblem(good, '')).toBeNull();
  });

  it('caps the comment at 500 characters, counting characters not bytes', () => {
    expect(feedbackProblem(5, 'x'.repeat(MAX_COMMENT))).toBeNull();
    expect(feedbackProblem(5, 'x'.repeat(MAX_COMMENT + 1))).toMatch(/under 500/);
    expect(feedbackProblem(5, '😀'.repeat(MAX_COMMENT))).toBeNull();
    expect(feedbackProblem(5, '😀'.repeat(MAX_COMMENT + 1))).toMatch(/under 500/);
  });

  it('ignores the spaces around a comment', () => {
    expect(feedbackProblem(5, '   ' + 'x'.repeat(MAX_COMMENT) + '   ')).toBeNull();
  });

  it('words each rating', () => {
    expect(ratingWord(1)).toBe('Poor');
    expect(ratingWord(5)).toBe('Excellent');
    expect(ratingWord(null)).toBe('');
    expect(ratingWord(9)).toBe('');
  });
});

describe('feedback figures', () => {
  it('shows a dash, never zero, when nobody has rated', () => {
    expect(averageText(null)).toBe('—');
    expect(averageText(4)).toBe('4.0');
    expect(averageText(3.5)).toBe('3.5');
  });

  it('draws filled and empty stars for a rating', () => {
    expect(stars(4)).toBe('★★★★☆');
    expect(stars(1)).toBe('★☆☆☆☆');
    expect(stars(5)).toBe('★★★★★');
    expect(stars(0)).toBe('☆☆☆☆☆');
    expect(stars(9)).toBe('★★★★★');
    expect(stars(-3)).toBe('☆☆☆☆☆');
  });

  it('sizes distribution bars against the biggest, and copes with no data', () => {
    const counts = [1, 0, 2, 4, 8];
    expect(barPercent(8, counts)).toBe(100);
    expect(barPercent(4, counts)).toBe(50);
    expect(barPercent(1, counts)).toBe(13);
    expect(barPercent(0, counts)).toBe(0);
    expect(barPercent(0, [0, 0, 0, 0, 0])).toBe(0);
  });

  it('says rating or ratings', () => {
    expect(ratingsText(1)).toBe('1 rating');
    expect(ratingsText(0)).toBe('0 ratings');
    expect(ratingsText(12)).toBe('12 ratings');
  });
});
