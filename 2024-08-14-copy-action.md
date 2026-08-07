---
created: 2024-08-14
last updated: 2026-07-15
status: Draft
reviewers: []
title: Copy Action
authors:
  - Silic0nS0ldier
---

# Abstract

Copying files and directories is a common need that is underserved in Bazel. This proposal seeks to make this a builtin capability to improve performance and simplify rule development.

# Background

## Status Quo

The following actions (`ctx.actions.*`) already exist;
- `args` - An abstraction for memory-efficient argument management. May produce a file.
- `declare_directory` - Declares a directory output.
- `declare_file` - Declares a file output.
- `declare_symlink` - Declares a symlink output.
- `do_nothing` - Does nothing, only serving as an insertion point for the deprecated 'extra actions' feature.
- `expand_template` - Creates a file using a template.
- `map_directory` - An abstraction for creating actions (via `template_ctx`) based on the files within input director(y/ies).
  - `args` - Same as `ctx.actions.args`.
  - `declare_file` - Same as `ctx.actions.declare_file`.
  - `declare_subdirectory` - Declares a directory output within one of `map_directory`'s outputs.
  - `run` - Same as `ctx.actions.run`.
- `run` and `run_shell` - Runs an executable/shell script. May produce file(s), director(y/ies) and/or symlink(s).
- `symlink` - Creates a symlink.
- `template_dict` - Abstraction for `expand_template` that allows deferring evaluation of values.
- `write` - Creates a file.

