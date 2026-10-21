import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { homeFor, useAuth } from '../../auth/AuthContext';
import { AuthShell } from '../../components/AuthShell';
import { ErrorMessage, Field } from '../../components/ui';

export default function RegisterOwnerPage() {
  const { user, registerOwner } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({
    restaurantName: '',
    restaurantEmail: '',
    restaurantPhone: '',
    ownerName: '',
    ownerEmail: '',
    password: '',
  });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to={homeFor(user.role)} replace />;

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [key]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = await registerOwner({
        ...form,
        restaurantEmail: form.restaurantEmail.trim() || undefined,
        restaurantPhone: form.restaurantPhone.trim() || undefined,
      });
      navigate(homeFor(me.role), { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      <form className="auth-form" onSubmit={submit} style={{ maxWidth: 520 }}>
        <h2>Register your restaurant</h2>
        <p className="lead">Create your owner account and start building your menu.</p>
        <Field label="Restaurant name">
          <input required value={form.restaurantName} onChange={set('restaurantName')} />
        </Field>
        <div className="form-row">
          <Field label="Restaurant email (optional)">
            <input type="email" value={form.restaurantEmail} onChange={set('restaurantEmail')} />
          </Field>
          <Field label="Restaurant phone (optional)">
            <input type="tel" value={form.restaurantPhone} onChange={set('restaurantPhone')} />
          </Field>
        </div>
        <Field label="Your name">
          <input required autoComplete="name" value={form.ownerName} onChange={set('ownerName')} />
        </Field>
        <Field label="Your email">
          <input type="email" required autoComplete="username" value={form.ownerEmail} onChange={set('ownerEmail')} />
        </Field>
        <Field label="Password" hint="Use a strong password; the server enforces its own minimum rules.">
          <input
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={form.password}
            onChange={set('password')}
          />
        </Field>
        <ErrorMessage error={error} />
        <button className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Creating…' : 'Create account'}
        </button>
        <p className="foot">
          Already registered? <Link to="/login">Sign in</Link>
        </p>
      </form>
    </AuthShell>
  );
}
