import { NotificationSettingsPanel } from '../../components/notifications/NotificationSettingsPanel';

export default function NotificationSettingsPage() {
  return (
    <>
      <div className="page-head">
        <h2>Alerts &amp; sound</h2>
      </div>
      <section className="card" aria-labelledby="notif-settings-title">
        <h3 id="notif-settings-title">Notification &amp; Sound Settings</h3>
        <p className="muted">Saved on this device for your account. Visual notifications always work, with or without sound.</p>
        <NotificationSettingsPanel variant="restaurant" />
      </section>
    </>
  );
}
