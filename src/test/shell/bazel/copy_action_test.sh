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
# Integration tests for the experimental ctx.actions.copy() API.

set -euo pipefail

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

#### SETUP #############################################################

add_to_bazelrc "build --experimental_copy_action"
add_to_bazelrc "build --allow_unresolved_symlinks"

# Writes pkg/rules.bzl with rules exercising each supported copy combination.
function setup_copy_rules() {
  mkdir -p pkg
  cat > pkg/rules.bzl <<'EOF'
def _copy_file_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.out)
    ctx.actions.copy(input = ctx.file.src, output = out)
    return DefaultInfo(files = depset([out]))

copy_file = rule(
    implementation = _copy_file_impl,
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
        "out": attr.string(mandatory = True),
    },
)

def _write_executable_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(out, "#!/bin/sh\nexit 0\n", is_executable = True)
    return DefaultInfo(files = depset([out]))

write_executable = rule(implementation = _write_executable_impl)

def _make_tree_impl(ctx):
    tree = ctx.actions.declare_directory(ctx.label.name + ".dir")
    ctx.actions.run_shell(
        outputs = [tree],
        command = """
            mkdir -p $1/sub
            echo top > $1/top.txt
            echo nested > $1/sub/nested.txt
        """,
        arguments = [tree.path],
    )
    return DefaultInfo(files = depset([tree]))

make_tree = rule(implementation = _make_tree_impl)

def _copy_tree_impl(ctx):
    src = ctx.attr.src[DefaultInfo].files.to_list()[0]
    out = ctx.actions.declare_directory(ctx.attr.out)
    ctx.actions.copy(input = src, output = out)
    return DefaultInfo(files = depset([out]))

copy_tree = rule(
    implementation = _copy_tree_impl,
    attrs = {
        "src": attr.label(mandatory = True),
        "out": attr.string(mandatory = True),
    },
)

def _make_symlink_impl(ctx):
    link = ctx.actions.declare_symlink(ctx.label.name + ".link")
    ctx.actions.symlink(output = link, target_path = ctx.attr.target)
    return DefaultInfo(files = depset([link]))

make_symlink = rule(
    implementation = _make_symlink_impl,
    attrs = {"target": attr.string(mandatory = True)},
)

def _make_symlink_spawn_impl(ctx):
    link = ctx.actions.declare_symlink(ctx.label.name + ".link")
    ctx.actions.run_shell(
        outputs = [link],
        command = "ln -s non/existent/target $1",
        arguments = [link.path],
    )
    return DefaultInfo(files = depset([link]))

make_symlink_spawn = rule(implementation = _make_symlink_spawn_impl)

def _copy_symlink_impl(ctx):
    src = ctx.attr.src[DefaultInfo].files.to_list()[0]
    out = ctx.actions.declare_symlink(ctx.attr.out)
    ctx.actions.copy(input = src, output = out)
    return DefaultInfo(files = depset([out]))

copy_symlink = rule(
    implementation = _copy_symlink_impl,
    attrs = {
        "src": attr.label(mandatory = True),
        "out": attr.string(mandatory = True),
    },
)

def _copy_file_to_directory_impl(ctx):
    out = ctx.actions.declare_directory(ctx.attr.out)
    ctx.actions.copy(input = ctx.file.src, output = out)
    return DefaultInfo(files = depset([out]))

copy_file_to_directory = rule(
    implementation = _copy_file_to_directory_impl,
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
        "out": attr.string(mandatory = True),
    },
)

def _copy_file_to_symlink_impl(ctx):
    out = ctx.actions.declare_symlink(ctx.attr.out)
    ctx.actions.copy(input = ctx.file.src, output = out)
    return DefaultInfo(files = depset([out]))

copy_file_to_symlink = rule(
    implementation = _copy_file_to_symlink_impl,
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
        "out": attr.string(mandatory = True),
    },
)

