const PATHS = {
  bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.6-3.6',
  burger: 'M4 7h16M4 12h16M4 17h10',
  back: 'M15 5l-7 7 7 7',
  arrowRight: 'M5 12h14M13 6l6 6-6 6',
  plus: 'M12 5v14M5 12h14',
  minus: 'M5 12h14',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3',
  cart: 'M3 4h2l2.4 11h10.2L20 7H6.2M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM17 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  home: 'M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z',
  receipt: 'M6 3h12v18l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6M9 16h3',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2',
  info: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8h.01',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  close: 'M6 6l12 12M18 6L6 18',
  pin: 'M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM12 7a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z',
  table: 'M3 9h18M5 9v10M19 9v10M8 9V6h8v3',
  bill: 'M7 3h10a1 1 0 0 1 1 1v17l-3-2-3 2-3-2-3 2V4a1 1 0 0 1 1-1zM9.5 8h5M9.5 11.5h5M9.5 15h3',
  note: 'M5 4h14v12l-4 4H5zM15 20v-4h4M8 9h8M8 13h5',
  // category icons
  handi: 'M4 11h16a8 8 0 0 1-16 0zM3 11h18M8 4c-.8 1 .8 2 0 3.5M12 4c-.8 1 .8 2 0 3.5M16 4c-.8 1 .8 2 0 3.5',
  skewer:
    'M3 21L21 3M10 14a2.3 2.3 0 1 1-4.6 0 2.3 2.3 0 0 1 4.6 0zM14.3 9.7a2.3 2.3 0 1 1-4.6 0 2.3 2.3 0 0 1 4.6 0zM18.6 5.4a2.3 2.3 0 1 1-4.6 0 2.3 2.3 0 0 1 4.6 0z',
  cloche: 'M3 18h18M5 18a7 7 0 0 1 14 0M12 11V9M10.5 9h3',
  bread: 'M5 11.5A3.5 3.5 0 0 1 7.5 5h9A3.5 3.5 0 0 1 19 11.5V18a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1zM9 9.5l1.5-1.5M12.5 10.5 14 9',
  rice: 'M3 12h18a9 9 0 0 1-18 0zM7 10a5 5 0 0 1 10 0M10 7.5l.4-.8M13 8l.6-.7M9 9.2h.6M14.5 9.3h.6',
  cup: 'M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 11h1.5a2.5 2.5 0 0 1 0 5H15M9 3c-.8 1 .8 2 0 3.5M12 3c-.8 1 .8 2 0 3.5',
  dessert: 'M4 13h16l-2.2 6.2a1 1 0 0 1-1 .8H7.2a1 1 0 0 1-1-.8zM8 13a4 4 0 0 1 8 0M12 6.5V9',
  egg: 'M12 3C8.5 3 6 8.5 6 13a6 6 0 0 0 12 0c0-4.5-2.5-10-6-10z',
  plate: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  star: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z',
  heart: 'M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z',
  wifi: 'M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.6 16a5 5 0 0 1 6.8 0M12 19.5h.01',
  chat: 'M4 5h16v11H9l-5 4zM8 9h8M8 12h5',
  phone: 'M6 3h3l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 4 5a2 2 0 0 1 2-2z',
  globe: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18',
  copy: 'M9 9h10v11H9zM5 15V4h10',
  gift: 'M4 11h16v9H4zM3 7h18v4H3zM12 7v13M12 7c-2 0-4-.5-4-2a2 2 0 0 1 4 0c0 1.500 0 2 0 2zM12 7c2 0 4-.5 4-2a2 2 0 0 0-4 0',
} as const;

export type CustomerIconName = keyof typeof PATHS;

export function CIcon({ name, size = 22, className }: { name: CustomerIconName; size?: number; className?: string }) {
  return (
    <svg
      className={`km-icon ${className ?? ''}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}

const CATEGORY_RULES: [RegExp, CustomerIconName][] = [
  [/kashmir|wazwan|special|signature|chef/i, 'handi'],
  [/starter|appeti|kebab|kabab|tikka|snack|small plate/i, 'skewer'],
  [/rice|pulao|pulav|biryani/i, 'rice'],
  [/bread|roti|naan|tchot|girda|kulcha|paratha/i, 'bread'],
  [/main|curr|entr/i, 'cloche'],
  [/bever|drink|kahwa|tea|coffee|juice|lassi|shake|soda/i, 'cup'],
  [/dessert|sweet|phirni|kheer|ice cream/i, 'dessert'],
  [/breakfast|morning|brunch/i, 'egg'],
];

/** Picks a fitting icon from a backend category name; unknown categories get a plate. */
export function iconForCategory(name: string): CustomerIconName {
  return CATEGORY_RULES.find(([re]) => re.test(name))?.[1] ?? 'plate';
}
