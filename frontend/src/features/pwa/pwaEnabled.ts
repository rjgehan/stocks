import { API_MOCK } from '@/lib/api/client'

/** The service worker runs in production builds only: never on the Vite dev server or in mock mode (§22.10). */
export const PWA_ENABLED: boolean = import.meta.env.PROD && !API_MOCK
