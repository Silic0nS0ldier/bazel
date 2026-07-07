# pylint: disable=g-backslash-continuation
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
# pylint: disable=g-long-ternary
# pylint: disable=g-bad-todo

"""Integration tests for --experimental_granular_repository_caching.

Unlike the repo contents cache, granular caching does not skip the repository
rule's implementation function; it makes individual repository_ctx operations
(currently ctx.execute) cacheable actions. The tests therefore detect cache
hits by having the executed command produce a nondeterministic stamp: if the
stamp is identical across a `clean --expunge`, the command's outputs were
replayed from the cache rather than re-executed.
"""

import hashlib
import io
import json
import os
import re
import tarfile
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import StaticHTTPServer

# Produces a value that is different on every actual execution of a command.
NONDETERMINISM = '$RANDOM$RANDOM$RANDOM-$$'


class GranularRepoCachingTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    if self.IsWindows():
      # The test commands require /bin/bash.
      self.skipTest('granular repo caching tests are not supported on Windows')
    self.worker_port = self.StartRemoteWorker()
    self.ScratchFile(
        '.bazelrc',
        [
            'common --experimental_granular_repository_caching',
            'common --remote_executor=grpc://localhost:'
            + str(self.worker_port),
            # Keep the (local) repo contents cache out of the picture: it would
            # skip repo rule re-evaluation entirely and mask what this feature
            # does.
            'common --repo_contents_cache=',
            'common --auth_enabled=false',
            'common --remote_timeout=3600s',
            'common --verbose_failures',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'repo = use_repo_rule("//:repo.bzl", "repo")',
            'repo(name = "my_repo")',
        ],
    )

  def tearDown(self):
    if not self.IsWindows():
      self.StopRemoteWorker()
    test_base.TestBase.tearDown(self)

  def RepoDir(self, repo_name):
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    self.assertLen(stdout, 1)
    output_base = stdout[0].strip()

    _, stdout, _ = self.RunBazel(['mod', 'dump_repo_mapping', ''])
    self.assertLen(stdout, 1)
    mapping = json.loads(stdout[0])
    canonical_repo_name = mapping[repo_name]

    return output_base + '/external/' + canonical_repo_name

  def Stamp(self, stderr):
    """Extracts the nondeterministic stamp printed by the repo rule."""
    match = re.search(r'STAMP: (\S+)', '\n'.join(stderr))
    self.assertIsNotNone(
        match, 'no STAMP found in stderr:\n' + '\n'.join(stderr)
    )
    return match.group(1)

  def AssertBlobInWorkerCas(self, content, present):
    """Asserts the sha256 blob of content is (not) in the remote worker CAS."""
    digest = hashlib.sha256(content).hexdigest()
    found = any(
        digest in files for _, _, files in os.walk(self._cas_path)
    )
    if present:
      self.assertTrue(found, 'blob %s not found in worker CAS' % digest)
    else:
      self.assertFalse(found, 'blob %s unexpectedly in worker CAS' % digest)

  def testExecuteCachedAcrossExpunge(self):
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  res = rctx.execute(',
            '    ["/bin/bash", "-c",',
            '     "echo -n %s > stamp.txt && echo -n env=$FOO"],'
            % NONDETERMINISM,
            '    environment = {"FOO": "bar"},',
            '  )',
            '  if res.return_code != 0:',
            '    fail("execute failed: " + res.stderr)',
            '  print("STDOUT: " + res.stdout)',
            '  print("STAMP: " + rctx.read("stamp.txt"))',
            'repo = repository_rule(_repo_impl)',
        ],
    )

    repo_dir = self.RepoDir('my_repo')

    # First fetch: the command is executed remotely.
    _, _, stderr = self.RunBazel(['build', '@my_repo//:haha'])
    self.assertIn('STDOUT: env=bar', '\n'.join(stderr))
    stamp1 = self.Stamp(stderr)
    # The command's output files are staged into the repo directory.
    self.assertTrue(os.path.exists(os.path.join(repo_dir, 'stamp.txt')))

    # After expunging, the repo rule reruns, but the command's result is
    # replayed from the action cache (stdout included).
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '@my_repo//:haha'])
    self.assertIn('STDOUT: env=bar', '\n'.join(stderr))
    self.assertEqual(stamp1, self.Stamp(stderr))
    self.assertTrue(os.path.exists(os.path.join(repo_dir, 'stamp.txt')))

    # Without the flag, the command actually executes (locally).
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel([
        'build',
        '--noexperimental_granular_repository_caching',
        '@my_repo//:haha',
    ])
    self.assertNotEqual(stamp1, self.Stamp(stderr))

  def testExecuteStagesInputsAndOutputs(self):
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("input.txt", "hello")',
            '  rctx.file("delete_me.txt", "bye")',
            '  res = rctx.execute(["/bin/bash", "-c", "&&".join([',
            '    "mkdir -p sub",',
            '    "tr a-z A-Z < input.txt > sub/derived.txt",',
            '    "rm delete_me.txt",',
            '    "echo -n %s > stamp.txt",' % NONDETERMINISM,
            '  ])])',
            '  if res.return_code != 0:',
            '    fail("execute failed: " + res.stderr)',
            # File changes made by the command (including deletions) are
            # visible to subsequent operations.
            '  if rctx.path("delete_me.txt").exists:',
            '    fail("delete_me.txt still exists")',
            '  if rctx.read("sub/derived.txt") != "HELLO":',
            '    fail("bad derived.txt: " + rctx.read("sub/derived.txt"))',
            '  rctx.file("BUILD", "exports_files([\'sub/derived.txt\'])")',
            '  print("STAMP: " + rctx.read("stamp.txt"))',
            'repo = repository_rule(_repo_impl)',
        ],
    )
    self.ScratchFile(
        'main/BUILD.bazel',
        [
            'genrule(',
            '  name = "use_derived",',
            '  srcs = ["@my_repo//:sub/derived.txt"],',
            '  outs = ["out.txt"],',
            '  cmd = "cat $< > $@",',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '//main:use_derived'])
    stamp1 = self.Stamp(stderr)
    with open(self.Path('bazel-bin/main/out.txt')) as f:
      self.assertEqual(f.read(), 'HELLO')

    # Cached across an expunge: same stamp, same staged outputs.
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '//main:use_derived'])
    self.assertEqual(stamp1, self.Stamp(stderr))
    with open(self.Path('bazel-bin/main/out.txt')) as f:
      self.assertEqual(f.read(), 'HELLO')

  def testExecuteWithLabelArguments(self):
    self.ScratchFile(
        'BUILD.bazel',
        ['exports_files(["cmd.sh", "hello.txt"])'],
    )
    self.ScratchFile(
        'cmd.sh',
        [
            '#!/bin/bash',
            'cat "$1" > copied.txt',
            'echo -n %s > stamp.txt' % NONDETERMINISM,
        ],
        executable=True,
    )
    self.ScratchFile('hello.txt', ['hello world'])
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  res = rctx.execute([Label("//:cmd.sh"), Label("//:hello.txt")])',
            '  if res.return_code != 0:',
            '    fail("execute failed: " + res.stderr)',
            '  if rctx.read("copied.txt").strip() != "hello world":',
            '    fail("bad copied.txt: " + rctx.read("copied.txt"))',
            '  print("STAMP: " + rctx.read("stamp.txt"))',
            'repo = repository_rule(_repo_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@my_repo//:haha'])
    stamp1 = self.Stamp(stderr)

    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '@my_repo//:haha'])
    self.assertEqual(stamp1, self.Stamp(stderr))

  def _AssertNotCached(self, repo_bzl_lines, extra_flags=None):
    """Asserts the repo rule's execute reruns (locally) across an expunge."""
    self.ScratchFile('repo.bzl', repo_bzl_lines)
    flags = extra_flags or []

    _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
    stamp1 = self.Stamp(stderr)

    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
    self.assertNotEqual(stamp1, self.Stamp(stderr))

  def testWorkingDirectoryOverrideFallsBackToLocal(self):
    self._AssertNotCached([
        'def _repo_impl(rctx):',
        '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
        '  rctx.file("sub/.keep", "")',
        '  res = rctx.execute(',
        '    ["/bin/bash", "-c", "echo -n %s > stamp.txt"],' % NONDETERMINISM,
        '    working_directory = "sub",',
        '  )',
        '  if res.return_code != 0:',
        '    fail("execute failed: " + res.stderr)',
        '  print("STAMP: " + rctx.read("sub/stamp.txt"))',
        'repo = repository_rule(_repo_impl)',
    ])

  def testPathArgumentOutsideRepoFallsBackToLocal(self):
    self.ScratchFile(
        'BUILD.bazel',
        ['exports_files(["local_cmd.sh"])'],
    )
    self.ScratchFile(
        'local_cmd.sh',
        [
            '#!/bin/bash',
            'echo -n %s > stamp.txt' % NONDETERMINISM,
        ],
        executable=True,
    )
    self._AssertNotCached([
        'def _repo_impl(rctx):',
        '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
        # A path argument outside the repo directory references the local
        # system, so the command cannot be cached.
        '  res = rctx.execute([rctx.path(Label("//:local_cmd.sh"))])',
        '  if res.return_code != 0:',
        '    fail("execute failed: " + res.stderr)',
        '  print("STAMP: " + rctx.read("stamp.txt"))',
        'repo = repository_rule(_repo_impl)',
    ])

  def testEmptyRepoDirFallsBackToLocal(self):
    self._AssertNotCached([
        'def _repo_impl(rctx):',
        # The repo directory is still empty at this point, which cannot be
        # represented as an action input tree (the REAPI working directory
        # must exist in the input tree).
        '  res = rctx.execute(',
        '    ["/bin/bash", "-c", "echo -n %s > stamp.txt"],' % NONDETERMINISM,
        '  )',
        '  if res.return_code != 0:',
        '    fail("execute failed: " + res.stderr)',
        '  print("STAMP: " + rctx.read("stamp.txt"))',
        '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
        'repo = repository_rule(_repo_impl)',
    ])

  def testLocalRuleIsExempt(self):
    self._AssertNotCached([
        'def _repo_impl(rctx):',
        '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
        '  res = rctx.execute(',
        '    ["/bin/bash", "-c", "echo -n %s > stamp.txt"],' % NONDETERMINISM,
        '  )',
        '  if res.return_code != 0:',
        '    fail("execute failed: " + res.stderr)',
        '  print("STAMP: " + rctx.read("stamp.txt"))',
        'repo = repository_rule(_repo_impl, local = True)',
    ])

  def testFileWriteInsertedIntoCas(self):
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  rctx.file("data.txt", "granular write content")',
            'repo = repository_rule(_repo_impl)',
        ],
    )
    self.RunBazel(['build', '@my_repo//:haha'])
    self.AssertBlobInWorkerCas(b'granular write content', present=True)

    # Without the flag, written files are not inserted into the CAS. Different
    # content, so the repo is refetched and the assertion is meaningful.
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  rctx.file("data.txt", "uncached write content")',
            'repo = repository_rule(_repo_impl)',
        ],
    )
    self.RunBazel([
        'build',
        '--noexperimental_granular_repository_caching',
        '@my_repo//:haha',
    ])
    self.AssertBlobInWorkerCas(b'uncached write content', present=False)

  def testDownloadInsertedIntoCas(self):
    checked_content = b'granular download content'
    unchecked_content = b'unverified download content'
    served = self.ScratchDir('served')
    self.ScratchFile('served/checked.txt', [checked_content.decode()])
    unchecked_path = self.ScratchFile(
        'served/unchecked.txt', [unchecked_content.decode()]
    )
    # ScratchFile appends a newline.
    checked_content += b'\n'
    unchecked_content += b'\n'

    with StaticHTTPServer(served) as server:
      self.ScratchFile(
          'repo.bzl',
          [
              'def _repo_impl(rctx):',
              '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
              '  rctx.download(',
              '    url = "%s/checked.txt",' % server.getURL(),
              '    output = "checked.txt",',
              '    sha256 = "%s",' % hashlib.sha256(checked_content).hexdigest(),
              '  )',
              # No checksum: the content is unverified and must not be inserted
              # into the CAS. (file:// since checksum-less plain http downloads
              # are rejected outright.)
              '  rctx.download(',
              '    url = "file://%s",' % unchecked_path,
              '    output = "unchecked.txt",',
              '  )',
              'repo = repository_rule(_repo_impl)',
          ],
      )
      self.RunBazel(['build', '@my_repo//:haha'])

    self.AssertBlobInWorkerCas(checked_content, present=True)
    self.AssertBlobInWorkerCas(unchecked_content, present=False)

  def SyntheticExtractionFlags(self):
    """Flags forcing the client-side extraction cache (no remote execution)."""
    return [
        '--remote_executor=',
        '--remote_cache=grpc://localhost:' + str(self.worker_port),
    ]

  def MakeTestArchive(self):
    """Creates a tar.gz under served/ and returns its URL path and sha256."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w:gz') as tar:

      def add_file(name, content, mode=0o644):
        info = tarfile.TarInfo(name)
        data = content.encode()
        info.size = len(data)
        info.mode = mode
        tar.addfile(info, io.BytesIO(data))

      add_file('pkg/file.txt', 'hello')
      add_file('pkg/exec.sh', '#!/bin/sh\n', mode=0o755)
      link = tarfile.TarInfo('pkg/link')
      link.type = tarfile.SYMTYPE
      link.linkname = 'file.txt'
      tar.addfile(link)
      empty_dir = tarfile.TarInfo('pkg/empty_dir')
      empty_dir.type = tarfile.DIRTYPE
      empty_dir.mode = 0o755
      tar.addfile(empty_dir)
      add_file('pkg/sub/nested.txt', 'nested')
    archive_bytes = buf.getvalue()
    served = self.ScratchDir('served')
    with open(os.path.join(served, 'archive.tar.gz'), 'wb') as f:
      f.write(archive_bytes)
    return served, hashlib.sha256(archive_bytes).hexdigest()

  def ScratchExtractingRepoRule(self, url, sha256):
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.download_and_extract(',
            '    url = "%s",' % url,
            '    sha256 = "%s",' % sha256,
            '    strip_prefix = "pkg",',
            '  )',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            'repo = repository_rule(_repo_impl)',
        ],
    )

  def AssertExtractedContents(self, repo_dir):
    with open(os.path.join(repo_dir, 'file.txt')) as f:
      self.assertEqual(f.read(), 'hello')
    with open(os.path.join(repo_dir, 'sub/nested.txt')) as f:
      self.assertEqual(f.read(), 'nested')
    self.assertTrue(os.path.islink(os.path.join(repo_dir, 'link')))
    self.assertEqual(os.readlink(os.path.join(repo_dir, 'link')), 'file.txt')
    self.assertTrue(os.access(os.path.join(repo_dir, 'exec.sh'), os.X_OK))
    self.assertTrue(os.path.isdir(os.path.join(repo_dir, 'empty_dir')))

  def testExtractionReplayedAcrossExpunge(self):
    served, sha256 = self.MakeTestArchive()
    repo_dir = self.RepoDir('my_repo')

    with StaticHTTPServer(served) as server:
      self.ScratchExtractingRepoRule(server.getURL() + '/archive.tar.gz', sha256)

      # First fetch: the archive is actually extracted, and the result stored.
      flags = self.SyntheticExtractionFlags()
      _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
      self.assertNotIn('replayed cached extraction', '\n'.join(stderr))
      self.AssertExtractedContents(repo_dir)

      # After expunging: the extraction is replayed from the cache with full
      # fidelity (symlinks, executable bits, empty directories).
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
      self.assertIn(
          'replayed cached extraction of archive.tar.gz', '\n'.join(stderr)
      )
      self.AssertExtractedContents(repo_dir)

      # Without the flag, the extraction reruns.
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel([
          'build',
          '--noexperimental_granular_repository_caching',
      ] + flags + ['@my_repo//:haha'])
      self.assertNotIn('replayed cached extraction', '\n'.join(stderr))
      self.AssertExtractedContents(repo_dir)

  def testExtractionWithPreexistingFilesReplayed(self):
    # toolchains_llvm-style pattern: the repo rule writes files (e.g. BUILD)
    # into the repository *before* extracting into it. The pre-extraction
    # destination state is part of the extraction key and the merged result is
    # what's cached and replayed.
    served, sha256 = self.MakeTestArchive()
    repo_dir = self.RepoDir('my_repo')

    with StaticHTTPServer(served) as server:
      self.ScratchFile(
          'repo.bzl',
          [
              'def _repo_impl(rctx):',
              '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
              '  rctx.download_and_extract(',
              '    url = "%s/archive.tar.gz",' % server.getURL(),
              '    sha256 = "%s",' % sha256,
              '    strip_prefix = "pkg",',
              '  )',
              'repo = repository_rule(_repo_impl)',
          ],
      )

      flags = self.SyntheticExtractionFlags()
      _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
      self.assertNotIn('replayed cached extraction', '\n'.join(stderr))
      self.AssertExtractedContents(repo_dir)

      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
      self.assertIn(
          'replayed cached extraction of archive.tar.gz', '\n'.join(stderr)
      )
      self.AssertExtractedContents(repo_dir)
      # The pre-existing file is part of the merged tree and survives replay.
      with open(os.path.join(repo_dir, 'BUILD')) as f:
        self.assertEqual(f.read(), "filegroup(name='haha')")

  def testExtractionCacheRespectsUploadLocalResults(self):
    # In deployments where clients may not upload action results, the
    # extraction cache degrades to the local disk cache: nothing is written to
    # the remote cache, but a disk cache still provides replay.
    served, sha256 = self.MakeTestArchive()
    repo_dir = self.RepoDir('my_repo')
    no_upload = '--noremote_upload_local_results'
    flags = self.SyntheticExtractionFlags() + [no_upload]

    with StaticHTTPServer(served) as server:
      self.ScratchExtractingRepoRule(server.getURL() + '/archive.tar.gz', sha256)

      # Without a disk cache, the extraction is never replayed: the client may
      # not write the entry to the remote cache.
      _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
      self.assertNotIn('replayed cached extraction', '\n'.join(stderr))
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build'] + flags + ['@my_repo//:haha'])
      self.assertNotIn('replayed cached extraction', '\n'.join(stderr))

      # With a disk cache, the extraction is replayed from disk.
      disk_cache = tempfile.mkdtemp(dir=os.environ['TEST_TMPDIR'])
      disk = '--disk_cache=' + disk_cache
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build', disk] + flags + ['@my_repo//:haha'])
      self.assertNotIn('replayed cached extraction', '\n'.join(stderr))
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build', disk] + flags + ['@my_repo//:haha'])
      self.assertIn(
          'replayed cached extraction of archive.tar.gz', '\n'.join(stderr)
      )
      self.AssertExtractedContents(repo_dir)

  def testRemoteExtraction(self):
    # With a remote executor, extraction runs as a remote action using the
    # bundled extractor: the action cache entry is produced by the remote
    # service, so this works even in deployments where clients may not upload
    # action results (--noremote_upload_local_results).
    served, sha256 = self.MakeTestArchive()
    repo_dir = self.RepoDir('my_repo')
    no_upload = '--noremote_upload_local_results'

    with StaticHTTPServer(served) as server:
      self.ScratchExtractingRepoRule(server.getURL() + '/archive.tar.gz', sha256)

      _, _, stderr = self.RunBazel(['build', no_upload, '@my_repo//:haha'])
      self.assertIn(
          'extracted archive.tar.gz via remote action', '\n'.join(stderr)
      )
      self.AssertExtractedContents(repo_dir)

      # After expunging, the remote action cache serves the extraction.
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build', no_upload, '@my_repo//:haha'])
      self.assertIn(
          'extracted archive.tar.gz via remote action', '\n'.join(stderr)
      )
      self.AssertExtractedContents(repo_dir)

  def testSandboxedExecuteHidesUndeclaredInputs(self):
    # Local execute() under granular caching runs in a hermetic sandbox where
    # possible: only declared inputs (the repo directory, label/path arguments,
    # explicit environment) and the OS are visible. A plain-string absolute
    # path is opaque — exactly as it would be under remote execution.
    secret = self.ScratchFile('secret.txt', ['undeclared'])
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  res = rctx.execute(["/bin/cat", "%s"])' % secret,
            '  print("CAT_RC: %d" % res.return_code)',
            'repo = repository_rule(_repo_impl)',
        ],
    )

    # Clear the remote executor to force the local path.
    _, _, stderr = self.RunBazel(
        ['build', '--remote_executor=', '@my_repo//:haha']
    )
    stderr_text = '\n'.join(stderr)
    if 'execute() running in hermetic sandbox' not in stderr_text:
      # E.g. user namespaces are unavailable in this environment.
      self.skipTest('hermetic sandboxing not available in this environment')
    match = re.search(r'CAT_RC: (\d+)', stderr_text)
    self.assertIsNotNone(match, stderr_text)
    self.assertNotEqual('0', match.group(1))

    # The same file passed as a path argument is a declared input and visible.
    self.ScratchFile(
        'repo.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  res = rctx.execute(["/bin/cat", rctx.path("%s")])' % secret,
            '  print("CAT_RC: %d" % res.return_code)',
            'repo = repository_rule(_repo_impl)',
        ],
    )
    _, _, stderr = self.RunBazel(
        ['build', '--remote_executor=', '@my_repo//:haha']
    )
    match = re.search(r'CAT_RC: (\d+)', '\n'.join(stderr))
    self.assertIsNotNone(match, '\n'.join(stderr))
    self.assertEqual('0', match.group(1))

  def testNoRemoteExecutorFallsBackToLocal(self):
    # With only a remote cache configured, there is nothing that could soundly
    # produce the AC entry, so the command executes locally and uncached.
    self._AssertNotCached(
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "filegroup(name=\'haha\')")',
            '  res = rctx.execute(',
            '    ["/bin/bash", "-c", "echo -n %s > stamp.txt"],'
            % NONDETERMINISM,
            '  )',
            '  if res.return_code != 0:',
            '    fail("execute failed: " + res.stderr)',
            '  print("STAMP: " + rctx.read("stamp.txt"))',
            'repo = repository_rule(_repo_impl)',
        ],
        extra_flags=[
            '--remote_executor=',
            '--remote_cache=grpc://localhost:' + str(self.worker_port),
        ],
    )


if __name__ == '__main__':
  absltest.main()
