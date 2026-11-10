/**
 * The site's Worker. The site itself is static (see astro.config.mjs) and served from Workers static assets; the
 * Worker only runs for /downloads (wrangler.jsonc's run_worker_first) and for paths with no asset, which it hands
 * back to the assets for their 404 page.
 */
import { handle, type Context, type Env } from './downloads';

export default {
  async fetch(request: Request, env: Env, ctx: Context): Promise<Response> {
    return (await handle(request, env, ctx)) ?? env.ASSETS.fetch(request);
  },
};
