/**
 * Familienstammbaum – Google Apps Script (Ende-zu-Ende-verschlüsselt)
 *
 * Google speichert nur verschlüsselte Daten:
 *   Google Drive / FS /
 *     «FS Daten»  Tabelle mit verschlüsselten Einträgen (E), Zugängen (Z) und Foto-Verweisen (B)
 *     b/          verschlüsselte Fotos und Vorschaubilder (zufällige Dateinamen)
 *     g/          verschlüsselte Gedenkseiten (lesbar nur mit dem Schlüssel aus dem Link)
 *     Sicherungen/ automatische, verschlüsselte Sicherungen
 *   Tabelle P: Geräte, die Mitteilungen im Browser bzw. auf dem iPhone möchten (Angaben verschlüsselt)
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
  F: ['id', 'x', 'f', 't', 'm', 'c'],                          // Fotowand: Foto, Fotowand, Bild, Vorschau, Angaben (verschlüsselt), Zeit
  P: ['id', 'c', 'd', 't'],                                     // Mitteilungen (Web Push): Gerät, Zugang, Angaben (verschlüsselt), Zeit
};
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const API_ = {
  apiStatus: apiStatus, apiSetup: apiSetup, apiLoad: apiLoad, apiActivate: apiActivate, apiCommit: apiCommit,
  apiPutBlob: apiPutBlob, apiHasBlobs: apiHasBlobs, apiGetBlobs: apiGetBlobs, apiDelBlobs: apiDelBlobs,
  apiCreds: apiCreds, apiAddCred: apiAddCred, apiSetMyMeta: apiSetMyMeta, apiUpdCred: apiUpdCred, apiDelCred: apiDelCred, apiRotate: apiRotate,
  apiStorage: apiStorage, apiLegacyExport: apiLegacyExport, apiLegacyImages: apiLegacyImages, apiLegacyPurge: apiLegacyPurge,
  apiVersion: apiVersion, apiSnapshots: apiSnapshots, apiSnapshotNow: apiSnapshotNow, apiSnapshotRestore: apiSnapshotRestore,
  apiMemPut: apiMemPut, apiMemGet: apiMemGet, apiMemDel: apiMemDel,
  apiNews: apiNews,
  apiFestNew: apiFestNew, apiFestEnd: apiFestEnd, apiFestDel: apiFestDel, apiFestRows: apiFestRows, apiFestFiles: apiFestFiles,
  apiFestInfo: apiFestInfo, apiFestUp: apiFestUp, apiFestList: apiFestList, apiFestGet: apiFestGet,
  apiLinkPut: apiLinkPut, apiLinkGet: apiLinkGet, apiLinkDel: apiLinkDel,
  apiPushSub: apiPushSub, apiPush: apiPush,
};
const VERSION = 6;
const SNAP_DAYS = 7;      // automatische Sicherung höchstens einmal pro Woche (beim Öffnen durch ein Mitglied)
const SNAP_KEEP = 12;     // so viele automatische Sicherungen bleiben erhalten (etwa drei Monate)
const TRUSTED_RE = /^vt/; // Notfall-Schlüssel von Vertrauenspersonen (es kann mehrere geben)
const MEM_RE = /^m[A-Za-z0-9_-]{20,40}$/; // Gedenkseiten
const MEM_MAX = 8 * 1024 * 1024;

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
    out.v = VERSION;
    out.push = pushRows_();
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
/**
 * Für die Mitteilungen der Android-App: nur die Einträge, die seit «since» (Serverzeit in ms) geschrieben wurden,
 * verschlüsselt wie immer. Ohne «zuletzt gesehen» und ohne Sicherung, damit die Abfrage im Hintergrund leicht bleibt.
 */
