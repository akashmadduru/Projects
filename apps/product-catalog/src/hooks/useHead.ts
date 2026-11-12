/**
 * Sets document title, description, canonical, Open Graph / Twitter tags and JSON-LD for the current page,
 * and restores the previous values on unmount. Client-side only (prerender/SSR is out of scope).
 */

import { useEffect } from 'react'

export interface HeadConfig {
  title: string
  description?: string
  canonical?: string
  image?: string
  type?: string
  jsonLd?: Record<string, unknown>
}

function setMeta(attr: 'name' | 'property', key: string, content: string | undefined, created: Element[]) {
  if (!content) return
  let el = document.head.querySelector<HTMLMetaElement>(`meta[${attr}="${key}"]`)
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
    created.push(el)
  }
  el.setAttribute('content', content)
}

export function useHead(config: HeadConfig | undefined): void {
  const key = config ? JSON.stringify(config) : ''
  useEffect(() => {
    if (!key) return
    const cfg = JSON.parse(key) as HeadConfig
    const previousTitle = document.title
    const created: Element[] = []
    const descEl = document.head.querySelector<HTMLMetaElement>('meta[name="description"]')
    const previousDesc = descEl?.getAttribute('content')

    document.title = cfg.title
    setMeta('name', 'description', cfg.description, created)
    setMeta('property', 'og:title', cfg.title, created)
    setMeta('property', 'og:description', cfg.description, created)
    setMeta('property', 'og:type', cfg.type ?? 'website', created)
    setMeta('property', 'og:url', cfg.canonical, created)
    setMeta('property', 'og:image', cfg.image, created)
    setMeta('name', 'twitter:card', cfg.image ? 'summary_large_image' : 'summary', created)
    setMeta('name', 'twitter:title', cfg.title, created)
    setMeta('name', 'twitter:description', cfg.description, created)
    setMeta('name', 'twitter:image', cfg.image, created)

    let link: HTMLLinkElement | null = null
    if (cfg.canonical) {
      link = document.createElement('link')
      link.rel = 'canonical'
      link.href = cfg.canonical
      document.head.appendChild(link)
    }
    let script: HTMLScriptElement | null = null
    if (cfg.jsonLd) {
      script = document.createElement('script')
      script.type = 'application/ld+json'
      script.text = JSON.stringify(cfg.jsonLd)
      document.head.appendChild(script)
    }

    return () => {
      document.title = previousTitle
      if (descEl && previousDesc != null) descEl.setAttribute('content', previousDesc)
      created.forEach((el) => el.remove())
      link?.remove()
      script?.remove()
    }
  }, [key])
}