def _extract_file_impl(ctx):
    src = ctx.attr.src[DefaultInfo].files.to_list()[0]
    out = ctx.actions.declare_file(ctx.attr.out)
    ctx.actions.copy(input = src, output = out, path = ctx.attr.path)
    return DefaultInfo(files = depset([out]))

extract_file = rule(
    implementation = _extract_file_impl,
    attrs = {
        "src": attr.label(mandatory = True),
        "out": attr.string(mandatory = True),
        "path": attr.string(mandatory = True),
    },
)

def _extract_from_file_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.out)
    ctx.actions.copy(input = ctx.file.src, output = out, path = ctx.attr.path)
    return DefaultInfo(files = depset([out]))

extract_from_file = rule(
    implementation = _extract_from_file_impl,
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
        "out": attr.string(mandatory = True),
        "path": attr.string(mandatory = True),
    },
)

def _use_copy_impl(ctx):
    copied = ctx.actions.declare_file(ctx.label.name + ".copy")
    ctx.actions.copy(input = ctx.file.src, output = copied)
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.run_shell(
        inputs = [copied],
        outputs = [out],
        command = "cat $1 $1 > $2",
        arguments = [copied.path, out.path],
    )
    return DefaultInfo(files = depset([out]))

use_copy = rule(
    implementation = _use_copy_impl,
    attrs = {
        "src": attr.label(mandatory = True, allow_single_file = True),
    },
)
EOF
}

#### TESTS #############################################################

function test_copy_unavailable_without_flag() {
  setup_copy_rules
  echo "hello" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file")

copy_file(
    name = "copy",
    src = "input.txt",
    out = "output.txt",
)
EOF

  bazel build --experimental_copy_action=false //pkg:copy >& $TEST_log \
    && fail "build should have failed without --experimental_copy_action"
  expect_log "no field or method 'copy'"
}

function test_copy_source_file() {
  setup_copy_rules
  echo "hello copy" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file")

copy_file(
    name = "copy",
    src = "input.txt",
    out = "output.txt",
)
EOF

  bazel build //pkg:copy >& $TEST_log || fail "build failed"
  diff pkg/input.txt bazel-bin/pkg/output.txt \
    || fail "copied file content differs from input"
  if [[ -L bazel-bin/pkg/output.txt ]]; then
    fail "expected a regular file, got a symlink"
  fi
}

function test_copy_generated_file_preserves_executable_bit() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file", "write_executable")

write_executable(name = "script")

copy_file(
    name = "copy",
    src = ":script",
    out = "script_copy.sh",
)
EOF

  bazel build //pkg:copy >& $TEST_log || fail "build failed"
  diff bazel-bin/pkg/script.sh bazel-bin/pkg/script_copy.sh \
    || fail "copied file content differs from input"
  test -x bazel-bin/pkg/script_copy.sh \
    || fail "executable bit not preserved by copy"
}

function test_copy_file_incrementality() {
  setup_copy_rules
  echo "version 1" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file")

copy_file(
    name = "copy",
    src = "input.txt",
    out = "output.txt",
)
EOF

  bazel build //pkg:copy >& $TEST_log || fail "build failed"
  assert_equals "version 1" "$(cat bazel-bin/pkg/output.txt)"

  echo "version 2" > pkg/input.txt
  bazel build //pkg:copy >& $TEST_log || fail "incremental build failed"
  assert_equals "version 2" "$(cat bazel-bin/pkg/output.txt)"
}

function test_copy_directory() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_tree", "make_tree")

make_tree(name = "tree")

copy_tree(
    name = "copy",
    src = ":tree",
    out = "tree_copy",
)
EOF

  bazel build //pkg:tree //pkg:copy >& $TEST_log || fail "build failed"
  diff -r bazel-bin/pkg/tree.dir bazel-bin/pkg/tree_copy \
    || fail "copied directory content differs from input"
  assert_equals "top" "$(cat bazel-bin/pkg/tree_copy/top.txt)"
  assert_equals "nested" "$(cat bazel-bin/pkg/tree_copy/sub/nested.txt)"
}

