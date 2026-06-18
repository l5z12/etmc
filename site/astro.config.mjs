// @ts-check
import { defineConfig } from 'astro/config';

// Build-time rendered (static) site. No SSR adapter: every page is prerendered
// to HTML at build, then served from Cloudflare Workers static assets (see
// wrangler.jsonc + worker/index.js). The config generator runs entirely in the
// browser, so no server runtime is needed.
export default defineConfig({
  // Canonical host for OG tags / canonical links. Change to the real domain.
  site: 'https://etmc.l5z12.dev',
  trailingSlash: 'ignore',
  build: {
    // routes resolve as /docs/hosting.html etc.; Workers html_handling makes the
    // URLs clean. Mirrors the reference deployment.
    format: 'file',
    inlineStylesheets: 'auto',
  },
  compressHTML: true,
  prefetch: {
    prefetchAll: true,
    defaultStrategy: 'viewport',
  },
  vite: {
    build: {
      cssMinify: 'esbuild',
    },
  },
});
