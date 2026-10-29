# The bridge is small, but R8 still needs to know about the Binder/AIDL and
# manifest entry points. Keep the bridge package and the shared AIDL interface
# intact; the rest of the Compose dependency graph is still tree-shaken.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class org.sunsetware.phocid.bridge.** { *; }
-keep class org.sunsetware.phocid.agent.** { *; }
