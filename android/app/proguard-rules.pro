# R8 rules for the release build, on top of Android's proguard-android-optimize.txt, which
# already keeps native methods (AssNative) and the classes the manifest names. Each library
# brings its own rules. Add a rule here only with a comment that says which code needs it.
