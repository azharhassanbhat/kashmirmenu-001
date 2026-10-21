import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { menuOptionApi } from '../../api';
import type { MenuItem } from '../../api/types';
import { ErrorMessage, Modal, QueryState } from '../../components/ui';
import { money } from '../../lib/format';
import {
  blankOption,
  move,
  newKey,
  TEMPLATES,
  toDraft,
  toInput,
  type DraftGroup,
  type DraftOption,
} from '../../lib/optionDraft';

/**
 * Portion sizes, add-ons and spice levels for one dish. Every group is a set of choices; each choice
 * changes the dish's base price by an amount (negative for a smaller portion, 0 for no change).
 * Saved as a whole, with ids echoed back so nothing already in a guest's cart is disturbed.
 */
export function OptionsEditor({ item, onClose }: { item: MenuItem; onClose: () => void }) {
  const queryClient = useQueryClient();
  const stored = useQuery({
    queryKey: ['menu-options', item.id],
    queryFn: () => menuOptionApi.get(item.id),
    // The editor works on its own copy; a background refetch must not overwrite typing.
    refetchOnWindowFocus: false,
  });

  const [drafts, setDrafts] = useState<DraftGroup[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (stored.data && drafts === null) setDrafts(toDraft(stored.data));
  }, [stored.data, drafts]);

  const save = useMutation({
    mutationFn: (groups: Parameters<typeof menuOptionApi.save>[1]) => menuOptionApi.save(item.id, groups),
    onSuccess: (saved) => {
      queryClient.setQueryData(['menu-options', item.id], saved);
      void queryClient.invalidateQueries({ queryKey: ['menu-items'] });
      onClose();
    },
  });

  const groups = drafts ?? [];
  const setGroups = (next: DraftGroup[]) => {
    setProblem(null);
    setDrafts(next);
  };
  const patchGroup = (key: string, patch: Partial<DraftGroup>) =>
    setGroups(groups.map((g) => (g.key === key ? { ...g, ...patch } : g)));
  const patchOption = (gKey: string, oKey: string, patch: Partial<DraftOption>) =>
    setGroups(
      groups.map((g) =>
        g.key !== gKey
          ? g
          : {
              ...g,
              // Only one pre-selected option in a single-choice group.
              options: g.options.map((o) => {
                if (o.key === oKey) return { ...o, ...patch };
                return patch.defaultOption && g.kind === 'SINGLE' ? { ...o, defaultOption: false } : o;
              }),
            },
      ),
    );

  function submit() {
    const result = toInput(groups);
    if (!result.ok) {
      setProblem(result.error);
      return;
    }
    save.mutate(result.groups);
  }

  return (
    <Modal title={`Options for ${item.name}`} onClose={onClose}>
      <p className="muted">
        Base price {money(item.price)}. Each option changes that price: use a negative amount for a smaller
        portion and 0 for no change. Guests always pay the price the server works out from these settings.
      </p>

      <QueryState isLoading={stored.isLoading} error={stored.error}>
        {groups.length === 0 && <p className="muted">This dish has no options yet. Add a group to start.</p>}

        {groups.map((g, gi) => (
          <fieldset className="opt-editor-group" key={g.key}>
            <div className="opt-editor-head">
              <label>
                Group name
                <input
                  type="text"
                  maxLength={80}
                  value={g.name}
                  onChange={(e) => patchGroup(g.key, { name: e.target.value })}
                />
              </label>
              <label>
                Guests choose
                <select
                  value={g.kind}
                  onChange={(e) => patchGroup(g.key, { kind: e.target.value as DraftGroup['kind'] })}
                >
                  <option value="SINGLE">One</option>
                  <option value="MULTI">Any number</option>
                </select>
              </label>
              {g.kind === 'MULTI' && (
                <label>
                  Up to (blank = no limit)
                  <input
                    type="number"
                    min={1}
                    max={20}
                    value={g.maxSelect}
                    onChange={(e) => patchGroup(g.key, { maxSelect: e.target.value })}
                  />
                </label>
              )}
              <label className="check">
                <input
                  type="checkbox"
                  checked={g.required}
                  onChange={(e) => patchGroup(g.key, { required: e.target.checked })}
                />{' '}
                Required
              </label>
              <span className="actions">
                <button
                  type="button"
                  className="btn btn-sm"
                  disabled={gi === 0}
                  aria-label={`Move ${g.name || 'group'} up`}
                  onClick={() => setGroups(move(groups, gi, gi - 1))}
                >
                  ↑
                </button>
                <button
                  type="button"
                  className="btn btn-sm"
                  disabled={gi === groups.length - 1}
                  aria-label={`Move ${g.name || 'group'} down`}
                  onClick={() => setGroups(move(groups, gi, gi + 1))}
                >
                  ↓
                </button>
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => setGroups(groups.filter((x) => x.key !== g.key))}
                >
                  Remove group
                </button>
              </span>
            </div>

            {g.options.map((o) => (
              <div className="opt-editor-row" key={o.key}>
                <input
                  type="text"
                  maxLength={80}
                  placeholder="Option name"
                  aria-label="Option name"
                  value={o.name}
                  onChange={(e) => patchOption(g.key, o.key, { name: e.target.value })}
                />
                <input
                  type="number"
                  step="0.01"
                  aria-label="Price change"
                  value={o.priceDelta}
                  onChange={(e) => patchOption(g.key, o.key, { priceDelta: e.target.value })}
                />
                <label className="check">
                  <input
                    type="checkbox"
                    checked={o.available}
                    onChange={(e) => patchOption(g.key, o.key, { available: e.target.checked })}
                  />{' '}
                  In stock
                </label>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={o.defaultOption}
                    onChange={(e) => patchOption(g.key, o.key, { defaultOption: e.target.checked })}
                  />{' '}
                  Pre-select
                </label>
                <button
                  type="button"
                  className="btn btn-sm"
                  aria-label={`Remove option ${o.name || ''}`}
                  disabled={g.options.length === 1}
                  onClick={() => patchGroup(g.key, { options: g.options.filter((x) => x.key !== o.key) })}
                >
                  ✕
                </button>
              </div>
            ))}

            <button
              type="button"
              className="btn btn-sm"
              style={{ marginTop: 'var(--space-2)' }}
              onClick={() => patchGroup(g.key, { options: [...g.options, blankOption()] })}
            >
              Add option
            </button>
          </fieldset>
        ))}

        <div className="actions" style={{ justifyContent: 'flex-start', flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-sm" onClick={() => setGroups([...groups, TEMPLATES.portion()])}>
            + Portion sizes
          </button>
          <button type="button" className="btn btn-sm" onClick={() => setGroups([...groups, TEMPLATES.addons()])}>
            + Add-ons
          </button>
          <button type="button" className="btn btn-sm" onClick={() => setGroups([...groups, TEMPLATES.spice()])}>
            + Spice level
          </button>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() =>
              setGroups([
                ...groups,
                { key: newKey(), name: '', kind: 'SINGLE', required: false, maxSelect: '', options: [blankOption()] },
              ])
            }
          >
            + Other group
          </button>
        </div>

        {problem && (
          <div className="alert alert-error" role="alert">
            {problem}
          </div>
        )}
        <ErrorMessage error={save.error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn-primary" disabled={save.isPending || drafts === null} onClick={submit}>
            {save.isPending ? 'Saving…' : 'Save options'}
          </button>
        </div>
      </QueryState>
    </Modal>
  );
}