function apiNews(a, since) {
  need_(a, 'view');
  const now = Date.now();
  since = Number(since) || 0;
  const kv = Number(props_().getProperty('FS_KV') || 0);
  if (since <= 0 || since > now) return { now: now, kv: kv, e: [] };
  const e = rows_('E').filter(function (x) { return Number(x.t) > since; }).map(function (x) { return [x.id, x.d]; });
  return { now: now, kv: kv, e: e.length > 300 ? e.slice(e.length - 300) : e };
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
    save_('P', [], rows_('P').filter(function (x) { return x.c === id; }).map(function (x) { return x.id; }));
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

/* ---------- Gedenkseiten ----------
 * Verschlüsselt in der App; der Schlüssel steht nur im Link hinter dem «#». Lesen darf jede/r mit dem Link,
 * ohne Anmeldung. Dieses Skript kann den Inhalt nicht lesen. Anlegen und löschen dürfen nur Mitglieder mit Bearbeiten-Rechten.
 */
function memMap_() { try { return JSON.parse(props_().getProperty('FS_MEM') || '{}'); } catch (e) { return {}; } }
function memFolder_() { return folder_(props_(), 'FS_MEMDIR', st_().dir, 'g'); }
function apiMemPut(a, id, b64) {
  need_(a, 'edit');
  id = String(id || '');
  if (!MEM_RE.test(id)) throw new Error('Ungültige Gedenkseite.');
  const bytes = Utilities.base64Decode(String(b64 || ''));
  if (!bytes.length) throw new Error('Leere Datei.');
  if (bytes.length > MEM_MAX) throw new Error('Die Gedenkseite ist zu gross.');
  const l = lock_();
  try {
    const m = memMap_();
    if (m[id]) { try { DriveApp.getFileById(m[id].f).setTrashed(true); } catch (e) { /* schon weg */ } }
    const f = memFolder_().createFile(Utilities.newBlob(bytes, 'application/octet-stream', id));
    m[id] = { f: f.getId(), t: now_() };
    props_().setProperty('FS_MEM', JSON.stringify(m));
  } finally { l.releaseLock(); }
  return { ok: true };
}
function apiMemGet(id) {
  id = String(id || '');
  const m = MEM_RE.test(id) ? memMap_()[id] : null;
  if (!m) throw new Error('Diese Gedenkseite gibt es nicht mehr.');
  try { return { d: Utilities.base64Encode(DriveApp.getFileById(m.f).getBlob().getBytes()) }; }
  catch (e) { throw new Error('Diese Gedenkseite gibt es nicht mehr.'); }
}
function apiMemDel(a, id) {
  need_(a, 'edit');
  id = String(id || '');
  const l = lock_();
  try {
    const m = memMap_();
    if (m[id]) { try { DriveApp.getFileById(m[id].f).setTrashed(true); } catch (e) { /* schon weg */ } delete m[id]; }
    props_().setProperty('FS_MEM', JSON.stringify(m));
  } finally { l.releaseLock(); }
  return { ok: true };
}

/* ---------- Fotowand am Fest ----------
 * Gäste ohne Einladung laden Fotos hoch. Die Fotos werden im Browser der Gäste verschlüsselt; der Schlüssel steht nur
 * im QR-Code-Link hinter dem «#». Dieses Skript sieht nur unlesbare Dateien und kennt nur einen Prüfwert des Links.
 * Anlegen, beenden und löschen dürfen Mitglieder mit Bearbeiten-Rechten.
 */
const FEST_RE = /^[A-Za-z0-9_-]{8,40}$/, FEST_MAX = 600, FEST_BYTES = 6000000, FEST_KEEP = 14 * 864e5;
function festMap_() { try { return JSON.parse(props_().getProperty('FS_FEST') || '{}'); } catch (e) { return {}; } }
function festSave_(m) { props_().setProperty('FS_FEST', JSON.stringify(m)); }
function festFolder_() { return folder_(props_(), 'FS_FESTDIR', st_().dir, 'x'); }
function festByTok_(tok) {
  const h = hash_('fest|' + String(tok || '')), m = festMap_();
  for (const id in m) if (m[id].h === h) return Object.assign({ id: id }, m[id]);
  throw new Error('Diese Fotowand gibt es nicht mehr.');
}
function festRows_(id, since) { return rows_('F').filter(function (r) { return r.x === id && Number(r.c) > (Number(since) || 0); }); }
function festGet_(id, ids, which) {
  const want = {}; (ids || []).slice(0, 12).forEach(function (i) { want[String(i)] = 1; });
  const out = {};
  rows_('F').forEach(function (r) {
    if (r.x !== id || !want[r.id]) return;
    try { out[r.id] = Utilities.base64Encode(DriveApp.getFileById(which === 'f' ? r.f : (r.t || r.f)).getBlob().getBytes()); } catch (e) { out[r.id] = null; }
  });
  return out;
}
function apiFestNew(a, id, h, exp, meta) {
  need_(a, 'edit');
  id = String(id || '');
  if (!FEST_RE.test(id) || !/^[0-9a-f]{64}$/.test(String(h || ''))) throw new Error('Ungültige Fotowand.');
  const l = lock_();
  try {
    const m = festMap_();
    if (!m[id] && Object.keys(m).length >= 20) throw new Error('Es gibt schon 20 Fotowände. Bitte alte löschen.');
    m[id] = { h: String(h), exp: Number(exp) || 0, meta: String(meta || '').slice(0, 600), n: (m[id] && m[id].n) || 0 };
    festSave_(m);
  } finally { l.releaseLock(); }
  return { ok: true };
}
function apiFestEnd(a, id, exp) {
  need_(a, 'edit');
  const l = lock_();
  try { const m = festMap_(); if (!m[id]) throw new Error('Diese Fotowand gibt es nicht mehr.'); m[id].exp = Number(exp) || Date.now(); festSave_(m); }
  finally { l.releaseLock(); }
  return { ok: true };
}
function apiFestDel(a, id) {
  need_(a, 'edit');
  id = String(id || '');
  const l = lock_();
  try {
    const rs = festRows_(id, 0);
    rs.forEach(function (r) { [r.f, r.t].filter(String).forEach(function (f) { try { DriveApp.getFileById(f).setTrashed(true); } catch (e) { /* schon weg */ } }); });
    save_('F', [], rs.map(function (r) { return r.id; }));
    const m = festMap_(); delete m[id]; festSave_(m);
  } finally { l.releaseLock(); }
  return { ok: true };
}
function apiFestRows(a, id, since) {
  need_(a, 'view');
  const m = festMap_()[String(id)];
  return { n: m ? m.n || 0 : 0, exp: m ? m.exp : 0, gone: !m, rows: festRows_(String(id), since).map(function (r) { return [r.id, r.m, Number(r.c)]; }) };
}
function apiFestFiles(a, id, ids, which) { need_(a, 'view'); return festGet_(String(id), ids, which); }
/* Gäste: nur mit dem Link */
function apiFestInfo(tok) { const f = festByTok_(tok); return { id: f.id, exp: f.exp, n: f.n || 0, meta: f.meta, open: Date.now() < f.exp }; }
function apiFestUp(tok, full, thumb, meta) {
  const f = festByTok_(tok);
  if (Date.now() > f.exp) throw new Error('Die Fotowand ist geschlossen.');
  const b1 = Utilities.base64Decode(String(full || '')), b2 = Utilities.base64Decode(String(thumb || ''));
  if (!b1.length || b1.length > FEST_BYTES || b2.length > 800000) throw new Error('Das Foto ist zu gross.');
  const l = lock_();
  try {
    const m = festMap_(), cur = m[f.id];
    if (!cur) throw new Error('Diese Fotowand gibt es nicht mehr.');
    if ((cur.n || 0) >= FEST_MAX) throw new Error('Die Fotowand ist voll.');
    const pid = Utilities.getUuid().replace(/-/g, '').slice(0, 20), dir = festFolder_();
    const ff = dir.createFile(Utilities.newBlob(b1, 'application/octet-stream', f.id + '_' + pid + '_f'));
    const ft = b2.length ? dir.createFile(Utilities.newBlob(b2, 'application/octet-stream', f.id + '_' + pid + '_t')) : null;
    save_('F', [{ id: pid, x: f.id, f: ff.getId(), t: ft ? ft.getId() : '', m: String(meta || '').slice(0, 600), c: String(Date.now()) }]);
    cur.n = (cur.n || 0) + 1; festSave_(m);
    return { ok: true, id: pid, n: cur.n };
  } finally { l.releaseLock(); }
}
function apiFestList(tok, since) {
  const f = festByTok_(tok);
  if (Date.now() > f.exp + FEST_KEEP) throw new Error('Die Fotowand ist geschlossen.');
  return { n: f.n || 0, open: Date.now() < f.exp, rows: festRows_(f.id, since).map(function (r) { return [r.id, r.m, Number(r.c)]; }) };
}
function apiFestGet(tok, ids, which) {
  const f = festByTok_(tok);
  if (Date.now() > f.exp + FEST_KEEP) throw new Error('Die Fotowand ist geschlossen.');
  return festGet_(f.id, ids, which);
}

/* ---------- Familienlink ----------
 * Ein Stammbaum zeigt einer anderen Familie ausgewählte Personen (z. B. die Familie der Braut). Die Auswahl wird
 * in der App mit einem eigenen Schlüssel verschlüsselt; der Schlüssel steht nur im Link hinter dem «#».
 * Dieses Skript speichert nur die unlesbare Datei. Ändern und beenden dürfen Mitglieder mit Bearbeiten-Rechten.
 */
const LINK_RE = /^[A-Za-z0-9_-]{16,40}$/, LINK_BYTES = 4000000;
function linkMap_() { try { return JSON.parse(props_().getProperty('FS_LINK') || '{}'); } catch (e) { return {}; } }
function linkFolder_() { return folder_(props_(), 'FS_LINKDIR', st_().dir, 'z'); }
function apiLinkPut(a, id, b64) {
  need_(a, 'edit');
  id = String(id || '');
  if (!LINK_RE.test(id)) throw new Error('Ungültiger Familienlink.');
  const bytes = Utilities.base64Decode(String(b64 || ''));
  if (!bytes.length) throw new Error('Leere Datei.');
  if (bytes.length > LINK_BYTES) throw new Error('Der Familienlink ist zu gross. Bitte weniger Personen wählen.');
  const l = lock_();
  try {
    const m = linkMap_();
    if (!m[id] && Object.keys(m).length >= 40) throw new Error('Es gibt schon 40 Familienlinks. Bitte alte beenden.');
    if (m[id]) { try { DriveApp.getFileById(m[id].f).setTrashed(true); } catch (e) { /* schon weg */ } }
    const f = linkFolder_().createFile(Utilities.newBlob(bytes, 'application/octet-stream', 'l'));
    m[id] = { f: f.getId(), u: Date.now() };
    props_().setProperty('FS_LINK', JSON.stringify(m));
    return { ok: true, u: m[id].u };
  } finally { l.releaseLock(); }
}
function apiLinkGet(id, since) {
  id = String(id || '');
  const m = LINK_RE.test(id) ? linkMap_()[id] : null;
  if (!m) throw new Error('Dieser Familienlink wurde beendet.');
  if (Number(since) >= m.u) return { u: m.u, same: true };
  try { return { u: m.u, d: Utilities.base64Encode(DriveApp.getFileById(m.f).getBlob().getBytes()) }; }
  catch (e) { throw new Error('Dieser Familienlink wurde beendet.'); }
}
function apiLinkDel(a, id) {
  need_(a, 'edit');
  id = String(id || '');
  const l = lock_();
  try {
    const m = linkMap_();
    if (m[id]) { try { DriveApp.getFileById(m[id].f).setTrashed(true); } catch (e) { /* schon weg */ } delete m[id]; }
    props_().setProperty('FS_LINK', JSON.stringify(m));
  } finally { l.releaseLock(); }
  return { ok: true };
}


/* ---------- Mitteilungen im Browser und auf dem iPhone (Web Push, braucht Version 6) ----------
 * Jedes Gerät meldet sich mit verschlüsselten Angaben an (Tabelle P). Wer etwas Neues hinzufügt, verschlüsselt
 * die Mitteilung schon auf dem eigenen Gerät für jedes Empfängergerät. Dieses Skript reicht sie nur an den
 * Mitteilungsdienst des Empfängers weiter (Apple, Google, Mozilla, Microsoft) und kann sie nicht lesen.
 */
const PUSH_RE = /^https:\/\/(fcm\.googleapis\.com|updates\.push\.services\.mozilla\.com|push\.services\.mozilla\.com|web\.push\.apple\.com|[a-z0-9-]+\.push\.apple\.com|[a-z0-9.-]+\.notify\.windows\.com)\//;
function pushRows_() {
  const live = {};
  rows_('Z').forEach(function (z) { if (z.s === 'dev') live[z.id] = 1; });
  return rows_('P').filter(function (x) { return live[x.c]; }).map(function (x) { return [x.id, x.c, x.d]; });
}
/** Dieses Gerät an- (d = verschlüsselte Angaben) oder abmelden (d leer) */
function apiPushSub(a, id, d) {
  const me = need_(a, 'view');
  id = String(id || '');
  if (!ID_RE.test(id)) throw new Error('Ungültige Geräte-ID.');
  d = String(d || '');
  if (d && !/^v\d+:[A-Za-z0-9_-]+$/.test(d)) throw new Error('Ungültiger Eintrag.');
  const l = lock_();
  try {
    const old = rows_('P').filter(function (x) { return x.id === id; })[0];
    if (old && old.c !== me.id && !d) return { ok: true };
    if (d) save_('P', [{ id: id, c: me.id, d: str_(d, 8000), t: String(Date.now()) }]);
    else save_('P', [], [id]);
  } finally { l.releaseLock(); }
  return { ok: true };
}
/** Fertig verschlüsselte Mitteilungen weiterreichen: [{i: Gerät, e: Adresse, a: VAPID-Kopf, b: Inhalt (Base64), t: Gültigkeit}] */
function apiPush(a, msgs) {
  const me = need_(a, 'view');
  msgs = (msgs || []).slice(0, 60);
  if (!msgs.length) return [];
  const cache = CacheService.getScriptCache(), ck = 'push_' + me.id, n = Number(cache.get(ck) || 0);
  if (n + msgs.length > 400) throw new Error('Zu viele Mitteilungen in kurzer Zeit.');
  cache.put(ck, String(n + msgs.length), 3600);
  const reqs = msgs.map(function (m) {
    const url = String(m && m.e || ''), auth = String(m && m.a || ''), b = String(m && m.b || '');
    if (url.length > 1000 || !PUSH_RE.test(url)) throw new Error('Ungültige Adresse für Mitteilungen.');
    if (auth.length > 1500 || !/^vapid t=[A-Za-z0-9_.-]+, k=[A-Za-z0-9_-]+$/.test(auth)) throw new Error('Ungültige Mitteilung.');
    if (b.length > 6000 || !/^[A-Za-z0-9+\/]+=*$/.test(b)) throw new Error('Ungültige Mitteilung.');
    return {
      url: url, method: 'post', contentType: 'application/octet-stream', payload: Utilities.base64Decode(b), muteHttpExceptions: true, followRedirects: false,
      headers: { Authorization: auth, 'Content-Encoding': 'aes128gcm', TTL: String(Math.max(60, Math.min(Number(m.t) || 86400, 604800))), Urgency: 'normal' },
    };
  });
  const codes = UrlFetchApp.fetchAll(reqs).map(function (r) { return r.getResponseCode(); });
  /* Abgemeldete Geräte (404/410) gleich entfernen */
  const gone = [];
  codes.forEach(function (c, i) { const id = String(msgs[i] && msgs[i].i || ''); if ((c === 404 || c === 410) && ID_RE.test(id)) gone.push(id); });
  if (gone.length) { const l = lock_(); try { save_('P', [], gone); } finally { l.releaseLock(); } }
  return codes;
}
/** Einmal im Editor ausführen (Version 6): Google fragt dann nach der Erlaubnis, Mitteilungen weiterzureichen. */
function mitteilungenFreischalten() {
  UrlFetchApp.getRequest('https://web.push.apple.com/');
  Logger.log('Mitteilungen sind freigeschaltet. Jetzt «Bereitstellen → Bereitstellungen verwalten → Neue Version».');
}
