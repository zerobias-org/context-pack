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
  "@zerobias-org/context-pack-dev": "^2.0.0"
},
// required — see "Transitive advisories" below; the exact set is published
// at @zerobias-org/context-pack-dev/overrides.json
"overrides": {
  "diff": ">=8.0.3",
  "serialize-javascript": ">=7.0.3"
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

**The packs are 1.x deliberately, and that is the update contract:**

| Consumer range | Picks up automatically | Requires a deliberate bump |
|---|---|---|
| `^2.0.0` | every 2.x minor and patch | majors (3.0.0) |

This is why they are not 0.x. Under semver, `^0.1.0` matches only `0.1.x` — a
`0.2.0` release would never reach a consumer, which defeats the entire point of
centralising the toolchain. On 1.x, routine updates flow on the next install
and a breaking toolchain change (a `chai` major, a TypeScript major) needs an
explicit range bump in each consumer. That gives staged rollout for free: the
fleet cannot be broken by a single merge here.

So: **put breaking toolchain changes in a pack major.** Anything that should
reach everyone quietly goes in a minor or patch.

**2.0.0 is the first use of that mechanism**: it moves the compiler from
TypeScript 5.9.3 to 6.0.3. Consumers opt in by bumping their range, rather
than inheriting a compiler major on their next install.

### Publishing order

`context-pack-dev` depends on `context-pack` at an exact version, so when both
change the base must publish first. The workflow sets `max-parallel: 1` for
exactly this reason — run in parallel, the dev pack's install races the base
pack's publish and fails with `E404` on a version that is seconds from
existing. It happened on both the 0.2.0 and 1.0.0 releases.

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

## Verifying a pack change

The gradle gate validates the packs' **shape** — exact pins, dependency vs
peer classification, a dependency-free root. It cannot tell you whether a
consumer still builds, which is the question that matters for a Renovate bump.

`scripts/consumer-smoke.sh` answers that: it builds the candidate packs from
the working tree and runs a fixture module (`test/consumer-smoke/`) against
them — peer placement at the consumer root, `tsc`, `eslint` through the shared
config, `mocha`, and `npm audit`. It runs on every PR touching `package/`.

```bash
./scripts/consumer-smoke.sh     # same thing locally
```

**What it does and does not catch**, measured by injecting each regression:

| Injected into the pack | Caught |
|---|---|
| `typescript` → 7 (crashes typescript-eslint) | ✅ yes |
| a peer demoted to a plain dependency | ✅ yes (explicit placement check) |
| a toolchain dep with a fresh advisory | ✅ yes (`npm audit`) |
| `chai` 4 → 6 | ❌ no — the fixture's assertion style still works |
| `lib`/`types` narrowed in the shipped tsconfig | ❌ no — with peers at the consumer root, TypeScript auto-includes `@types` anyway |

So a green smoke means *the toolchain still works*, not *no consumer can
break*. For a change with real API surface — an assertion library major, a
codegen major — pilot it on a real module before releasing, and put it in a
pack **major** so consumers opt in.

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
