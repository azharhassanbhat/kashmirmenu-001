import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { publicApi } from '../../api';
import { ErrorMessage } from '../ui';
import { feedbackProblem, MAX_COMMENT, RATINGS, ratingWord, stars } from '../../lib/feedback';

/**
 * "How was your meal?" for a served order. Private: it goes to the restaurant, not to Google, and the guest
 * is told so. The guest's order token is their only credential, exactly as for tracking, so this can rate
 * that one order and nothing else. Sending again replaces the earlier rating.
 */
export function FeedbackCard({
  token,
  rating: savedRating,
  comment: savedComment,
  onSaved,
}: {
  token: string;
  rating?: number | null;
  comment?: string | null;
  onSaved: () => void;
}) {
  const hasSaved = typeof savedRating === 'number';
  const [editing, setEditing] = useState(!hasSaved);
  const [rating, setRating] = useState<number | null>(savedRating ?? null);
  const [comment, setComment] = useState(savedComment ?? '');
  const [problem, setProblem] = useState<string | null>(null);

  const send = useMutation({
    mutationFn: () => publicApi.sendFeedback(token, rating ?? 0, comment.trim()),
    onSuccess: () => {
      setEditing(false);
      onSaved();
    },
  });

  if (!editing && hasSaved) {
    return (
      <section className="km-panel km-feedback" aria-live="polite">
        <h2 className="km-panel-title">Thank you</h2>
        <p className="km-feedback-saved">
          <span className="km-stars" aria-hidden="true">
            {stars(savedRating!)}
          </span>{' '}
          You rated your meal {savedRating} out of 5 ({ratingWord(savedRating!)}).
        </p>
        <button type="button" className="km-btn-ghost" onClick={() => setEditing(true)}>
          Change my rating
        </button>
      </section>
    );
  }

  function submit() {
    const p = feedbackProblem(rating, comment);
    setProblem(p);
    if (!p) send.mutate();
  }

  return (
    <section className="km-panel km-feedback" aria-labelledby="km-feedback-title">
      <h2 id="km-feedback-title" className="km-panel-title">
        How was your meal?
      </h2>
      <p className="muted">Only the restaurant sees this. It helps them put things right.</p>
      <div className="km-rating" role="radiogroup" aria-label="Your rating">
        {RATINGS.map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={rating === n}
            aria-label={`${n} out of 5, ${ratingWord(n)}`}
            className={`km-star${rating !== null && n <= rating ? ' is-on' : ''}`}
            onClick={() => {
              setRating(n);
              setProblem(null);
            }}
          >
            ★
          </button>
        ))}
      </div>
      <p className="km-rating-word" aria-live="polite">
        {ratingWord(rating)}
      </p>
      <label className="km-feedback-field">
        <span>Anything you would like us to know? (optional)</span>
        <textarea
          rows={3}
          maxLength={MAX_COMMENT}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
        <small className="muted">
          {comment.length} / {MAX_COMMENT}
        </small>
      </label>
      {problem && (
        <div className="km-alert" role="alert">
          {problem}
        </div>
      )}
      <div className="km-alert-slot">
        <ErrorMessage error={send.error} />
      </div>
      <button type="button" className="km-btn-fire km-btn-block" disabled={send.isPending} onClick={submit}>
        {send.isPending ? 'Sending…' : 'Send feedback'}
      </button>
    </section>
  );
}
