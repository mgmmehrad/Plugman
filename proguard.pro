# ------------------------------------------------------------------
# Plugman ProGuard rules
# ------------------------------------------------------------------
# Goal: full obfuscation — random class/member names, no readable package
# structure left on disk, jar shrunk to the minimum. The ONLY thing that
# must stay exactly as-is is the Velocity plugin entry point, because
# Velocity finds it by the literal string "net.mehradmgm.plugman.Plugman"
# written into velocity-plugin.json (generated at compile time from the
# @Plugin annotation) and then instantiates it and invokes its @Subscribe
# methods via reflection. Rename that one class and Velocity can no longer
# find it — "Unable to load plugin" at boot.
#
# Every other class in this project (ReflectionBridge, PluginController,
# PlugmanCommand, BuildConstants, LoadResult, ...) is safe to rename and
# repackage freely: all of Plugman's own reflection calls look up methods
# on VELOCITY's internal classes (VelocityPluginManager, JavaPluginLoader,
# etc.) by literal name, never on Plugman's own classes — so obfuscating
# our own code cannot break that reflection.
# ------------------------------------------------------------------

-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# Collapse every non-kept class into a single flat package nested under our
# own namespace (net.mehradmgm.plugman.<random>), instead of leaving the
# full net/mehradmgm/plugman/core/... structure visible on disk, and
# instead of moving classes out to the root package.
-repackageclasses 'net.mehradmgm.plugman'
-flattenpackagehierarchy 'net.mehradmgm.plugman'

# Random (not human-readable / not dictionary-based) names for every class,
# field and method that isn't explicitly kept below.
-useuniqueclassmembernames
-overloadaggressively

# Keep only what Velocity/Guice must find by reflection. Everything else in
# the jar, including class and method names, is fair game for renaming.
-keep class net.mehradmgm.plugman.Plugman {
    public <init>(...);
    public *** onProxyInitialize(...);
    public *** onProxyShutdown(...);
}

# Keep annotation attributes in general so annotation-driven behavior stays
# intact after obfuscation (Guice's @Inject, Velocity's @Subscribe scanning).
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod

# Keep line numbers (but not source file names) so server logs still show
# usable stack traces without revealing the original source layout.
-keepattributes LineNumberTable
-renamesourcefileattribute ''

# Anything annotated for reflection-driven discovery must keep its name,
# wherever it ends up after repackaging.
-keepclassmembers class * {
    @com.velocitypowered.api.event.Subscribe *;
    @com.google.inject.Inject <init>(...);
    @com.google.inject.Inject <fields>;
}

# velocity-api and guice are compileOnly (provided by the proxy at runtime,
# never bundled into this jar), so ProGuard can't resolve them from our
# classpath here — don't warn/error about that.
-dontwarn com.velocitypowered.**
-dontwarn com.google.inject.**
-dontwarn com.mojang.**
-dontwarn net.kyori.**
-dontwarn org.slf4j.**
-dontwarn io.netty.**
-dontnote net.mehradmgm.plugman.**

# Write out a mapping file so stack traces from a shrunk/renamed jar can
# still be de-obfuscated locally later if you ever need to debug a crash.
# Delete this line if you don't want a mapping file produced at all.
-printmapping build/proguard/mapping.txt