function test_copy_unresolved_symlink() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_symlink", "make_symlink")

make_symlink(
    name = "link",
    target = "non/existent/target",
)

copy_symlink(
    name = "copy",
    src = ":link",
    out = "link_copy",
)
EOF

  bazel build //pkg:copy >& $TEST_log || fail "build failed"
  if [[ ! -L bazel-bin/pkg/link_copy ]]; then
    fail "expected 'bazel-bin/pkg/link_copy' to be a symlink"
  fi
  assert_equals "non/existent/target" "$(readlink bazel-bin/pkg/link_copy)"
}

function test_copy_file_to_directory_is_an_error() {
  setup_copy_rules
  echo "hello" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file_to_directory")

copy_file_to_directory(
    name = "copy",
    src = "input.txt",
    out = "output_dir",
)
EOF

  bazel build //pkg:copy >& $TEST_log \
    && fail "build should have failed for file -> directory copy"
  expect_log "copy() requires that \"input\" and \"output\" be of the same type"
  expect_log "\"input\" is a file and \"output\" was declared as a directory"
}

function test_copy_file_to_symlink_is_an_error() {
  setup_copy_rules
  echo "hello" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file_to_symlink")

copy_file_to_symlink(
    name = "copy",
    src = "input.txt",
    out = "output_link",
)
EOF

  bazel build //pkg:copy >& $TEST_log \
    && fail "build should have failed for file -> symlink copy"
  expect_log "copy() requires that \"input\" and \"output\" be of the same type"
  expect_log "\"input\" is a file and \"output\" was declared as a symlink"
}

function test_copy_path_extracts_file_from_directory() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "extract_file", "make_tree")

make_tree(name = "tree")

extract_file(
    name = "extract",
    src = ":tree",
    out = "extracted.txt",
    path = "sub/nested.txt",
)
EOF

  bazel build //pkg:extract >& $TEST_log || fail "build failed"
  assert_equals "nested" "$(cat bazel-bin/pkg/extracted.txt)"
}

function test_copy_path_missing_file_is_an_execution_error() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "extract_file", "make_tree")

make_tree(name = "tree")

extract_file(
    name = "extract",
    src = ":tree",
    out = "extracted.txt",
    path = "sub/missing.txt",
)
EOF

  bazel build //pkg:extract >& $TEST_log \
    && fail "build should have failed for a missing path"
  expect_log "failed to copy 'sub/missing.txt'"
  expect_log "no such file in the directory"
}

function test_copy_path_requires_directory_input() {
  setup_copy_rules
  echo "hello" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "extract_from_file")

extract_from_file(
    name = "extract",
    src = "input.txt",
    out = "extracted.txt",
    path = "some/path",
)
EOF

  bazel build //pkg:extract >& $TEST_log \
    && fail "build should have failed for path with a file input"
  expect_log "copy() with \"path\" param requires that \"input\" be a directory"
}

function test_copy_path_rejects_uplevel_references() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "extract_file", "make_tree")

make_tree(name = "tree")

extract_file(
    name = "extract",
    src = ":tree",
    out = "extracted.txt",
    path = "../escape.txt",
)
EOF

  bazel build //pkg:extract >& $TEST_log \
    && fail "build should have failed for an uplevel path"
  expect_log "must be a non-empty relative path that does not escape"
}

function test_copy_path_in_aquery() {
  setup_copy_rules
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "extract_file", "make_tree")

make_tree(name = "tree")

extract_file(
    name = "extract",
    src = ":tree",
    out = "extracted.txt",
    path = "sub/nested.txt",
)
EOF

  bazel aquery 'mnemonic("Copy", //pkg:extract)' >& $TEST_log || fail "aquery failed"
  expect_log "Mnemonic: Copy"
  expect_log "CopyPath: sub/nested.txt"
}

function test_copy_output_consumed_by_downstream_action() {
  setup_copy_rules
  echo "once" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "use_copy")

