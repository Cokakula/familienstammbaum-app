/* Familienstammbaum: Seite offline verfügbar machen. Google-Daten und Kartenbilder laufen nicht hierüber. */
const C = 'fs-5b1889bfb02e';
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
