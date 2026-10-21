/*
 * What the screen checks before a photo is uploaded or a link is saved. The server checks all of it again
 * (and more: it looks inside the file), so this only saves a round trip and gives a clear message sooner.
 * The link rules mirror the server's ImageUrlRules.
 */

export const ACCEPTED_TYPES = ['image/jpeg', 'image/png'];
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const MAX_LINK_LENGTH = 500;

const OUR_PHOTO = /^\/api\/public\/images\/[0-9a-f]{32}$/;

/** "1.5 MB", "300 KB": a size for people. */
export function sizeText(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** What is wrong with a chosen file, as a sentence, or null. Some browsers give no type, so the name is the fallback. */
export function fileProblem(file: { type: string; size: number; name?: string }): string | null {
  if (file.size === 0) return 'That file is empty.';
  if (file.size > MAX_UPLOAD_BYTES) {
    return `That photo is ${sizeText(file.size)}; the limit is ${sizeText(MAX_UPLOAD_BYTES)}.`;
  }
  const typeOk = file.type ? ACCEPTED_TYPES.includes(file.type) : /\.(jpe?g|png)$/i.test(file.name ?? '');
  return typeOk ? null : 'Please choose a JPEG or PNG photo.';
}

/** True for one of our own uploaded photos. */
export function isUploadedPhoto(url: string): boolean {
  return OUR_PHOTO.test(url.trim());
}

/** What is wrong with a photo link, or null when it is fine or blank. */
export function photoLinkProblem(url: string): string | null {
  const u = url.trim();
  if (!u) return null;
  if (u.length > MAX_LINK_LENGTH) return `The photo link is too long (limit ${MAX_LINK_LENGTH} characters).`;
  if (/\s/.test(u)) return 'The photo link cannot contain spaces.';
  if (u.startsWith('/api/public/images/')) return OUR_PHOTO.test(u) ? null : 'That is not a valid uploaded photo.';
  if (!/^https:\/\//i.test(u)) return 'The photo link must start with https:// (or upload a photo instead).';
  // JavaScript's URL parser quietly turns https:///path into host "path"; the server correctly refuses it.
  if (!/^https:\/\/[^/?#]/i.test(u)) return 'The photo link is not a valid web address.';
  try {
    const parsed = new URL(u);
    if (!parsed.hostname) return 'The photo link is not a valid web address.';
    if (parsed.username || parsed.password) return 'The photo link must not contain a username or password.';
  } catch {
    return 'The photo link is not a valid web address.';
  }
  return null;
}
