/** Words and arithmetic for private feedback. The server validates everything again; this gives a quick, clear answer. */

export const MAX_COMMENT = 500;
export const RATINGS = [1, 2, 3, 4, 5] as const;

const WORDS: Record<number, string> = { 1: 'Poor', 2: 'Fair', 3: 'Good', 4: 'Very good', 5: 'Excellent' };

export const ratingWord = (rating: number | null): string => (rating !== null ? (WORDS[rating] ?? '') : '');

/** What is wrong with the form, as a sentence, or null. Mirrors the server's FeedbackRules. */
export function feedbackProblem(rating: number | null, comment: string): string | null {
  if (rating === null || !Number.isInteger(rating) || rating < 1 || rating > 5) return 'Please choose a rating from 1 to 5.';
  if (Array.from(comment.trim()).length > MAX_COMMENT) return `Please keep your comment under ${MAX_COMMENT} characters.`;
  return null;
}

/** The average to one decimal, or a dash when nobody has rated (never "0.0": unrated is not rated zero). */
export const averageText = (average: number | null): string => (average === null ? '—' : average.toFixed(1));

/** Filled and empty stars for a whole rating: 4 gives "★★★★☆". */
export function stars(rating: number): string {
  const n = Math.max(0, Math.min(5, Math.round(rating)));
  return '★'.repeat(n) + '☆'.repeat(5 - n);
}

/** Width of a distribution bar, 0 to 100, relative to the biggest bar (so the biggest is full). */
export function barPercent(count: number, counts: number[]): number {
  const max = Math.max(0, ...counts);
  return max === 0 ? 0 : Math.round((count / max) * 100);
}

/** "1 rating" / "12 ratings". */
export const ratingsText = (n: number): string => `${n} ${n === 1 ? 'rating' : 'ratings'}`;
