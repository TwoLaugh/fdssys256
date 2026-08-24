/**
 * Live-API mode switch.
 *
 * Default OFF — the app runs on in-memory fixtures (`npm run dev`). Set
 * `VITE_LIVE=1` to hydrate the store from the real backend through the Vite
 * `/api` proxy (see vite.config.ts). The page components are unchanged either
 * way: they read the store, and this flag only decides who fills it.
 */
export const LIVE: boolean = Boolean(import.meta.env.VITE_LIVE);
