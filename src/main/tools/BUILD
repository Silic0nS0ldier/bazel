load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_graalvm//graalvm:defs.bzl", "native_image")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_cc//cc:cc_library.bzl", "cc_library")

package(default_visibility = ["//src:__subpackages__"])

cc_binary(
    name = "daemonize",
    srcs = ["daemonize.cc"],
    deps = [
        ":logging",
        ":process-tools",
    ],
)

cc_library(
    name = "logging",
    srcs = ["logging.cc"],
    hdrs = ["logging.h"],
)

cc_library(
    name = "process-tools",
    srcs = ["process-tools.cc"] + select({
        "//src/conditions:darwin": ["process-tools-darwin.cc"],
        "//src/conditions:openbsd": ["process-tools-darwin.cc"],
        "//conditions:default": ["process-tools-linux.cc"],
    }),
    hdrs = ["process-tools.h"],
    deps = [
        ":logging",
        "//src/main/protobuf:execution_statistics_cc_proto",
    ],
)

cc_binary(
    name = "process-wrapper",
    srcs = select({
        "//src/conditions:windows": ["process-wrapper-windows.cc"],
        "//conditions:default": [
            "process-wrapper.cc",
            "process-wrapper.h",
            "process-wrapper-legacy.cc",
            "process-wrapper-legacy.h",
            "process-wrapper-options.cc",
            "process-wrapper-options.h",
        ],
    }),
    linkopts = select({
        "//src/conditions:darwin": ["-lm"],
        "//src/conditions:openbsd": [],
        "//src/conditions:windows": [],
        "//conditions:default": [
            "-lm",
            "-lrt",
        ],
    }),
    deps = select({
        "//src/conditions:windows": [],
        "//conditions:default": [
            ":logging",
            ":process-tools",
        ],
    }),
)

cc_binary(
    name = "linux-sandbox",
    srcs = select({
        "//src/conditions:darwin": ["dummy-sandbox.c"],
        "//src/conditions:freebsd": ["dummy-sandbox.c"],
        "//src/conditions:openbsd": ["dummy-sandbox.c"],
        "//src/conditions:windows": ["dummy-sandbox.c"],
        "//conditions:default": [
            "linux-sandbox.cc",
            "linux-sandbox.h",
            "linux-sandbox-options.cc",
            "linux-sandbox-options.h",
            "linux-sandbox-pid1.cc",
            "linux-sandbox-pid1.h",
        ],
    }),
    linkopts = select({
        "//src/conditions:darwin": [],
        "//src/conditions:freebsd": [],
        "//src/conditions:openbsd": [],
        "//src/conditions:windows": [],
        "//conditions:default": [
            "-lm",
            "-lrt",
        ],
    }),
    deps = select({
        "//src/conditions:darwin": [],
        "//src/conditions:freebsd": [],
        "//src/conditions:openbsd": [],
        "//src/conditions:windows": [],
        "//conditions:default": [
            ":logging",
            ":process-tools",
            "//src/main/cpp/util",
        ],
    }),
)

exports_files([
    "build_interface_so",
])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__pkg__"],
)

# A native-image build of Bazel's own archive decompressors, bundled among the embedded tools
# and used by --experimental_granular_repository_caching to run repository archive extractions
# as remote execution actions. Wrapping the in-process implementation (rather than a separate
# extractor) means remote extraction has exactly the semantics -- and the battle-tested
# hardening -- of local extraction.
java_library(
    name = "repo-extractor-native-image-config",
    resource_strip_prefix = "src/main/tools/native-image-config",
    resources = glob(["native-image-config/**"]),
)

native_image(
    name = "repo-extractor",
    executable_name = "repo-extractor",
    extra_args = [
        "--strict-image-heap",
        # Workaround for https://github.com/oracle/graal/issues/4757.
        "-H:-UseContainerSupport",
        # StringEncoding/StringUnsafe reflect into java.lang.String internals. Initializing them
        # at build time (with java.lang opened to the image builder) bakes the resolved method
        # and var handles into the image heap; Graal recomputes Unsafe field offsets
        # automatically for build-time-initialized classes.
        "-J--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--initialize-at-build-time=com.google.devtools.build.lib.util.StringEncoding",
        "--initialize-at-build-time=com.google.devtools.build.lib.unsafe.StringUnsafe",
        # The tar decompressor distinguishes USTAR from PAX entry names via a custom marker
        # charset registered through a CharsetProvider. The native image's charset registry is
        # baked at build time, so the charset (and its random marker name) must be too.
        "--initialize-at-build-time=com.google.devtools.build.lib.bazel.repository.decompressor.CompressedTarFunction$MarkedIso88591CharsetProvider",
        "--initialize-at-build-time=com.google.devtools.build.lib.bazel.repository.decompressor.CompressedTarFunction$MarkedIso88591Charset",
        "-H:+AddAllCharsets",
    ] + select({
        "@platforms//os:linux": [
            # Statically link zlib but not glibc.
            "-H:+StaticExecutableWithDynamicLibC",
        ],
        "//conditions:default": [],
    }) + select({
        "@platforms//cpu:x86_64": [
            # Match turbine: Graal's defaults aren't sufficiently compatible for general use.
            "-march=x86-64-v2",
        ],
        "//conditions:default": [],
    }),
    main_class = "com.google.devtools.build.lib.bazel.repository.decompressor.RepoExtractorMain",
    static_zlib = "@zlib",
    visibility = ["//visibility:public"],
    deps = [
        ":repo-extractor-native-image-config",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/decompressor",
    ],
)
