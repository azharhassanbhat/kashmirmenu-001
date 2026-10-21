import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { categoryApi } from '../../api';
import type { Category } from '../../api/types';
import { EmptyState, ErrorMessage, Field, Modal, QueryState } from '../../components/ui';

export default function CategoriesPage() {
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['categories'], queryFn: categoryApi.list });
  const [editing, setEditing] = useState<Category | 'new' | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['categories'] });
  const toggle = useMutation({
    mutationFn: (c: Category) => categoryApi.setActive(c.id, !c.active),
    onSuccess: refresh,
  });

  const list = [...(categories.data?.items ?? [])].sort((a, b) => a.displayOrder - b.displayOrder);

  return (
    <>
      <div className="page-head">
        <h2>Categories</h2>
        <button className="btn btn-primary" onClick={() => setEditing('new')}>
          Add category
        </button>
      </div>
      <ErrorMessage error={toggle.error} />
      <QueryState isLoading={categories.isLoading} error={categories.error}>
        {list.length === 0 ? (
          <EmptyState>No categories yet.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Description</th>
                  <th className="num">Order</th>
                  <th>Visible</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {list.map((c) => (
                  <tr key={c.id}>
                    <td data-label="Name">{c.name}</td>
                    <td className="muted" data-label="Description">
                      {c.description}
                    </td>
                    <td className="num" data-label="Order">
                      {c.displayOrder}
                    </td>
                    <td data-label="Visible">
                      <input
                        type="checkbox"
                        aria-label={`${c.name} visible`}
                        checked={c.active}
                        disabled={toggle.isPending}
                        onChange={() => toggle.mutate(c)}
                      />
                    </td>
                    <td className="num">
                      <button className="btn btn-sm" onClick={() => setEditing(c)}>
                        Edit
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>

      {editing && (
        <CategoryForm
          category={editing === 'new' ? null : editing}
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

function CategoryForm({
  category,
  onClose,
  onSaved,
}: {
  category: Category | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(category?.name ?? '');
  const [description, setDescription] = useState(category?.description ?? '');
  const [displayOrder, setDisplayOrder] = useState(String(category?.displayOrder ?? 0));
  const [active, setActive] = useState(category?.active ?? true);

  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        description: description.trim() || undefined,
        displayOrder: Number(displayOrder),
        active,
      };
      return category ? categoryApi.update(category.id, body) : categoryApi.create(body);
    },
    onSuccess: onSaved,
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate();
  }

  return (
    <Modal title={category ? 'Edit category' : 'Add category'} onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Name">
          <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Description">
          <textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <Field label="Display order">
          <input
            type="number"
            required
            min="0"
            value={displayOrder}
            onChange={(e) => setDisplayOrder(e.target.value)}
          />
        </Field>
        <label className="check">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Visible on the menu
        </label>
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
