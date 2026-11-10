/**
 * PriceDisplay component - formats and displays product price with counter animation
 */

import React, { useEffect, useRef, useState } from 'react'

interface Props {
  amount: number
  currency: string
  size?: 'sm' | 'md' | 'lg'
}

function PriceDisplayComponent({ amount, currency, size = 'md' }: Props) {
  const sizeClasses = {
    sm: 'text-sm',
    md: 'text-lg',
    lg: 'text-2xl',
  }

  const [displayValue, setDisplayValue] = useState(0)
  const animationFrameRef = useRef<number | null>(null)
  const startTimeRef = useRef<number | null>(null)

  useEffect(() => {
    // Check if prefers-reduced-motion
    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    if (prefersReducedMotion) {
      setDisplayValue(amount)
      return
    }

    const animate = (currentTime: number) => {
      if (!startTimeRef.current) {
        startTimeRef.current = currentTime
      }

      const elapsed = currentTime - startTimeRef.current
      const duration = 800 // 0.8s animation

      if (elapsed < duration) {
        // Easing function: easeOut
        const progress = 1 - Math.pow(1 - elapsed / duration, 3)
        setDisplayValue(amount * progress)
        animationFrameRef.current = requestAnimationFrame(animate)
      } else {
        setDisplayValue(amount)
        startTimeRef.current = null
      }
    }

    animationFrameRef.current = requestAnimationFrame(animate)

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current)
      }
    }
  }, [amount])

  const formattedPrice = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
  }).format(displayValue)

  return (
    <span
      className={`${sizeClasses[size]} font-semibold text-on-surface animate-counter`}
      aria-label={`Price: ${new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency,
      }).format(amount)}`}
    >
      {formattedPrice}
    </span>
  )
}

export const PriceDisplay = React.memo(PriceDisplayComponent)
