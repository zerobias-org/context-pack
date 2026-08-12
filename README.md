# context-pack

Shared build toolchain and TypeScript config for ZeroBias content packages —
Hub modules, collector bots, and content repos.

## Why

Every content package declares the same toolchain by hand. Measured across the
meta-repo: **1,985 duplicated declarations across 293 packages in 32 repos**.

| Repo | Packages | Declarations | Distinct `typescript` versions |
|---|---|---|---|
| `auditlogic/module` | 140 | 1,102 | 9 |
| `auditlogic/collectorbot` | 74 | 569 | 4 |
| `auditlogic/benchmark` | 19 | 38 | 3 |
| `org/collectorbot` | 7 | 56 | 3 |
| `org/module` | 4 | 38 | 2 |
| 27 others | 49 | ~182 | — |

The cost is not the duplication itself — it is that every toolchain question
has to be answered once per package. ESLint 10 support, TypeScript 7 (blocked
on `typescript-eslint`), `@types/node` 24 dropping ambient globals: each of
those became a separate investigation in each repo. Here they are answered
once, and consumers pick up the answer with a version bump.

## Packages

| Package | For | Contents |
|---|---|---|
| `@zerobias-org/context-pack` | everything (~293 pkgs) | `typescript`, `@types/node`, base tsconfig |
| `@zerobias-org/context-pack-dev` | modules + collector bots (~225 pkgs) | the base, plus `mocha`, `chai`, `tsx`, `@redocly/cli`, `eslint-config`, `util-codegen`, `module-test-client`, and a tsconfig with the mocha types |

Content packages that only need a compiler (benchmarks, frameworks) take the
base. Anything with a test suite takes `-dev`.

## Usage

```jsonc
// package.json
"devDependencies": {
  "@zerobias-org/context-pack-dev": "^0.1.0"
}
```

```jsonc
// tsconfig.json
{
  "extends": "@zerobias-org/context-pack-dev/tsconfig.json",
  "compilerOptions": { "outDir": "dist" },
  "include": ["src/**/*", "test/**/*", "generated/**/*"],
  "exclude": ["dist", "node_modules", "hub-sdk"]
}
```

## `dependencies` vs `peerDependencies`

The split is not stylistic — it follows how each package is *resolved* by the
consumer:

| Resolution | Examples | Declared as |
|---|---|---|
| `node_modules/.bin` binary | `typescript` (tsc), `mocha`, `tsx`, `@redocly/cli` | `dependencies` — safe to nest |
| imported by consumer source | `chai`, `module-test-client` | `peerDependencies` |
| referenced by literal path | `eslint-config` (gradle lint reads `node_modules/@zerobias-org/eslint-config/...`) | `peerDependencies` |
| ambient types via tsconfig | `@types/node`, `@types/mocha`, `@types/chai` | `peerDependencies` |

Only binaries are safe as plain dependencies. Everything else must land at the
**consumer's** `node_modules` root to resolve, and npm will nest a plain
dependency whenever nothing else in the tree requires it. npm 7+ installs peer
dependencies at the consumer root, which is the only placement that guarantees
resolution — and a version conflict surfaces loudly as `ERESOLVE` instead of
silently nesting.

This was learned the hard way: an earlier build declared `chai` as a plain
dependency, npm nested it, and every consumer failed with
`TS2307: Cannot find module 'chai'`.

## Version policy

**Exact pins live here.** Consumers take a caret range on the pack; the pack
pins each tool exactly. The fleet's toolchain is then uniform by construction —
packages cannot drift from one another, only the pack moves.

A package that genuinely needs a different version can still declare it
directly. The pack is a default, not a jail.

## Why this repo is not a zbb monorepo

It deliberately does **not** apply the `zb.monorepo-base/gate/build/publish`
gradle plugins, and publishes with a plain npm workflow.

Those plugins' `Prepublish.resolve()` rewrites each package's dependency
versions from the monorepo root's `package.json` at publish time. That is
correct for a monorepo of libraries — one version per repo, enforced at
publish — but it is fundamentally incompatible with a package whose purpose is
to *carry* pins. A previous attempt to host this in `org/util` published
`chai ^6.0.0` in place of the pinned `4.5.0`.

The root `package.json` here also declares no dependencies, so there is nothing
to hoist even if that machinery is introduced later.

**Do not add the monorepo plugins to this repo.** It will silently un-pin every
pack.

## Staying current

Renovate runs on this repo (weekly, plus immediate PRs for vulnerability
alerts). Because ~293 packages consume these packs, an update here is a
fleet-wide event: merging publishes a new pack version and every consumer
picks it up on its next install.

Standing holds, each with a real blocker rather than caution:

| Package | Held at | Blocker |
|---|---|---|
| `typescript` | `<7` | `typescript-eslint` does not support the TS 7 compiler API — `typescript-estree` crashes on `ts.Extension.Cjs`, failing lint for every consumer |
| `chai` / `@types/chai` | `<6` | chai 6 is ESM-only with a changed assertion API; consumer test suites are written against 4.x |
| `@types/node` | `<23` | Must track the Node **runtime** major (`.nvmrc` v22, `node:22-alpine` images), not npm latest — typing against APIs the runtime lacks is a bug, not an upgrade |

Majors and internal `@zerobias-*` updates require dashboard approval before a
PR opens. Patch/minor toolchain updates are grouped into a single PR so the
fleet moves in one pack release.

## Releasing

Bump the version in the same PR that changes the pins. Merging to `main`
publishes any workspace whose version is not yet on the registry — there is no
automatic version bumping, because a toolchain change should be a deliberate,
reviewable act.
