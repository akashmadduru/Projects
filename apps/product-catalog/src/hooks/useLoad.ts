/**
 * Loads async data on mount (and whenever `load` changes). `load` must be memoized by the caller.
 */

import { useCallback, useEffect, useState } from 'react'

interface LoadState<T> {
  data: T | undefined
  error: unknown
  loading: boolean
  reload: () => void
  setData: (data: T) => void
}

export function useLoad<T>(load: () => Promise<T>): LoadState<T> {
  const [data, setData] = useState<T | undefined>()
  const [error, setError] = useState<unknown>()
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError(undefined)
    load()
      .then((result) => {
        if (active) setData(result)
      })
      .catch((e: unknown) => {
        if (active) setError(e)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [load, tick])

  const reload = useCallback(() => setTick((n) => n + 1), [])
  return { data, error, loading, reload, setData }
}