Among these actions, the closest builtin analogue to copying a file is using `ctx.actions.symlink`, which depending on the scenario can lead to different behavior at runtime (e.g. NodeJS import resolution is affected, and package managers like [pnpm](https://pnpm.io/) with their integrations like [Rules JS](https://github.com/aspect-build/rules_js) rely on these observable differences). This makes it a poor subsitutite.

A true copy today requires a spawn, via `ctx.actions.run`, `ctx.actions.run_shell`, or `genrule`. Utility rulesets wrap these:

- [`@bazel_skylib`](https://github.com/bazelbuild/bazel-skylib)
  - [`copy_directory`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/docs/copy_directory_doc.md)
  - [`copy_file`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/docs/copy_file_doc.md)
- [`bazel_lib`](https://github.com/bazel-contrib/bazel-lib)
  - [`copy_directory` + `copy_directory_bin_action`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_directory.bzl)
  - [`copy_file` + `copy_file_action`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_file.bzl)
  - [`copy_to_bin` + `copy_file_to_bin_action` + `copy_files_to_bin_actions`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_to_bin.bzl)
  - [`copy_to_directory` + `copy_to_directory_bin_action`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/copy_to_directory.bzl)

Because these are all spawns, they inherit spawn costs:

- **Per-copy overhead disproportionate to the task.**
  Process launch (or worker round trip), sandbox setup, action-cache lookups, and with remote execution an `Execute` call, an `ActionResult`, and CAS round trips — all to produce bytes the build already has.
- **Merkle tree and memory growth.**
  Each copy spawn contributes its tool and inputs to merkle tree construction, increasing CPU and memory costs.
- **Workarounds that punish remote builds.**
  Forcing copies to run locally with [`no-remote`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/rules/private/copy_common.bzl#L43) avoids remote round trips but forces remote-produced inputs to be downloaded under `--remote_download_minimal` (defeating Build without the Bytes). It also breaks down when using a remote execution service that forbids clients from uploading `ActionResult`s (a common security measure since they can reference arbitrary bytes).
- **Workarounds that punish cache hit rates.**
  Opting copies out of caching with [`no-cache`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/rules/private/copy_common.bzl#L44) reduces storage demands by the disk cache, but causes the spawns to run more often.
- **Cache-key fragility.**
  The copy is keyed on its implementation (tool digest, command line), so ruleset upgrades invalidate every copy in the graph. If the spawn does not support path mapping (`--experimental_output_paths=strip` flag set and `supports-path-mapping` present in `execution_requirements`) configuration differences (e.g. OS) also contribute to invalidations.
- **Batching trades one problem for another.**
  Batching copies into one spawn amortises overhead but destroys incrementality. In most incremental builds only one input of the batch changed, yet the whole batch re-runs (and re-uploads).

## Artifact Type vs. Materialised Filesystem Type

Declared artifact types do not always map to the actual materialised filesystem types, at least on the surface level.
- Sandboxing
  - Under Bazel, the sandbox spawn strategy populates a directory with symlinks.
  - Under certain RBE services (e.g. EngFlow), sandboxing is implicit. No symlinks are necessary, hardlinks may be used.
- Runfiles Directory
  - Under Bazel (Linux default), runfiles are represented as a tree of symlinks.
  - Under certain RBE services (e.g. EngFlow), runfiles content is materialised as hardlinks.

This proposal does not touch these materialisation strategies, just the resources (files, directories, symlinks) they refer to. e.g.
```
With ctx.actions.symlink
original.txt (file) -> copied.txt (symlink) -> __.runfiles/copied.txt (symlink)
                                   ^^^^^^^
With ctx.actions.copy
original.txt (file) -> copied.txt (file) -> __.runfiles/copied.txt (symlink)
                                   ^^^^
```

Another noteworthy callout is `--remote_download_symlink_template`. Any workloads which are problematic with this flag today (e.g. NodeJS canonicalising file paths before import resolution) will remain problematic with this proposal.

## Source Artifact Assumptions

Bazel assumes all source inputs (files not generated by actions) are ordinary files. That is, [`File.is_directory`](https://bazel.build/rules/lib/builtins/File#is_directory) and [`File.is_symlink`](https://bazel.build/rules/lib/builtins/File#is_symlink) will always be `False` and `File.is_source == True`.

Additionally remote execution has historically had [issues supporting source directories](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/rules/private/copy_common.bzl#L30-L32).

## Build Without The Bytes

Since Bazel 7 BwoB (Build without the Bytes) has been [enabled by default](https://blog.bazel.build/2023/10/06/bwob-in-bazel-7.html). This capability greatly reduces data transfers between Bazel and remote caching/execution services, as well as reducing local storage requirements.

BwoB has come along way since it's [introduction in Bazel 0.25](https://blog.bazel.build/2019/05/07/builds-without-bytes.html), however it has limits. Namely certain builtin action types (`symlink` and `write`) eagerly materialise their outputs. This does not contradict `--remote_download_outputs` as the outputs are _not technically_ produced by a remote, however a naive copy implementation can have much higher IO and storage demands when eagerly materialised.

## Bazel Remote Output Service

The [Bazel Remote Output Service](https://docs.google.com/document/d/1W6Tqq8cndssnDI0yzFSoj95oezRKcIhU57nwLHaN1qk/edit) proposal saw the introduction of `--experimental_remote_output_service` which delegates management of Bazel's output tree to a separate service.

# Proposal

Introduce a new built-in `copy` action:

```starlark
ctx.actions.copy(
    # File: a file (including source tree), tree or symlink
    input,
    # File: a declared file, tree or symlink
    output,
    # string|None: Optional string specifying a single file or tree artifact to extract from input
    path = None,
    # string|None: Optional progress message string.
    # Defaults to "Copying %{input} to %{output}"
    progress_message = None,
)
```

This `copy` action has the following behaviours:

1. **Preserves artifact type.**
   A file copies to a file, a directory to a directory, symlink to a symlink. Mismatched input/output types are an analysis-time error with 2 exceptions:
   1. Directory contents (directory or file) can be copied out by specifying `path`. Type mismatch and not found errors are surfaced when contents are known at execution-time.
   2. Source artifact types are checked as execution-time (see [Source Artifact Assumptions](#source-artifact-assumptions)).
2. **Identical content and executable bit.**
   - File content is identical and executable permission is propagated (`is_executable = True` not required).
   - Files within a directory have identical content.
   - File or directory fulfilled with a symlink (e.g. `symlink(target_file = some_file, output = declare_file(...))`) references the same file or directory as the input and executable permission is propagated.
   - Symlink (e.g. `symlink(target_path = "../some_path", output = declare_symlink(...))`) use the same target string.
3. **No spawns.**
   Like symlink actions, it has no execution strategy, no execution platform, no sandbox, and never executes remotely. Bazel performs the work in-process without affecting spawn specific queues.
4. **Realisation of the output is deferred where possible.** <span id="deferred-realisation"></span>
   When the input's content is remote-backed under Build without the Bytes, the copy completes as a metadata-only operation and the output is materialised on demand, exactly like any other remote-backed output.
   > [!NOTE]
   > Lazy realisation of source artifacts is out of scope for this proposal as Bazel's rewinding machinery recovers a lost artifact by re-executing its generating action. Source artifacts have no generating action, and [`ActionRewindStrategy`](https://github.com/bazelbuild/bazel/blob/5cce7794834a1531ee5fad131f11a9d4d8e66781/src/main/java/com/google/devtools/build/lib/skyframe/rewinding/ActionRewindStrategy.java) requires all lost artifacts to be derived.
   >
   > Related work:
   > - [Lazily create runfiles symlink trees with BwoB](https://github.com/bazelbuild/bazel/pull/26971)
   > - [Lazily create local symlinks with BwoB](https://github.com/bazelbuild/bazel/pull/26045)
   > - [Add `--file_write_strategy` to Bazel](https://github.com/bazelbuild/bazel/pull/24921)
5. **Copy-on-Write and hardlinking.**
   Local copies (e.g. copying source files under local execution) will be performed by [`java.nio.file.Files#copy`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)). Optimisations such as Copy-on-Write (CoW, cloning, reflinking) and hardlinking are not a part of this proposal, but can be implemented later.
   > [!NOTE]
   > Hardlinking is more widly supported, but not always the desired behaviour.
   > - Modifications to hardlinked files can produce unexpected results (edits affecting more files than intended), especially if a file is hardlinked multiple times.
   > - On macOS hardlinking may cause issues with the Gatekeeper if [an executable shares an `inode` with a previously quarantined path](https://developer.apple.com/forums/thread/663456).
   >
   > If hardlinking optimisations are implemented, a way to opt-out (e.g. flag) should be included.

## Supported Input/Output Combinations

| Input               | Output              | `path`   | Outcome                                     |
|---------------------|---------------------|----------|---------------------------------------------|
| file                | `declare_file`      | —        | copied file                                 |
| file (symlink)      | `declare_file`      | —        | copied file (no symlink)                    |
| directory           | `declare_directory` | —        | copied directory                            |
| directory           | `declare_directory` | required | copied subdirectory                         |
| directory           | `declare_file`      | required | copied file in directory                    |
| directory (symlink) | `declare_directory` | —        | copied directory (no symlink)               |
| directory (symlink) | `declare_directory` | required | copied subdirectory in referenced directory |
| directory (symlink) | `declare_file`      | required | copied file in referenced directory         |
| symlink             | `declare_symlink`   | —        | new symlink with the identical target path  |

Copying a symlink out of a directory is not supported as Bazel already dereferences encountered symlinks during metadata collection (raising an error on encountering a dangling symlink).

## Caching

Compared to spawns (`run` and `run_shell` action types) a `copy` action needs no dedicated result type for caching. Everything can be cheaply computed locally from input metadata. In certain circumstances caches aren't even touched (e.g. copied file not used by any remote spawns).

> [!NOTE]
> Like with [_4. Realisation of the output is deferred where possible_](#deferred-realisation), Bazel's rewinding machinery relies on `ActionResult`s. Lazy uploading of source artifacts to remote caches is out of scope for this proposal.

## Introspection

Like with the `symlink` action, little can be inferred from BEP and disk/remote cache records alone. Identification of the input requires `bazel aquery`.

**File Copy Example**

<details open>
<summary>Copy</summary>

```
action 'Copying inputs/u000001.bin to copy/u000001.bin'
  Mnemonic: Copy
  Target: //:copy
  Configuration: k8-fastbuild
  Execution platform: @@platforms//host:host
  ActionKey: 70b26b7b8da2f7e12e89fba77c8dd50d5cef58c5f285fe93520a000041cba820
  Inputs: [bazel-out/k8-fastbuild/bin/inputs/u000001.bin]
  Outputs: [bazel-out/k8-fastbuild/bin/copy/u000001.bin]
```
</details>

<details>
<summary>Run Shell</summary>

```
action 'Copying (spawn) u000000.bin'
  Mnemonic: CopySpawn
  Target: //:spawn
  Configuration: k8-fastbuild
  Execution platform: @@platforms//host:host
  ActionKey: 0adda2c484cd8d4afd7a3e0a9de660b6c659393b8a0419c30f9b79e43cfef12e
  Inputs: [bazel-out/k8-fastbuild/bin/inputs/u000000.bin]
  Outputs: [bazel-out/k8-fastbuild/bin/spawn/u000000.bin]
  Command Line: (exec /bin/bash \
    -c \
    'cp -L "$1" "$2"' \
    '' \
    bazel-out/k8-fastbuild/bin/inputs/u000000.bin \
    bazel-out/k8-fastbuild/bin/spawn/u000000.bin)
# Configuration: 52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241
# Execution platform: @@platforms//host:host
```
</details>

<details>
<summary>Symlink</summary>

```
action 'Symlinking symlink/u000001.bin'
  Mnemonic: Symlink
  Target: //:symlink
  Configuration: k8-fastbuild
  Execution platform: @@platforms//host:host
  ActionKey: c90914cc1e7ea06f84518cf4f179e63dbd20dad9358a550644494828e4a4ed28
  Inputs: [bazel-out/k8-fastbuild/bin/inputs/u000001.bin]
  Outputs: [bazel-out/k8-fastbuild/bin/symlink/u000001.bin]
```
</details>

**File Within Directory Copy Example**

<details open>
<summary>Copy</summary>

```
action 'Copying pkg/tree.dir to pkg/extracted.txt'
  Mnemonic: Copy
  Target: //:extract
  Configuration: k8-fastbuild
  Execution platform: @@platforms//host:host
  ActionKey: 70b26b7b8da2f7e12e89fba77c8dd50d5cef58c5f285fe93520a000041cba820
  Inputs: [bazel-out/k8-fastbuild/bin/inputs/u000.tree]
  Outputs: [bazel-out/k8-fastbuild/bin/extract/u000001.bin]
  CopyPath: u000001.bin
```
</details>

## Build Event Protocol

When `--build_event_publish_all_actions` is specified, BEP will include events for builtin `copy` actions. Content is comparable to what `symlink` actions produce.

**File Copy Example**

<details open>
<summary>Copy</summary>

```json
{
    "id": {
        "actionCompleted": {
            "primaryOutput": "bazel-out/k8-fastbuild/bin/copy/u000001.bin",
            "label": "//:copy",
            "configuration": {
                "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
            }
        }
    },
    "action": {
        "success": true,
        "label": "//:copy",
        "primaryOutput": {
            "uri": "bytestream://127.0.0.1:50051/blobs/cbedcbc892db1449a64cfb8f89a77f692446bf8d63a375d67e9f741069d32e08/4194304"
        },
        "configuration": {
            "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
        },
        "type": "Copy"
    }
}
```
</details>

<details>
<summary>Run Shell</summary>

```json
{
    "id": {
        "actionCompleted": {
            "primaryOutput": "bazel-out/k8-fastbuild/bin/run_shell/u000001.bin",
            "label": "//:spawn",
            "configuration": {
                "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
            }
        }
    },
    "action": {
        "success": true,
        "label": "//:spawn",
        "primaryOutput": {
            "uri": "bytestream://127.0.0.1:50051/blobs/bd145647b14b30aede9085bbaebf0a08fb5ea1b313d062076e86addb9ad257ee/4194304"
        },
        "configuration": {
            "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
        },
        "type": "CopySpawn",
        "commandLine": [
            "/bin/bash",
            "-c",
            "cp -L \"$1\" \"$2\"",
            "",
            "inputs/u000001.bin",
            "bazel-out/k8-fastbuild/bin/run_shell/u000001.bin"
        ],
        "startTime": "2026-07-16T08:40:10.248274260Z",
        "endTime": "2026-07-16T08:40:10.261274260Z"
    }
}
```
</details>

<details>
<summary>Symlink</summary>

```json
{
    "id": {
        "actionCompleted": {
            "primaryOutput": "bazel-out/k8-fastbuild/bin/symlink/u000001.bin",
            "label": "//:symlink",
            "configuration": {
                "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
            }
        }
    },
    "action": {
        "success": true,
        "label": "//:symlink",
        "primaryOutput": {
            "uri": "bytestream://127.0.0.1:50051/blobs/09118149ed6648a30d0f00a48fd3e57e5e3c59696fbfd14832b196d714e96c61/4194304"
        },
        "configuration": {
            "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
        },
        "type": "Symlink"
    }
}
```
</details>

**Directory Copy Example**

<details open>
<summary>Copy</summary>

```json
{
    "id": {
        "actionCompleted": {
            "primaryOutput": "bazel-out/k8-fastbuild/bin/copy/u000.tree",
            "label": "//:copy",
            "configuration": {
                "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
            }
        }
    },
    "action": {
        "success": true,
        "label": "//:copy",
        "configuration": {
            "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
        },
        "type": "Copy"
    }
}
```
</details>

<details>
<summary>Run Shell</summary>

```json
{
    "id": {
        "actionCompleted": {
            "primaryOutput": "bazel-out/k8-fastbuild/bin/run_shell/u000.tree",
            "label": "//:spawn",
            "configuration": {
                "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
            }
        }
    },
    "action": {
        "success": true,
        "label": "//:spawn",
        "configuration": {
            "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
        },
        "type": "CopySpawn",
        "commandLine": [
            "/bin/bash",
            "-c",
            "cp -RL \"$1\"/. \"$2\"/",
            "",
            "bazel-out/k8-fastbuild/bin/u000.tree",
            "bazel-out/k8-fastbuild/bin/run_shell/u000.tree"
        ],
        "startTime": "2026-07-22T04:43:46.260465564Z",
        "endTime": "2026-07-22T04:43:46.276465564Z"
    }
}
```
</details>

<details>
<summary>Symlink</summary>

```json
{
    "id": {
        "actionCompleted": {
            "primaryOutput": "bazel-out/k8-fastbuild/bin/copy/u000.tree",
            "label": "//:symlink",
            "configuration": {
                "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
            }
        }
    },
    "action": {
        "success": true,
        "label": "//:symlink",
        "configuration": {
            "id": "52c85d7b35d3f6598ca5991e2fcb16a4e5cd92789e362244901c18381d8f7241"
        },
        "type": "Symlink"
    }
}
```
</details>

## Example usage

### `@bazel_skylib`'s [`copy_directory`](https://github.com/bazelbuild/bazel-skylib/blob/bac104bc6065308a043489757f2a7ffd159c7fd1/rules/private/copy_directory_private.bzl)

```starlark
def _copy_directory_impl(ctx):
    dst = ctx.actions.declare_directory(ctx.attr.out)
    # Analysis-time error if `src` is not a tree artifact
    ctx.actions.copy(ctx.file.src, dst)

    files = depset(direct = [dst])
    runfiles = ctx.runfiles(files = [dst])

    return [DefaultInfo(files = files, runfiles = runfiles)]

copy_directory = rule(
    implementation = _copy_directory_impl,
    provides = [DefaultInfo],
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
        # Cannot declare out as an output here, because there's no API for
        # declaring TreeArtifact outputs.
        "out": attr.string(mandatory = True),
    },
)
```

Compared to the canonical implementation:

- The implementation is significantly smaller.
- Both spawn branches (POSIX shell and `cmd.exe`) are replaced by one `copy` call.
- The `_exec_is_windows` attribute and `:is_windows` target are gone.
- The `no-remote`/`no-cache` execution-requirement workaround is gone, along with its Build-without-the-Bytes download penalty.

### `bazel_lib`'s [`copy_to_bin`](https://github.com/bazel-contrib/bazel-lib/blob/222a5bf32e8b6a546059cfff85fe01af7164e596/lib/private/copy_to_bin.bzl)

```starlark
def copy_file_to_bin_action(ctx, file):
    if not file.is_source:
        return file
    if ctx.label.workspace_name != file.owner.workspace_name:
        fail(_file_in_external_repo_error_msg(file))
    if ctx.label.package != file.owner.package:
        fail(_file_in_different_package_error_msg(file, ctx.label))

    if file.path.startswith("bazel-"):
        first = file.path.split("/")[0]
        suffix = first[len("bazel-"):]
        if suffix in ["testlogs", "bin", "out"]:
            print(_probably_should_git_ignore(first))

    dst = ctx.actions.declare_file(file.basename, sibling = file)
    ctx.actions.copy(file, dst)
    return dst

def copy_files_to_bin_actions(ctx, files):
    return [copy_file_to_bin_action(ctx, file) for file in files]

def _copy_to_bin_impl(ctx):
    files = copy_files_to_bin_actions(ctx, ctx.files.srcs)
    return DefaultInfo(
        files = depset(files),
        runfiles = ctx.runfiles(files = files),
    )

copy_to_bin = rule(
    implementation = _copy_to_bin_impl,
    provides = [DefaultInfo],
    attrs = {
        "srcs": attr.label_list(mandatory = True, allow_files = True),
    },
)
```

Compared to the canonical implementation:

- Core validation logic remains unchanged.
- No helper required to copy file(s).
- No toolchain resolution is necessary.
- N/A for the macro since the the canonical implementation just fowards to the rule.

# Backward-compatibility

This proposal won't impact backward compatibility, although feature detection should be considered so that existing utility rulesets can optionally use the newer (and more efficent) API without needing to wait for supported Bazel versions to age out of the support matrix.

# Alternatives Considered

## `ctx.actions.symlink`

Not a copy. Symlinks are runtime-observable and ecosystems (NodeJS resolution, pnpm layouts) assign them meaning. Substituting symlinks where copies are required changes behaviour.

## Server-side short-circuiting of copy spawns

A remote execution service can recognise known copy actions and synthesise the `ActionResult` without scheduling execution. Brittle (copy spawn structure can change easily, breaking heuristics), more network activity vs. proposal, does not benefit local execution.

## Persistent worker / batched copy spawns

Workers amortise process launch but keep every other spawn cost (cache round trips, merkle trees, BES events) and add worker lifecycle complexity. Batching amortises overhead at the cost of incrementality (a batch re-runs and re-uploads when any one input changes). Both are optimisations of the wrong primitive.
