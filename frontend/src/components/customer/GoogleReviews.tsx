import { useQuery } from '@tanstack/react-query';
import { publicApi } from '../../api';
import type { ReactNode } from 'react';
import type { GoogleReview, PublicReviews } from '../../api/types';
import { isSafeExternal } from '../../lib/landing';
import { CIcon } from './CustomerIcon';

/** Rating and recent reviews; only fetched once the restaurant has a review link. */
export function usePublicReviews(qr: string, enabled: boolean) {
  return useQuery({
    queryKey: ['public-reviews', qr],
    queryFn: () => publicApi.reviews(qr),
    enabled,
    staleTime: 10 * 60_000,
    retry: false,
  });
}

const STAR = 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z';

/** Five stars with fractional fill; announced as "4.6 out of 5 stars". */
export function Stars({ value, size = 16 }: { value: number; size?: number }) {
  const pct = Math.max(0, Math.min(100, (value / 5) * 100));
  const row = (cls: string) => (
    <span className={cls}>
      {Array.from({ length: 5 }, (_, i) => (
        <svg key={i} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d={STAR} />
        </svg>
      ))}
    </span>
  );
  return (
    <span className="km-stars" role="img" aria-label={`${value.toFixed(1)} out of 5 stars`}>
      {row('km-stars-base')}
      <span className="km-stars-fill" style={{ width: `${pct}%` }}>
        {row('km-stars-on')}
      </span>
    </span>
  );
}

/** Opens the restaurant's reviews on Google Maps in a new tab. */
export function MapsReviewsLink({ href, className, children }: { href: string; className: string; children: ReactNode }) {
  // The server only stores https Google links; this refuses anything else (javascript:, data:, http:) at the point of use too.
  if (!isSafeExternal(href)) return null;
  return (
    <a className={className} href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <span className="sr-only"> (opens Google Maps in a new tab)</span>
    </a>
  );
}

export function WriteReviewButton({ href, label = 'Review us on Google', block = false }: { href: string; label?: string; block?: boolean }) {
  if (!isSafeExternal(href)) return null;
  return (
    <a
      className={`km-btn-ghost km-btn-google${block ? ' km-btn-block' : ''}`}
      href={href}
      target="_blank"
      rel="noopener noreferrer"
    >
      <CIcon name="star" size={18} />
      {label}
      <span className="sr-only"> (opens Google in a new tab)</span>
    </a>
  );
}

/** Body of the reviews sheet. Google requires author attribution and a source credit. */
export function ReviewsPanel({ data, loading }: { data: PublicReviews | undefined; loading: boolean }) {
  if (loading) {
    return (
      <div role="status" aria-label="Loading reviews">
        <div className="km-shimmer km-sk-block" />
      </div>
    );
  }
  if (!data?.writeReviewUrl) {
    return <p className="km-sheet-note">This restaurant has not linked its Google listing yet.</p>;
  }
  return (
    <div className="km-reviews">
      {data.rating != null && (
        <div className="km-reviews-summary">
          <span className="km-reviews-score">{data.rating.toFixed(1)}</span>
          <div>
            <Stars value={data.rating} size={20} />
            <span className="km-reviews-count">
              {(data.ratingCount ?? 0).toLocaleString('en-IN')} reviews on Google
            </span>
          </div>
        </div>
      )}
      {data.reviews.length > 0 && (
        <ul className="km-review-list">
          {data.reviews.map((r, i) => (
            <ReviewItem key={i} review={r} />
          ))}
        </ul>
      )}
      <WriteReviewButton href={data.writeReviewUrl} label="Write a review on Google" block />
      {data.mapsUrl && (
        <MapsReviewsLink href={data.mapsUrl} className="km-reviews-more">
          {data.rating != null ? 'See all reviews on Google Maps' : 'Read reviews on Google Maps'}
          <CIcon name="arrowRight" size={16} />
        </MapsReviewsLink>
      )}
      {data.rating != null && <p className="km-reviews-credit">Ratings and reviews from Google</p>}
    </div>
  );
}

function ReviewItem({ review }: { review: GoogleReview }) {
  const name = isSafeExternal(review.authorUrl) ? (
    <a href={review.authorUrl} target="_blank" rel="noopener noreferrer">
      {review.authorName}
    </a>
  ) : (
    review.authorName
  );
  return (
    <li className="km-review">
      <div className="km-review-head">
        {isSafeExternal(review.authorPhotoUrl) ? (
          <img src={review.authorPhotoUrl} alt="" width={36} height={36} loading="lazy" referrerPolicy="no-referrer" />
        ) : (
          <span className="km-review-avatar" aria-hidden="true">
            {review.authorName.charAt(0).toUpperCase()}
          </span>
        )}
        <div>
          <strong>{name}</strong>
          <span className="km-review-meta">
            {review.rating != null && <Stars value={review.rating} size={13} />}
            {review.relativeTime && <span>{review.relativeTime}</span>}
          </span>
        </div>
      </div>
      {review.text && <p className="km-review-text">{review.text}</p>}
    </li>
  );
}

/**
 * Prominent "review us" card for the diner screens. "Read reviews" opens the in-app sheet
 * when a rating is available, otherwise the reviews on Google Maps.
 */
export function ReviewCallout({
  qr,
  restaurantName,
  reviewUrl,
  mapsUrl,
  onOpenSheet,
  heading,
}: {
  qr: string;
  restaurantName: string;
  reviewUrl: string;
  mapsUrl: string | null;
  onOpenSheet: () => void;
  heading?: string;
}) {
  const reviews = usePublicReviews(qr, true);
  const rating = reviews.data?.rating ?? null;
  return (
    <section className="km-panel km-review-prompt" aria-labelledby="km-review-callout">
      <h2 className="km-panel-title" id="km-review-callout">
        <CIcon name="star" size={22} />
        {heading ?? `Enjoying ${restaurantName}?`}
      </h2>
      {rating != null && (
        <p className="km-review-callout-score">
          <Stars value={rating} size={16} />
          <strong>{rating.toFixed(1)}</strong>
          <span>from {(reviews.data?.ratingCount ?? 0).toLocaleString('en-IN')} Google reviews</span>
        </p>
      )}
      <p className="km-muted">
        Tell other diners what you loved. Your review goes on Google with your Google account and takes less than a
        minute.
      </p>
      <WriteReviewButton href={reviewUrl} label="Write a review on Google" block />
      {rating != null ? (
        <button type="button" className="km-link-btn km-btn-block" onClick={onOpenSheet}>
          Read what other guests say
        </button>
      ) : (
        mapsUrl && (
          <MapsReviewsLink href={mapsUrl} className="km-link-btn km-link-center km-btn-block">
            Read reviews on Google Maps
          </MapsReviewsLink>
        )
      )}
    </section>
  );
}
