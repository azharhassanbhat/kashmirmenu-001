import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { tableApi } from '../../api';
import { EmptyState, ErrorMessage, QueryState } from '../../components/ui';
import { chunk, layoutById, LAYOUTS, mapLimit, sheetsText, toggled } from '../../lib/qrSheet';

/** The most QR images fetched at once: enough to be quick, few enough not to flood the server. */
const AT_A_TIME = 4;

type Image = { url: string } | { error: string };

/**
 * Printable table cards: the restaurant's name, the table's name and its QR code, laid out several to an A4
 * sheet. The QR images are drawn by the server at a size matched to the layout, so they stay sharp on paper.
 * Printing hides everything but the sheets (see the print-cards rules in the stylesheet).
 */
export default function QrCardsPage() {
  const cards = useQuery({ queryKey: ['qr-cards'], queryFn: tableApi.qrCards });
  const [layoutId, setLayoutId] = useState('six');
  const [selected, setSelected] = useState<Set<number> | null>(null);   // null = every table, until the owner chooses
  const [images, setImages] = useState<Record<string, Image>>({});
  const created = useRef<string[]>([]);
  const requested = useRef<Set<string>>(new Set());
  const mounted = useRef(true);

  const layout = layoutById(layoutId);
  const all = useMemo(() => cards.data?.tables ?? [], [cards.data]);
  const chosen = useMemo(() => all.filter((t) => selected === null || selected.has(t.id)), [all, selected]);
  const keyOf = (id: number) => `${id}@${layout.imagePixels}`;

  // Fetch what has not been asked for yet, at this layout's size (a bigger size asks for sharper ones). Each
  // image is requested ONCE: ticking boxes while others load neither repeats a request nor throws a result away.
  useEffect(() => {
    const todo = chosen.filter((t) => !requested.current.has(keyOf(t.id)));
    if (todo.length === 0) return;
    todo.forEach((t) => requested.current.add(keyOf(t.id)));
    void mapLimit(todo, AT_A_TIME, async (t) => {
      const blob = await tableApi.qr(t.id, layout.imagePixels);
      const url = URL.createObjectURL(blob);
      created.current.push(url);
      return url;
    }).then((results) => {
      if (!mounted.current) return;
      setImages((prev) => {
        const next = { ...prev };
        results.forEach((r, i) => {
          next[keyOf(todo[i].id)] =
            r.status === 'fulfilled'
              ? { url: r.value }
              : { error: r.reason instanceof Error ? r.reason.message : 'This QR code could not be loaded.' };
        });
        return next;
      });
    });
  }, [chosen, layout.imagePixels]);

  // Free the in-memory images when leaving the page.
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      created.current.forEach((u) => URL.revokeObjectURL(u));
      created.current = [];
    };
  }, []);

  // Print rules that show ONLY the sheets apply while this class is on the page, and nowhere else; the paper is A4.
  useEffect(() => {
    document.body.classList.add('print-cards');
    const style = document.createElement('style');
    style.textContent = '@page { size: A4; margin: 8mm; }';
    document.head.appendChild(style);
    return () => {
      document.body.classList.remove('print-cards');
      style.remove();
    };
  }, []);

  const ready = chosen.every((t) => keyOf(t.id) in images);
  const failed = chosen.filter((t) => {
    const img = images[keyOf(t.id)];
    return img && 'error' in img;
  });
  const pages = chunk(chosen, layout.perPage);
  const name = cards.data?.restaurantName ?? '';

  return (
    <>
      <div className="page-head no-print">
        <h2>Print QR cards</h2>
        <Link className="btn" to="/admin/tables">
          Back to tables
        </Link>
      </div>

      <QueryState isLoading={cards.isLoading} error={cards.error}>
        {all.length === 0 ? (
          <EmptyState>No active tables yet. Add a table first, then come back to print its card.</EmptyState>
        ) : (
          <>
            <div className="card qr-controls no-print">
              <label className="field">
                <span>Cards per page</span>
                <select value={layoutId} onChange={(e) => setLayoutId(e.target.value)}>
                  {LAYOUTS.map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.label}
                    </option>
                  ))}
                </select>
                <small className="muted">{layout.hint}</small>
              </label>

              <fieldset className="field qr-pick">
                <legend>Tables to print ({chosen.length} of {all.length})</legend>
                <span className="actions" style={{ justifyContent: 'flex-start' }}>
                  <button type="button" className="btn btn-sm" onClick={() => setSelected(new Set(all.map((t) => t.id)))}>
                    Select all
                  </button>
                  <button type="button" className="btn btn-sm" onClick={() => setSelected(new Set())}>
                    Select none
                  </button>
                </span>
                <div className="qr-pick-list">
                  {all.map((t) => (
                    <label className="check" key={t.id}>
                      <input
                        type="checkbox"
                        checked={selected === null || selected.has(t.id)}
                        onChange={() => setSelected(toggled(selected ?? new Set(all.map((x) => x.id)), t.id))}
                      />{' '}
                      {t.tableName}
                    </label>
                  ))}
                </div>
              </fieldset>

              <div className="actions" style={{ justifyContent: 'space-between' }}>
                <span className="muted">
                  {sheetsText(chosen.length, layout.perPage)}
                  {!ready && chosen.length > 0 && ' · loading the QR codes…'}
                </span>
                <button type="button" className="btn btn-primary" disabled={!ready || chosen.length === 0} onClick={() => window.print()}>
                  Print
                </button>
              </div>
              {failed.length > 0 && (
                <ErrorMessage error={new Error(`${failed.length} QR code(s) could not be loaded: ${failed.map((t) => t.tableName).join(', ')}. Reload the page to try again.`)} />
              )}
              <p className="muted">
                Print on A4 at 100% (&ldquo;actual size&rdquo;), not &ldquo;fit to page&rdquo;. Try scanning one card with a
                phone before printing them all. A table&apos;s QR code stays the same if you print it again.
              </p>
            </div>

            <div className="qr-sheets">
              {pages.map((page, p) => (
                <section className={`qr-page qr-cols-${layout.columns}`} key={p} aria-label={`Sheet ${p + 1}`}>
                  {page.map((t) => {
                    const img = images[keyOf(t.id)];
                    return (
                      <article className="qr-card" key={t.id}>
                        <h3 className="qr-card-restaurant">{name}</h3>
                        <p className="qr-card-table">{t.tableName}</p>
                        {img && 'url' in img ? (
                          <img className="qr-card-img" src={img.url} alt={`QR code for ${t.tableName}`} />
                        ) : (
                          <div className="qr-card-img qr-card-pending">{img ? 'Not loaded' : '…'}</div>
                        )}
                        <p className="qr-card-caption">Scan with your phone camera to view the menu and order</p>
                      </article>
                    );
                  })}
                </section>
              ))}
            </div>
          </>
        )}
      </QueryState>
    </>
  );
}
