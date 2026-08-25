# UniFFI's generated JNA interface resolves native symbols by method name.
-keep class uniffi.mdbx_ffi.** { *; }
-keep interface uniffi.mdbx_ffi.** { *; }

# JNA's jnidispatch library resolves fields such as Pointer.peer and
# Structure.memory/typeInfo by their original JNI names. R8 renaming these
# members makes every UniFFI call fail before it reaches Rust.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
}
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
