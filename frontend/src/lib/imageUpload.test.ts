import { describe, expect, it } from 'vitest';
import { fileProblem, isUploadedPhoto, MAX_UPLOAD_BYTES, photoLinkProblem, sizeText } from './imageUpload';

const KEY = '0123456789abcdef0123456789abcdef';

describe('photo file checks', () => {
  it('accepts a JPEG and a PNG', () => {
    expect(fileProblem({ type: 'image/jpeg', size: 2_000_000, name: 'a.jpg' })).toBeNull();
    expect(fileProblem({ type: 'image/png', size: 100, name: 'a.png' })).toBeNull();
  });

  it('refuses other types, even ones that are pictures', () => {
    for (const type of ['image/gif', 'image/webp', 'image/svg+xml', 'application/pdf', 'text/html']) {
      expect(fileProblem({ type, size: 1000, name: 'x' })).toBe('Please choose a JPEG or PNG photo.');
    }
  });

  it('falls back to the file name when the browser gives no type', () => {
    expect(fileProblem({ type: '', size: 1000, name: 'Dish.JPG' })).toBeNull();
    expect(fileProblem({ type: '', size: 1000, name: 'dish.jpeg' })).toBeNull();
    expect(fileProblem({ type: '', size: 1000, name: 'dish.exe' })).toMatch(/JPEG or PNG/);
    expect(fileProblem({ type: '', size: 1000 })).toMatch(/JPEG or PNG/);
  });

  it('refuses an empty file and one over the limit, saying how big it was', () => {
    expect(fileProblem({ type: 'image/jpeg', size: 0 })).toBe('That file is empty.');
    expect(fileProblem({ type: 'image/jpeg', size: MAX_UPLOAD_BYTES })).toBeNull();
    expect(fileProblem({ type: 'image/jpeg', size: MAX_UPLOAD_BYTES + 1 })).toMatch(/limit is 10\.0 MB/);
    expect(fileProblem({ type: 'image/jpeg', size: 15 * 1024 * 1024 })).toMatch(/15\.0 MB/);
  });

  it('writes sizes for people', () => {
    expect(sizeText(500)).toBe('500 B');
    expect(sizeText(300 * 1024)).toBe('300 KB');
    expect(sizeText(1.5 * 1024 * 1024)).toBe('1.5 MB');
  });
});

describe('photo link checks (mirror the server)', () => {
  it('blank is fine: a photo is optional', () => {
    expect(photoLinkProblem('')).toBeNull();
    expect(photoLinkProblem('   ')).toBeNull();
  });

  it('accepts https links and our own uploaded photos', () => {
    expect(photoLinkProblem('https://example.com/dish.jpg')).toBeNull();
    expect(photoLinkProblem('HTTPS://Example.com/dish')).toBeNull();
    expect(photoLinkProblem('  https://example.com/dish.jpg ')).toBeNull();
    expect(photoLinkProblem(`/api/public/images/${KEY}`)).toBeNull();
  });

  it('refuses insecure and dangerous addresses', () => {
    for (const bad of ['http://example.com/a.jpg', 'javascript:alert(1)', 'data:image/png;base64,AAAA',
      'ftp://example.com/a.jpg', '//example.com/a.jpg', 'example.com/a.jpg', 'file:///etc/passwd', '/etc/passwd']) {
      expect(photoLinkProblem(bad)).not.toBeNull();
    }
  });

  it('refuses malformed addresses', () => {
    expect(photoLinkProblem('https://')).not.toBeNull();
    expect(photoLinkProblem('https:///path')).not.toBeNull();   // JavaScript alone would read this as host "path"
    expect(photoLinkProblem('https:////path')).not.toBeNull();
    expect(photoLinkProblem('https://?x=1')).not.toBeNull();
    expect(photoLinkProblem('https://exa mple.com/a.jpg')).toMatch(/spaces/);
    expect(photoLinkProblem('https://user:pass@example.com/a.jpg')).toMatch(/username/);
    expect(photoLinkProblem('https://example.com/' + 'a'.repeat(500))).toMatch(/too long/);
  });

  it('refuses an uploaded-photo address that is not real', () => {
    expect(photoLinkProblem('/api/public/images/notakey')).toMatch(/not a valid uploaded photo/);
    expect(photoLinkProblem(`/api/public/images/${KEY}/extra`)).toMatch(/not a valid uploaded photo/);
  });

  it('recognises our own photos', () => {
    expect(isUploadedPhoto(`/api/public/images/${KEY}`)).toBe(true);
    expect(isUploadedPhoto(`https://example.com/api/public/images/${KEY}`)).toBe(false);
    expect(isUploadedPhoto('')).toBe(false);
  });
});
