# WeddingApp — Teejei & Judith (December 20, 2026)

Native Android admin app (Kotlin, Compose + Material 3, MVVM, Coroutines/Flow, Retrofit, Room,
DataStore, Coil, Media3 ExoPlayer, Navigation Compose). It reads the **existing** backends
found in `TheWedding.zip`; the website itself is untouched.

## Toolchain
| Item | Version |
|---|---|
| JDK | 17 |
| Android Studio | Ladybug 2024.2.1 or newer |
| Gradle | 8.9 (wrapper properties included) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / KSP | 2.0.21 / 2.0.21-1.0.28 |
| Compose BOM | 2024.10.01 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

Open the folder in Android Studio and let it sync (it downloads Gradle 8.9 from the wrapper
properties; `gradle-wrapper.jar` isn't bundled — run `gradle wrapper` if you want one).
**This project was written without a build environment, so it has not been compiled.** If sync
reports a version or typo issue, send me the error and I'll fix it.

## What was verified in the website source
- Invitation link = `?token=<token>` on the site (`script.js` reads `token`, else `invalid.html`).
- Apps Script spreadsheet: sheet **`Guest Invi`** (`token | group_name | max_pax | rsvp_status | updated_at | url | contact`)
  and **`RSVP_Seats`** (`token | seat_no | name | attending | updated_at`); `attending` is `pending | attending | not_attending`.
  The sheet's `rsvp_status` is just `"submitted"`, so the app derives Confirmed / Declined / Awaiting from the seat rows.
- Supabase project `jwixdwokeguliuwbwbqs`, event `teejei-judith`; tables `uploads` and `files`; media on Cloudinary (`ddokjiyuy`).
- The invitation URL shown in the app is the sheet's `url` column (or the imported Excel `url`). It is never built from a guess;
  a missing/invalid URL or a URL whose `token` differs from the row's token is flagged.

## Setup step 1 — Apps Script (required for Guests, Dashboard, Sent/Opened tracking)
1. Open the Apps Script project bound to the wedding sheet.
2. Add a new file **Admin** and paste `apps-script/Admin.gs`.
3. Apply the two edits in `apps-script/PATCH_EXISTING_CODE.md`.
4. *Project Settings → Script properties → Add* `ADMIN_KEY` = a long random string (this is the app's password).
5. Run `setupAdminColumns` once (grants permission, adds the six tracking columns to `Guest Invi`).
6. *Deploy → Manage deployments → ✏️ Edit → Version: New version → Deploy.* The `/exec` URL stays the same, so the website keeps working.
7. In the app: **Settings → Admin key** → paste `ADMIN_KEY` → *Save & refresh*.

New columns: `invitation_sent, sent_at, sent_notes, first_opened_at, last_opened_at, visit_count`.

## Setup step 2 — Supabase read access (needed for Memories)
The website only *inserts* with the publishable key. If Memories shows nothing or "Access denied", add read policies
(I could not see your existing policies, so check first under *Authentication → Policies*):
```sql
create policy "admin app can read uploads" on public.uploads for select to anon using (true);
create policy "admin app can read files"   on public.files   for select to anon using (true);
```
Caveat: this makes upload metadata (names, messages, Cloudinary URLs) readable by anyone holding the publishable key
that is already public in `qr-script.js`. The media URLs are already public Cloudinary links, so the added exposure is
mainly uploader names/messages. Tighten later (e.g. an Edge Function checked against ADMIN_KEY) if that matters.

## Behaviour notes
- **Sent tracking** is manual only. Copy/Share never marks anything as sent. Changing status always asks for confirmation;
  it is saved on this device immediately, pushed to the sheet, and retried on the next refresh if offline ("Sync pending").
- **Opened tracking** is recorded server-side when the invitation page loads (`action=guest`). A repeat load within
  30 minutes counts once. **Opening a link from this app counts as a visit** — use it sparingly, or clear the row's
  tracking cells afterwards.
- **Excel import** (Settings): custom lightweight `.xlsx` reader (no Apache POI). Reads the sheet named `Guest Invi`, else the first
  sheet. Needs a `token` header (rows without a token are skipped, never invented). Recognised headers: `token`,
  `group_name|group|name`, `max_pax|pax|seats`, `contact|phone`, `url|invitation_url|link`. Backend data wins on the next sync;
  Excel URLs are used only when the backend has none. Tokens that exist only in Excel disappear on sync.
- Stats are computed from the cached records; "Guest groups" and "Seats" are shown separately.
- The admin key is stored in app DataStore (private app storage). Treat the APK/device accordingly.

## Heads-up about the website
`qr-script.js` has `WEDDING_DATE = "2026-10-12"` (upload window opens 5 PM that day, closes 2 days later), while the
countdown in `script.js` uses December 20, 2026. Looks like test data — I did not change it.

## Layout
`app/src/main/java/com/teejei/weddingapp/`: `model/` (domain + stats), `data/` (Room, DataStore, Retrofit, Excel reader,
Repository), `ui/` (ViewModel, theme, Dashboard, Guests, Memories, Settings), `MainActivity.kt` (nav + bottom bar).
