/**
 * Familienstammbaum – Google Apps Script (Ende-zu-Ende-verschlüsselt)
 *
 * Google speichert nur verschlüsselte Daten:
 *   Google Drive / FS /
 *     «FS Daten»  Tabelle mit verschlüsselten Einträgen (E), Zugängen (Z) und Foto-Verweisen (B)
 *     b/          verschlüsselte Fotos und Vorschaubilder (zufällige Dateinamen)
 *
 * Ver- und entschlüsselt wird nur in der App bzw. im Browser der Familienmitglieder.
 * Der Schlüssel steht im Einladungslink hinter dem «#» und wird nie an Google gesendet.
 * Dieses Skript kennt keinen Schlüssel und kann die Daten nicht lesen. Es prüft nur,
 * wer laden und speichern darf (Ansehen, Bearbeiten, Admin).
 *
 * Bereitstellen als Web-App: «Ausführen als: Ich», «Zugriff: Jeder».
 */

const DIR_NAME = 'FS';
const DATA_NAME = 'FS Daten';
const DRIVE_HTML_NAME = 'Familienstammbaum-App.html';
const ROLES = ['view', 'edit', 'admin'];
const RANK = { view: 1, edit: 2, admin: 3 };
const TABLES = {
  E: ['id', 'd', 't'],                                         // Einträge: Stammbäume, Personen, Fotos (verschlüsselt)
  Z: ['id', 'h', 'r', 's', 'pub', 'pk', 'kw', 'm', 'c', 'l'],  // Zugänge: Prüfwert, Rechte, Status, Schlüsselpaket, Name (verschlüsselt)
  B: ['id', 'f', 'n'],                                         // verschlüsselte Dateien: ID, Drive-Datei, Grösse
};
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const API_ = {
  apiStatus: apiStatus, apiSetup: apiSetup, apiLoad: apiLoad, apiActivate: apiActivate, apiCommit: apiCommit,
  apiPutBlob: apiPutBlob, apiHasBlobs: apiHasBlobs, apiGetBlobs: apiGetBlobs, apiDelBlobs: apiDelBlobs,
  apiCreds: apiCreds, apiAddCred: apiAddCred, apiSetMyMeta: apiSetMyMeta, apiUpdCred: apiUpdCred, apiDelCred: apiDelCred, apiRotate: apiRotate,
  apiStorage: apiStorage, apiLegacyExport: apiLegacyExport, apiLegacyImages: apiLegacyImages, apiLegacyPurge: apiLegacyPurge,
  apiVersion: apiVersion, apiSnapshots: apiSnapshots, apiSnapshotNow: apiSnapshotNow, apiSnapshotRestore: apiSnapshotRestore,
};
const VERSION = 2;
const SNAP_DAYS = 7;      // automatische Sicherung höchstens einmal pro Woche (beim Öffnen durch ein Mitglied)
const SNAP_KEEP = 12;     // so viele automatische Sicherungen bleiben erhalten (etwa drei Monate)
const TRUSTED_RE = /^vt/; // Notfall-Schlüssel von Vertrauenspersonen (es kann mehrere geben)

/* ---------- Web-App ---------- */
function doGet(e) {
  if (e && e.parameter && e.parameter.api) {
    return json_({ ok: false, error: 'Bitte die Familienstammbaum-App aktualisieren.' });
  }
  let out;
  try {
    out = HtmlService.createHtmlOutputFromFile('Index');
  } catch (err) {
    const it = DriveApp.getFilesByName(DRIVE_HTML_NAME);
    if (!it.hasNext()) {
      return HtmlService.createHtmlOutput('<p style="font:16px sans-serif;padding:24px;max-width:520px">' +
        'Die App-Datei fehlt. Lege im Apps-Script-Projekt eine HTML-Datei mit dem Namen <b>Index</b> an ' +
        'oder lade <b>' + DRIVE_HTML_NAME + '</b> in dein Google Drive hoch.</p>')
        .addMetaTag('viewport', 'width=device-width, initial-scale=1');
    }
    out = HtmlService.createHtmlOutput(it.next().getBlob().getDataAsString('UTF-8'));
  }
  return out.setTitle('Familienstammbaum').addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/** Schnittstelle für die Android-App: {fn, args} → {ok, r} oder {ok:false, e} */
function doPost(e) {
  let out;
  try {
    const q = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    const f = Object.prototype.hasOwnProperty.call(API_, q.fn) ? API_[q.fn] : null;
    if (!f) throw new Error('Unbekannte Funktion.');
    out = { ok: true, r: f.apply(null, Array.isArray(q.args) ? q.args : []) };
  } catch (err) {
    out = { ok: false, e: String((err && err.message) || err) };
  }
  return json_(out);
}
function json_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }

