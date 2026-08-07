#!/usr/bin/env bash
#
# Copyright 2025 The Bazel Authors. All rights reserved.
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
# Integration tests for visibility checking of tests referenced by test_suite.
#
# Regression tests for https://github.com/bazelbuild/bazel/issues/XXXXX:
# bazel cquery checks visibility of test_suite test references, but bazel build
# and bazel test do not.
#
# The root cause: for build/test, test_suite targets are expanded in the target
# pattern phase (TargetPatternPhaseFunction) before analysis. The suite is
# replaced by its constituent tests, so the test_suite itself is never analyzed
# and visibility of the reference is never checked. cquery sets
# --noexpand_test_suites, so the test_suite IS analyzed and visibility IS
# checked.

# --- begin runfiles.bash initialization ---
set -euo pipefail
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

#### HELPER ################################################################

function write_test_and_suite() {
  # Creates two packages:
  #   //$1/testpkg:the_test  — an sh_test with the given visibility
  #   //$1/suitepkg:the_suite — a test_suite referencing the_test
  local -r prefix="$1"
  local -r visibility="$2"

  mkdir -p "${prefix}/testpkg" "${prefix}/suitepkg"

  cat > "${prefix}/testpkg/BUILD" <<EOF
sh_test(
    name = "the_test",
    srcs = ["the_test.sh"],
    visibility = ${visibility},
)
EOF
  cat > "${prefix}/testpkg/the_test.sh" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod +x "${prefix}/testpkg/the_test.sh"

  cat > "${prefix}/suitepkg/BUILD" <<EOF
test_suite(
    name = "the_suite",
    tests = ["//${prefix}/testpkg:the_test"],
)
EOF
}

#### TESTS #################################################################

# Baseline: cquery correctly reports a visibility error when the test_suite
# references a private test in another package. This is the correct behaviour.
function test_cquery_catches_private_test_in_suite() {
  local -r pkg="$FUNCNAME"
  write_test_and_suite "$pkg" '["//visibility:private"]'

  bazel cquery "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      && fail "cquery should have failed due to visibility error"
  expect_log "is not visible from"
  expect_log "target '//${pkg}/suitepkg:the_suite'"
}

# Verify that bazel build catches a visibility error when the test_suite
# references a private test in another package, even with the default
# --expand_test_suites=true.
function test_build_catches_private_test_in_suite() {
  local -r pkg="$FUNCNAME"
  write_test_and_suite "$pkg" '["//visibility:private"]'

  bazel build "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      && fail "bazel build should have failed due to visibility error"
  expect_log "is not visible from"
  expect_log "target '//${pkg}/suitepkg:the_suite'"
}

# Verify that bazel test catches a visibility error when the test_suite
# references a private test in another package, even with the default
# --expand_test_suites=true.
function test_test_catches_private_test_in_suite() {
  local -r pkg="$FUNCNAME"
  write_test_and_suite "$pkg" '["//visibility:private"]'

  bazel test "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      && fail "bazel test should have failed due to visibility error"
  expect_log "is not visible from"
  expect_log "target '//${pkg}/suitepkg:the_suite'"
}

# When --noexpand_test_suites is passed explicitly, bazel build DOES catch
# the visibility error because the suite is analyzed as a configured target.
function test_build_noexpand_catches_private_test_in_suite() {
  local -r pkg="$FUNCNAME"
  write_test_and_suite "$pkg" '["//visibility:private"]'

  bazel build --noexpand_test_suites "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      && fail "bazel build --noexpand_test_suites should have failed due to visibility error"
  expect_log "is not visible from"
  expect_log "target '//${pkg}/suitepkg:the_suite'"
}

# Sanity check: when the test grants visibility to the suite's package,
# all commands should succeed.
function test_build_with_visible_test_in_suite_succeeds() {
  local -r pkg="$FUNCNAME"
  write_test_and_suite "$pkg" "[\"//${pkg}/suitepkg:__pkg__\"]"

  bazel build "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      || fail "bazel build should have succeeded"
  bazel cquery "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      || fail "bazel cquery should have succeeded"
}

# Sanity check: when the test is publicly visible, all commands should succeed.
function test_build_with_public_test_in_suite_succeeds() {
  local -r pkg="$FUNCNAME"
  write_test_and_suite "$pkg" '["//visibility:public"]'

  bazel build "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      || fail "bazel build should have succeeded"
  bazel cquery "//${pkg}/suitepkg:the_suite" &> "$TEST_log" \
      || fail "bazel cquery should have succeeded"
}

