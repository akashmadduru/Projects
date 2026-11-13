/** Renders text as a QR code SVG (built from modules as React elements, no HTML injection). */

import { useMemo } from 'react'
import qrcode from 'qrcode-generator'

export function QrCode({ value, label }: { value: string; label: string }) {
  const { size, path } = useMemo(() => {
    const qr = qrcode(0, 'M')
    qr.addData(value)
    qr.make()
    const n = qr.getModuleCount()
    let d = ''
    for (let r = 0; r < n; r += 1) {
      for (let c = 0; c < n; c += 1) {
        if (qr.isDark(r, c)) d += `M${c + 2},${r + 2}h1v1h-1z`
      }
    }
    return { size: n + 4, path: d }
  }, [value])

  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${size} ${size}`} className="h-48 w-48 rounded-lg border-2 border-ink bg-white" shapeRendering="crispEdges">
      <path d={path} fill="#000" />
    </svg>
  )
}
