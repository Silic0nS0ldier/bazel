# pylint: disable=g-bad-file-header
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
"""Integration tests for --experimental_repository_download_validation."""

import base64
import hashlib
import http.server
import os
import socketserver
import tempfile
import threading

from absl.testing import absltest
from src.test.py.bazel import test_base

CONTENT_A = b'download validation content A\n'
CONTENT_B = b'download validation content B\n'
SHA256_A = hashlib.sha256(CONTENT_A).hexdigest()
SHA256_B = hashlib.sha256(CONTENT_B).hexdigest()


class _Handler(http.server.BaseHTTPRequestHandler):
  """Serves in-memory files and records every requested path."""

  files = {}
  requests = []
  lock = threading.Lock()

  def do_GET(self):  # pylint: disable=invalid-name
    with _Handler.lock:
      _Handler.requests.append(self.path)
    content = _Handler.files.get(self.path)
    if content is None:
      self.send_response(404)
      self.end_headers()
      return
    self.send_response(200)
    self.send_header('Content-Length', str(len(content)))
    self.end_headers()
    self.wfile.write(content)

  def log_message(self, *args):
    pass


class _ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
  allow_reuse_address = True


class DownloadValidationTest(test_base.TestBase):

  def setUp(self):
    super().setUp()
    _Handler.files = {
        '/a.bin': CONTENT_A,
        '/b.bin': CONTENT_B,
    }
    _Handler.requests = []
    # Bind and address by IPv4 explicitly: Java resolves "localhost" to ::1
    # first on some hosts and does not fall back to 127.0.0.1.
    self._server = _ThreadedTCPServer(('127.0.0.1', 0), _Handler)
    port = self._server.server_address[1]
    self._url_base = 'http://127.0.0.1:%d' % port
    thread = threading.Thread(target=self._server.serve_forever)
    thread.daemon = True
    thread.start()

    # Isolated per test method: self._temp is shared across methods in a run.
    self._repository_cache = tempfile.mkdtemp(dir=self._temp)

    self.ScratchFile(
        'repo.bzl',
        [
            'def _dl_impl(ctx):',
            '    ctx.download(',
            '        url = ctx.attr.urls,',
            '        output = "out.bin",',
            '        sha256 = ctx.attr.sha256,',
            '        allow_fail = ctx.attr.allow_fail,',
            '    )',
            '    ctx.file("BUILD", "exports_files([\'out.bin\'])")',
            '    if ctx.attr.reproducible:',
            '        return ctx.repo_metadata(reproducible = True)',
            '    return None',
            '',
            'dl_repo = repository_rule(',
            '    implementation = _dl_impl,',
            '    attrs = {',
            '        "urls": attr.string_list(),',
            '        "sha256": attr.string(),',
            '        "allow_fail": attr.bool(default = False),',
            '        "reproducible": attr.bool(default = False),',
            '    },',
            ')',
        ],
    )
    self.ScratchFile('BUILD')

  def tearDown(self):
    self._server.shutdown()
    self._server.server_close()
    super().tearDown()

  def _WriteModuleFile(self, repos):
    """Writes MODULE.bazel declaring dl_repo repos: {name: (urls, sha256)}."""
    lines = [
        'dl_repo = use_repo_rule("//:repo.bzl", "dl_repo")',
    ]
    for name, value in repos.items():
      urls, sha256 = value[0], value[1]
      allow_fail = value[2] if len(value) > 2 else False
      reproducible = value[3] if len(value) > 3 else False
      lines += [
          'dl_repo(',
          '    name = "%s",' % name,
          '    urls = %r,' % [self._url_base + u for u in urls],
          '    sha256 = "%s",' % sha256,
          '    allow_fail = %s,' % allow_fail,
          '    reproducible = %s,' % reproducible,
          ')',
      ]
    self.ScratchFile('MODULE.bazel', lines)

  def _Build(self, target, validation=None, url_patterns=None, allow_failure=False):
    args = ['build', '--repository_cache=' + self._repository_cache]
    if validation:
      args.append('--experimental_repository_download_validation=' + validation)
    for pattern in url_patterns or []:
      args.append('--experimental_repository_download_validation_urls=' + pattern)
    args.append(target)
    return self.RunBazel(args, allow_failure=allow_failure)

  def _RequestCount(self, path):
    with _Handler.lock:
      return _Handler.requests.count(path)

  def testStaleChecksumMaskedWithoutValidationCaughtWithValidation(self):
    # Warm the download cache with content A.
    self._WriteModuleFile({'warm': (['/a.bin'], SHA256_A)})
    self._Build('@warm//:out.bin')
    self.assertEqual(self._RequestCount('/a.bin'), 1)

    # The bug this feature exists to catch: URL points at B, checksum still A.
    # Without validation the checksum-keyed cache masks it completely.
    self._WriteModuleFile({'masked': (['/b.bin'], SHA256_A)})
    self._Build('@masked//:out.bin')
    self.assertEqual(self._RequestCount('/b.bin'), 0)

    # With validation the URL is exercised and the mismatch is fatal,
    # in tolerant mode too (mismatches are not fetch failures).
    self._WriteModuleFile({'caught': (['/b.bin'], SHA256_A)})
    exit_code, _, stderr = self._Build(
        '@caught//:out.bin', validation='tolerant', allow_failure=True)
    self.assertNotEqual(exit_code, 0)
    stderr = '\n'.join(stderr)
    self.assertIn('Download validation failed', stderr)
    self.assertIn('/b.bin', stderr)
    self.assertIn(SHA256_B, stderr)
    self.assertGreaterEqual(self._RequestCount('/b.bin'), 1)

  def testValidationRecordsSkipRevalidation(self):
    self._WriteModuleFile({'foo': (['/a.bin'], SHA256_A)})
    self._Build('@foo//:out.bin', validation='strict')
    # The validation fetch doubles as the content fetch: exactly one request.
    self.assertEqual(self._RequestCount('/a.bin'), 1)

    # Nuke all local state except the repository cache, which holds the record.
    self.RunBazel(['clean', '--expunge'])
    self._Build('@foo//:out.bin', validation='strict')
    self.assertEqual(self._RequestCount('/a.bin'), 1)

  def testUnfetchableUrlTolerantWarnsStrictFails(self):
    # Warm and validate content A so content resolution succeeds from cache.
    self._WriteModuleFile({'warm': (['/a.bin'], SHA256_A)})
    self._Build('@warm//:out.bin', validation='strict')

    self._WriteModuleFile({'gone': (['/missing.bin'], SHA256_A)})
    _, _, stderr = self._Build('@gone//:out.bin', validation='tolerant')
    stderr = '\n'.join(stderr)
    self.assertIn('could not be fetched', stderr)
    self.assertIn('/missing.bin', stderr)

    self._WriteModuleFile({'gone2': (['/missing.bin'], SHA256_A)})
    exit_code, _, stderr = self._Build(
        '@gone2//:out.bin', validation='strict', allow_failure=True)
    self.assertNotEqual(exit_code, 0)
    self.assertIn('could not be fetched', '\n'.join(stderr))

  def testMirrorFallthroughValidatesEveryUrl(self):
    # Content resolution would succeed via the second URL, but strict
    # validation exercises the first (missing) one and fails.
    self._WriteModuleFile({'mirrored': (['/missing.bin', '/a.bin'], SHA256_A)})
    exit_code, _, stderr = self._Build(
        '@mirrored//:out.bin', validation='strict', allow_failure=True)
    self.assertNotEqual(exit_code, 0)
    self.assertIn('/missing.bin', '\n'.join(stderr))

    # Tolerant mode warns about the missing mirror and validates the rest.
    self._WriteModuleFile({'mirrored2': (['/missing.bin', '/a.bin'], SHA256_A)})
    _, _, stderr = self._Build('@mirrored2//:out.bin', validation='tolerant')
    stderr = '\n'.join(stderr)
    self.assertIn('/missing.bin', stderr)
    self.assertGreaterEqual(self._RequestCount('/a.bin'), 1)

  def testAllowFailExemptFromStrictFetchFailure(self):
    # Warm and validate content A.
    self._WriteModuleFile({'warm': (['/a.bin'], SHA256_A)})
    self._Build('@warm//:out.bin', validation='strict')

    # allow_fail downloads are expected to be unreliable: an unfetchable URL
    # must not fail the fetch even in strict mode (content resolves from
    # cache), only warn.
    self._WriteModuleFile({'flaky': (['/missing.bin'], SHA256_A, True)})
    _, _, stderr = self._Build('@flaky//:out.bin', validation='strict')
    stderr = '\n'.join(stderr)
    self.assertIn('could not be fetched', stderr)
    self.assertIn('/missing.bin', stderr)

  def testDownloadManifestWritten(self):
    # Manifests are written by every fetch, validation flags or not.
    self._WriteModuleFile({'foo': (['/a.bin', '/mirror-a.bin'], SHA256_A)})
    _Handler.files['/mirror-a.bin'] = CONTENT_A
    self._Build('@foo//:out.bin')

    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    manifest_path = os.path.join(
        stdout[0].strip(), 'external', '@+dl_repo+foo.downloads')
    with open(manifest_path, 'r') as f:
      content = f.read()

    integrity_a = 'sha256-' + base64.b64encode(
        hashlib.sha256(CONTENT_A).digest()).decode()
    self.assertIn('bazel download manifest v1', content)
    self.assertIn(integrity_a, content)
    # Original URLs in declaration order, tab-separated on one entry line.
    self.assertIn(
        '%s/a.bin\t%s/mirror-a.bin' % (self._url_base, self._url_base), content)

  def testManifestCarriedIntoRepoContentsCache(self):
    contents_cache = tempfile.mkdtemp(dir=self._temp)
    self._WriteModuleFile({'foo': (['/a.bin'], SHA256_A, False, True)})
    self.RunBazel([
        'build',
        '--repository_cache=' + self._repository_cache,
        '--repo_contents_cache=' + contents_cache,
        '@foo//:out.bin',
    ])

    # The manifest travels into the cache entry as <uuid>.downloads, moved
    # out of the output base together with the marker. Other cacheable repos
    # (e.g. module repos like `platforms`) carry their own manifests too, so
    # filter for ours.
    contents = []
    for root, _, files in os.walk(contents_cache):
      for f in files:
        if f.endswith('.downloads'):
          with open(os.path.join(root, f), 'r') as manifest:
            contents.append(manifest.read())
    ours = [c for c in contents if self._url_base + '/a.bin' in c]
    self.assertEqual(len(ours), 1)
    self.assertIn('bazel download manifest v1', ours[0])

  def testUrlPolicyRestrictsValidation(self):
    # Warm the cache with A.
    self._WriteModuleFile({'warm': (['/a.bin'], SHA256_A)})
    self._Build('@warm//:out.bin')

    # The bad URL is not selected by the policy, so it is not exercised and
    # the cached content masks it, as without validation.
    self._WriteModuleFile({'unselected': (['/b.bin'], SHA256_A)})
    self._Build(
        '@unselected//:out.bin',
        validation='strict',
        url_patterns=['.*/selected/.*'],
    )
    self.assertEqual(self._RequestCount('/b.bin'), 0)


if __name__ == '__main__':
  absltest.main()
