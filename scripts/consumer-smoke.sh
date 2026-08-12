#!/usr/bin/env bash
#
# Consumer smoke test.
#
# The packs' own gate only validates their SHAPE — exact pins, dependency vs
# peer classification, a dependency-free root. It cannot tell you whether a
# module still builds with the versions inside them. Without this, a Renovate
# bump could pass the gate, publish, and break every consumer on next install.
#
# So: build the candidate packs from the working tree, install them into a
# fixture that consumes them exactly as a Hub module does, and run the same
# three things a module's gate runs — tsc, eslint (through the pack's shared
# config, resolved by literal path), mocha — plus an audit.
#
# Runs in a temp copy so the repo is never left dirty.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "→ packing candidate packs from the working tree"
BASE_TGZ="$(cd "$REPO_ROOT/package/context-pack" && npm pack --pack-destination "$WORK" --silent | tail -n1)"
DEV_TGZ="$(cd "$REPO_ROOT/package/context-pack-dev" && npm pack --pack-destination "$WORK" --silent | tail -n1)"
echo "  base: $BASE_TGZ"
echo "  dev:  $DEV_TGZ"

echo "→ staging fixture"
cp -R "$REPO_ROOT/test/consumer-smoke" "$WORK/consumer"
cp "$REPO_ROOT/.npmrc" "$WORK/consumer/.npmrc" 2>/dev/null || true
rm -rf "$WORK/consumer/node_modules" "$WORK/consumer/package-lock.json"

# Point the fixture at the candidate tarballs. The dev pack depends on the base
# pack at an exact version that is not published yet during a version-bump PR,
# so the base is pinned through an override as well.
node - "$WORK" "$BASE_TGZ" "$DEV_TGZ" <<'NODE'
const fs = require('node:fs');
const [work, baseTgz, devTgz] = process.argv.slice(2);
const p = `${work}/consumer/package.json`;
const j = JSON.parse(fs.readFileSync(p, 'utf8'));
j.devDependencies['@zerobias-org/context-pack-dev'] = `file:${work}/${devTgz}`;
j.overrides['@zerobias-org/context-pack'] = `file:${work}/${baseTgz}`;
fs.writeFileSync(p, JSON.stringify(j, null, 2));
NODE

echo "→ installing"
(cd "$WORK/consumer" && npm install --no-audit --no-fund --silent)

echo "→ checking peer placement (must be at the CONSUMER root, not nested)"
missing=0
for pkg in chai @types/chai @types/mocha @types/node @zerobias-org/eslint-config; do
  if [ ! -d "$WORK/consumer/node_modules/$pkg" ]; then
    echo "  ✗ $pkg is NOT at the consumer root — it would fail to resolve in a module"
    missing=1
  fi
done
[ "$missing" -eq 0 ] || { echo "peer placement check failed"; exit 1; }
echo "  ✓ all peers at the consumer root"

echo "→ tsc / eslint / mocha"
(cd "$WORK/consumer" && npm run --silent smoke)

echo "→ npm audit (production tree, with the documented overrides)"
(cd "$WORK/consumer" && npm audit --omit=dev)

echo "✓ consumer smoke passed"
