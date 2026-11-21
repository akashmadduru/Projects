/**
 * Lightbox: keyboard navigation, zoom bounds and reset (TD-P7-17: it was untested).
 */

import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { Lightbox } from '../Lightbox'
import { safeHttpUrl } from '../../pages/ProductDetailPage'

function Harness({ onClose = vi.fn() }: { onClose?: () => void }) {
  const [index, setIndex] = useState(0)
  return <Lightbox images={['/1.png', '/2.png', '/3.png']} index={index} alt="Lamp" onIndexChange={setIndex} onClose={onClose} />
}

const image = () => screen.getByRole('img', { name: /Lamp, \d of 3/ })

describe('Lightbox', () => {
  it('arrow keys move through the images and wrap around', () => {
    render(<Harness />)
    expect(image()).toHaveAccessibleName('Lamp, 1 of 3')
    fireEvent.keyDown(document, { key: 'ArrowLeft' })
    expect(image()).toHaveAccessibleName('Lamp, 3 of 3')
    fireEvent.keyDown(document, { key: 'ArrowRight' })
    expect(image()).toHaveAccessibleName('Lamp, 1 of 3')
  })

  it('Escape closes', () => {
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('zoom stays between 1x and 5x and resets when the image changes', () => {
    render(<Harness />)
    const zoomOut = screen.getByRole('button', { name: 'Zoom out' })
    const zoomIn = screen.getByRole('button', { name: 'Zoom in' })
    expect(zoomOut).toBeDisabled()
    for (let i = 0; i < 10; i++) fireEvent.click(zoomIn)
    expect(image().style.transform).toContain('scale(5)')
    expect(zoomIn).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Next image' }))
    expect(image().style.transform).toContain('scale(1)')
  })

  it('double-click toggles a 2.5x zoom', () => {
    render(<Harness />)
    fireEvent.doubleClick(image().parentElement!)
    expect(image().style.transform).toContain('scale(2.5)')
    fireEvent.doubleClick(image().parentElement!)
    expect(image().style.transform).toContain('scale(1)')
  })
})

describe('safeHttpUrl (360 view link)', () => {
  it('accepts http(s) only', () => {
    expect(safeHttpUrl('https://spin.example/p/1')).toBe('https://spin.example/p/1')
    expect(safeHttpUrl('javascript:alert(1)')).toBeUndefined()
    expect(safeHttpUrl('not a url')).toBeUndefined()
    expect(safeHttpUrl(null)).toBeUndefined()
  })
})
