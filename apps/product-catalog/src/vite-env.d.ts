/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Origin of auth-service for starting Google sign-in. Default http://localhost:8081. */
  readonly VITE_AUTH_BASE_URL?: string
  /** Set to "false" to hide Google sign-in. Default: shown. */
  readonly VITE_GOOGLE_SSO_ENABLED?: string
  /** Origin of the admin/seller portal for the "Sell on Acme" link. Default http://localhost:5173. */
  readonly VITE_ADMIN_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
