// @ts-check
import { defineConfig } from 'astro/config';

// Build-time rendered (static) site. No SSR adapter: every page is prerendered
// to HTML at build, then served from Cloudflare Workers static assets (see
// wrangler.jsonc). The config generator runs entirely in the browser, so no
// server runtime is needed.
export default defineConfig({
  // Canonical host for OG tags and canonical links.
  site: 'https://etmc.l5z12.dev',
  trailingSlash: 'ignore',
  redirects: {
    '/docs': '/docs/getting-started',
  },
  build: {
    // routes resolve as /docs/hosting.html etc.; Workers html_handling makes the
    // URLs clean.
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