/* ---------- Ablage ---------- */
function props_() { return PropertiesService.getScriptProperties(); }
let ST_ = null;
function st_() {
  if (ST_) return ST_;
  const P = props_();
  const dir = folder_(P, 'FS_DIR', null, DIR_NAME);
  const blobs = folder_(P, 'FS_BLOBS', dir, 'b');
  let ss = null;
  const id = P.getProperty('FS_DATA');
  if (id) {
    try { if (!DriveApp.getFileById(id).isTrashed()) ss = SpreadsheetApp.openById(id); } catch (e) { ss = null; }
  }
  if (!ss) {
    ss = SpreadsheetApp.create(DATA_NAME);
    DriveApp.getFileById(ss.getId()).moveTo(dir);
    P.setProperty('FS_DATA', ss.getId());
    Object.keys(TABLES).forEach(function (k) { tab_(ss, k); });
    ss.getSheets().forEach(function (sh) { if (!TABLES[sh.getName()] && ss.getSheets().length > 1) ss.deleteSheet(sh); });
  }
  ST_ = { ss: ss, dir: dir, blobs: blobs };
  return ST_;
}
function folder_(P, key, parent, name) {
  const id = P.getProperty(key);
  if (id) {
    try { const f = DriveApp.getFolderById(id); if (!f.isTrashed()) return f; } catch (e) { /* neu anlegen */ }
  }
  const f = parent ? parent.createFolder(name) : DriveApp.createFolder(name);
  P.setProperty(key, f.getId());
  return f;
}
function tab_(ss, name) {
  const cols = TABLES[name];
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.getRange(1, 1, sh.getMaxRows(), cols.length).setNumberFormat('@');
    sh.getRange(1, 1, 1, cols.length).setValues([cols]).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  return sh;
}
function rows_(name) {
  const cols = TABLES[name];
  const sh = tab_(st_().ss, name);
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, cols.length).getDisplayValues()
    .filter(function (r) { return r[0]; })
    .map(function (r) { const o = {}; cols.forEach(function (c, i) { o[c] = r[i]; }); return o; });
}
/** Einfügen oder ersetzen (nach ID) und löschen */
function save_(name, objs, delIds) {
  objs = objs || []; delIds = delIds || [];
  if (!objs.length && !delIds.length) return;
  const cols = TABLES[name], n = cols.length;
  const sh = tab_(st_().ss, name);
  const last = sh.getLastRow();
  const ids = last > 1 ? sh.getRange(2, 1, last - 1, 1).getDisplayValues().map(function (r) { return r[0]; }) : [];
  const rowOf = {};
  ids.forEach(function (id, i) { if (id) rowOf[id] = i + 2; });
  const add = [];
  objs.forEach(function (o) {
    const row = cols.map(function (c) { return o[c] == null ? '' : String(o[c]); });
    const r = rowOf[o.id];
    if (r > 1) sh.getRange(r, 1, 1, n).setNumberFormat('@').setValues([row]);
    else if (r !== -1) { add.push(row); rowOf[o.id] = -1; }
  });
  if (add.length) {
    const start = Math.max(sh.getLastRow(), 1) + 1;
    const need = start + add.length - 1;
    if (need > sh.getMaxRows()) sh.insertRowsAfter(sh.getMaxRows(), need - sh.getMaxRows() + 50);
    sh.getRange(start, 1, add.length, n).setNumberFormat('@').setValues(add);
  }
  delIds.map(function (id) { return rowOf[id]; })
    .filter(function (r) { return r > 1; })
    .sort(function (a, b) { return b - a; })
    .forEach(function (r) { sh.deleteRow(r); });
}

