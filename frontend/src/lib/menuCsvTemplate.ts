/*
 * The sample file owners download, and the wording around it. The server's parser (MenuCsv) is the only
 * thing that reads an uploaded file; this template is checked against that very parser by the repo's
 * checks (docs/checks/pure/CheckMenuCsvTemplate.java), so the example can never drift into something the
 * import would refuse.
 */
export const MENU_CSV_TEMPLATE = `name,category,price,veg,description,available,prep_time
Paneer Tikka,Starters,280,veg,Cottage cheese marinated in spices and grilled,yes,15
Rogan Josh,Mains,450,non-veg,Slow-cooked Kashmiri lamb curry,yes,25
Dum Aloo,Mains,320,veg,Baby potatoes in a yoghurt gravy,yes,20
Noon Chai,Drinks,60,veg,Traditional pink tea,yes,5
Kahwa,Drinks,50,veg,,yes,5
`;

export const MENU_CSV_FILENAME = 'kashmir-menu-template.csv';

/** The largest file the server accepts, mirrored so the screen can say so before uploading. */
export const MAX_CSV_BYTES = 1_000_000;

export const CSV_COLUMNS: { name: string; required: boolean; note: string }[] = [
  { name: 'name', required: true, note: 'The dish name.' },
  { name: 'category', required: true, note: 'Created automatically if you do not have it yet.' },
  { name: 'price', required: true, note: 'A number, for example 280 or 280.50. A rupee sign is fine.' },
  { name: 'veg', required: true, note: 'veg or non-veg for every dish. Nothing is assumed.' },
  { name: 'description', required: false, note: 'Shown under the dish name.' },
  { name: 'available', required: false, note: 'yes or no. Blank means yes.' },
  { name: 'prep_time', required: false, note: 'Minutes. Blank means 10.' },
];

/** A readable size: "12 KB", "1.2 MB". */
export function fileSize(bytes: number): string {
  if (bytes < 1000) return `${bytes} B`;
  if (bytes < 1_000_000) return `${Math.round(bytes / 1000)} KB`;
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}