use_copy(
    name = "use",
    src = "input.txt",
)
EOF

  bazel build //pkg:use >& $TEST_log || fail "build failed"
  assert_equals "once
once" "$(cat bazel-bin/pkg/use.out)"
}

function test_copy_is_metadata_only_under_build_without_the_bytes() {
  setup_copy_rules
  local cache_dir="${TEST_TMPDIR}/copy_bwob_cache"
  rm -rf "$cache_dir"
  echo "remote content" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file")

genrule(
    name = "gen",
    srcs = ["input.txt"],
    outs = ["gen.txt"],
    cmd = "cat $(location input.txt) $(location input.txt) > $@",
)

copy_file(
    name = "copy",
    src = ":gen",
    out = "copy.txt",
)
EOF

  # Prime the disk cache with the generating action's result.
  bazel build --disk_cache="$cache_dir" //pkg:copy >& $TEST_log || fail "build failed"

  # Re-execute from a cold state: the generating action is a disk cache hit whose
  # output is not downloaded, and the copy must complete without downloading it
  # or materializing its own output.
  bazel clean --expunge >& $TEST_log
  bazel build --disk_cache="$cache_dir" --remote_download_minimal //pkg:copy >& $TEST_log \
    || fail "build failed"
  if [[ -f bazel-bin/pkg/gen.txt ]]; then
    fail "the copy's input should not have been downloaded"
  fi
  if [[ -f bazel-bin/pkg/copy.txt ]]; then
    fail "the copy's output should not have been materialized"
  fi

  # The output materializes on demand, with the input's content.
  bazel build --disk_cache="$cache_dir" --remote_download_toplevel //pkg:copy >& $TEST_log \
    || fail "build failed"
  assert_equals "remote content
remote content" "$(cat bazel-bin/pkg/copy.txt)"
  if [[ -f bazel-bin/pkg/gen.txt ]]; then
    fail "the copy's input should still not be materialized"
  fi
}

function test_copy_directory_is_metadata_only_under_build_without_the_bytes() {
  setup_copy_rules
  local cache_dir="${TEST_TMPDIR}/copy_tree_bwob_cache"
  rm -rf "$cache_dir"
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_tree", "extract_file", "make_tree")

make_tree(name = "tree")

copy_tree(
    name = "copy",
    src = ":tree",
    out = "tree_copy",
)

extract_file(
    name = "extract",
    src = ":tree",
    out = "extracted.txt",
    path = "sub/nested.txt",
)
EOF

  # Prime the disk cache with the tree-generating action's result.
  bazel build --disk_cache="$cache_dir" //pkg:copy //pkg:extract >& $TEST_log \
    || fail "build failed"

  # Re-execute from a cold state under Build without the Bytes.
  bazel clean --expunge >& $TEST_log
  bazel build --disk_cache="$cache_dir" --remote_download_minimal //pkg:copy //pkg:extract \
    >& $TEST_log || fail "build failed"
  # Note: empty directory skeletons are expected (Bazel pre-creates tree artifact
  # directories); only materialized files indicate downloaded bytes.
  if [[ -n "$(find -L bazel-bin/pkg/tree.dir -type f 2>/dev/null)" ]]; then
    fail "the copy's input tree should not have been downloaded"
  fi
  if [[ -n "$(find -L bazel-bin/pkg/tree_copy -type f 2>/dev/null)" ]]; then
    fail "the copied tree's contents should not have been materialized"
  fi
  if [[ -f bazel-bin/pkg/extracted.txt ]]; then
    fail "the extracted file should not have been materialized"
  fi

  # The outputs materialize on demand, with the input's content.
  bazel build --disk_cache="$cache_dir" --remote_download_toplevel //pkg:copy //pkg:extract \
    >& $TEST_log || fail "build failed"
  assert_equals "top" "$(cat bazel-bin/pkg/tree_copy/top.txt)"
  assert_equals "nested" "$(cat bazel-bin/pkg/tree_copy/sub/nested.txt)"
  assert_equals "nested" "$(cat bazel-bin/pkg/extracted.txt)"
}

