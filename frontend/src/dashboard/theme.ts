import { useCallback, useEffect, useState } from 'react';

export type DashboardMode = 'dark' | 'light';

const KEY = 'kmenu.dashboard.mode';

function read(): DashboardMode {
  try {
    const v = localStorage.getItem(KEY);
    if (v === 'light' || v === 'dark') return v;
  } catch {
    // storage blocked
  }
  return 'dark';
}

/** The dashboard's own light/dark choice, remembered on this device. Dark is the default look. */
export function useDashboardMode(): { mode: DashboardMode; toggle: () => void } {
  const [mode, setMode] = useState<DashboardMode>(read);
  useEffect(() => {
    try {
      localStorage.setItem(KEY, mode);
    } catch {
      // not remembered
    }
  }, [mode]);
  const toggle = useCallback(() => setMode((m) => (m === 'dark' ? 'light' : 'dark')), []);
  return { mode, toggle };
}

/** True while the viewport matches `query`; follows changes. */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setMatches(mq.matches);
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, [query]);
  return matches;
}