/* ---------- Zugriff ---------- */
function hash_(s) {
  const b = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(s), Utilities.Charset.UTF_8);
  return b.map(function (x) { const v = (x + 256) % 256; return (v < 16 ? '0' : '') + v.toString(16); }).join('');
}
function now_() { return Utilities.formatDate(new Date(), Session.getScriptTimeZone() || 'Europe/Zurich', 'yyyy-MM-dd HH:mm'); }
function appUrl_() { try { return ScriptApp.getService().getUrl() || ''; } catch (e) { return ''; } }
function isSetup_() { return !!props_().getProperty('FS_KV'); }
function lock_() { const l = LockService.getScriptLock(); l.waitLock(30000); return l; }
function forget_(h) { if (h) CacheService.getScriptCache().remove('z_' + h); }

/** Wer ruft auf? {id, r, s} oder null */
function me_(a) {
  a = String(a || '');
  if (a.length < 32) return null;
  const h = hash_(a);
  const cache = CacheService.getScriptCache();
  const hit = cache.get('z_' + h);
  if (hit) return JSON.parse(hit);
  const z = rows_('Z').filter(function (x) { return x.h === h; })[0];
  if (!z || !RANK[z.r]) return null;
  const me = { id: z.id, r: z.r, s: z.s, h: h };
  cache.put('z_' + h, JSON.stringify(me), 300);
  return me;
}
function need_(a, role) {
  const me = me_(a);
  if (!me) throw new Error('Kein Zugriff.');
  if (me.s !== 'dev') throw new Error('Dieser Link muss zuerst in der App eingelöst werden.');
  if (RANK[me.r] < RANK[role]) throw new Error('Kein Zugriff für diese Aktion.');
  return me;
}
function touch_(me) {
  const cache = CacheService.getScriptCache();
  if (cache.get('seen_' + me.id)) return;
  cache.put('seen_' + me.id, '1', 3600);
  const z = rows_('Z').filter(function (x) { return x.id === me.id; })[0];
  if (z) { z.l = now_(); save_('Z', [z]); }
}
function str_(v, max) {
  const s = v == null ? '' : String(v);
  if (s.length > (max || 45000)) throw new Error('Eintrag ist zu gross.');
  return s;
}
/** Neuer Zugang aus den Angaben der App (die App erzeugt Schlüsselpaar und Prüfwert) */
function credRow_(c, role, state) {
  c = c || {};
  if (!ID_RE.test(String(c.id || ''))) throw new Error('Ungültige Zugangs-ID.');
  if (String(c.a || '').length < 32) throw new Error('Ungültiger Zugang.');
  if (!RANK[role]) throw new Error('Ungültige Rechte.');
  return { id: c.id, h: hash_(c.a), r: role, s: state, pub: str_(c.pub, 600), pk: str_(c.pk, 1200), kw: str_(c.kw, 1200), m: str_(c.m, 4000), c: now_(), l: '' };
}

/* ---------- Einrichten ---------- */
function apiStatus() {
  return { setup: isSetup_(), legacy: legacy_(), url: appUrl_() };
}
/** Erster Start: Admin-Zugang und Notfall-Schlüssel anlegen. Mit alten Daten nur mit dem bisherigen Admin-Link. */
function apiSetup(legacyKey, admin, rec) {
  const l = lock_();
  try {
    const P = props_();
    if (isSetup_()) throw new Error('Der Stammbaum ist schon eingerichtet.');
    const owner = P.getProperty('OWNER_KEY');
    if (legacy_() && owner && String(legacyKey || '') !== owner) throw new Error('Bitte die App mit deinem bisherigen Admin-Link öffnen.');
    const a = credRow_(admin, 'admin', 'dev');
    const r = credRow_(rec, 'admin', 'rec');
    if (a.id === r.id) throw new Error('Ungültige Zugangs-ID.');
    st_();
    save_('Z', [a, r]);
    P.setProperty('FS_CHAIN', '{}');
    P.setProperty('FS_KV', '1');
    return { ok: true, url: appUrl_() };
  } finally { l.releaseLock(); }
}

