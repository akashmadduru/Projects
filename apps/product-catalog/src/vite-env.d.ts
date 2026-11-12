/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Origin of auth-service for starting Google sign-in. Default http://localhost:8081. */
  readonly VITE_AUTH_BASE_URL?: string
  /** Set to "false" to hide Google sign-in. Default: shown. */
  readonly VITE_GOOGLE_SSO_ENABLED?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
