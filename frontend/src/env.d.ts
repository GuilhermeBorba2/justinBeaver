/// <reference types="vite/client" />

interface Window {
  justinBeaverDesktop?: {
    openGoogleSignIn(): Promise<{ url: string }>
  }
}