/* ---------- Laden und Speichern ---------- */
function apiLoad(a) {
  const me = me_(a);
  if (!me) return { denied: true, setup: isSetup_() };
  const z = rows_('Z').filter(function (x) { return x.id === me.id; })[0];
  if (!z) { forget_(me.h); return { denied: true, setup: isSetup_() }; }
  const P = props_();
  const out = {
    me: { id: me.id, r: z.r, s: z.s },
    cred: { pub: z.pub, pk: z.pk, kw: z.kw, m: z.m },
    kv: Number(P.getProperty('FS_KV') || 0),
    chain: JSON.parse(P.getProperty('FS_CHAIN') || '{}'),
    url: appUrl_(),
  };
  if (z.s === 'dev') {
    touch_(me);
    snapIfDue_();
    out.e = rows_('E').map(function (x) { return [x.id, x.d]; });
    out.legacy = z.r === 'admin' && legacy_();
  }
  return out;
}

/** Einladung (einmalig) oder Notfall-Schlüssel einlösen: dieses Gerät bekommt einen eigenen Zugang. */
function apiActivate(a, dev) {
  const l = lock_();
  try {
    const me = me_(a);
    if (!me || (me.s !== 'inv' && me.s !== 'rec')) throw new Error('Dieser Link ist nicht mehr gültig.');
    const all = rows_('Z');
    const z = all.filter(function (x) { return x.id === me.id; })[0];
    if (!z) throw new Error('Dieser Link ist nicht mehr gültig.');
    if (me.s === 'inv') {
      const nr = credRow_(Object.assign({}, dev, { id: z.id }), z.r, 'dev');
      nr.m = dev && dev.m ? nr.m : z.m;
      nr.c = z.c;
      save_('Z', [nr]);
      forget_(z.h);
      return { id: z.id, r: z.r };
    }
    const nr = credRow_(dev, 'admin', 'dev');
    if (all.some(function (x) { return x.id === nr.id; })) throw new Error('Ungültige Zugangs-ID.');
    save_('Z', [nr]);
    return { id: nr.id, r: 'admin' };
  } finally { l.releaseLock(); }
}

function cleanPut_(list) {
  const t = String(Date.now());
  return (list || []).map(function (x) {
    const id = String(x && x[0] || ''), d = String(x && x[1] || '');
    if (!ID_RE.test(id) || !/^v\d+:[A-Za-z0-9_-]+$/.test(d)) throw new Error('Ungültiger Eintrag.');
    return { id: id, d: str_(d, 49000), t: t };
  });
}
function apiCommit(a, b) {
  need_(a, 'edit');
  b = b || {};
  const put = cleanPut_(b.put);
  const del = (b.del || []).map(String).filter(function (id) { return ID_RE.test(id); });
  const l = lock_();
  try { save_('E', put, del); SpreadsheetApp.flush(); } finally { l.releaseLock(); }
  return { ok: true, at: Date.now() };
}

/* ---------- Verschlüsselte Fotos ---------- */
function blobMap_() { const m = {}; rows_('B').forEach(function (x) { m[x.id] = x; }); return m; }
function apiPutBlob(a, id, b64) {
  need_(a, 'edit');
  id = String(id || '');
  if (!ID_RE.test(id)) throw new Error('Ungültige Datei.');
  const bytes = Utilities.base64Decode(String(b64 || ''));
  if (!bytes.length) throw new Error('Leere Datei.');
  const l = lock_();
  try {
    if (blobMap_()[id]) return { ok: true, exists: true };
    const f = st_().blobs.createFile(Utilities.newBlob(bytes, 'application/octet-stream', id));
    save_('B', [{ id: id, f: f.getId(), n: String(bytes.length) }]);
  } finally { l.releaseLock(); }
  return { ok: true };
}
function apiHasBlobs(a, ids) {
  need_(a, 'edit');
  const m = blobMap_();
  return (ids || []).filter(function (id) { return !!m[id]; });
}
function apiGetBlobs(a, ids) {
  need_(a, 'view');
  const m = blobMap_();
  const out = {};
  (ids || []).slice(0, 20).forEach(function (id) {
    const b = m[id];
    if (!b) { out[id] = null; return; }
    try { out[id] = Utilities.base64Encode(DriveApp.getFileById(b.f).getBlob().getBytes()); } catch (e) { out[id] = null; }
  });
  return out;
}
function apiDelBlobs(a, ids) {
  need_(a, 'edit');
  const m = blobMap_();
  const gone = (ids || []).map(String).filter(function (id) { return !!m[id]; });
  const l = lock_();
  try { save_('B', [], gone); } finally { l.releaseLock(); }
  gone.forEach(function (id) { try { DriveApp.getFileById(m[id].f).setTrashed(true); } catch (e) { /* schon weg */ } });
  return { ok: true, n: gone.length };
}

