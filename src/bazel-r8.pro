# No obfuscation: shrinking + optimization only (names preserved for debugging)
-dontobfuscate

# --- Entry point ---
-keep class com.google.devtools.build.lib.bazel.Bazel {
  public static void main(java.lang.String[]);
}

# --- Attributes needed by frameworks that reflect over annotations/generics ---
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,PermittedSubclasses

# --- JNI: native method names must survive; the JNI lib also calls back into Java ---
-keepclasseswithmembers,includedescriptorclasses class * {
  native <methods>;
}
# JNI callbacks construct these types from C++ (unix_jni.cc et al.)
-keep class com.google.devtools.build.lib.unix.** { *; }
-keep class com.google.devtools.build.lib.windows.** { *; }
-keep class com.google.devtools.build.lib.platform.** { *; }
-keep class com.google.devtools.build.lib.profiler.SystemNetworkStats* { *; }

# --- ServiceLoader (grpc providers, blockhound, JVM charset provider) ---
-adaptresourcefilenames META-INF/services/**
-adaptresourcefilecontents META-INF/services/**
-keep class * implements io.grpc.LoadBalancerProvider { *; }
-keep class * implements io.grpc.NameResolverProvider { *; }
-keep class * implements io.grpc.ManagedChannelProvider { *; }
-keep class * implements io.grpc.ServerProvider { *; }
-keep class * extends java.nio.charset.spi.CharsetProvider { *; }

# --- Options parser: reflects over @Option fields and instantiates OptionsBase subclasses ---
-keep class * extends com.google.devtools.common.options.OptionsBase { *; }
-keep class * implements com.google.devtools.common.options.Converter { <init>(); *; }
-keep class com.google.devtools.common.options.** { *; }

# --- Starlark: @StarlarkMethod methods are invoked reflectively; annotation values read at runtime ---
-keepclasseswithmembers class * {
  @net.starlark.java.annot.StarlarkMethod <methods>;
}
# Annotations live on starlarkbuildapi interfaces; concrete impl methods are resolved reflectively
-keep class * implements com.google.devtools.build.lib.starlarkbuildapi.** { *; }
-keep class com.google.devtools.build.lib.starlarkbuildapi.** { *; }
-keep @net.starlark.java.annot.StarlarkBuiltin class * { *; }
-keep class net.starlark.java.** { *; }

# --- Protobuf full runtime: descriptor-driven reflection resolves accessor methods by name ---
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage$Builder { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage$ExtendableBuilder { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3$Builder { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.AbstractMessage { *; }
-keepclassmembers class * extends com.google.protobuf.AbstractMessage$Builder { *; }
-keepclassmembers class * implements com.google.protobuf.ProtocolMessageEnum { *; }
-keep class com.google.protobuf.** { *; }

# --- gRPC/Netty: heavy internal reflection (channel factories, unsafe field offsets) ---
-keep class io.netty.** { *; }
-keep class io.grpc.** { *; }

# --- GSON reflective (de)serialization ---
-keepclassmembers class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
# GSON-deserialized model classes: keep whole classes so Signature attrs survive
-keep class **_GsonTypeAdapter { <init>(...); *; }
-keep class com.google.devtools.build.lib.bazel.bzlmod.** { *; }
# Full mode prunes generic signatures unless referenced types are also kept (weak keep)
-keep,allowshrinking,allowoptimization,allowaccessmodification class com.google.common.collect.** 
-keep,allowshrinking,allowoptimization,allowaccessmodification class com.google.devtools.build.lib.bazel.repository.downloader.** 
-keep,allowshrinking,allowoptimization,allowaccessmodification class java.util.Optional
-keepclassmembers class com.google.devtools.build.lib.authandtls.credentialhelper.** { <init>(); <fields>; }
-keepclassmembers class com.google.devtools.build.lib.remote.** { <init>(); <fields>; }
-keepclassmembers class com.google.devtools.build.lib.runtime.commands.** { <init>(); <fields>; }
-keep class * extends com.google.gson.TypeAdapter { <init>(...); *; }
-keep class * implements com.google.gson.TypeAdapterFactory { <init>(...); *; }

# --- Skyframe serialization: codecs are discovered by classpath scanning ---
-keep class * implements com.google.devtools.build.lib.skyframe.serialization.ObjectCodec { *; }
-keep class * implements com.google.devtools.build.lib.skyframe.serialization.CodecRegisterer { *; }
-keepclassmembers class * {
  @com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant <fields>;
}

# --- Enum reflection (valueOf / values via converters) ---
-keepclassmembers enum * {
  public static **[] values();
  public static ** valueOf(java.lang.String);
}

# --- RxJava/reactor plugins looked up reflectively ---
-keep class io.reactivex.rxjava3.plugins.RxJavaPlugins { *; }

# --- Flogger is caller-sensitive (stack-walks to find the logging class); inlining breaks it ---
-keep class com.google.common.flogger.** { *; }

# --- JCA: MessageDigest/Provider impls instantiated reflectively by java.security ---
-keep class * extends java.security.MessageDigest { *; }
-keep class * extends java.security.MessageDigestSpi { *; }
-keep class * extends java.security.Provider { *; }

# --- Caffeine builds cache impl class names dynamically (SSA, SSMS, ...) ---
-keep class com.github.benmanes.caffeine.** { *; }

# --- Rule/aspect factories instantiated reflectively by ConfiguredRuleClassProvider ---
-keep class * implements com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory { <init>(); *; }
-keep class * implements com.google.devtools.build.lib.analysis.RuleDefinition { <init>(); *; }
-keep class * implements com.google.devtools.build.lib.packages.NativeAspectClass { <init>(); *; }

# --- @Command annotation read at runtime to register commands ---
-keep @interface com.google.devtools.build.lib.runtime.Command { *; }
-keep @com.google.devtools.build.lib.runtime.Command class * { *; }

# --- All Bazel/Starlark annotation interfaces are potentially read at runtime ---
-keep @interface com.google.devtools.** { *; }
-keep @interface net.starlark.java.** { *; }

# --- Configuration fragments: instantiated reflectively, @RequiresOptions read at runtime ---
-keep class * extends com.google.devtools.build.lib.analysis.config.Fragment { <init>(...); *; }

# --- Guava EventBus discovers @Subscribe methods reflectively ---
-keepclassmembers,includedescriptorclasses class * {
  @com.google.common.eventbus.Subscribe <methods>;
}

# --- Package model: Starlark-reflective dispatch confuses R8 reachability (AbstractMethodError) ---
-keep class * extends com.google.devtools.build.lib.packages.RuleOrMacroInstance { *; }

# --- BlazeModules instantiated reflectively via no-arg constructor ---
-keepclassmembers class * extends com.google.devtools.build.lib.runtime.BlazeModule {
  <init>();
}

# --- LogHandlerQuerier looked up via system property ---
-keep class com.google.devtools.build.lib.util.SimpleLogHandler* { *; }

# --- JUL handlers/formatters instantiated by name from -Djava.util.logging.config ---
-keep class * extends java.util.logging.Handler { *; }
-keep class * extends java.util.logging.Formatter { *; }

# --- Guava reflect: TypeToken/Types introspect their own generic signatures ---
-keep class com.google.common.reflect.** { *; }
-keep class * extends com.google.common.reflect.TypeToken { *; }

# --- Guava Striped64 / AbstractFuture use Unsafe field-name lookups ---
-keepclassmembers class com.google.common.** {
  <fields>;
}

# --- Silence warnings about optional deps not on the compile path ---
-dontwarn **
-ignorewarnings

# Keep line numbers to keep stack traces debuggable in the experiment
-keepattributes SourceFile,LineNumberTable
