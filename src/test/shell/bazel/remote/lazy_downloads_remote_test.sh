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
# Tests the lazy downloads API against remote execution and the Remote Asset
# API: with Build without the Bytes, download content must reach the remote
# CAS through the Fetch service without ever being materialized locally.

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
source "$(rlocation "io_bazel/src/test/shell/bazel/remote/remote_utils.sh")" \
  || { echo "remote_utils.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote_helpers.sh")" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }

LAZY_DOWNLOADS_FLAG="--experimental_lazy_downloads"

function set_up() {
  start_worker
}

function tear_down() {
  shutdown_server
  bazel clean >& $TEST_log
  stop_worker
}

# Prints the Subresource Integrity checksum (sha256) of the given file.
function sri_sha256() {
  python3 -c "
import base64, hashlib, sys
digest = hashlib.sha256(open(sys.argv[1], 'rb').read()).digest()
print('sha256-' + base64.b64encode(digest).decode())
" "$1"
}

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
    )

fetch_file = rule(
    implementation = _impl,
    attrs = {
        "path": attr.string(mandatory = True, configurable = False),
        "urls": attr.string_list(mandatory = True, configurable = False),
        "integrity": attr.string(mandatory = True, configurable = False),
    },
    downloads = _downloads,
)
EOF
}

function test_digest_only_download_under_build_without_the_bytes() {
  echo "never touched the local machine" > payload.txt
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

  # The Fetch service downloads into the CAS and returns a digest; the
  # consuming action executes remotely against that digest. Neither the
  # download nor the action output may be materialized locally.
  bazel build $LAZY_DOWNLOADS_FLAG \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_downloader=grpc://localhost:${worker_port} \
      --remote_download_outputs=minimal \
      //:fetched >& $TEST_log \
    || fail "expected build with digest-only downloads to succeed"

  [[ ! -e "bazel-out/downloads/fetched/payload.txt" ]] \
    || fail "expected the download not to be materialized locally"

  # Flipping to toplevel materializes the (cached) consuming action's output,
  # proving the content round-tripped through the CAS intact.
  bazel build $LAZY_DOWNLOADS_FLAG \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_downloader=grpc://localhost:${worker_port} \
      --remote_download_outputs=toplevel \
      //:fetched >& $TEST_log \
    || fail "expected toplevel rebuild to succeed"
  assert_contains "never touched the local machine" bazel-bin/fetched.out
}

function test_digest_only_falls_back_without_remote_downloader() {
  # Without a remote downloader, Build without the Bytes still works: the
  # download materializes locally and is uploaded like any other action input.
  echo "materialized and uploaded" > payload.txt
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

  bazel build $LAZY_DOWNLOADS_FLAG \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_download_outputs=toplevel \
      //:fetched >& $TEST_log \
    || fail "expected build without a remote downloader to succeed"
  assert_contains "materialized and uploaded" bazel-bin/fetched.out
}

run_suite "lazy downloads with remote execution tests"
