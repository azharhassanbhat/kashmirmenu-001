# Language policy: English only

The app is English only: no translation system, no language switch, no translated text.

## What is enforced

- **The interface and server messages are English.** There is no translation library or language file. `<html lang="en">` is set.
- **Display formats are English whatever the device's language is.** Prices, dates and times are shown in English (India), for example `Rs 1,00,000.00` style grouping and `3:45 pm`. Before this was pinned, about 8 places followed the *device*, so a guest whose phone was set to Hindi, Urdu or Arabic could see other numerals or date styles. `docs/checks/locale_check.py` now fails if any date, time or number formatting follows the device language again.
- **Server messages ignore the browser's `Accept-Language`.** `spring.web.locale-resolver=fixed` with `locale=en`. Without it, the default wording of validation errors ("must not be blank") is translated by Hibernate Validator for callers whose browser asks for another language. `EnglishOnlyIT` proves a request asking for German, Hindi, Arabic, Japanese, French, Chinese, Urdu or Spanish still gets English.

## What is NOT restricted (a decision for the owner)

Text that people **type** is not limited to English letters. In particular:

- **Guest names** accept letters of any script (a deliberate, tested rule in `DinerIdentity`: "so Hindi, Urdu and Kashmiri names work"). A guest named in Urdu or Devanagari can order.
- Dish names, descriptions, categories, notes and the CSV import accept any script. International domain names in photo links are accepted.

Restricting input to English letters would stop a guest whose name is written in another script from joining a table, so it has **not** been done unless explicitly requested.
