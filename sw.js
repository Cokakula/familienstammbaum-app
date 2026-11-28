/* Familienstammbaum: Seite offline verfügbar machen. Google-Daten und Kartenbilder laufen nicht hierüber. */
const C = 'fs-94a8ece3c5aa';
const FILES = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png', './apple-touch-icon.png', './favicon-32.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(C).then(c => c.addAll(FILES)).catch(() => {})); self.skipWaiting(); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== C).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== self.location.origin) return;
  /* Seite immer beim Server nachfragen (sonst zeigen Browser bis zu 10 Minuten die alte Version) */
  const req = e.request.mode === 'navigate' || /index\.html$|\/$/.test(u.pathname) ? fetch(u.href, { cache: 'no-cache', credentials: 'same-origin' }) : fetch(e.request);
  e.respondWith(req.then(r => {
    if (r.ok && r.type === 'basic') { const cp = r.clone(); caches.open(C).then(c => c.put(e.request.mode === 'navigate' ? './index.html' : e.request, cp)); }
    return r;
  }).catch(() => caches.match(e.request, { ignoreSearch: true }).then(r => r || caches.match('./index.html'))));
});
/* Mitteilungen bei Neuem (Web Push): Der Inhalt kommt Ende-zu-Ende-verschlüsselt an, der Browser entschlüsselt ihn hier */
self.addEventListener('push', e => {
  let d = {}; try { d = e.data ? e.data.json() : {}; } catch (x) { d = {}; }
  const tag = d.tag || 'fs-neu';
  e.waitUntil((self.registration.getNotifications ? self.registration.getNotifications({ tag }) : Promise.resolve([])).catch(() => []).then(list => {
    const prev = (list[0] && list[0].data && list[0].data.lines) || [];
    const lines = (d.lines || []).concat(prev.filter(l => !/^\+ \d+ /.test(l))).slice(0, 6);
    return self.registration.showNotification(d.t || 'Neu im Stammbaum', { body: lines.join('\n'), tag, renotify: true, icon: 'icon-192.png', badge: 'icon-192.png', timestamp: d.at || Date.now(), data: { u: d.u || './', lines } });
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const u = new URL((e.notification.data && e.notification.data.u) || './', self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    for (const c of cs) { if (c.url.startsWith(self.registration.scope) && 'focus' in c) { c.postMessage({ fsGo: u }); return c.focus(); } }
    return self.clients.openWindow ? self.clients.openWindow(u) : null;
  }));
});
