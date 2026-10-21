# Menu import and the get-started checklist (Stage E)

**Status:** the rules (CSV reading, validation, progress) have been compiled and executed for real; the endpoints and screens have **not** been run. Apply Stages A to D first.

## Run the checks that exist today, and the ones for later

```bash
python3 docs/checks/run_all.py     # runs without Gradle: executes MenuCsvTest (30) and OnboardingRulesTest (10)
./gradlew test                     # later: also MenuImportIT and OnboardingIT
cd frontend && npm install && npm run typecheck && npm test
```
No database migration: it uses existing tables.

## Importing a menu from a spreadsheet

Admin, Menu items, **Import from CSV**. Download the sample, replace the rows, save as CSV, choose the file.

| Column | Needed | Notes |
|---|---|---|
| `name` | yes | up to 150 characters |
| `category` | yes | created automatically if new; matched ignoring capitals |
| `price` | yes | a number with at most 2 decimals; a rupee sign, `Rs`, `INR` and thousands commas are accepted |
| `veg` | **yes, for every dish** | `veg` or `non-veg` (also v, yes, nonveg, nv, egg...). **Nothing is assumed**: a blank or unrecognised value is an error, because a wrong default could label meat as vegetarian |
| `description` | no | up to 1000 characters |
| `available` | no | yes or no; blank means yes |
| `prep_time` | no | minutes, 0 to 300; blank means 10 |

Headers may be in any order, in any case, with a few common spellings (`Dish Name`, `Section`, `Rate`). Extra columns are ignored. Excel's byte-order mark, Windows line endings, quoted commas and line breaks, and semicolon-separated files are all handled. At most 500 dishes and 1 MB per file.

**What the server guarantees**
- **Preview changes nothing.** It lists each dish as "Will be added" or "Already there", and the new categories.
- **The import reads the file again.** It never trusts a preview.
- **All or nothing.** If any row has a problem, nothing is imported, and every problem is listed with its line number (line 1 is the header). A failure part-way rolls everything back.
- **Existing dishes are never overwritten.** The same name in the same category is skipped and counted, so importing a file twice is harmless. Changing an existing dish's price still goes through Edit.
- Imported dishes go through the same service as "Add item", so they obey the same rules. Only your own restaurant is touched. The import is recorded in the audit trail.
- Allowed roles: owner, admin, manager.

## The get-started checklist

Shown at the top of the Dashboard until the required steps are done, then it disappears. **Every step is ticked because the thing exists**, never because someone said so:

| Step | Done when | Required |
|---|---|---|
| Create a menu category | at least one active category | yes |
| Add your dishes | at least one active, available dish | yes |
| Add your tables | at least one active table | yes |
| Choose how guests can pay | at least one payment method enabled | yes |
| Place a test order | at least one order exists | yes |
| Add your team | at least one manager, chef, waiter or cashier | no |

Steps that cannot be verified from data (printing QR cards, reviewing tax) are deliberately left out rather than guessed.

## API

| Method and path | Purpose |
|---|---|
| `POST /api/menu-items/import/preview` `{"csv":"..."}` | What importing would do |
| `POST /api/menu-items/import` `{"csv":"..."}` | Import: `{created, skippedExisting, categoriesCreated}`; 400 if the file has problems |
| `GET /api/v1/onboarding` | `{steps[], requiredDone, requiredTotal, percent, complete, nextKey}` |

## Manual test

1. Admin, **Menu items**, **Import from CSV**, download the sample, choose it unchanged. The preview shows 5 dishes, all new. Import. Your menu has 5 dishes and 3 categories.
2. Choose the same file again: everything says "Already there", the Import button is disabled.
3. Edit the file: blank out a `veg` cell and put `abc` in a price. Choose it: a table lists both problems with their line numbers, and nothing can be imported.
4. Change a price in the file for a dish that exists and import: the existing dish keeps its old price.
5. On a brand-new restaurant, open the Dashboard: "0 of 5 done", the first step highlighted. Complete a step and refresh: it ticks itself. Place a test order from a table QR: all five tick and the card vanishes.

## Known limits

- Import only adds. It cannot update existing dishes, deactivate dishes missing from the file, set allergens, options, images, or portion sizes (add those after, on each dish).
- One category level only; no category descriptions or ordering from the file.
- CSV only (save a spreadsheet as CSV). No `.xlsx` upload.
- I have not seen either screen rendered.
