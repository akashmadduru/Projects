/**
 * Web push opt-in. The service worker is registered only when the user turns push on.
 */

import { engageApi } from '../services/engageApi'

export function pushSupported(): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

function toKey(base64: string): Uint8Array<ArrayBuffer> {
  const padded = (base64 + '='.repeat((4 - (base64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(padded)
  const out = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i)
  return out
}

function encode(buffer: ArrayBuffer | null): string {
  if (!buffer) return ''
  let s = ''
  new Uint8Array(buffer).forEach((b) => {
    s += String.fromCharCode(b)
  })
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export async function currentPushEndpoint(): Promise<string | null> {
  if (!pushSupported()) return null
  const reg = await navigator.serviceWorker.getRegistration('/sw.js')
  const sub = await reg?.pushManager.getSubscription()
  return sub?.endpoint ?? null
}

export async function enablePush(): Promise<void> {
  if (!pushSupported()) throw new Error('This browser does not support push notifications.')
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') throw new Error('Notifications are blocked in your browser settings.')
  const res = await engageApi.vapidKey()
  const key = typeof res === 'string' ? res : (res.publicKey ?? res.key ?? '')
  if (!key) throw new Error('Push is not configured on the server.')
  const reg = await navigator.serviceWorker.register('/sw.js')
  await navigator.serviceWorker.ready
  const sub =
    (await reg.pushManager.getSubscription()) ??
    (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: toKey(key) }))
  await engageApi.subscribePush({
    endpoint: sub.endpoint,
    keys: { p256dh: encode(sub.getKey('p256dh')), auth: encode(sub.getKey('auth')) },
  })
}

export async function disablePush(): Promise<void> {
  const reg = await navigator.serviceWorker.getRegistration('/sw.js')
  const sub = await reg?.pushManager.getSubscription()
  if (!sub) return
  await engageApi.unsubscribePush(sub.endpoint).catch(() => undefined)
  await sub.unsubscribe()
}
