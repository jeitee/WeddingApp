/* ==========================================================
   WEDDING ADMIN EXTENSION  (add as a NEW file in the existing
   Apps Script project; it shares SPREADSHEET_ID, GUEST_SHEET,
   SEAT_SHEET and jsonResponse() with your existing Code.gs)

   Adds to the "Guest Invi" sheet (columns are created for you):
     invitation_sent | sent_at | sent_notes |
     first_opened_at | last_opened_at | visit_count

   Security: every admin call must include the secret stored in
   Project Settings -> Script properties -> ADMIN_KEY.
========================================================== */

const ADMIN_COLUMNS = [
  "invitation_sent", "sent_at", "sent_notes",
  "first_opened_at", "last_opened_at", "visit_count"
];

// A repeat visit inside this window is not counted again
// (the website calls action=guest again after RSVP saves).
const VISIT_DEDUPE_MS = 30 * 60 * 1000;


/** Run once from the editor (optional - columns are also created lazily). */
function setupAdminColumns() {
  const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName(GUEST_SHEET);
  const headers = ensureColumns_(sheet);
  Logger.log("Guest sheet headers: " + JSON.stringify(headers));
}

function ensureColumns_(sheet) {
  const lastCol = Math.max(sheet.getLastColumn(), 1);
  const headers = sheet.getRange(1, 1, 1, lastCol).getValues()[0].map(function (h) {
    return String(h).trim();
  });
  ADMIN_COLUMNS.forEach(function (name) {
    if (headers.indexOf(name) === -1) {
      headers.push(name);
      sheet.getRange(1, headers.length).setValue(name);
    }
  });
  return headers;
}

function isAdmin_(key) {
  const expected = PropertiesService.getScriptProperties().getProperty("ADMIN_KEY");
  return !!expected && String(key || "") === expected;
}

function iso_(v) {
  if (v instanceof Date) return v.toISOString();
  return v === null || v === undefined ? "" : String(v);
}

function truthy_(v) {
  if (v === true) return true;
  const s = String(v === null || v === undefined ? "" : v).trim().toLowerCase();
  return s === "true" || s === "yes" || s === "sent" || s === "1";
}


/* ---------- admin_list: every guest group with seats + tracking ---------- */
function adminList(key) {
  if (!isAdmin_(key)) return jsonResponse({ status: "error", message: "Unauthorized." });

  const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  const guestSheet = ss.getSheetByName(GUEST_SHEET);
  const seatSheet = ss.getSheetByName(SEAT_SHEET);
  if (!guestSheet) throw new Error("Guests sheet not found.");
  if (!seatSheet) throw new Error("Seats sheet not found.");

  const headers = ensureColumns_(guestSheet);
  const col = function (n) { return headers.indexOf(n); };

  // seats grouped by token
  const seatsByToken = {};
  const sd = seatSheet.getDataRange().getValues();
  if (sd.length > 1) {
    const h = sd[0].map(String);
    const t = h.indexOf("token"), n = h.indexOf("seat_no"),
          nm = h.indexOf("name"), a = h.indexOf("attending");
    for (let i = 1; i < sd.length; i++) {
      const tok = String(sd[i][t] || "").trim();
      if (!tok) continue;
      (seatsByToken[tok] = seatsByToken[tok] || []).push({
        seat_no: Number(sd[i][n] || 0),
        name: String(sd[i][nm] || ""),
        attending: String(sd[i][a] || "pending")
      });
    }
  }

  const data = guestSheet.getDataRange().getValues();
  const guests = [];
  for (let i = 1; i < data.length; i++) {
    const r = data[i];
    const token = String(r[col("token")] || "").trim();
    if (!token) continue;
    const get = function (n) { return col(n) >= 0 ? r[col(n)] : ""; };
    guests.push({
      token: token,
      group_name: String(get("group_name") || ""),
      max_pax: Number(get("max_pax") || 0),
      rsvp_status: String(get("rsvp_status") || ""),
      contact: String(get("contact") || ""),
      url: String(get("url") || ""),
      tiny_url: String(get("tiny URL") || ""),
      updated_at: iso_(get("updated_at")),
      invitation_sent: truthy_(get("invitation_sent")),
      sent_at: iso_(get("sent_at")),
      sent_notes: String(get("sent_notes") || ""),
      first_opened_at: iso_(get("first_opened_at")),
      last_opened_at: iso_(get("last_opened_at")),
      visit_count: Number(get("visit_count") || 0),
      seats: seatsByToken[token] || []
    });
  }
  return jsonResponse({ status: "success", generated_at: new Date().toISOString(), guests: guests });
}


/* ---------- admin_set_sent: explicit Sent / Not Sent ---------- */
function adminSetSent(payload) {
  if (!isAdmin_(payload.key)) return jsonResponse({ status: "error", message: "Unauthorized." });
  const token = String(payload.token || "").trim();
  if (!token) return jsonResponse({ status: "error", message: "Missing token." });

  const sent = payload.sent === true;
  const notes = String(payload.notes || "").slice(0, 500);

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return jsonResponse({ status: "error", message: "Server busy, try again." });
  try {
    const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName(GUEST_SHEET);
    const headers = ensureColumns_(sheet);
    const data = sheet.getDataRange().getValues();
    const tc = headers.indexOf("token");
    for (let i = 1; i < data.length; i++) {
      if (String(data[i][tc] || "").trim() !== token) continue;
      const row = i + 1;
      const now = new Date();
      sheet.getRange(row, headers.indexOf("invitation_sent") + 1).setValue(sent);
      sheet.getRange(row, headers.indexOf("sent_at") + 1).setValue(sent ? now : "");
      sheet.getRange(row, headers.indexOf("sent_notes") + 1).setValue(sent ? notes : "");
      return jsonResponse({ status: "success", token: token, sent_at: sent ? now.toISOString() : "" });
    }
    return jsonResponse({ status: "error", message: "Invalid token." });
  } finally {
    lock.releaseLock();
  }
}


/* ---------- trackVisit: called from getGuest() ---------- */
function trackVisit(sheet, row) {
  try {
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(3000)) return;           // never block the guest page
    try {
      const headers = ensureColumns_(sheet);
      const firstC = headers.indexOf("first_opened_at") + 1;
      const lastC = headers.indexOf("last_opened_at") + 1;
      const cntC = headers.indexOf("visit_count") + 1;
      const now = new Date();
      const last = sheet.getRange(row, lastC).getValue();
      if (last instanceof Date && now - last < VISIT_DEDUPE_MS) return;
      if (!sheet.getRange(row, firstC).getValue()) sheet.getRange(row, firstC).setValue(now);
      sheet.getRange(row, lastC).setValue(now);
      sheet.getRange(row, cntC).setValue((Number(sheet.getRange(row, cntC).getValue()) || 0) + 1);
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    Logger.log("trackVisit failed: " + err.message);   // tracking must never break RSVP
  }
}
