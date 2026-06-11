/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend base URL. When unset the app runs in fixture (mock) mode. */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