/* ---------- Zugänge (nur Admins) ---------- */
function apiCreds(a) {
  const me = need_(a, 'admin');
  return {
    me: me.id,
    creds: rows_('Z').map(function (z) {
      const o = { id: z.id, r: z.r, s: z.s, pub: z.pub, m: z.m, c: z.c, l: z.l };
      if (z.s === 'rec') o.pk = z.pk;
      return o;
    }),
    url: appUrl_(),
  };
}
/** Einladung oder neuer Notfall-Schlüssel. Gleiche ID: ersetzt den bisherigen Zugang (altes Gerät ist ausgesperrt). */
function apiAddCred(a, c) {
  const me = need_(a, 'admin');
  c = c || {};
  const state = c.s === 'rec' ? 'rec' : 'inv';
  const role = state === 'rec' ? 'admin' : (ROLES.indexOf(c.r) >= 0 ? c.r : 'view');
  if (c.id === me.id) throw new Error('Du kannst deinen eigenen Zugang nicht ersetzen.');
  const row = credRow_(c, role, state);
  const l = lock_();
  try {
    const all = rows_('Z');
    /* Der Haupt-Notfall-Schlüssel ersetzt den bisherigen; Schlüssel von Vertrauenspersonen bleiben bestehen */
    const old = all.filter(function (x) { return x.id === row.id || (state === 'rec' && x.s === 'rec' && !TRUSTED_RE.test(row.id) && !TRUSTED_RE.test(x.id)); });
    old.forEach(function (x) { forget_(x.h); });
    const replaced = old.filter(function (x) { return x.id === row.id; })[0];
    if (replaced && !c.m) row.m = replaced.m;
    save_('Z', [], old.filter(function (x) { return x.id !== row.id; }).map(function (x) { return x.id; }));
    save_('Z', [row]);
  } finally { l.releaseLock(); }
  return { id: row.id, url: appUrl_() };
}
function apiUpdCred(a, id, patch) {
  const me = need_(a, 'admin');
  patch = patch || {};
  const l = lock_();
  try {
    const z = rows_('Z').filter(function (x) { return x.id === id; })[0];
    if (!z) throw new Error('Zugang nicht gefunden.');
    if (patch.r !== undefined) {
      if (ROLES.indexOf(patch.r) < 0 || z.s === 'rec') throw new Error('Ungültige Rechte.');
      if (z.id === me.id && patch.r !== 'admin') throw new Error('Du kannst dir die Admin-Rechte nicht selbst entziehen.');
      z.r = patch.r;
    }
    if (patch.m !== undefined) z.m = str_(patch.m, 4000);
    save_('Z', [z]);
    forget_(z.h);
  } finally { l.releaseLock(); }
  return { ok: true };
}
function apiDelCred(a, id) {
  const me = need_(a, 'admin');
  if (id === me.id) throw new Error('Du kannst deinen eigenen Zugang nicht entfernen.');
  const l = lock_();
  try {
    const z = rows_('Z').filter(function (x) { return x.id === id; })[0];
    if (!z) return { ok: true };
    if (z.s === 'rec' && !TRUSTED_RE.test(z.id)) throw new Error('Den Notfall-Schlüssel kannst du nur neu erstellen.');
    forget_(z.h);
    save_('Z', [], [id]);
  } finally { l.releaseLock(); }
  return { ok: true };
}

/** Eigene Angaben (verschlüsselt): Name und eigene Person im Stammbaum, damit alle Bezeichnungen aus der eigenen Sicht stimmen */
function apiSetMyMeta(a, m) {
  const me = need_(a, 'view');
  const l = lock_();
  try {
    const z = rows_('Z').filter(function (x) { return x.id === me.id; })[0];
    if (!z) throw new Error('Kein Zugriff.');
    z.m = str_(m, 4000);
    save_('Z', [z]);
  } finally { l.releaseLock(); }
  return { ok: true };
}

