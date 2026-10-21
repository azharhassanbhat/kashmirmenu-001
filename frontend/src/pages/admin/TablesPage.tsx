import { Link } from 'react-router-dom';
import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tableApi } from '../../api';
import type { RestaurantTable } from '../../api/types';
import { EmptyState, ErrorMessage, Field, Modal, QueryState, Spinner } from '../../components/ui';

export default function TablesPage() {
  const queryClient = useQueryClient();
  const tables = useQuery({ queryKey: ['tables'], queryFn: tableApi.list });
  const [editing, setEditing] = useState<RestaurantTable | 'new' | null>(null);
  const [qrFor, setQrFor] = useState<RestaurantTable | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tables'] });
  const toggle = useMutation({
    mutationFn: (t: RestaurantTable) => tableApi.setActive(t.id, !t.active),
    onSuccess: refresh,
  });
  const remove = useMutation({ mutationFn: (t: RestaurantTable) => tableApi.remove(t.id), onSuccess: refresh });

  return (
    <>
      <div className="page-head">
        <h2>Tables &amp; QR codes</h2>
        <span className="actions">
          <Link className="btn" to="/admin/tables/cards">
            Print QR cards
          </Link>
          <button className="btn btn-primary" onClick={() => setEditing('new')}>
            Add table
          </button>
        </span>
      </div>
      <ErrorMessage error={toggle.error ?? remove.error} />
      <QueryState isLoading={tables.isLoading} error={tables.error}>
        {tables.data?.length === 0 ? (
          <EmptyState>No tables yet. Add one to generate its QR code.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th className="num">Seats</th>
                  <th>Active</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {tables.data?.map((t) => (
                  <tr key={t.id}>
                    <td data-label="Code">{t.tableCode}</td>
                    <td data-label="Name">{t.tableName}</td>
                    <td className="num" data-label="Seats">
                      {t.capacity}
                    </td>
                    <td data-label="Active">
                      <input
                        type="checkbox"
                        aria-label={`${t.tableName} active`}
                        checked={t.active}
                        disabled={toggle.isPending}
                        onChange={() => toggle.mutate(t)}
                      />
                    </td>
                    <td className="num">
                      <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                        <button className="btn btn-sm" onClick={() => setQrFor(t)}>
                          QR code
                        </button>
                        <button className="btn btn-sm" onClick={() => setEditing(t)}>
                          Edit
                        </button>
                        <button
                          className="btn btn-sm btn-danger"
                          disabled={remove.isPending}
                          onClick={() =>
                            window.confirm(`Delete ${t.tableName}? Its QR code will stop working.`) && remove.mutate(t)
                          }
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>

      {editing && (
        <TableForm
          table={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void refresh();
          }}
        />
      )}
      {qrFor && <QrModal table={qrFor} onClose={() => setQrFor(null)} />}
    </>
  );
}

function TableForm({
  table,
  onClose,
  onSaved,
}: {
  table: RestaurantTable | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [tableCode, setTableCode] = useState(table?.tableCode ?? '');
  const [tableName, setTableName] = useState(table?.tableName ?? '');
  const [capacity, setCapacity] = useState(String(table?.capacity ?? 4));

  const save = useMutation({
    mutationFn: () => {
      const body = { tableCode: tableCode.trim(), tableName: tableName.trim(), capacity: Number(capacity) };
      return table ? tableApi.update(table.id, body) : tableApi.create(body);
    },
    onSuccess: onSaved,
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  return (
    <Modal title={table ? 'Edit table' : 'Add table'} onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Code" hint="Short unique code, e.g. T1">
          <input required value={tableCode} onChange={(e) => setTableCode(e.target.value)} />
        </Field>
        <Field label="Name">
          <input required value={tableName} onChange={(e) => setTableName(e.target.value)} />
        </Field>
        <Field label="Seats">
          <input type="number" required min="1" value={capacity} onChange={(e) => setCapacity(e.target.value)} />
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

function QrModal({ table, onClose }: { table: RestaurantTable; onClose: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    tableApi
      .qr(table.id)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((e) => !cancelled && setError(e));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [table.id]);

  return (
    <Modal title={`QR code · ${table.tableName}`} onClose={onClose}>
      <div className="print-area" style={{ textAlign: 'center' }}>
        <h3>{table.tableName}</h3>
        {url ? <img className="qr-img" src={url} alt={`QR code for ${table.tableName}`} /> : !error && <Spinner />}
        <p className="muted">Scan to view the menu and order</p>
      </div>
      <ErrorMessage error={error} />
      <div className="actions">
        {url && (
          <a className="btn" href={url} download={`${table.tableCode}-qr.png`}>
            Download
          </a>
        )}
        <button className="btn btn-primary" disabled={!url} onClick={() => window.print()}>
          Print
        </button>
      </div>
    </Modal>
  );
}
