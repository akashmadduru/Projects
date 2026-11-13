/** Minimal, injection-safe markdown-lite renderer: ## headings, - lists, 1. lists, **bold**, blank-line paragraphs. */

import type { ReactNode } from 'react'

function inline(text: string, keyBase: string): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') && part.endsWith('**') && part.length > 4 ? (
      <strong key={`${keyBase}-${i}`}>{part.slice(2, -2)}</strong>
    ) : (
      part
    ),
  )
}

export function MarkdownLite({ source }: { source: string }) {
  const blocks = source.replace(/\r\n/g, '\n').split(/\n{2,}/)
  return (
    <div className="space-y-3 text-on-surface">
      {blocks.map((block, bi) => {
        const lines = block.split('\n').filter((l) => l.trim())
        if (lines.length === 0) return null
        const key = `b${bi}`
        const first = lines[0] ?? ''
        if (/^#{1,3}\s/.test(first)) {
          return (
            <h3 key={key} className="pt-2 font-display text-lg font-bold">
              {inline(first.replace(/^#{1,3}\s/, ''), key)}
            </h3>
          )
        }
        if (lines.every((l) => /^\s*[-*]\s+/.test(l))) {
          return (
            <ul key={key} className="list-disc space-y-1 pl-6">
              {lines.map((l, i) => (
                <li key={i}>{inline(l.replace(/^\s*[-*]\s+/, ''), `${key}-${i}`)}</li>
              ))}
            </ul>
          )
        }
        if (lines.every((l) => /^\s*\d+[.)]\s+/.test(l))) {
          return (
            <ol key={key} className="list-decimal space-y-1 pl-6">
              {lines.map((l, i) => (
                <li key={i}>{inline(l.replace(/^\s*\d+[.)]\s+/, ''), `${key}-${i}`)}</li>
              ))}
            </ol>
          )
        }
        return <p key={key}>{inline(lines.join(' '), key)}</p>
      })}
    </div>
  )
}
