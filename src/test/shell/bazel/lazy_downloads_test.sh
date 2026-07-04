#!/usr/bin/env bash
#
# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Integration tests for the experimental lazy downloads API:
# rule(downloads = ...), downloads_ctx.download(), and ctx.downloads.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "${CURRENT_DIR}/remote_helpers.sh" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }

LAZY_DOWNLOADS_FLAG="--experimental_lazy_downloads"

function set_up() {
  repo_cache_dir="$TEST_TMPDIR/repository_cache_$RANDOM"
  create_new_workspace
}

function tear_down() {
  shutdown_server
  rm -rf "$repo_cache_dir"
}

# Prints the Subresource Integrity checksum (sha256) of the given file.
function sri_sha256() {
  python3 -c "
import base64, hashlib, sys
digest = hashlib.sha256(open(sys.argv[1], 'rb').read()).digest()
print('sha256-' + base64.b64encode(digest).decode())
" "$1"
}

# Writes fetch.bzl defining a `fetch_file` rule that declares one download and
# copies it to its output via an action.
function write_fetch_rule() {
  cat > fetch.bzl <<'EOF'
def _impl(ctx):
    downloaded = ctx.downloads[ctx.attr.path]
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.run_shell(
        inputs = [downloaded],
        outputs = [out],
        command = "cp '{}' '{}'".format(downloaded.path, out.path),
    )
    return [DefaultInfo(files = depset([out]))]

def _downloads(ctx):
    ctx.download(
        path = ctx.attr.path,
        urls = ctx.attr.urls,
        integrity = ctx.attr.integrity,
        canonical_id = ctx.attr.canonical_id,
    )

fetch_file = rule(
    implementation = _impl,
    attrs = {
        "path": attr.string(mandatory = True, configurable = False),
        "urls": attr.string_list(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
        "canonical_id": attr.string(configurable = False),
    },
    downloads = _downloads,
)
EOF
}

function test_download_and_use() {
  echo "hello lazy downloads" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  write_fetch_rule
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:$nc_port/payload.txt"],
    integrity = "$integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:fetched >& $TEST_log \
    || fail "expected build to succeed"
  assert_contains "hello lazy downloads" bazel-bin/fetched.out
}

function test_downloads_are_lazy() {
  # The download points at a dead address and would fail if fetched eagerly,
  # but nothing consumes it, so the build must succeed.
  cat > lazy.bzl <<'EOF'
def _impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.write(out, "built without downloading")
    return [DefaultInfo(files = depset([out]))]

def _downloads(ctx):
    ctx.download(
        path = "unused.txt",
        urls = ["http://127.0.0.1:1/unreachable.txt"],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )

lazy_rule = rule(
    implementation = _impl,
    downloads = _downloads,
)
EOF
  cat > BUILD <<'EOF'
load("//:lazy.bzl", "lazy_rule")

lazy_rule(name = "lazy")
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:lazy >& $TEST_log \
    || fail "expected build to succeed without fetching the unused download"
  assert_contains "built without downloading" bazel-bin/lazy.out
}

function test_unconsumed_downloads_of_built_target_are_not_fetched() {
  # Two downloads are declared; only one is consumed by the registered action.
  # The unconsumed one points at a dead address.
  echo "the good file" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  cat > variants.bzl <<'EOF'
def _impl(ctx):
    downloaded = ctx.downloads["good.txt"]
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.run_shell(
        inputs = [downloaded],
        outputs = [out],
        command = "cp '{}' '{}'".format(downloaded.path, out.path),
    )
    return [DefaultInfo(files = depset([out]))]

def _downloads(ctx):
    ctx.download(
        path = "good.txt",
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
    )
    ctx.download(
        path = "bad.txt",
        urls = ["http://127.0.0.1:1/unreachable.txt"],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )

variants = rule(
    implementation = _impl,
    attrs = {
        "url": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)
EOF
  cat > BUILD <<EOF
load("//:variants.bzl", "variants")

variants(
    name = "variants",
    url = "http://127.0.0.1:$nc_port/payload.txt",
    integrity = "$integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:variants >& $TEST_log \
    || fail "expected build to succeed fetching only the consumed download"
  assert_contains "the good file" bazel-bin/variants.out
}

function test_integrity_mismatch_fails() {
  echo "actual content" > payload.txt
  serve_file payload.txt

  write_fetch_rule
  # Integrity of different content.
  echo "expected content" > expected.txt
  local wrong_integrity="$(sri_sha256 expected.txt)"
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:$nc_port/payload.txt"],
    integrity = "$wrong_integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:fetched >& $TEST_log \
    && fail "expected build to fail on integrity mismatch"
  expect_log "Checksum was sha256-.* but wanted sha256-"
}

function test_download_cache_enables_offline_rebuild() {
  echo "cache me" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  write_fetch_rule
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:$nc_port/payload.txt"],
    integrity = "$integrity",
)
EOF

  # Populate the content-addressed download cache.
  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache="$repo_cache_dir" \
      //:fetched >& $TEST_log || fail "expected online build to succeed"

  # Now rebuild from scratch with the server gone: the download action must be
  # satisfied entirely from the cache.
  shutdown_server
  bazel clean --expunge >& $TEST_log
  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache="$repo_cache_dir" \
      //:fetched >& $TEST_log \
    || fail "expected offline rebuild to succeed from the download cache"
  assert_contains "cache me" bazel-bin/fetched.out
}

function test_url_fallback_to_mirror() {
  # The first URL is dead; the download manager must fall back to the mirror.
  echo "mirrored content" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  write_fetch_rule
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = [
        "http://127.0.0.1:1/payload.txt",
        "http://127.0.0.1:$nc_port/payload.txt",
    ],
    integrity = "$integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:fetched >& $TEST_log \
    || fail "expected build to succeed via the mirror URL"
  assert_contains "mirrored content" bazel-bin/fetched.out
}

function test_changing_urls_does_not_invalidate() {
  # The integrity checksum is the identity of a download; urls are acquisition
  # hints excluded from the action key. Changing them must not re-execute an
  # already resolved download.
  echo "url change is free" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  write_fetch_rule
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:$nc_port/payload.txt"],
    integrity = "$integrity",
)
EOF

  # The download cache is disabled throughout so that only the action cache
  # can satisfy the rebuild.
  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache= //:fetched >& $TEST_log \
    || fail "expected online build to succeed"

  # Point the declaration at a dead address and take the network away. The
  # rebuild must be satisfied by the action cache without executing anything.
  shutdown_server
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:1/payload.txt"],
    integrity = "$integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache= //:fetched >& $TEST_log \
    || fail "expected rebuild with changed urls to succeed without re-downloading"
  assert_contains "url change is free" bazel-bin/fetched.out
}

function test_canonical_id_restricts_cache_reuse() {
  echo "canonically yours" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  write_fetch_rule
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:$nc_port/payload.txt"],
    integrity = "$integrity",
    canonical_id = "id-one",
)
EOF

  # Populate the download cache under canonical ID "id-one".
  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache="$repo_cache_dir" \
      //:fetched >& $TEST_log || fail "expected online build to succeed"

  # Same canonical ID: the offline rebuild is a cache hit.
  shutdown_server
  bazel clean --expunge >& $TEST_log
  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache="$repo_cache_dir" \
      //:fetched >& $TEST_log \
    || fail "expected offline rebuild with matching canonical ID to succeed"
  assert_contains "canonically yours" bazel-bin/fetched.out

  # A different canonical ID must refuse the cache entry and hit the (dead)
  # network.
  sed -i 's/id-one/id-two/' BUILD
  bazel build $LAZY_DOWNLOADS_FLAG --repository_cache="$repo_cache_dir" \
      //:fetched >& $TEST_log \
    && fail "expected build with mismatched canonical ID to fail offline"
  expect_log "failed to download"
}

