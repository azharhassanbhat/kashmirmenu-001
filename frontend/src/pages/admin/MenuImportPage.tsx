import { useState, type ChangeEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { menuImportApi } from '../../api';
import type { MenuImportPreview } from '../../api/types';
import { ErrorMessage } from '../../components/ui';
import {
  CSV_COLUMNS,
  fileSize,
  MAX_CSV_BYTES,
  MENU_CSV_FILENAME,
  MENU_CSV_TEMPLATE,
} from '../../lib/menuCsvTemplate';
import { money } from '../../lib/format';

/**
 * Load a whole menu from a spreadsheet. The server reads the file, here and again when importing, so
 * what is shown is what will happen. A file with any problem imports nothing; dishes that already exist
 * are skipped, never overwritten, so importing the same file twice is harmless.
 */
export default function MenuImportPage() {
  const queryClient = useQueryClient();
  const [fileName, setFileName] = useState<string | null>(null);
  const [csv, setCsv] = useState<string | null>(null);
  const [tooBig, setTooBig] = useState<string | null>(null);
  const [preview, setPreview] = useState<MenuImportPreview | null>(null);

  const previewMutation = useMutation({
    mutationFn: (text: string) => menuImportApi.preview(text),
    onSuccess: setPreview,
  });
  const importMutation = useMutation({
    mutationFn: (text: string) => menuImportApi.commit(text),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['menu-items'] });
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      void queryClient.invalidateQueries({ queryKey: ['onboarding'] });
    },
  });

  async function onFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    setPreview(null);
    setCsv(null);
    setTooBig(null);
    importMutation.reset();
    previewMutation.reset();
    if (!file) return;
    setFileName(file.name);
    if (file.size > MAX_CSV_BYTES) {
      setTooBig(`This file is ${fileSize(file.size)}; the limit is ${fileSize(MAX_CSV_BYTES)}. Split it into several files.`);
      return;
    }
    const text = await file.text();
    setCsv(text);
    previewMutation.mutate(text);
  }

  function downloadTemplate() {
    const blob = new Blob([MENU_CSV_TEMPLATE], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = MENU_CSV_FILENAME;
    a.click();
    URL.revokeObjectURL(url);
  }

  const done = importMutation.data;

  return (
    <>
      <div className="page-head">
        <h2>Import a menu</h2>
        <Link className="btn" to="/admin/menu">
          Back to menu items
        </Link>
      </div>

      <div className="card">
        <h3>1. Start from the sample</h3>
        <p className="muted">
          Open it in Excel or Google Sheets, replace the rows with your dishes, and save as CSV.
        </p>
        <button type="button" className="btn" onClick={downloadTemplate}>
          Download sample file
        </button>
        <div className="table-wrap" style={{ marginTop: 'var(--space-3)' }}>
          <table>
            <thead>
              <tr>
                <th>Column</th>
                <th>Needed</th>
                <th>What to put</th>
              </tr>
            </thead>
            <tbody>
              {CSV_COLUMNS.map((c) => (
                <tr key={c.name}>
                  <td data-label="Column">
                    <code>{c.name}</code>
                  </td>
                  <td data-label="Needed">{c.required ? 'Yes' : 'Optional'}</td>
                  <td data-label="What to put">{c.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h3>2. Choose your file</h3>
        <input type="file" accept=".csv,text/csv" onChange={onFile} aria-label="CSV file" />
        {fileName && csv && <p className="muted">{fileName} ({fileSize(csv.length)})</p>}
        {tooBig && (
          <div className="alert alert-error" role="alert">
            {tooBig}
          </div>
        )}
        <ErrorMessage error={previewMutation.error} />
      </div>

      {previewMutation.isPending && <p className="muted">Checking the file…</p>}

      {preview && !preview.canImport && (
        <div className="card">
          <h3>Fix these first</h3>
          <p className="muted">
            Nothing has been imported. Correct the file, then choose it again. Line 1 is the header row.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th className="num">Line</th>
                  <th>Column</th>
                  <th>Problem</th>
                </tr>
              </thead>
              <tbody>
                {preview.problems.map((p, i) => (
                  <tr key={i}>
                    <td className="num" data-label="Line">
                      {p.line > 0 ? p.line : '—'}
                    </td>
                    <td data-label="Column">{p.field}</td>
                    <td data-label="Problem">{p.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {preview && preview.canImport && !done && (
        <div className="card">
          <h3>3. Check, then import</h3>
          <p>
            <strong>{preview.toCreate}</strong> {preview.toCreate === 1 ? 'dish' : 'dishes'} will be added
            {preview.newCategories.length > 0 && (
              <>
                , with {preview.newCategories.length} new{' '}
                {preview.newCategories.length === 1 ? 'category' : 'categories'} (
                {preview.newCategories.join(', ')})
              </>
            )}
            .
            {preview.alreadyExist > 0 && (
              <>
                {' '}
                {preview.alreadyExist} already {preview.alreadyExist === 1 ? 'exists' : 'exist'} in the same category
                and will be left as they are.
              </>
            )}
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th className="num">Line</th>
                  <th>Dish</th>
                  <th>Category</th>
                  <th className="num">Price</th>
                  <th>Type</th>
                  <th>Result</th>
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((r) => (
                  <tr key={r.line}>
                    <td className="num" data-label="Line">
                      {r.line}
                    </td>
                    <td data-label="Dish">{r.name}</td>
                    <td data-label="Category">
                      {r.category}
                      {r.newCategory && <span className="muted"> (new)</span>}
                    </td>
                    <td className="num" data-label="Price">
                      {money(r.price)}
                    </td>
                    <td data-label="Type">{r.veg ? 'Veg' : 'Non-veg'}</td>
                    <td data-label="Result">
                      <span className={`badge ${r.status === 'NEW' ? 'badge-green' : 'badge-gray'}`}>
                        {r.status === 'NEW' ? 'Will be added' : 'Already there'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ErrorMessage error={importMutation.error} />
          <div className="actions">
            <button
              type="button"
              className="btn btn-primary"
              disabled={importMutation.isPending || csv === null || preview.toCreate === 0}
              onClick={() => csv !== null && importMutation.mutate(csv)}
            >
              {importMutation.isPending ? 'Importing…' : `Import ${preview.toCreate} ${preview.toCreate === 1 ? 'dish' : 'dishes'}`}
            </button>
          </div>
          {preview.toCreate === 0 && <p className="muted">Everything in this file is already on your menu.</p>}
        </div>
      )}

      {done && (
        <div className="alert alert-ok" role="status">
          Imported {done.created} {done.created === 1 ? 'dish' : 'dishes'}
          {done.categoriesCreated > 0 && <>, and {done.categoriesCreated} new {done.categoriesCreated === 1 ? 'category' : 'categories'}</>}
          {done.skippedExisting > 0 && <>. {done.skippedExisting} already existed and were not changed</>}
          . <Link to="/admin/menu">See your menu</Link>
        </div>
      )}
    </>
  );
}
