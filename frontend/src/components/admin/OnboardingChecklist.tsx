import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { onboardingApi } from '../../api';

/**
 * "Get started" for a new restaurant. Every step is ticked because the thing exists (a category, a dish,
 * a table), never because someone said so, and the whole card disappears once the required steps are
 * done. If the check cannot be loaded it simply does not appear: it must never get in the way.
 */
export function OnboardingChecklist() {
  const progress = useQuery({ queryKey: ['onboarding'], queryFn: onboardingApi.get, retry: false });
  const p = progress.data;
  if (!p || p.complete) return null;

  return (
    <section className="card onboarding" aria-labelledby="onboarding-title">
      <div className="onboarding-head">
        <h3 id="onboarding-title">Get your restaurant ready</h3>
        <span className="muted">
          {p.requiredDone} of {p.requiredTotal} done
        </span>
      </div>
      <div
        className="onboarding-bar"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={p.percent}
        aria-label="Setup progress"
      >
        <span style={{ width: `${p.percent}%` }} />
      </div>
      <ol className="onboarding-steps">
        {p.steps.map((s) => (
          <li key={s.key} className={`${s.done ? 'is-done' : ''}${s.key === p.nextKey ? ' is-next' : ''}`}>
            <span className="onboarding-mark" aria-hidden="true">
              {s.done ? '✓' : ''}
            </span>
            <span className="onboarding-text">
              <strong>
                {s.title}
                {!s.required && <span className="muted"> (optional)</span>}
              </strong>
              {!s.done && <small>{s.hint}</small>}
            </span>
            {!s.done && (
              <Link className={`btn btn-sm${s.key === p.nextKey ? ' btn-primary' : ''}`} to={s.route}>
                {s.key === p.nextKey ? 'Do this next' : 'Open'}
              </Link>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