function test_copy_symlink_under_build_without_the_bytes() {
  setup_copy_rules
  local cache_dir="${TEST_TMPDIR}/copy_symlink_bwob_cache"
  rm -rf "$cache_dir"
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_symlink", "make_symlink_spawn")

make_symlink_spawn(name = "link")

copy_symlink(
    name = "copy",
    src = ":link",
    out = "link_copy",
)
EOF

  # Prime the disk cache with the symlink-generating spawn's result.
  bazel build --disk_cache="$cache_dir" //pkg:copy >& $TEST_log || fail "build failed"

  # Re-execute from a cold state under Build without the Bytes: the copy reads the
  # target from tracked metadata, not from the input's materialization.
  bazel clean --expunge >& $TEST_log
  bazel build --disk_cache="$cache_dir" --remote_download_minimal //pkg:copy >& $TEST_log \
    || fail "build failed"

  bazel build --disk_cache="$cache_dir" --remote_download_toplevel //pkg:copy >& $TEST_log \
    || fail "build failed"
  if [[ ! -L bazel-bin/pkg/link_copy ]]; then
    fail "expected 'bazel-bin/pkg/link_copy' to be a symlink"
  fi
  assert_equals "non/existent/target" "$(readlink bazel-bin/pkg/link_copy)"
}

function test_copy_recovers_from_remote_cache_eviction() {
  setup_copy_rules
  local cache_dir="${TEST_TMPDIR}/copy_eviction_cache"
  rm -rf "$cache_dir"
  echo "evictable content" > pkg/input.txt
  echo "v1" > pkg/stamp.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file")

genrule(
    name = "gen",
    srcs = ["input.txt"],
    outs = ["gen.txt"],
    cmd = "cat $(location input.txt) > $@",
)

copy_file(
    name = "copy",
    src = ":gen",
    out = "copy.txt",
)

genrule(
    name = "use",
    srcs = [
        ":copy",
        "stamp.txt",
    ],
    outs = ["use.txt"],
    cmd = "cat $(SRCS) > $@",
)
EOF

  # Prime the disk cache.
  bazel build --disk_cache="$cache_dir" //pkg:use >& $TEST_log || fail "build failed"

  # Cold minimal build: all spawns are disk cache hits and the copy is metadata-only.
  bazel clean --expunge >& $TEST_log
  bazel build --disk_cache="$cache_dir" --remote_download_minimal --rewind_lost_inputs \
    //pkg:use >& $TEST_log || fail "build failed"

  # Evict everything from the disk cache and invalidate only the consumer. Its
  # input is the copy's output, whose bytes no longer exist anywhere; recovery
  # must attribute the loss through the copy to the generating action.
  rm -rf "$cache_dir"/cas "$cache_dir"/ac
  echo "v2" > pkg/stamp.txt
  bazel build --disk_cache="$cache_dir" --remote_download_minimal --rewind_lost_inputs \
    //pkg:use >& $TEST_log || fail "build failed to recover from eviction"

  bazel build --disk_cache="$cache_dir" --remote_download_toplevel //pkg:use >& $TEST_log \
    || fail "build failed"
  assert_equals "evictable content
v2" "$(cat bazel-bin/pkg/use.txt)"
}

function test_copy_action_in_aquery() {
  setup_copy_rules
  echo "hello" > pkg/input.txt
  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "copy_file")

copy_file(
    name = "copy",
    src = "input.txt",
    out = "output.txt",
)
EOF

  bazel aquery 'mnemonic("Copy", //pkg:copy)' >& $TEST_log || fail "aquery failed"
  expect_log "Mnemonic: Copy"
  expect_log "pkg/input.txt"
  expect_log "pkg/output.txt"
}

run_suite "ctx.actions.copy() tests"
