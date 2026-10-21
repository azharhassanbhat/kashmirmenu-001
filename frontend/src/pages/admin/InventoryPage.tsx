import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { inventoryApi, menuApi } from '../../api';
import type { Inventory } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { EmptyState, ErrorMessage, Field, Modal, QueryState } from '../../components/ui';

export default function InventoryPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const inventory = useQuery({ queryKey: ['inventory'], queryFn: inventoryApi.list });
  const menu = useQuery({ queryKey: ['menu-items'], queryFn: menuApi.list });
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Inventory | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['inventory'] });
  const remove = useMutation({ mutationFn: (i: Inventory) => inventoryApi.remove(i.id), onSuccess: refresh });

  const tracked = new Set(inventory.data?.map((i) => i.menuItemId));
  const untracked = (menu.data?.items ?? []).filter((m) => !tracked.has(m.id));

  return (
    <>
      <div className="page-head">
        <h2>Inventory</h2>
        <button
          className="btn btn-primary"
          disabled={untracked.length === 0 || user?.restaurantId == null}
          onClick={() => setAdding(true)}
        >
          Track an item
        </button>
      </div>
      <ErrorMessage error={remove.error} />
      <QueryState isLoading={inventory.isLoading} error={inventory.error}>
        {inventory.data?.length === 0 ? (
          <EmptyState>No stock is tracked yet.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Item</th>
                  <th className="num">In stock</th>
                  <th className="num">Minimum</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {inventory.data?.map((i) => {
                  const low = i.availableQuantity <= i.minimumQuantity;
                  return (
                    <tr key={i.id}>
                      <td data-label="Item">{i.menuItemName}</td>
                      <td className="num" data-label="In stock">
                        {i.availableQuantity}
                      </td>
                      <td className="num" data-label="Minimum">
                        {i.minimumQuantity}
                      </td>
                      <td data-label="Status">
                        {!i.inStock ? (
                          <span className="badge badge-red">Out of stock</span>
                        ) : low ? (
                          <span className="badge badge-amber">Low</span>
                        ) : (
                          <span className="badge badge-green">OK</span>
                        )}
                      </td>
                      <td className="num">
                        <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                          <button className="btn btn-sm" onClick={() => setEditing(i)}>
                            Update
                          </button>
                          <button
                            className="btn btn-sm btn-danger"
                            disabled={remove.isPending}
                            onClick={() => window.confirm(`Stop tracking ${i.menuItemName}?`) && remove.mutate(i)}
                          >
                            Remove
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>

      {adding && user?.restaurantId != null && (
        <AddForm
          restaurantId={user.restaurantId}
          options={untracked.map((m) => ({ id: m.id, name: m.name }))}
          onClose={() => setAdding(false)}
          onSaved={() => {
            setAdding(false);
            void refresh();
          }}
        />
      )}
      {editing && (
        <EditForm
          item={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void refresh();
          }}
        />
      )}
    </>
  );
}

function AddForm({
  restaurantId,
  options,
  onClose,
  onSaved,
}: {
  restaurantId: number;
  options: { id: number; name: string }[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [menuItemId, setMenuItemId] = useState(String(options[0]?.id ?? ''));
  const [available, setAvailable] = useState('0');
  const [minimum, setMinimum] = useState('0');

  const save = useMutation({
    mutationFn: () =>
      inventoryApi.create({
        restaurantId,
        menuItemId: Number(menuItemId),
        availableQuantity: Number(available),
        minimumQuantity: Number(minimum),
      }),
    onSuccess: onSaved,
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  return (
    <Modal title="Track an item" onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Menu item">
          <select required value={menuItemId} onChange={(e) => setMenuItemId(e.target.value)}>
            {options.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </Field>
        <div className="form-row">
          <Field label="In stock">
            <input type="number" required min="0" value={available} onChange={(e) => setAvailable(e.target.value)} />
          </Field>
          <Field label="Minimum level">
            <input type="number" required min="0" value={minimum} onChange={(e) => setMinimum(e.target.value)} />
          </Field>
        </div>
        <ErrorMessage error={save.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={save.isPending}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}

function EditForm({ item, onClose, onSaved }: { item: Inventory; onClose: () => void; onSaved: () => void }) {
  const [available, setAvailable] = useState(String(item.availableQuantity));
  const [minimum, setMinimum] = useState(String(item.minimumQuantity));

  const save = useMutation({
    mutationFn: async () => {
      if (Number(available) !== item.availableQuantity) {
        await inventoryApi.updateStock(item.id, Number(available));
      }
      if (Number(minimum) !== item.minimumQuantity) {
        await inventoryApi.updateMinimum(item.id, Number(minimum));
      }
    },
    onSuccess: onSaved,
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  return (
    <Modal title={`Update ${item.menuItemName}`} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <Field label="In stock">
            <input type="number" required min="0" value={available} onChange={(e) => setAvailable(e.target.value)} />
          </Field>
          <Field label="Minimum level">
            <input type="number" required min="0" value={minimum} onChange={(e) => setMinimum(e.target.value)} />
          </Field>
        </div>
        <ErrorMessage error={save.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={save.isPending}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}
