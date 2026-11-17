// Keeps the app opening with no internet. It caches only the app's own files, never the API:
// orders and prices always come from the server (or from the offline queue in the app itself).
const CACHE = "cp-shell-v1";

self.addEventListener("install", () => self.skipWaiting());

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key);
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/actuator/")) return;

  // opening a page: always try the network first so a new version shows up, fall back to the saved copy
  if (req.mode === "navigate") {
    event.respondWith((async () => {
      try {
        const res = await fetch(req);
        if (res.ok) (await caches.open(CACHE)).put("/index.html", res.clone());
        return res;
      } catch {
        return (await caches.match("/index.html")) || Response.error();
      }
    })());
    return;
  }

  // built files have a hash in their name, so a saved copy never goes stale
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith((async () => {
      const cached = await caches.match(req);
      if (cached) return cached;
      const res = await fetch(req);
      if (res.ok) (await caches.open(CACHE)).put(req, res.clone());
      return res;
    })());
  }
});