# Regression check: target exclusion of a test referenced by a test_suite must
# still take effect. Analyzing the test_suite (so its tests attribute is
# visibility-checked) must not cause the excluded test to run.
function test_target_exclusion_of_test_in_suite() {
  local -r pkg="$FUNCNAME"
  mkdir -p "${pkg}"

  cat > "${pkg}/BUILD" <<EOF
load("@rules_shell//shell:sh_test.bzl", "sh_test")
sh_test(name = "test_in_suite_1", srcs = ["t.sh"])
sh_test(name = "test_in_suite_2", srcs = ["t.sh"])
test_suite(
    name = "the_suite",
    tests = [
        ":test_in_suite_1",
        ":test_in_suite_2",
    ],
)
EOF
  cat > "${pkg}/t.sh" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod +x "${pkg}/t.sh"

  # Note: '--' is required so that '-//pkg:test_in_suite_1' isn't parsed as a
  # flag.
  bazel test -- "//${pkg}:the_suite" "-//${pkg}:test_in_suite_1" \
      &> "$TEST_log" || fail "bazel test should have succeeded"

  expect_log "//${pkg}:test_in_suite_2\s\+PASSED"
  expect_not_log "//${pkg}:test_in_suite_1\s\+PASSED"
  expect_log "Executed 1 out of 1 test"
}

# Nested test_suite: a suite that references another suite. Visibility checking
# must apply transitively — a private test reached only through a nested suite
# should still trigger a visibility error when building the outer suite. This
# works because analyzing outer_suite pulls inner_suite in as a configured
# target, whose own `tests` attribute references are then visibility-checked.
function test_build_catches_private_test_in_nested_suite() {
  local -r pkg="$FUNCNAME"
  mkdir -p "${pkg}/testpkg" "${pkg}/innerpkg" "${pkg}/outerpkg"

  # The leaf test is private — only visible in its own package.
  cat > "${pkg}/testpkg/BUILD" <<EOF
sh_test(
    name = "the_test",
    srcs = ["the_test.sh"],
    visibility = ["//visibility:private"],
)
EOF
  cat > "${pkg}/testpkg/the_test.sh" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod +x "${pkg}/testpkg/the_test.sh"

  # The inner test_suite references the private test. inner_suite itself is
  # public so outer_suite can see it — the only visibility violation is on
  # inner_suite -> the_test.
  cat > "${pkg}/innerpkg/BUILD" <<EOF
test_suite(
    name = "inner_suite",
    tests = ["//${pkg}/testpkg:the_test"],
    visibility = ["//visibility:public"],
)
EOF

  # The outer test_suite references the inner test_suite.
  cat > "${pkg}/outerpkg/BUILD" <<EOF
test_suite(
    name = "outer_suite",
    tests = ["//${pkg}/innerpkg:inner_suite"],
)
EOF

  bazel build "//${pkg}/outerpkg:outer_suite" &> "$TEST_log" \
      && fail "bazel build should have failed due to visibility error"
  expect_log "is not visible from"
  # The violation is reported on the inner suite's reference to the private
  # test, not on the outer suite.
  expect_log "target '//${pkg}/innerpkg:inner_suite'"
}

# Sanity check: a nested test_suite composition with proper visibility should
# build and test successfully, and the underlying test should run exactly once.
function test_nested_suite_with_visible_tests_succeeds() {
  local -r pkg="$FUNCNAME"
  mkdir -p "${pkg}/testpkg" "${pkg}/innerpkg" "${pkg}/outerpkg"

  cat > "${pkg}/testpkg/BUILD" <<EOF
sh_test(
    name = "the_test",
    srcs = ["the_test.sh"],
    visibility = ["//visibility:public"],
)
EOF
  cat > "${pkg}/testpkg/the_test.sh" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod +x "${pkg}/testpkg/the_test.sh"

  cat > "${pkg}/innerpkg/BUILD" <<EOF
test_suite(
    name = "inner_suite",
    tests = ["//${pkg}/testpkg:the_test"],
    visibility = ["//visibility:public"],
)
EOF

  cat > "${pkg}/outerpkg/BUILD" <<EOF
test_suite(
    name = "outer_suite",
    tests = ["//${pkg}/innerpkg:inner_suite"],
)
EOF

  bazel test "//${pkg}/outerpkg:outer_suite" &> "$TEST_log" \
      || fail "bazel test should have succeeded"
  expect_log "//${pkg}/testpkg:the_test\s\+PASSED"
  expect_log "Executed 1 out of 1 test"
}

run_suite "Integration tests for test_suite visibility checking"
