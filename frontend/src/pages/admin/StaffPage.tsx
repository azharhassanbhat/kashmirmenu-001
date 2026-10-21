import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { staffApi } from '../../api';
import type { RoleName, StaffMember } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { EmptyState, ErrorMessage, Field, Modal, QueryState } from '../../components/ui';
import { assignableRoles, generatePassword, passwordProblem, ROLE_HELP, roleLabel } from '../../lib/staff';

/**
 * The restaurant's team. Owners and admins can hire managers and every floor and kitchen role; managers
 * can hire chefs, waiters and cashiers. The screen offers only what the server will accept, and the
 * server checks again. There is no email system: whoever sets an account up chooses the first password
 * and hands it over, so it is shown once, here, and never again.
 */
export default function StaffPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const team = useQuery({ queryKey: ['staff'], queryFn: staffApi.list });
  const roles = assignableRoles(user?.role);

  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<StaffMember | null>(null);
  const [resetting, setResetting] = useState<StaffMember | null>(null);
  const [handover, setHandover] = useState<{ name: string; email: string; password: string } | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['staff'] });
  const toggle = useMutation({
    mutationFn: (m: StaffMember) => staffApi.update(m.id, { active: !m.active }),
    onSuccess: refresh,
  });

  return (
    <>
      <div className="page-head">
        <h2>Team</h2>
        <button className="btn btn-primary" disabled={roles.length === 0} onClick={() => setAdding(true)}>
          Add staff
        </button>
      </div>

      <p className="muted">
        Give each person their own sign-in. Deactivating someone signs them out at once on every device, and keeps
        their history.
      </p>
      <ErrorMessage error={toggle.error} />

      <QueryState isLoading={team.isLoading} error={team.error}>
        {(team.data ?? []).length === 0 ? (
          <EmptyState>No team members yet.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Email</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {team.data!.map((m) => (
                  <tr key={m.id}>
                    <td data-label="Name">
                      {m.name}
                      {m.you && <span className="muted"> (you)</span>}
                    </td>
                    <td data-label="Email">{m.email}</td>
                    <td data-label="Role">{roleLabel(m.role)}</td>
                    <td data-label="Status">
                      <span className={`badge ${m.active ? 'badge-green' : 'badge-gray'}`}>
                        {m.active ? 'Active' : 'Deactivated'}
                      </span>
                    </td>
                    <td className="num">
                      {m.canManage && (
                        <span className="actions">
                          <button className="btn btn-sm" onClick={() => setEditing(m)}>
                            Edit
                          </button>
                          <button className="btn btn-sm" onClick={() => setResetting(m)}>
                            Reset password
                          </button>
                          <button className="btn btn-sm" disabled={toggle.isPending} onClick={() => toggle.mutate(m)}>
                            {m.active ? 'Deactivate' : 'Reactivate'}
                          </button>
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>

      {adding && (
        <AddStaff
          roles={roles}
          onClose={() => setAdding(false)}
          onCreated={(details) => {
            setAdding(false);
            setHandover(details);
            void refresh();
          }}
        />
      )}
      {editing && (
        <EditStaff
          member={editing}
          roles={roles}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void refresh();
          }}
        />
      )}
      {resetting && (
        <ResetPassword
          member={resetting}
          onClose={() => setResetting(null)}
          onDone={(password) => {
            setHandover({ name: resetting.name, email: resetting.email, password });
            setResetting(null);
          }}
        />
      )}
      {handover && <Handover details={handover} onClose={() => setHandover(null)} />}
    </>
  );
}

function PasswordField({
  value,
  onChange,
}: {
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <Field label="Password" hint="At least 8 characters with a letter and a number. You will hand it to them.">
      <span className="actions" style={{ justifyContent: 'flex-start' }}>
        <input
          required
          autoComplete="off"
          spellCheck={false}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          style={{ flex: 1 }}
        />
        <button type="button" className="btn btn-sm" onClick={() => onChange(generatePassword())}>
          Generate
        </button>
      </span>
    </Field>
  );
}

function AddStaff({
  roles,
  onClose,
  onCreated,
}: {
  roles: RoleName[];
  onClose: () => void;
  onCreated: (d: { name: string; email: string; password: string }) => void;
}) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<RoleName>(roles.includes('WAITER') ? 'WAITER' : roles[0]);
  const [password, setPassword] = useState(() => generatePassword());
  const [problem, setProblem] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () => staffApi.create({ name: name.trim(), email: email.trim(), role, password }),
    onSuccess: () => onCreated({ name: name.trim(), email: email.trim(), password }),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    const p = passwordProblem(password, email, name);
    setProblem(p);
    if (!p) create.mutate();
  }

  return (
    <Modal title="Add staff" onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Name">
          <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Email (their sign-in)">
          <input type="email" required maxLength={150} value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Role" hint={ROLE_HELP[role]}>
          <select value={role} onChange={(e) => setRole(e.target.value as RoleName)}>
            {roles.map((r) => (
              <option key={r} value={r}>
                {roleLabel(r)}
              </option>
            ))}
          </select>
        </Field>
        <PasswordField value={password} onChange={setPassword} />
        {problem && (
          <div className="alert alert-error" role="alert">
            {problem}
          </div>
        )}
        <ErrorMessage error={create.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create account'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function EditStaff({
  member,
  roles,
  onClose,
  onSaved,
}: {
  member: StaffMember;
  roles: RoleName[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(member.name);
  const [role, setRole] = useState<RoleName>(member.role);
  const save = useMutation({
    // Only what changed, so a no-op edit sends nothing the server could misread.
    mutationFn: () =>
      staffApi.update(member.id, {
        name: name.trim() !== member.name ? name.trim() : undefined,
        role: role !== member.role ? role : undefined,
      }),
    onSuccess: onSaved,
  });

  return (
    <Modal title={`Edit ${member.name}`} onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label="Name">
          <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Role" hint={`${ROLE_HELP[role] ?? ''} A role change applies on their very next action.`}>
          <select value={role} onChange={(e) => setRole(e.target.value as RoleName)}>
            {roles.map((r) => (
              <option key={r} value={r}>
                {roleLabel(r)}
              </option>
            ))}
          </select>
        </Field>
        <ErrorMessage error={save.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ResetPassword({
  member,
  onClose,
  onDone,
}: {
  member: StaffMember;
  onClose: () => void;
  onDone: (password: string) => void;
}) {
  const [password, setPassword] = useState(() => generatePassword());
  const [problem, setProblem] = useState<string | null>(null);
  const reset = useMutation({
    mutationFn: () => staffApi.resetPassword(member.id, password),
    onSuccess: () => onDone(password),
  });

  return (
    <Modal title={`New password for ${member.name}`} onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const p = passwordProblem(password, member.email, member.name);
          setProblem(p);
          if (!p) reset.mutate();
        }}
      >
        <p className="muted">They will be signed out everywhere and need this password to sign in again.</p>
        <PasswordField value={password} onChange={setPassword} />
        {problem && (
          <div className="alert alert-error" role="alert">
            {problem}
          </div>
        )}
        <ErrorMessage error={reset.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={reset.isPending}>
            {reset.isPending ? 'Saving…' : 'Set password'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Shown once after creating an account or resetting a password. The password is not kept anywhere. */
function Handover({
  details,
  onClose,
}: {
  details: { name: string; email: string; password: string };
  onClose: () => void;
}) {
  return (
    <Modal title="Give these sign-in details to them" onClose={onClose}>
      <p>
        <strong>{details.name}</strong> can sign in with:
      </p>
      <dl className="kv">
        <div>
          <dt>Email</dt>
          <dd>{details.email}</dd>
        </div>
        <div>
          <dt>Password</dt>
          <dd>
            <code>{details.password}</code>
          </dd>
        </div>
      </dl>
      <div className="alert alert-info">
        This is the only time the password is shown. Pass it on privately, and ask them to keep it to themselves.
      </div>
      <div className="actions">
        <button className="btn btn-primary" onClick={onClose}>
          Done
        </button>
      </div>
    </Modal>
  );
}
