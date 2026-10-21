# Team management (Stage D)

Until now there was **no way to create a waiter, chef, cashier or second manager through the product**; the tests inserted them straight into the database. A restaurant can now hire and manage its own team.

**Status: written but not yet run.** Apply Stages A, B and C first.

## Run the automated checks (when you can)

```bash
./gradlew test        # StaffRulesTest, StaffIT (+ earlier stages)
cd frontend && npm install && npm run typecheck && npm test
```
No database migration: staff are ordinary rows in the existing `users` table, and the new audit actions are stored as text in the existing `audit_log.action` column.

## Who may do what (enforced on the server)

| Actor | May create / change | May not |
|---|---|---|
| Owner, restaurant admin | managers, chefs, waiters, cashiers | owners, admins, platform owners, diners |
| Manager | chefs, waiters, cashiers | other managers, owners, anything above them |
| Chef, waiter, cashier | nothing: the staff endpoints answer 403 | everything |

Nobody can change their **own** account here (so nobody can lock themselves out or promote themselves). Another restaurant's account answers "not found", exactly like one that does not exist.

## What each action does

- **Create:** the person setting it up chooses the first password and hands it over (there is no email system). It is shown once, on screen, and never again. A generator makes a 12-character password with no look-alike characters, using the browser's cryptographic random source.
- **Password rules:** 8 to 100 characters, at least one letter and one number, and not the email address, its first part, or the person's name. Stored only as a hash; never returned, logged or written to the audit trail.
- **Change role:** applies on the person's very next request with the same token (permissions are read from the database on every request).
- **Deactivate:** the account is refused immediately on every request, even with a token already in hand, and cannot sign in. History is kept. **Reactivate** restores it.
- **Reset password:** the person is signed out everywhere and needs the new password.
- Every one of these is recorded in the audit trail (who, what, when) without any password.

## A security fix made along the way

The request filter checked a token's signature, expiry and revocation time, but **never checked that the account was still enabled**. A deactivated or suspended user with an unexpired token kept working until it expired. The filter now refuses disabled accounts on every request. This also tightens the existing platform "suspend user" action, which relied on the same check.

## API

| Method and path | Purpose |
|---|---|
| `GET /api/v1/staff` | The team, each with `you` and `canManage` for the caller |
| `POST /api/v1/staff` `{"name","email","role","password"}` | Create (201) |
| `PUT /api/v1/staff/{id}` `{"name"?,"role"?,"active"?}` | Rename, change role, deactivate/reactivate; anything omitted is unchanged |
| `PUT /api/v1/staff/{id}/password` `{"password"}` | Reset (204) |

Owners, admins and managers reach these endpoints; floor and kitchen roles get 403; signed-out callers get 401.

## Manual test (browser)

1. Owner: **Team** in the admin menu. Click **Add staff**, name "Sana", role Waiter. A password is pre-filled; note it. Create. The one-time sign-in details appear.
2. In a private window sign in as Sana: she lands on the kitchen board and cannot open `/admin`.
3. Owner: **Edit** Sana, change role to Cashier. Sana's next click on the kitchen board is refused; live tables still work.
4. Owner: **Deactivate** Sana. Her open window stops working on the next click; signing in again fails. **Reactivate** and she can sign in.
5. Owner: **Reset password**. Sana is signed out; the old password fails; the new one works.
6. Create a Manager, sign in as them: they can add a chef but the Role list has no Manager, and no buttons appear on other managers or the owner.
7. Try a short or letters-only password: refused with a clear message.

## Known limits

- **No email.** Passwords are handed over by a person. Staff cannot yet change their own password or reset a forgotten one themselves; the owner or a manager does it. Self-service password change and email-based reset need an email provider (a cost and credentials decision for you).
- Owners cannot change their own password in the product yet (same reason: the screen blocks self-changes).
- No staff photos, shifts or per-person permissions beyond the four roles.
- I have not seen the new screen rendered.
