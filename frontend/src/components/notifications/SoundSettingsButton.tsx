import { useState } from 'react';
import { Modal } from '../ui';
import { NotificationSettingsPanel } from './NotificationSettingsPanel';

/** A compact "Sound" button for screens (kitchen, floor) whose staff cannot open the admin area. */
export function SoundSettingsButton() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="btn btn-sm" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        Sound
      </button>
      {open && (
        <Modal title="Notification & Sound Settings" onClose={() => setOpen(false)}>
          <NotificationSettingsPanel variant="restaurant" />
        </Modal>
      )}
    </>
  );
}
