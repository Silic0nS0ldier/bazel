// Copyright 2025 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DownloadErrors}. */
@RunWith(JUnit4.class)
public class DownloadErrorsTest {

  @Test
  public void describe_plainException() {
    assertThat(DownloadErrors.describe(new IOException("boom")))
        .isEqualTo("java.io.IOException: boom");
  }

  @Test
  public void describe_includesCauseAndSuppressed() {
    IOException failure = new IOException("all mirrors failed");
    failure.initCause(new SocketTimeoutException("connect timed out"));
    failure.addSuppressed(new IOException("mirror 1"));
    failure.addSuppressed(new IOException("mirror 2"));

    assertThat(DownloadErrors.describe(failure))
        .isEqualTo(
            """
            java.io.IOException: all mirrors failed
              caused by: java.net.SocketTimeoutException: connect timed out
              also: java.io.IOException: mirror 1
              also: java.io.IOException: mirror 2""");
  }

  @Test
  public void describe_nestsRecursively() {
    IOException mirror = new IOException("mirror 1");
    mirror.addSuppressed(new IOException("attempt 1"));
    IOException failure = new IOException("all mirrors failed");
    failure.addSuppressed(mirror);

    assertThat(DownloadErrors.describe(failure))
        .isEqualTo(
            """
            java.io.IOException: all mirrors failed
              also: java.io.IOException: mirror 1
                also: java.io.IOException: attempt 1""");
  }

  @Test
  public void describe_collapsesRepeatedSuppressedExceptions() {
    IOException failure = new IOException("all attempts failed");
    for (int i = 0; i < 3; i++) {
      failure.addSuppressed(new SocketTimeoutException("connect timed out"));
    }
    failure.addSuppressed(new IOException("something else"));
    failure.addSuppressed(new SocketTimeoutException("connect timed out"));

    assertThat(DownloadErrors.describe(failure))
        .isEqualTo(
            """
            java.io.IOException: all attempts failed
              also: java.net.SocketTimeoutException: connect timed out (x3)
              also: java.io.IOException: something else
              also: java.net.SocketTimeoutException: connect timed out""");
  }

  @Test
  public void describe_appliesIndentToEveryLine() {
    IOException failure = new IOException("outer");
    failure.addSuppressed(new IOException("inner"));

    assertThat(DownloadErrors.describe(failure, "> "))
        .isEqualTo(
            """
            > java.io.IOException: outer
            >   also: java.io.IOException: inner""");
  }

  @Test
  public void describe_toleratesCycles() {
    IOException failure = new IOException("outer");
    IOException inner = new IOException("inner");
    failure.addSuppressed(inner);
    inner.addSuppressed(failure);

    assertThat(DownloadErrors.describe(failure))
        .isEqualTo(
            """
            java.io.IOException: outer
              also: java.io.IOException: inner
                also: [circular reference to java.io.IOException]""");
  }
}
