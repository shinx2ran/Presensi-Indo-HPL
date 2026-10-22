/**
 * Service worker dashboard owner.
 * - App shell di-precache dengan versi VERSION (saat publish, workflow mengganti penanda build dengan hash commit,
 *   jadi setiap rilis memasang SW baru dan halaman menampilkan "Dashboard diperbarui").
 * - Berkas aplikasi: network-first (versi baru langsung dipakai saat online), cadangan dari cache saat offline.
 * - Permintaan ke Supabase (data presensi, kunci owner) TIDAK PERNAH disentuh / disimpan di cache.
 */
const VERSION = 'presensi-owner-v1-66dcba4';
const SHELL = [
  './',
  './index.html',
  './styles.css',
  './app.js',
  './api.js',
  './rekap.js',
  './manifest.webmanifest',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
];
const NETWORK_TIMEOUT_MS = 6000;

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(VERSION)
      .then((cache) => cache.addAll(SHELL.map((url) => new Request(url, { cache: 'reload' }))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('presensi-owner-') && k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Hanya berkas dashboard sendiri. Supabase dan domain lain dibiarkan langsung ke jaringan (tanpa cache).
  if (url.origin !== self.location.origin || url.hostname.endsWith('supabase.co')) return;
  const isPage = req.mode === 'navigate';
  // cache: 'no-cache' = selalu tanya server (ETag), supaya cache HTTP GitHub Pages (10 menit) tidak menahan versi baru.
  const network = fetch(req, { cache: 'no-cache' });
  // Simpan salinan terbaru ke cache (dipanggil sinkron supaya service worker tidak berhenti sebelum selesai).
  event.waitUntil(
    network
      .then((res) => {
        if (!res || !res.ok || res.type !== 'basic') return undefined;
        const copy = res.clone();
        // Cache versi ini sudah dihapus = SW yang lebih baru sudah aktif: jangan buat ulang cache lama.
        return caches.has(VERSION).then((alive) => alive && caches.open(VERSION).then((cache) => cache.put(isPage ? './index.html' : req, copy)));
      })
      .catch(() => {}),
  );
  event.respondWith(respond(req, isPage, network));
});

async function respond(req, isPage, network) {
  const fromCache = () =>
    caches.open(VERSION).then((cache) => (isPage ? cache.match('./index.html') : cache.match(req, { ignoreSearch: true })));
  try {
    // Jaringan lambat: setelah beberapa detik pakai cache bila ada (respons jaringan tetap memperbarui cache).
    const timeout = new Promise((resolve) => setTimeout(() => resolve(null), NETWORK_TIMEOUT_MS));
    const first = await Promise.race([network, timeout]);
    if (first) return first;
    return (await fromCache()) ?? (await network);
  } catch {
    const hit = await fromCache().catch(() => undefined);
    if (hit) return hit;
    return new Response('Offline dan dashboard belum tersimpan di HP ini. Buka dashboard sekali saat online.', {
      status: 503,
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    });
  }
}