function test_offline_mode_reports_actionable_error() {
  write_fetch_rule
  cat > BUILD <<'EOF'
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:1/payload.txt"],
    integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
)
EOF

  # With downloads disabled and a cold cache the failure must name the cause
  # rather than surface a connection error.
  bazel build $LAZY_DOWNLOADS_FLAG --repository_disable_download \
      --repository_cache="$repo_cache_dir" //:fetched >& $TEST_log \
    && fail "expected build to fail with downloads disabled"
  expect_log "download is disabled"
}

function test_executable_download() {
  cat > tool.sh <<'EOF'
#!/bin/sh
echo "ran the downloaded tool"
EOF
  local integrity="$(sri_sha256 tool.sh)"
  serve_file tool.sh

  cat > tool.bzl <<'EOF'
def _impl(ctx):
    tool = ctx.downloads["tool.sh"]
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.run_shell(
        inputs = [tool],
        outputs = [out],
        command = "'{}' > '{}'".format(tool.path, out.path),
    )
    return [DefaultInfo(files = depset([out]))]

def _downloads(ctx):
    ctx.download(
        path = "tool.sh",
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
        executable = True,
    )

run_downloaded_tool = rule(
    implementation = _impl,
    attrs = {
        "url": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)
EOF
  cat > BUILD <<EOF
load("//:tool.bzl", "run_downloaded_tool")

run_downloaded_tool(
    name = "tool_runner",
    url = "http://127.0.0.1:$nc_port/tool.sh",
    integrity = "$integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:tool_runner >& $TEST_log \
    || fail "expected build to succeed"
  assert_contains "ran the downloaded tool" bazel-bin/tool_runner.out
}

function test_configurable_attr_not_accessible_in_downloads_callback() {
  cat > bad.bzl <<'EOF'
def _impl(ctx):
    return [DefaultInfo()]

def _downloads(ctx):
    ctx.download(
        path = "file.txt",
        urls = [ctx.attr.url],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )

bad_rule = rule(
    implementation = _impl,
    attrs = {
        # Deliberately configurable (the default).
        "url": attr.string(mandatory = True),
    },
    downloads = _downloads,
)
EOF
  cat > BUILD <<'EOF'
load("//:bad.bzl", "bad_rule")

bad_rule(
    name = "bad",
    url = "http://127.0.0.1:1/file.txt",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:bad >& $TEST_log \
    && fail "expected analysis to fail"
  expect_log "no such attribute 'url'"
  expect_log "configurable"
}

function test_duplicate_download_path_fails() {
  cat > dup.bzl <<'EOF'
def _impl(ctx):
    return [DefaultInfo()]

def _downloads(ctx):
    for _ in range(2):
        ctx.download(
            path = "file.txt",
            urls = ["http://127.0.0.1:1/file.txt"],
            integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
        )

dup_rule = rule(
    implementation = _impl,
    downloads = _downloads,
)
EOF
  cat > BUILD <<'EOF'
load("//:dup.bzl", "dup_rule")

dup_rule(name = "dup")
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:dup >& $TEST_log \
    && fail "expected analysis to fail"
  expect_log "download path 'file.txt' is declared more than once"
}

function test_download_path_prefix_conflict_fails() {
  cat > prefix.bzl <<'EOF'
def _impl(ctx):
    return [DefaultInfo()]

def _downloads(ctx):
    ctx.download(
        path = "dir",
        urls = ["http://127.0.0.1:1/dir"],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )
    ctx.download(
        path = "dir/file.txt",
        urls = ["http://127.0.0.1:1/file.txt"],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )

prefix_rule = rule(
    implementation = _impl,
    downloads = _downloads,
)
EOF
  cat > BUILD <<'EOF'
load("//:prefix.bzl", "prefix_rule")

prefix_rule(name = "prefix")
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:prefix >& $TEST_log \
    && fail "expected analysis to fail"
  expect_log "download path 'dir/file.txt' conflicts with download path 'dir'"
}

function test_multi_checksum_integrity_fails_at_analysis() {
  cat > multi.bzl <<'EOF'
def _impl(ctx):
    return [DefaultInfo()]

def _downloads(ctx):
    ctx.download(
        path = "file.txt",
        urls = ["http://127.0.0.1:1/file.txt"],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE= sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )

multi_rule = rule(
    implementation = _impl,
    downloads = _downloads,
)
EOF
  cat > BUILD <<'EOF'
load("//:multi.bzl", "multi_rule")

multi_rule(name = "multi")
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:multi >& $TEST_log \
    && fail "expected analysis to fail"
  expect_log "exactly one checksum must be given"
}

function test_invalid_integrity_fails_at_analysis() {
  cat > invalid.bzl <<'EOF'
def _impl(ctx):
    return [DefaultInfo()]

def _downloads(ctx):
    ctx.download(
        path = "file.txt",
        urls = ["http://127.0.0.1:1/file.txt"],
        integrity = "md5-abcdef",
    )

invalid_rule = rule(
    implementation = _impl,
    downloads = _downloads,
)
EOF
  cat > BUILD <<'EOF'
load("//:invalid.bzl", "invalid_rule")

invalid_rule(name = "invalid")
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:invalid >& $TEST_log \
    && fail "expected analysis to fail"
  expect_log "unsupported algorithm 'md5'"
}

function test_aquery_reports_download_details() {
  write_fetch_rule
  cat > BUILD <<'EOF'
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = [
        "http://127.0.0.1:1/payload.txt",
        "http://127.0.0.1:2/mirror/payload.txt",
    ],
    integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    canonical_id = "aquery-canonical-id",
)
EOF

  # No download is executed by aquery; the details come from analysis alone.
  bazel aquery $LAZY_DOWNLOADS_FLAG 'mnemonic("Download", //:fetched)' \
      >& $TEST_log || fail "expected aquery to succeed"
  expect_log "Mnemonic: Download"
  expect_log "http://127.0.0.1:1/payload.txt"
  expect_log "http://127.0.0.1:2/mirror/payload.txt"
  expect_log "Integrity: sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE="
  expect_log "CanonicalId: aquery-canonical-id"
  expect_log "IsExecutable: false"

  bazel aquery $LAZY_DOWNLOADS_FLAG --output=textproto \
      'mnemonic("Download", //:fetched)' >& $TEST_log \
    || fail "expected proto aquery to succeed"
  expect_log 'download_urls: "http://127.0.0.1:1/payload.txt"'
  expect_log 'download_urls: "http://127.0.0.1:2/mirror/payload.txt"'
  expect_log 'download_integrity: "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE="'
  expect_log 'download_canonical_id: "aquery-canonical-id"'
}

# Prints the hex sha256 digest of the given file (the download store key).
function hex_sha256() {
  python3 -c "
import hashlib, sys
print(hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())
" "$1"
}

function test_download_artifacts_use_config_free_root() {
  write_fetch_rule
  cat > BUILD <<'EOF'
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:1/payload.txt"],
    integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
)
EOF

  # Download artifacts live under the configuration-free root bazel-out/downloads,
  # derived from the declaring label plus the declared path only.
  bazel aquery $LAZY_DOWNLOADS_FLAG 'mnemonic("Download", //:fetched)' \
      >& $TEST_log || fail "expected aquery to succeed"
  expect_log "Outputs: \[bazel-out/downloads/fetched/payload.txt\]"
}

function test_download_shared_across_configurations() {
  echo "shared across configurations" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  write_fetch_rule
  # //:fetched is built in the target configuration directly and in the exec
  # configuration as a genrule tool. Both configured targets register the same
  # Download action at the same configuration-free exec path; shared-action
  # deduplication must accept this (an action conflict fails the build).
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:$nc_port/payload.txt"],
    integrity = "$integrity",
)

genrule(
    name = "use_exec",
    outs = ["use_exec.out"],
    cmd = "cat \$(location :fetched) > \$@",
    tools = [":fetched"],
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:fetched //:use_exec >& $TEST_log \
    || fail "expected build with the download in two configurations to succeed"
  assert_contains "shared across configurations" bazel-bin/fetched.out
  assert_contains "shared across configurations" bazel-bin/use_exec.out
}

function test_download_runfiles_placement() {
  # A download artifact in runfiles must appear at the conventional location,
  # <workspace>/<package>/<target name>/<declared path>, like any other
  # derived file — not under a doubled or repo-mangled prefix.
  echo "present in runfiles" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  mkdir -p pkg
  cat > pkg/defs.bzl <<'EOF'
def _dl_impl(ctx):
    f = ctx.downloads["sub/payload.txt"]
    return [DefaultInfo(files = depset([f]), runfiles = ctx.runfiles(files = [f]))]

def _downloads(ctx):
    ctx.download(
        path = "sub/payload.txt",
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
    )

dl = rule(
    implementation = _dl_impl,
    attrs = {
        "url": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)

def _bin_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(out, "#!/bin/sh\n", is_executable = True)
    rf = ctx.runfiles(files = ctx.files.dep).merge(
        ctx.attr.dep[DefaultInfo].default_runfiles,
    )
    return [DefaultInfo(executable = out, runfiles = rf)]

bin = rule(implementation = _bin_impl, executable = True, attrs = {"dep": attr.label()})
EOF
  cat > pkg/BUILD <<EOF
load("//pkg:defs.bzl", "bin", "dl")

dl(
    name = "dltarget",
    url = "http://127.0.0.1:$nc_port/payload.txt",
    integrity = "$integrity",
)

bin(
    name = "runner",
    dep = ":dltarget",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG --enable_runfiles //pkg:runner >& $TEST_log \
    || fail "expected build to succeed"
  assert_contains "present in runfiles" \
      "bazel-bin/pkg/runner.runfiles/_main/pkg/dltarget/sub/payload.txt"
}

function test_sibling_repository_layout() {
  # Under --experimental_sibling_repository_layout the download root mirrors
  # how the configured roots move: bazel-out/downloads for the main repository,
  # bazel-out/<repo>/downloads for external repositories. Runfiles placement
  # must be identical to the default layout.
  echo "sibling layout payload" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  serve_file payload.txt

  mkdir -p m
  cat > m/MODULE.bazel <<'EOF'
module(name = "m")
EOF
  cat > m/fetch.bzl <<'EOF'
def _impl(ctx):
    f = ctx.downloads["payload.txt"]
    return [DefaultInfo(files = depset([f]), runfiles = ctx.runfiles(files = [f]))]

def _downloads(ctx):
    ctx.download(
        path = "payload.txt",
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
    )

fetch_file = rule(
    implementation = _impl,
    attrs = {
        "url": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)
EOF
  cat > m/BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    url = "http://127.0.0.1:$nc_port/payload.txt",
    integrity = "$integrity",
    visibility = ["//visibility:public"],
)
EOF
  cat > MODULE.bazel <<'EOF'
bazel_dep(name = "m")
local_path_override(module_name = "m", path = "m")
EOF
  mkdir -p pkg
  cat > pkg/defs.bzl <<'EOF'
def _bin_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(out, "#!/bin/sh\n", is_executable = True)
    rf = ctx.runfiles(files = ctx.files.dep).merge(
        ctx.attr.dep[DefaultInfo].default_runfiles,
    )
    return [DefaultInfo(executable = out, runfiles = rf)]

bin = rule(implementation = _bin_impl, executable = True, attrs = {"dep": attr.label()})
EOF
  cat > pkg/BUILD <<'EOF'
load("//pkg:defs.bzl", "bin")

bin(
    name = "runner",
    dep = "@m//:fetched",
)
EOF
  touch BUILD

  bazel aquery $LAZY_DOWNLOADS_FLAG --experimental_sibling_repository_layout \
      'mnemonic("Download", @m//:fetched)' >& $TEST_log \
    || fail "expected aquery to succeed"
  expect_log "Outputs: \[bazel-out/m+/downloads/fetched/payload.txt\]"

  bazel build $LAZY_DOWNLOADS_FLAG --experimental_sibling_repository_layout \
      --enable_runfiles //pkg:runner >& $TEST_log \
    || fail "expected sibling-layout build to succeed"
  assert_contains "sibling layout payload" \
      "bazel-bin/pkg/runner.runfiles/m+/fetched/payload.txt"
}

function test_vendor_dir_resolution() {
  # A hand-populated content-addressed store must satisfy the download without
  # any network: the URL is dead and the download cache is disabled.
  echo "from the vendor store" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  local hex="$(hex_sha256 payload.txt)"
  mkdir -p vdir/downloads/sha256
  cp payload.txt "vdir/downloads/sha256/$hex"

  write_fetch_rule
  cat > BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:1/payload.txt"],
    integrity = "$integrity",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG --vendor_dir=vdir --repository_cache= \
      //:fetched >& $TEST_log \
    || fail "expected build to succeed from the vendor download store"
  assert_contains "from the vendor store" bazel-bin/fetched.out
}

function test_vendor_targets_vendors_consumed_set_only() {
  # Consumed-set (configured) semantics: `bazel vendor <targets>` fetches
  # exactly what a build of the targets would fetch. The unconsumed download
  # points at a dead address and must be neither fetched nor vendored.
  echo "the good file" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  local good_hex="$(hex_sha256 payload.txt)"
  serve_file payload.txt

  cat > variants.bzl <<'EOF'
def _impl(ctx):
    downloaded = ctx.downloads["good.txt"]
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.run_shell(
        inputs = [downloaded],
        outputs = [out],
        command = "cp '{}' '{}'".format(downloaded.path, out.path),
    )
    return [DefaultInfo(files = depset([out]))]

def _downloads(ctx):
    ctx.download(
        path = "good.txt",
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
    )
    ctx.download(
        path = "bad.txt",
        urls = ["http://127.0.0.1:1/unreachable.txt"],
        integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
    )

variants = rule(
    implementation = _impl,
    attrs = {
        "url": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)
EOF
  cat > BUILD <<EOF
load("//:variants.bzl", "variants")

variants(
    name = "variants",
    url = "http://127.0.0.1:$nc_port/payload.txt",
    integrity = "$integrity",
)
EOF

  bazel vendor $LAZY_DOWNLOADS_FLAG --vendor_dir=vdir //:variants >& $TEST_log \
    || fail "expected vendoring the target to succeed"

  [[ -f "vdir/downloads/sha256/$good_hex" ]] \
    || fail "expected the consumed download to be vendored"
  local bad_hex="$(python3 -c "
import base64
print(base64.b64decode('C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=').hex())
")"
  [[ ! -e "vdir/downloads/sha256/$bad_hex" ]] \
    || fail "expected the unconsumed download not to be vendored"
  assert_contains "//:variants" vdir/downloads/MANIFEST
  assert_contains "payload.txt" vdir/downloads/MANIFEST

  # The vendored store must satisfy a fully offline build from scratch.
  shutdown_server
  bazel clean --expunge >& $TEST_log
  bazel build $LAZY_DOWNLOADS_FLAG --vendor_dir=vdir --repository_cache= \
      //:variants >& $TEST_log \
    || fail "expected offline build from the vendored store to succeed"
  assert_contains "the good file" bazel-bin/variants.out
}

# Note: no-argument `bazel vendor` (declared-set semantics over the main repo
# and the whole module graph) is not tested here because it inherently fetches
# every repository of the module graph — including this test environment's
# implicit module dependencies, whose extensions (e.g. maven) require
# unsandboxable network access. Its download collection shares the code path
# exercised by test_vendor_repo_vendors_declared_set_of_repo.

function test_vendor_repo_vendors_declared_set_of_repo() {
  # Declared-set semantics scoped to one repo: `bazel vendor --repo=@m`
  # vendors the downloads declared by targets defined in @m.
  echo "from the module repo" > payload.txt
  local integrity="$(sri_sha256 payload.txt)"
  local hex="$(hex_sha256 payload.txt)"
  serve_file payload.txt

  mkdir -p m
  cat > m/MODULE.bazel <<'EOF'
module(name = "m")
EOF
  cat > m/fetch.bzl <<'EOF'
def _impl(ctx):
    return [DefaultInfo(files = depset([ctx.downloads["payload.txt"]]))]

def _downloads(ctx):
    ctx.download(
        path = "payload.txt",
        urls = [ctx.attr.url],
        integrity = ctx.attr.integrity,
    )

fetch_file = rule(
    implementation = _impl,
    attrs = {
        "url": attr.string(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)
EOF
  cat > m/BUILD <<EOF
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    url = "http://127.0.0.1:$nc_port/payload.txt",
    integrity = "$integrity",
)
EOF
  cat > MODULE.bazel <<'EOF'
bazel_dep(name = "m")
local_path_override(module_name = "m", path = "m")
EOF
  touch BUILD

  bazel vendor $LAZY_DOWNLOADS_FLAG --vendor_dir=vdir --repo=@m >& $TEST_log \
    || fail "expected vendoring the repo to succeed"
  [[ -f "vdir/downloads/sha256/$hex" ]] \
    || fail "expected the repo's declared download to be vendored"
  assert_contains "@@.*//:fetched" vdir/downloads/MANIFEST
}

function test_downloads_parameter_requires_flag() {
  write_fetch_rule
  cat > BUILD <<'EOF'
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = ["http://127.0.0.1:1/payload.txt"],
    integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
)
EOF

  bazel build //:fetched >& $TEST_log \
    && fail "expected loading to fail without $LAZY_DOWNLOADS_FLAG"
  expect_log "experimental_lazy_downloads"
}

function test_select_on_nonconfigurable_attr_fails() {
  write_fetch_rule
  cat > BUILD <<'EOF'
load("//:fetch.bzl", "fetch_file")

fetch_file(
    name = "fetched",
    path = "payload.txt",
    urls = select({
        "//conditions:default": ["http://127.0.0.1:1/payload.txt"],
    }),
    integrity = "sha256-C60QAIWuqTvUgy/l8DEDBzHqEyhLcNqlnLwT07mrrGE=",
)
EOF

  bazel build $LAZY_DOWNLOADS_FLAG //:fetched >& $TEST_log \
    && fail "expected loading to fail"
  expect_log "attribute \"urls\" is not configurable"
}

run_suite "lazy downloads tests"