/** Schlüssel erneuern: neuer Datenschlüssel für alle verbleibenden Zugänge, alle Einträge neu verschlüsselt. */
function apiRotate(a, x) {
  need_(a, 'admin');
  x = x || {};
  const l = lock_();
  try {
    const P = props_();
    const kv = Number(P.getProperty('FS_KV') || 0);
    if (Number(x.from) !== kv || Number(x.to) !== kv + 1) throw new Error('Der Schlüssel wurde inzwischen geändert. Bitte neu laden.');
    if (!/^[A-Za-z0-9_-]{40,200}$/.test(String(x.chain || ''))) throw new Error('Ungültiger Schlüssel.');
    const kws = x.kws || {}, ms = x.ms || {};
    const all = rows_('Z');
    if (all.some(function (z) { return !kws[z.id]; })) throw new Error('Die Mitglieder haben sich inzwischen geändert. Bitte nochmals versuchen.');
    const put = cleanPut_(x.put);
    const chain = JSON.parse(P.getProperty('FS_CHAIN') || '{}');
    chain[kv] = String(x.chain);
    P.setProperty('FS_CHAIN', JSON.stringify(chain));
    all.forEach(function (z) { z.kw = str_(kws[z.id], 1200); if (ms[z.id] !== undefined) z.m = str_(ms[z.id], 4000); });
    save_('Z', all);
    P.setProperty('FS_KV', String(kv + 1));
    save_('E', put);
    SpreadsheetApp.flush();
    return { ok: true, kv: kv + 1 };
  } finally { l.releaseLock(); }
}

function apiStorage(a) {
  need_(a, 'admin');
  return { used: DriveApp.getStorageUsed(), limit: DriveApp.getStorageLimit() };
}

/* ---------- Automatische Sicherung bei Google ----------
 * Einmal pro Woche, wenn jemand den Stammbaum öffnet, legt das Skript eine Kopie der (verschlüsselten)
 * Einträge im Drive-Ordner «FS / Sicherungen» ab. Es braucht dafür keinen Zeitplan und keine weiteren Rechte.
 */
