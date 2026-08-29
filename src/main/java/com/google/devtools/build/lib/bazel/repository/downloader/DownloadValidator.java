// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.downloader;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.KeyType;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Validates that download URLs actually serve content matching their declared checksum.
 *
 * <p>Checksum-first caching means a declared URL is never contacted once content matching the
 * checksum is cached anywhere, so definition rot (URL updated without a checksum update, mirrors
 * missing files) goes unnoticed. When enabled, each URL selected by policy is exercised once and
 * the outcome is recorded as a validation record in the download cache, keyed by the (URL,
 * checksum) pair, so subsequent fetches skip revalidation.
 */
public final class DownloadValidator {

  /** How fetch failures (as opposed to checksum mismatches) are treated. */
  public enum Mode {
    /** Fetch failures are reported as warnings. */
    TOLERANT,
    /** Fetch failures fail the fetch. */
    STRICT,
  }

  /** A checksum mismatch observed while validating a URL. Always fatal. */
  public static final class DownloadValidationException extends IOException {
    DownloadValidationException(String message) {
      super(message);
    }
  }

  private static final String RECORD_FORMAT_VERSION = "v1";

  private final Mode mode;
  private final ImmutableList<Pattern> urlPatterns;
  private final DownloadCache downloadCache;
  @Nullable private final DownloadValidationRecordStore sharedStore;
  private final ExtendedEventHandler eventHandler;

  /**
   * Deduplicates concurrent validations of the same record within this server instance. The value
   * completes with {@link Optional#empty()} on successful validation, with a present {@link
   * IOException} on a fetch failure (mode policy applied by each caller), and exceptionally on a
   * checksum mismatch.
   */
  private final ConcurrentHashMap<String, CompletableFuture<Optional<IOException>>> validations =
      new ConcurrentHashMap<>();

  public DownloadValidator(
      Mode mode,
      ImmutableList<Pattern> urlPatterns,
      DownloadCache downloadCache,
      @Nullable DownloadValidationRecordStore sharedStore,
      ExtendedEventHandler eventHandler) {
    this.mode = mode;
    this.urlPatterns = urlPatterns;
    this.downloadCache = downloadCache;
    this.sharedStore = sharedStore;
    this.eventHandler = eventHandler;
  }

  /**
   * The canonical validation record document for a (URL, checksum) pair. Deterministic, so its
   * digest is computable without network access and record existence is a content-addressed check.
   */
  public static String recordDocument(URI url, Checksum checksum) {
    return "bazel download validation record "
        + RECORD_FORMAT_VERSION
        + "\nuri: "
        + url
        + "\nintegrity: "
        + checksum.toSubresourceIntegrity()
        + "\n";
  }

  public static String recordDigest(URI url, Checksum checksum) {
    return Hashing.sha256().hashString(recordDocument(url, checksum), UTF_8).toString();
  }

  private boolean isSelected(URI url) {
    if (urlPatterns.isEmpty()) {
      return true;
    }
    String urlString = url.toString();
    return urlPatterns.stream().anyMatch(p -> p.matcher(urlString).matches());
  }

  /**
   * Validates the policy-selected URLs of a download against its declared checksum, skipping URLs
   * with an existing validation record.
   *
   * <p>Fetched bytes are discarded, except that on a validation fetch of not-yet-cached content
   * the verified bytes populate the download cache so the subsequent content resolution does not
   * download again.
   *
   * @param allowFail whether the download was declared with {@code allow_fail = True}; such
   *     downloads are expected to be unreliable, so their fetch failures never fail the fetch even
   *     in {@link Mode#STRICT} (checksum mismatches still do)
   * @throws DownloadValidationException on a checksum mismatch, regardless of mode
   * @throws IOException on a fetch failure in {@link Mode#STRICT}
   */
  public void validate(
      List<URI> effectiveUrls,
      Map<String, List<String>> headers,
      Credentials credentials,
      Checksum checksum,
      String canonicalId,
      Downloader downloader,
      Path tmpDir,
      Map<String, String> clientEnv,
      String context,
      boolean allowFail)
      throws IOException, InterruptedException {
    for (URI url : effectiveUrls) {
      if (!isSelected(url)) {
        continue;
      }
      Optional<IOException> fetchFailure =
          validateUrl(url, headers, credentials, checksum, canonicalId, downloader, tmpDir,
              clientEnv, context);
      if (fetchFailure.isPresent()) {
        if (mode == Mode.STRICT && !allowFail) {
          throw new IOException(
              String.format(
                  "Download validation failed for %s (in %s): URL could not be fetched: %s",
                  url, context, fetchFailure.get().getMessage()),
              fetchFailure.get());
        }
        eventHandler.handle(
            Event.warn(
                String.format(
                    "Download validation: %s (in %s) could not be fetched: %s",
                    url, context, fetchFailure.get().getMessage())));
      }
    }
  }

