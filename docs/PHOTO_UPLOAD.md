# Photo upload, stored in the database (Stage G)

**Status:** the photo processing and link rules are compiled and executed for real, with real images. The endpoints and the form have **not** been run. Apply Stages A to F first.

```bash
python3 docs/checks/run_all.py     # today: runs ImageProcessorTest (27, real pixels), ImageRulesTest (17), link parity (45 addresses)
./gradlew test                     # later: also ImageUploadIT (17)
./gradlew mysqlTest                # later: IMPORTANT, see "Verify on first build"
```

## What the restaurant gets

On a dish: **Upload a photo** (JPEG or PNG, up to 10 MB), **Remove photo**, a live preview, and the old "paste a link" box with clear guidance. Photos are stored in your MySQL database: they are backed up with everything else and **`docker-compose.yml` is unchanged**.

## What happens to an uploaded photo

1. The file's **bytes** decide what it is (its name and the browser's claim are ignored). Only a real JPEG or PNG passes; HTML, SVG, PDF, programs and scripts renamed to .jpg are refused.
2. It must decode as a photo, and a JPEG must reach its end (a photo cut off by an interrupted upload is refused, not stored half grey).
3. Pixel count is read from the header first: a tiny file declaring a huge canvas is refused without building it (limit 24 megapixels). Large photos are decoded at reduced size to bound memory, and only 2 are processed at once.
4. Phone rotation is applied, then **all metadata is discarded**, including GPS position and camera details a restaurant would otherwise publish by accident.
5. Transparency becomes white (JPEG has none).
6. A **new JPEG** is written, at most 1200 pixels on its longest side, typically 100 to 300 KB. What is stored is never the uploaded bytes, so anything appended to or hidden in the original does not survive.

## Addresses, access and housekeeping

- Each photo has a random 128-bit address, `/api/public/images/<32 hex characters>`. Guests are not signed in, so the address is public: holding it is enough, and nobody can guess or count up to other photos. Browsers may cache it for a year because a changed photo gets a new address.
- Only owners, admins and managers can upload. Floor and kitchen staff get 403; signed-out callers get 401.
- **Quota per restaurant: 150 MB and 1,000 photos.** Over the limit, the upload is refused with a plain message.
- A scheduled job (every 6 hours) deletes photos **no dish and no logo uses**, but only if older than 24 hours, so a photo uploaded while a form is open is never deleted from under the person using it. Replacing a photo frees the old one the same way.
- The dish stores only the photo's address in its existing image field, so menus, orders and the customer screens needed no change.

## Image links (the stricter rule, applies to pasted links)

A dish's image address must be an `https://` link or one of our own uploaded photos. Refused: `http://`, `javascript:`, `data:`, addresses with a username or password, spaces, over 500 characters, or no host (`https:///x`). International domain names (Hindi, Arabic, Japanese, etc.) are accepted. **The rule applies only when the address changes**, so a restaurant with an older `http://` link can still edit a dish's price. The screen applies the same rules for a quicker answer, and a check proves the two implementations agree on every address in a 45-address list.

## Database (V38, additive only) and other config

New table `stored_images`. Rollback before it matters: `DROP TABLE stored_images;` and delete the `flyway_schema_history` row for version 38.
Also changed: `frontend/nginx.conf` (`client_max_body_size 12m` for `/api/`, because nginx's default of 1 MB would reject nearly every phone photo) and `application.yaml` (multipart limits 10 MB / 11 MB). **Rebuild the frontend container for the nginx change to take effect.**

## API

| Method and path | Purpose |
|---|---|
| `POST /api/v1/images` (multipart form, field `file`) | Upload. Returns `{url, width, height, sizeBytes}`; 400 with a plain message if refused |
| `GET /api/public/images/{key}` | The photo (public, cached); 404 for an unknown or malformed key |

## Verify on first build

- **`./gradlew mysqlTest` is the one that matters.** Production validates the schema at startup, and Hibernate is picky about binary column types. The photo column is `MEDIUMBLOB` mapped as `LONGVARBINARY` (the usual fix), but only a real MySQL run can confirm it. If the app refuses to start with a message about the `data` column of `stored_images`, that is this.
- The "file too large" 413 response comes from the web server's own limit, which the test framework does not enforce. The app's own 10 MB check (a 400) is tested.

## Manual test

1. Admin, Menu items, Add item (or Edit). **Upload a photo** from your phone. The preview appears, and you can save the dish.
2. Open the guest menu: the dish shows the photo. A portrait photo is upright.
3. Upload a photo with a long edge of 4000 pixels: it works, and loads quickly on the menu.
4. Choose a text file renamed to `.jpg`: refused with "Please choose a JPEG or PNG photo" or "could not be read".
5. Paste `http://example.com/a.jpg` in the link box: the form tells you it must start with `https://` and will not save.
6. Replace a dish's photo. After a day the old one is cleared automatically.
7. Edit the price of a dish that has an old `http://` link: it saves.

## Known limits

- JPEG and PNG only. WebP, GIF, HEIC and CMYK JPEGs are refused with a message (phones normally convert to JPEG when uploading).
- No cropping, rotating or filters in the app.
- No logo upload yet; only dish photos.
- Photos are public by address: someone who has an address can fetch that photo, even from another restaurant's account.
- The memory protection (reduced-size decoding, two at a time) cannot be observed by any test.
- I have not seen the form rendered.
