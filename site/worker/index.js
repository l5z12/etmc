/**
 * Edge entry for the etmc site on Cloudflare Workers.
 *
 * The site is fully static (build-time rendered) and the config generator runs
 * in the browser, so there is nothing to render at request time. Because
 * `run_worker_first` is OFF in wrangler.jsonc, requests that match a static
 * asset are served directly by the assets layer and never reach this Worker —
 * so they don't count against Worker request limits. This handler therefore only
 * runs for unmatched routes; it just defers to the assets layer, which applies
 * `not_found_handling` (the 404 page). Response headers come from public/_headers.
 */
export default {
  async fetch(request, env) {
    return env.ASSETS.fetch(request);
  },
};
