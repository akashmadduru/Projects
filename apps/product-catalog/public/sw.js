/* Cartwise service worker: offline shell, caching strategies and web push (single worker so push keeps working). */
const VERSION = 'v1'
const STATIC_CACHE = `cartwise-static-${VERSION}`
const RUNTIME_CACHE = `cartwise-runtime-${VERSION}`
const SHELL = ['/', '/offline.html', '/manifest.webmanifest', '/favicon.svg']
const MAX_RUNTIME_ENTRIES = 120

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(STATIC_CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('cartwise-') && k !== STATIC_CACHE && k !== RUNTIME_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

async function trim(cacheName, max) {
  const cache = await caches.open(cacheName)
  const keys = await cache.keys()
  if (keys.length > max) await Promise.all(keys.slice(0, keys.length - max).map((k) => cache.delete(k)))
}

// Cache-first: hashed build assets never change for a given URL.
async function cacheFirst(request) {
  const cached = await caches.match(request)
  if (cached) return cached
  const response = await fetch(request)
  if (response.ok) {
    const copy = response.clone()
    caches.open(STATIC_CACHE).then((c) => c.put(request, copy))
  }
  return response
}

// Stale-while-revalidate: answer from cache immediately, refresh in the background.
async function staleWhileRevalidate(request) {
  const cache = await caches.open(RUNTIME_CACHE)
  const cached = await cache.match(request)
  const network = fetch(request)
    .then((response) => {
      if (response.ok || response.type === 'opaque') {
        cache.put(request, response.clone()).then(() => trim(RUNTIME_CACHE, MAX_RUNTIME_ENTRIES))
      }
      return response
    })
    .catch(() => cached)
  return cached || network
}

async function navigation(request) {
  try {
    const response = await fetch(request)
    if (response.ok && new URL(request.url).pathname === '/') {
      const copy = response.clone()
      caches.open(STATIC_CACHE).then((c) => c.put('/', copy))
    }
    return response
  } catch (e) {
    return (await caches.match('/offline.html')) || Response.error()
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (request.method !== 'GET') return
  const url = new URL(request.url)

  if (request.mode === 'navigate') {
    event.respondWith(navigation(request))
    return
  }
  // Never cache signed-in traffic.
  if (request.headers.has('Authorization')) return

  if (url.origin === self.location.origin) {
    if (url.pathname.startsWith('/assets/')) {
      event.respondWith(cacheFirst(request))
    } else if (url.pathname.startsWith('/api/products')) {
      event.respondWith(staleWhileRevalidate(request))
    }
    return
  }
  if (request.destination === 'image') event.respondWith(staleWhileRevalidate(request))
})

/* Web push: show the notification, open its link on click. */
self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch (e) {
    data = { title: 'Cartwise', body: event.data ? event.data.text() : '' }
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'Cartwise', {
      body: data.body || '',
      icon: '/favicon.svg',
      data: { link: data.link || '/' },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const link = (event.notification.data && event.notification.data.link) || '/'
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const c of clients) {
        if ('focus' in c) {
          c.navigate(link)
          return c.focus()
        }
      }
      return self.clients.openWindow(link)
    }),
  )
})