function apiVersion() { return { v: VERSION }; }
function snapFolder_() { return folder_(props_(), 'FS_SNAP', st_().dir, 'Sicherungen'); }
function snapshot_(kind) {
  const P = props_();
  const e = rows_('E').map(function (x) { return [x.id, x.d, x.t]; });
  const b = rows_('B').map(function (x) { return [x.id, x.f, x.n]; });
  const at = Date.now();
  const data = JSON.stringify({ format: 'familienstammbaum-google-sicherung', v: 1, at: at, kind: kind, kv: Number(P.getProperty('FS_KV') || 0), e: e, b: b });
  const name = 'sicherung-' + Utilities.formatDate(new Date(at), Session.getScriptTimeZone() || 'Europe/Zurich', 'yyyy-MM-dd-HHmm') + '-' + kind + '.json';
  const f = snapFolder_().createFile(name, data, 'application/json');
  f.setDescription(JSON.stringify({ at: at, n: e.length, kind: kind }));
  P.setProperty('FS_SNAP_AT', String(at));
  /* ältere automatische Sicherungen aufräumen */
  const list = snapList_().filter(function (x) { return x.kind === 'auto'; });
  list.slice(SNAP_KEEP).forEach(function (x) { try { DriveApp.getFileById(x.id).setTrashed(true); } catch (err) { /* schon weg */ } });
  return { id: f.getId(), at: at, n: e.length, kind: kind };
}
function snapIfDue_() {
  try {
    const last = Number(props_().getProperty('FS_SNAP_AT') || 0);
    if (Date.now() - last < SNAP_DAYS * 864e5) return;
    const l = LockService.getScriptLock();
    if (!l.tryLock(5000)) return;
    try {
      if (Date.now() - Number(props_().getProperty('FS_SNAP_AT') || 0) < SNAP_DAYS * 864e5) return;
      if (!rows_('E').length) return;
      snapshot_('auto');
    } finally { l.releaseLock(); }
  } catch (err) { /* Sicherung ist nur eine Zugabe: Laden nie deswegen abbrechen */ }
}
function snapList_() {
  const out = [];
  const it = snapFolder_().getFiles();
  while (it.hasNext()) {
    const f = it.next();
    if (f.isTrashed() || !/^sicherung-.*\.json$/.test(f.getName())) continue;
    let d = {}; try { d = JSON.parse(f.getDescription() || '{}'); } catch (err) { d = {}; }
    out.push({ id: f.getId(), at: Number(d.at) || f.getDateCreated().getTime(), n: Number(d.n) || 0, kind: d.kind || 'auto', size: f.getSize() });
  }
  return out.sort(function (a, b) { return b.at - a.at; });
}
function apiSnapshots(a) {
  need_(a, 'admin');
  return { v: VERSION, every: SNAP_DAYS, keep: SNAP_KEEP, list: snapList_() };
}
function apiSnapshotNow(a) {
  need_(a, 'admin');
  const l = lock_();
  try { return snapshot_('manuell'); } finally { l.releaseLock(); }
}
/** Stand einer Sicherung zurückholen. Vorher wird der jetzige Stand gesichert, damit sich das rückgängig machen lässt. */
function apiSnapshotRestore(a, id) {
  need_(a, 'admin');
  const l = lock_();
  try {
    const x = snapList_().filter(function (s) { return s.id === String(id); })[0];
    if (!x) throw new Error('Sicherung nicht gefunden.');
    const d = JSON.parse(DriveApp.getFileById(x.id).getBlob().getDataAsString('UTF-8'));
    if (d.format !== 'familienstammbaum-google-sicherung' || !Array.isArray(d.e)) throw new Error('Die Sicherung ist beschädigt.');
    const before = snapshot_('vorher');
    const sh = tab_(st_().ss, 'E');
    const last = sh.getLastRow();
    if (last > 1) sh.getRange(2, 1, last - 1, 3).clearContent();
    const rows = d.e.filter(function (r) { return r && ID_RE.test(String(r[0])); }).map(function (r) { return [String(r[0]), String(r[1] || ''), String(r[2] || '')]; });
    if (rows.length) {
      if (rows.length + 1 > sh.getMaxRows()) sh.insertRowsAfter(sh.getMaxRows(), rows.length + 1 - sh.getMaxRows() + 50);
      for (let i = 0; i < rows.length; i += 2000) sh.getRange(2 + i, 1, Math.min(2000, rows.length - i), 3).setNumberFormat('@').setValues(rows.slice(i, i + 2000));
    }
    /* Dateien (Fotos usw.), die seither gelöscht wurden, aus dem Drive-Papierkorb zurückholen, soweit noch vorhanden */
    const have = blobMap_(), back = [];
    (d.b || []).forEach(function (r) {
      if (!r || have[r[0]] || !ID_RE.test(String(r[0]))) return;
      try { const f = DriveApp.getFileById(String(r[1])); if (f.isTrashed()) f.setTrashed(false); back.push({ id: String(r[0]), f: String(r[1]), n: String(r[2] || '') }); } catch (err) { /* endgültig weg */ }
    });
    if (back.length) save_('B', back);
    SpreadsheetApp.flush();
    return { ok: true, n: rows.length, files: back.length, before: before.id };
  } finally { l.releaseLock(); }
}

