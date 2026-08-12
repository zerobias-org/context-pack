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

## Why this repo is not an npm workspace

It uses the standard zbb pipeline — `zb.npm-only` per pack, publishing through
`zbb-publish-reusable` — but it is deliberately **not** an npm workspace, and
its root `package.json` declares no dependencies. Each pack under `package/`
resolves and locks its own, the same shape as `auditlogic/module`.

That matters because `Prepublish.resolve()` rewrites a package's dependency
versions from the **root** `package.json` at publish time. In a workspace
monorepo with a populated root that silently un-pins every pack: an earlier
attempt to host these in `org/util` published `chai ^6.0.0` in place of the
pinned `4.5.0`. With no workspace root carrying dependencies there is nothing
to hoist, so the pins survive structurally rather than by convention.

Two guards keep it that way, both failing the gate:

- the root `verifyRootHasNoDeps` task, if the root ever gains dependencies
- the `npmOnlyValidator`, if a pack declares a range instead of an exact pin,
  or declares a consumer-resolved package as a dependency instead of a peer

**Do not make this repo an npm workspace, and do not add dependencies to the
root `package.json`.**

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

Merging to `main` runs `zbb-publish-reusable`: it bumps each changed pack's
version from the conventional-commit history, publishes it, and tags the
release. A `workflow_dispatch` with a `pack` input publishes one pack on
demand.

Order matters when both packs change: `context-pack-dev` depends on
`context-pack` at an exact version, so the base publishes first.
