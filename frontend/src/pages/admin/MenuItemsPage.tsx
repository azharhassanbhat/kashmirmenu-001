import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { categoryApi, imageApi, menuApi } from '../../api';
import type { MenuItem, MenuItemInput } from '../../api/types';
import { Link } from 'react-router-dom';
import { OptionsEditor } from '../../components/admin/OptionsEditor';
import { EmptyState, ErrorMessage, Field, Modal, QueryState } from '../../components/ui';
import { money } from '../../lib/format';
import { fileProblem, MAX_UPLOAD_BYTES, photoLinkProblem, sizeText } from '../../lib/imageUpload';
import { ALLERGENS, DIETARY_LABELS } from '../../lib/menuTags';

export default function MenuItemsPage() {
  const queryClient = useQueryClient();
  const items = useQuery({ queryKey: ['menu-items'], queryFn: menuApi.list });
  const categories = useQuery({ queryKey: ['categories'], queryFn: categoryApi.list });
  const [editing, setEditing] = useState<MenuItem | 'new' | null>(null);
  const [optionsFor, setOptionsFor] = useState<MenuItem | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['menu-items'] });
  const toggleAvailable = useMutation({
    mutationFn: (i: MenuItem) => menuApi.setAvailable(i.id, !i.available),
    onSuccess: refresh,
  });
  const toggleActive = useMutation({
    mutationFn: (i: MenuItem) => menuApi.setActive(i.id, !i.active),
    onSuccess: refresh,
  });

  const list = items.data?.items ?? [];
  const noCategories = categories.isSuccess && categories.data.items.length === 0;

  return (
    <>
      <div className="page-head">
        <h2>Menu items</h2>
        <span className="actions">
          <Link className="btn" to="/admin/menu/import">
            Import from CSV
          </Link>
          <button className="btn btn-primary" disabled={noCategories} onClick={() => setEditing('new')}>
            Add item
          </button>
        </span>
      </div>
      {noCategories && <div className="alert alert-info">Create a category first, then add menu items to it.</div>}
      <ErrorMessage error={toggleAvailable.error ?? toggleActive.error} />
      <QueryState isLoading={items.isLoading} error={items.error}>
        {list.length === 0 ? (
          <EmptyState>No menu items yet.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Category</th>
                  <th className="num">Price</th>
                  <th>Available</th>
                  <th>Visible</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {list.map((i) => (
                  <tr key={i.id}>
                    <td data-label="Name">
                      <span className={`veg-dot${i.veg ? '' : ' nonveg'}`} />
                      {i.name}
                    </td>
                    <td data-label="Category">{i.categoryName}</td>
                    <td className="num" data-label="Price">
                      {money(i.price)}
                    </td>
                    <td data-label="Available">
                      <input
                        type="checkbox"
                        aria-label={`${i.name} available`}
                        checked={i.available}
                        disabled={toggleAvailable.isPending}
                        onChange={() => toggleAvailable.mutate(i)}
                      />
                    </td>
                    <td data-label="Visible">
                      <input
                        type="checkbox"
                        aria-label={`${i.name} visible on menu`}
                        checked={i.active}
                        disabled={toggleActive.isPending}
                        onChange={() => toggleActive.mutate(i)}
                      />
                    </td>
                    <td className="num">
                      <button className="btn btn-sm" onClick={() => setEditing(i)}>
                        Edit
                      </button>{' '}
                      <button className="btn btn-sm" onClick={() => setOptionsFor(i)}>
                        Options
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>

      {optionsFor && <OptionsEditor item={optionsFor} onClose={() => setOptionsFor(null)} />}

      {editing && (
        <ItemForm
          item={editing === 'new' ? null : editing}
          categories={categories.data?.items ?? []}
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

function ItemForm({
  item,
  categories,
  onClose,
  onSaved,
}: {
  item: MenuItem | null;
  categories: { id: number; name: string }[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState({
    name: item?.name ?? '',
    description: item?.description ?? '',
    price: item ? String(item.price) : '',
    imageUrl: item?.imageUrl ?? '',
    veg: item?.veg ?? true,
    available: item?.available ?? true,
    active: item?.active ?? true,
    displayOrder: String(item?.displayOrder ?? 0),
    preparationTime: String(item?.preparationTime ?? 10),
    categoryId: String(item?.categoryId ?? categories[0]?.id ?? ''),
    chefPick: item?.chefPick ?? false,
    todaysSpecial: item?.todaysSpecial ?? false,
  });
  // Allergens are the restaurant's declaration, never a default. "Declared" separates "we have not said"
  // (nothing is shown to guests) from "we declare these, or none". Once declared it stays declared.
  const alreadyDeclared = item?.allergenInfoProvided ?? false;
  // The photo: an upload (stored by us) or a pasted https link. The server checks both again.
  const [photo, setPhoto] = useState<{ busy: boolean; error: string | null }>({ busy: false, error: null });
  // What the preview shows. It follows an upload or a removal at once, but a pasted link only when the
  // owner leaves the box, so typing does not send a request to every half-typed address.
  const [previewUrl, setPreviewUrl] = useState(item?.imageUrl ?? '');
  const [declared, setDeclared] = useState(alreadyDeclared);
  const [allergens, setAllergens] = useState<Set<string>>(new Set((item?.allergens ?? []).map((t) => t.code)));
  const [dietary, setDietary] = useState<Set<string>>(new Set((item?.dietaryLabels ?? []).map((t) => t.code)));
  const flip = (set: Set<string>, code: string) => {
    const next = new Set(set);
    if (next.has(code)) next.delete(code);
    else next.add(code);
    return next;
  };

  const save = useMutation({
    mutationFn: () => {
      const body: MenuItemInput = {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        price: Number(form.price),
        imageUrl: form.imageUrl.trim() || undefined,
        veg: form.veg,
        available: form.available,
        active: form.active,
        displayOrder: Number(form.displayOrder),
        preparationTime: Number(form.preparationTime),
        categoryId: Number(form.categoryId),
        chefPick: form.chefPick,
        todaysSpecial: form.todaysSpecial,
        dietaryLabels: [...dietary],
        // Left out unless declared, so an undeclared dish stays undeclared on the server.
        allergens: declared ? [...allergens] : undefined,
      };
      return item ? menuApi.update(item.id, body) : menuApi.create(body);
    },
    onSuccess: onSaved,
  });

  const text = (key: 'name' | 'description' | 'imageUrl' | 'price' | 'displayOrder' | 'preparationTime') => ({
    value: form[key],
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [key]: e.target.value }),
  });
  const check = (key: 'veg' | 'available' | 'active' | 'chefPick' | 'todaysSpecial') => ({
    checked: form[key],
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.checked }),
  });

  async function onPhoto(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';                       // so choosing the same file again still fires
    if (!file) return;
    const problem = fileProblem(file);
    if (problem) {
      setPhoto({ busy: false, error: problem });
      return;
    }
    setPhoto({ busy: true, error: null });
    try {
      const uploaded = await imageApi.upload(file);
      setForm((f) => ({ ...f, imageUrl: uploaded.url }));
      setPreviewUrl(uploaded.url);
      setPhoto({ busy: false, error: null });
    } catch (error) {
      setPhoto({ busy: false, error: error instanceof Error ? error.message : 'The upload failed. Please try again.' });
    }
  }

  const linkProblem = photoLinkProblem(form.imageUrl);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (linkProblem || photo.busy) return;
    save.mutate();
  }

  return (
    <Modal title={item ? 'Edit menu item' : 'Add menu item'} onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Name">
          <input required maxLength={150} {...text('name')} />
        </Field>
        <Field label="Description">
          <textarea rows={2} {...text('description')} />
        </Field>
        <div className="form-row">
          <Field label="Price">
            <input type="number" required min="0" step="0.01" {...text('price')} />
          </Field>
          <Field label="Category">
            <select required value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Prep time (min)">
            <input type="number" required min="0" {...text('preparationTime')} />
          </Field>
          <Field label="Display order">
            <input type="number" required min="0" {...text('displayOrder')} />
          </Field>
        </div>
        <div className="field">
          <span>Photo (optional)</span>
          <div className="photo-field">
            <PhotoPreview key={previewUrl} url={previewUrl} />
            <div className="photo-controls">
              <span className="actions" style={{ justifyContent: 'flex-start' }}>
                <label className="btn btn-sm">
                  {photo.busy ? 'Uploading…' : 'Upload a photo'}
                  <input
                    type="file"
                    accept="image/jpeg,image/png"
                    hidden
                    disabled={photo.busy}
                    onChange={onPhoto}
                  />
                </label>
                {form.imageUrl.trim() && (
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => {
                      setForm({ ...form, imageUrl: '' });
                      setPreviewUrl('');
                    }}
                  >
                    Remove photo
                  </button>
                )}
              </span>
              <small className="muted">
                JPEG or PNG, up to {sizeText(MAX_UPLOAD_BYTES)}. It is shrunk for fast loading, and any location
                data in it is removed.
              </small>
              {photo.error && (
                <div className="alert alert-error" role="alert">
                  {photo.error}
                </div>
              )}
            </div>
          </div>
          <label className="field">
            <span>Or paste a link to a photo</span>
            <input
              type="text"
              inputMode="url"
              autoComplete="off"
              spellCheck={false}
              placeholder="https://"
              value={form.imageUrl}
              onChange={(e) => setForm({ ...form, imageUrl: e.target.value })}
              onBlur={() => setPreviewUrl(linkProblem ? '' : form.imageUrl.trim())}
              aria-invalid={linkProblem ? true : undefined}
            />
            {linkProblem ? (
              <small className="field-error" role="alert">
                {linkProblem}
              </small>
            ) : (
              <small className="muted">
                It must be a direct link to the picture itself and start with https://. Links from Google Drive,
                Instagram and WhatsApp usually do not work.
              </small>
            )}
          </label>
        </div>
        <label className="check">
          <input type="checkbox" {...check('veg')} /> Vegetarian
        </label>
        <label className="check">
          <input type="checkbox" {...check('available')} /> Available to order
        </label>
        <label className="check">
          <input type="checkbox" {...check('active')} /> Visible on the menu
        </label>
        <label className="check">
          <input type="checkbox" {...check('chefPick')} /> Chef&apos;s pick
        </label>
        <label className="check">
          <input type="checkbox" {...check('todaysSpecial')} /> Today&apos;s special
        </label>

        <div className="field">
          <span>Dietary labels</span>
          <div className="check-grid">
            {DIETARY_LABELS.map((t) => (
              <label key={t.code}>
                <input type="checkbox" checked={dietary.has(t.code)} onChange={() => setDietary(flip(dietary, t.code))} />{' '}
                {t.label}
              </label>
            ))}
          </div>
          <small className="muted">Only tick what is true for this dish.</small>
        </div>

        <div className="field">
          <span>Allergens</span>
          <small className="muted">
            Shown to guests only once you declare them. Leave undeclared if you are not sure.
          </small>
          <label className="check">
            <input
              type="checkbox"
              checked={declared}
              disabled={alreadyDeclared}
              onChange={(e) => setDeclared(e.target.checked)}
            />{' '}
            {alreadyDeclared ? 'Declared (untick every box to declare none)' : 'I am declaring this dish\'s allergens'}
          </label>
          {declared && (
            <div className="check-grid">
              {ALLERGENS.map((a) => (
                <label key={a.code}>
                  <input
                    type="checkbox"
                    checked={allergens.has(a.code)}
                    onChange={() => setAllergens(flip(allergens, a.code))}
                  />{' '}
                  {a.label}
                </label>
              ))}
            </div>
          )}
        </div>
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

/** A small preview of the photo, so the owner sees at once whether an uploaded photo or a pasted link works. */
function PhotoPreview({ url }: { url: string }) {
  const [failed, setFailed] = useState(false);
  if (!url) {
    return <div className="photo-preview photo-preview-empty">No photo</div>;
  }
  if (failed) {
    return <div className="photo-preview photo-preview-empty">That link does not show a photo</div>;
  }
  return (
    <img
      className="photo-preview"
      src={url}
      alt="Preview of the dish photo"
      width={96}
      height={96}
      onError={() => setFailed(true)}
    />
  );
}
