// Resolves `vitest` to the stand-in and extensionless relative imports to .ts files.
export async function resolve(specifier, context, next) {
  if (specifier === 'vitest') return { url: new URL('./vitest.mjs', import.meta.url).href, shortCircuit: true };
  if ((specifier.startsWith('./') || specifier.startsWith('../')) && !/\.[a-z]+$/.test(specifier)) {
    for (const ext of ['.ts', '.tsx', '/index.ts']) {
      try { return await next(specifier + ext, context); } catch { /* try the next */ }
    }
  }
  return next(specifier, context);
}

// Vite provides import.meta.env; plain Node does not. Give modules an empty environment so their
// documented defaults apply (for example the currency falls back to INR).
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
export async function load(url, context, next) {
  if (url.startsWith('file:') && url.endsWith('.ts') && !url.includes('/node_modules/')) {
    const source = await readFile(fileURLToPath(url), 'utf8');
    if (source.includes('import.meta.env')) {
      return { format: 'module-typescript', source: source.replaceAll('import.meta.env', '({})'), shortCircuit: true };
    }
  }
  return next(url, context);
}
