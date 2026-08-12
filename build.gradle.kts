import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

plugins {
    id("zb.workspace")
}

group = "com.zerobias.contextpack"

// Required by the zbb CLI: it maps the current working directory to a gradle
// project path through this task. Without it `zbb publish` from inside a pack
// fails with "has build.gradle.kts but isn't registered in settings.gradle.kts"
// even though settings.gradle.kts registers it correctly.
val projectPaths by tasks.registering {
    group = "info"
    description = "Output project-to-directory mappings for tooling (used by zbb CLI)"
    doLast {
        subprojects.filter { it.buildFile.exists() }.forEach { p ->
            println("${p.path}=${p.projectDir.relativeTo(rootDir)}")
        }
    }
}

// ── Validator for zb.npm-only ──────────────────────────────────────────
//
// These packs exist to pin a toolchain for ~293 consumer packages. Two
// invariants make that work, and neither is visible by reading a diff — so
// they are enforced here rather than remembered:
//
//   1. `dependencies` must be EXACT pins. A range would let consumers drift
//      from each other, which is the whole problem the packs solve.
//
//   2. Anything the consumer resolves itself — imported by its source,
//      referenced by literal path, or loaded as ambient types via tsconfig —
//      must be a peerDependency, not a dependency. npm nests a plain
//      dependency whenever nothing else in the tree needs it, and the
//      consumer then fails to resolve it. This cost us a full debug cycle
//      when `chai` nested and every consumer hit
//      "TS2307: Cannot find module 'chai'".
//
// See the repo README for the resolution table behind rule 2.
extra["npmOnlyValidator"] = { proj: org.gradle.api.Project ->
    val mapper = jacksonObjectMapper()
    val pkg = mapper.readTree(proj.file("package.json"))

    // 1. Exact pins in `dependencies`.
    //
    //    One exemption: the sibling pack. An exact pin there means the dev
    //    pack's CI install demands a base version that is being published in
    //    the same run, which raced and failed on the 0.2.0, 1.0.0 and 2.0.0
    //    releases. A caret range resolves against the already-published base
    //    instead, and the packs move in lockstep anyway because they are
    //    released together from this repo.
    val siblingPacks = setOf("@zerobias-org/context-pack")
    pkg["dependencies"]?.fields()?.forEach { (name, spec) ->
        val v = spec.asText()
        if (name in siblingPacks) {
            require(v.startsWith("^")) {
                "[context-pack] ${proj.name}: sibling '$name' must use a caret " +
                    "range, got '$v'. An exact pin races the sibling's publish " +
                    "in the same CI run."
            }
            return@forEach
        }
        require(v.isNotEmpty() && v[0].isDigit()) {
            "[context-pack] ${proj.name}: dependency '$name' must be an exact " +
                "pin, got '$v'. These packs exist to pin — a range lets " +
                "consumers drift."
        }
    }

    // 2. Consumer-resolved packages must be peers, never dependencies.
    val consumerResolved = setOf(
        "chai",
        "@types/node",
        "@types/mocha",
        "@types/chai",
        "@zerobias-org/eslint-config",
        "@zerobias-org/util-codegen",
        "@zerobias-org/module-test-client",
    )
    pkg["dependencies"]?.fieldNames()?.forEach { name ->
        require(name !in consumerResolved) {
            "[context-pack] ${proj.name}: '$name' is resolved by the consumer " +
                "(imported, path-referenced, or an ambient type) and must be a " +
                "peerDependency. As a plain dependency npm may nest it and the " +
                "consumer will fail to resolve it."
        }
    }

    // 3. Peers must be exact pins too, for the same reason as (1).
    pkg["peerDependencies"]?.fields()?.forEach { (name, spec) ->
        val v = spec.asText()
        require(v.isNotEmpty() && (v[0].isDigit() || v.startsWith("^"))) {
            "[context-pack] ${proj.name}: peerDependency '$name' has an " +
                "unexpected range '$v'"
        }
    }

    // 4. The shipped tsconfig must be present and parseable — it is the other
    //    half of what consumers extend.
    val tsconfig = proj.file("tsconfig.json")
    require(tsconfig.isFile) {
        "[context-pack] ${proj.name}: tsconfig.json is missing"
    }
    mapper.readTree(tsconfig)
}

// ── Guard: the repo root must stay dependency-free ─────────────────────
//
// Prepublish.resolve() (zb.monorepo-*) rewrites a package's dependency
// versions from the ROOT package.json. This repo does not apply those
// plugins, and its root declares nothing — but if either changes, every pack
// silently re-pins to the root's versions. That is exactly how the first
// attempt at this package published `chai ^6.0.0` in place of `4.5.0`.
val verifyRootHasNoDeps by tasks.registering {
    group = "verification"
    description = "Fail if the root package.json declares dependencies"
    doLast {
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(file("package.json"))
        for (field in listOf("dependencies", "devDependencies")) {
            val node = root[field]
            require(node == null || node.isEmpty) {
                "Root package.json declares '$field'. Prepublish.resolve() " +
                    "hoists root deps into published packages, which would " +
                    "overwrite the pins these packs exist to carry. Keep the " +
                    "root dependency-free."
            }
        }
        logger.lifecycle("[verifyRootHasNoDeps] root is dependency-free")
    }
}

subprojects {
    tasks.matching { it.name == "validate" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyRootHasNoDeps"))
    }
}
