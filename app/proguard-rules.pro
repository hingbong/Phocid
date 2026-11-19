# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers so crash reports remain actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Intentional R8 trade-off (do not "fix" without a full regression pass):
# -dontobfuscate plus the two broad -keep rules below mean minifyEnabled=true effectively only
# performs resource shrinking (shrinkResources) and limited unused-code removal inside libraries.
# Real code shrinking would require replacing the keep-all rules with narrow entry-point keeps,
# which is risky with Compose, jaudiotagger reflection and generated AppFunctions classes.
# Obfuscation provides absolutely zero size reduction.
-dontobfuscate

# Hack to fix VerifyError for Jetpack Compose. This doesn't seem to impact binary size anyway.
-keep class org.sunsetware.phocid.** { *; }

# This library uses reflection.
-keep class org.jaudiotagger.** { *; }
-keep class org.jcodec.** { *; }
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.ImageWriter
-dontwarn javax.imageio.stream.ImageInputStream
-dontwarn javax.imageio.stream.ImageOutputStream
-dontwarn javax.swing.filechooser.FileFilter
-dontwarn sun.security.action.GetPropertyAction