/* ---------- Übernahme der bisherigen, unverschlüsselten Daten ---------- */
const LEGACY = {
  trees: ['Stammbäume', ['id', 'name', 'homePersonId', ['createdAt', 'num']]],
  people: ['Personen', ['id', 'treeId', 'firstName', 'lastName', 'birthName', 'gender', 'birthDate', 'birthPlace', ['deceased', 'bool'], 'deathDate',
    'phone', 'email', 'address', 'notes', ['parentIds', 'list'], ['partnerIds', 'list'], 'avatarPhotoId', ['createdAt', 'num'], ['updatedAt', 'num']]],
  photos: ['Fotos', ['id', 'treeId', 'full', 'thumb', ['w', 'num'], ['h', 'num'], 'caption', 'date', ['personIds', 'list'], ['createdAt', 'num']]],
  members: ['Mitglieder', ['id', 'name', 'email', 'role', 'key', 'invitedAt', 'lastSeen']],
};
function legacySS_() {
  const id = props_().getProperty('SHEET_ID');
  if (!id) return null;
  try { if (DriveApp.getFileById(id).isTrashed()) return null; return SpreadsheetApp.openById(id); } catch (e) { return null; }
}
function legacy_() { return !!legacySS_(); }
function legacyRead_(ss, key) {
  const def = LEGACY[key];
  const sh = ss.getSheetByName(def[0]);
  if (!sh) return [];
  const cols = def[1].map(function (c) { return typeof c === 'string' ? [c, ''] : c; });
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, cols.length).getDisplayValues().filter(function (r) { return r[0]; }).map(function (r) {
    const o = {};
    cols.forEach(function (c, i) {
      let s = String(r[i] == null ? '' : r[i]);
      if (/^'[=+\-@]/.test(s)) s = s.slice(1);
      if (c[1] === 'list') o[c[0]] = s.split(',').map(function (x) { return x.trim(); }).filter(String);
      else if (c[1] === 'bool') o[c[0]] = /^(ja|true|wahr|x|1)$/i.test(s.trim());
      else if (c[1] === 'num') o[c[0]] = Number(s) || 0;
      else o[c[0]] = s;
    });
    return o;
  });
}
function apiLegacyExport(a) {
  need_(a, 'admin');
  const ss = legacySS_();
  if (!ss) return { none: true };
  return {
    trees: legacyRead_(ss, 'trees'),
    people: legacyRead_(ss, 'people'),
    photos: legacyRead_(ss, 'photos'),
    members: legacyRead_(ss, 'members').map(function (m) { return { name: m.name, email: m.email, role: m.role }; }),
  };
}
function apiLegacyImages(a, ids) {
  need_(a, 'admin');
  const ss = legacySS_();
  if (!ss) return {};
  const ok = {};
  legacyRead_(ss, 'photos').forEach(function (p) { if (p.full) ok[p.full] = 1; if (p.thumb) ok[p.thumb] = 1; });
  const out = {};
  (ids || []).slice(0, 10).forEach(function (id) {
    if (!ok[id]) { out[id] = null; return; }
    try { out[id] = Utilities.base64Encode(DriveApp.getFileById(id).getBlob().getBytes()); } catch (e) { out[id] = null; }
  });
  return out;
}
/** Alte Tabelle und alte Fotoordner in den Papierkorb, alte Links ungültig */
function apiLegacyPurge(a) {
  need_(a, 'admin');
  const P = props_();
  const trash = function (id, folder) {
    if (!id) return;
    try { (folder ? DriveApp.getFolderById(id) : DriveApp.getFileById(id)).setTrashed(true); } catch (e) { /* schon weg */ }
  };
  trash(P.getProperty('SHEET_ID'), false);
  trash(P.getProperty('PHOTOS_ID'), true);
  trash(P.getProperty('THUMBS_ID'), true);
  const root = P.getProperty('ROOT_ID');
  if (root) {
    try {
      const it = DriveApp.getFolderById(root).getFoldersByName('Sicherungen');
      while (it.hasNext()) it.next().setTrashed(true);
    } catch (e) { /* schon weg */ }
    trash(root, true);
  }
  ['OWNER_KEY', 'SHEET_ID', 'PHOTOS_ID', 'THUMBS_ID', 'ROOT_ID', 'PUBLIC_ROLE'].forEach(function (k) { P.deleteProperty(k); });
  return { ok: true };
}

/** Falls der bisherige Admin-Link (alte Version) fehlt: im Editor ausführen, das Ausführungsprotokoll zeigt ihn. */
function bisherigenAdminLinkAnzeigen() {
  const k = props_().getProperty('OWNER_KEY');
  Logger.log(k && legacy_() ? 'Bisheriger Admin-Link: ' + appUrl_() + '?k=' + k : 'Keine alten Daten vorhanden. Öffne einfach die Web-App-Adresse.');
}

/** Optional im Editor ausführen: erteilt die Berechtigungen und legt die Ablage an. */
function einrichten() {
  const s = st_();
  Logger.log('Ablage (nur verschlüsselte Daten): ' + s.dir.getUrl());
  Logger.log('Web-App: ' + (appUrl_() || '(erst nach dem Bereitstellen verfügbar)'));
}
