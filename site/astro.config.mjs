// @ts-check
import { defineConfig } from 'astro/config';
import { unified } from '@astrojs/markdown-remark';
import remarkCjkFriendly from 'remark-cjk-friendly';

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
    '/zh-cn/docs': '/zh-cn/docs/getting-started',
    '/zh-tw/docs': '/zh-tw/docs/getting-started',
  },
  build: {
    // routes resolve as /docs/hosting.html etc.; Workers html_handling makes the
    // URLs clean.
    format: 'file',
    inlineStylesheets: 'auto',
  },
  markdown: {
    processor: unified({
      // Plain CommonMark only closes **bold** that ends in punctuation if a space follows, which Chinese
      // never has: **你的密钥不受保护。**它以明文保存 would show its asterisks. This plugin makes it bold.
      remarkPlugins: [remarkCjkFriendly],
      // Quotes stay as written.
      smartypants: false,
    }),
    // Code blocks keep the site's own colours.
    syntaxHighlight: false,
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
