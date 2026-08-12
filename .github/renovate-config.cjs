// Self-hosted Renovate runner configuration (bot runtime settings).
// Update *rules* live in /renovate.json at the repo root — this file only
// tells the bot how to run and how to authenticate against our registries.
// On-demand scoping: workflow_dispatch can pass a comma-separated list of
// module paths (e.g. "amazon/aws/s3,github/github"). When set it overrides
// the repo config's includePaths AND clears schedules, so the dispatched
// run processes exactly those packages immediately.
const dispatchPackages = (process.env.RENOVATE_DISPATCH_PACKAGES || '')
  .split(',')
  .map((p) => p.trim())
  .filter(Boolean);

module.exports = {
  platform: 'github',
  repositories: ['zerobias-org/context-pack'],
  onboarding: false,
  requireConfig: 'required',
  ...(dispatchPackages.length > 0 && {
    force: {
      includePaths: dispatchPackages.map((p) => `package/${p}/**`),
      schedule: ['at any time'],
    },
  }),
  // Registry -> scope mapping comes from the repo's own .npmrc (Renovate reads
  // it automatically). We only inject the auth tokens it references:
  //   npm.pkg.github.com  (@auditlogic, @auditmation, @zerobias-com) -> NPM_TOKEN
  //   pkg.zerobias.org    (@zerobias-org)                            -> ZB_TOKEN
  hostRules: [
    process.env.NPM_PKG_TOKEN && {
      matchHost: 'npm.pkg.github.com',
      hostType: 'npm',
      token: process.env.NPM_PKG_TOKEN,
    },
    process.env.ZB_PKG_TOKEN && {
      matchHost: 'pkg.zerobias.org',
      hostType: 'npm',
      token: process.env.ZB_PKG_TOKEN,
    },
  ].filter(Boolean),
};