  /**
   * Validates a single URL, deduplicating concurrent attempts on the same record.
   *
   * @return a fetch failure to be handled per {@link Mode}, or empty on success
   * @throws DownloadValidationException on checksum mismatch
   */
  private Optional<IOException> validateUrl(
      URI url,
      Map<String, List<String>> headers,
      Credentials credentials,
      Checksum checksum,
      String canonicalId,
      Downloader downloader,
      Path tmpDir,
      Map<String, String> clientEnv,
      String context)
      throws IOException, InterruptedException {
    String recordDigest = recordDigest(url, checksum);
    String cacheKey = checksum.toString();
    KeyType keyType = checksum.getKeyType();

    if (downloadCache.isEnabled()
        && downloadCache.hasValidationRecord(cacheKey, keyType, recordDigest)) {
      return Optional.empty();
    }
    if (sharedStore != null && sharedStore.hasRecord(recordDocument(url, checksum))) {
      // Validated elsewhere in the fleet; localise the record so future runs skip the round-trip.
      if (downloadCache.isEnabled()) {
        try {
          downloadCache.putValidationRecord(cacheKey, keyType, recordDigest);
        } catch (IOException e) {
          // Best effort; the shared record remains authoritative.
        }
      }
      return Optional.empty();
    }

    CompletableFuture<Optional<IOException>> future = new CompletableFuture<>();
    CompletableFuture<Optional<IOException>> existing =
        validations.putIfAbsent(recordDigest, future);
    if (existing != null) {
      try {
        return existing.join();
      } catch (CompletionException | CancellationException e) {
        if (e.getCause() instanceof DownloadValidationException validationException) {
          throw validationException;
        }
        if (e.getCause() instanceof InterruptedException) {
          // The validating thread was interrupted; this thread retries by falling through after
          // clearing the poisoned entry.
          validations.remove(recordDigest, existing);
          return validateUrl(
              url, headers, credentials, checksum, canonicalId, downloader, tmpDir, clientEnv,
              context);
        }
        throw new IOException(e.getCause() == null ? e : e.getCause());
      }
    }

    try {
      Optional<IOException> result =
          exerciseUrl(url, headers, credentials, checksum, canonicalId, downloader, tmpDir,
              clientEnv, context, cacheKey, keyType, recordDigest);
      future.complete(result);
      return result;
    } catch (IOException | InterruptedException | RuntimeException e) {
      future.completeExceptionally(e);
      throw e;
    }
  }

  private Optional<IOException> exerciseUrl(
      URI url,
      Map<String, List<String>> headers,
      Credentials credentials,
      Checksum checksum,
      String canonicalId,
      Downloader downloader,
      Path tmpDir,
      Map<String, String> clientEnv,
      String context,
      String cacheKey,
      KeyType keyType,
      String recordDigest)
      throws IOException, InterruptedException {
    Path tmp = tmpDir.getRelative("validation-" + UUID.randomUUID());
    try {
      try {
        tmpDir.createDirectoryAndParents();
        // The checksum is deliberately not passed to the downloader: verifying it here cleanly
        // separates fetch failures (mode-governed) from mismatches (always fatal).
        downloader.download(
            ImmutableList.of(url),
            headers,
            credentials,
            /* checksum= */ Optional.empty(),
            canonicalId,
            tmp,
            eventHandler,
            clientEnv,
            /* type= */ Optional.empty(),
            context);
      } catch (IOException e) {
        return Optional.of(e);
      }

      String actualChecksum = DownloadCache.getChecksum(keyType, tmp);
      if (!actualChecksum.equalsIgnoreCase(cacheKey)) {
        throw new DownloadValidationException(
            String.format(
                "Download validation failed for %s (in %s): content has %s %s, does not match"
                    + " declared %s. The URL likely serves different content than the"
                    + " declaration expects (e.g. it was updated without updating the checksum).",
                url, context, keyType, actualChecksum, cacheKey));
      }

      if (downloadCache.isEnabled()) {
        // The bytes are verified; cache them so content resolution does not download again.
        downloadCache.put(cacheKey, tmp, keyType, canonicalId);
        downloadCache.putValidationRecord(cacheKey, keyType, recordDigest);
      }
      if (sharedStore != null) {
        try {
          sharedStore.putRecord(recordDocument(url, checksum));
        } catch (IOException e) {
          // Record insertion is best-effort and must never fail the fetch.
          eventHandler.handle(
              Event.warn(
                  String.format(
                      "Download validation: failed to share validation record for %s: %s",
                      url, e.getMessage())));
        }
      }
      return Optional.empty();
    } finally {
      try {
        tmp.delete();
      } catch (IOException e) {
        // Best effort; a stray temp file is harmless.
      }
    }
  }
}
