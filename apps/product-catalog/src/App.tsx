/**
 * Root app component
 */

import { RouterProvider } from 'react-router-dom'
import { ErrorBoundary } from './components'
import { router } from './router'
import './style.css'

export function App() {
  return (
    <ErrorBoundary>
      <RouterProvider router={router} />
    </ErrorBoundary>
  )
}
