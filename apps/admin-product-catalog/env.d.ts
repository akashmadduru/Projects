/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Gateway origin. Everything the SPA calls goes through the edge. */
  readonly VITE_API_BASE_URL?: string;
  /** This SPA's own origin; used to build the OAuth2 callback target. */
  readonly VITE_APP_ORIGIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
