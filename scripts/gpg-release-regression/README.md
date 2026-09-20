# GPG release shrinker regression

Debug instrumentation does not exercise R8. This probe packages the actual
compiled `GpgKeyGenerator` with the resolved Bouncy Castle and Kotlin jars,
applies R8 and the app's production ProGuard rules, then runs generation and
private/public import round-trips. It never prints key material or passphrases.

Requirements: build the app first, Python 3, JDK 17, and the build's R8 jar
(AGP's `builder` jar also contains R8). Use the **resolved** runtime versions of
`bcprov`, `bcpg`, `bcutil`, and `kotlin-stdlib`, not arbitrary cached versions.

```text
python scripts/gpg-release-regression/verify.py \
  --java-home <jdk17> --r8 <builder-or-r8.jar> \
  --classes app/build/tmp/kotlin-classes/debug \
  --dependency <bcprov.jar> --dependency <bcpg.jar> \
  --dependency <bcutil.jar> --dependency <kotlin-stdlib.jar> \
  --android-jar <sdk/platforms/android-36/android.jar> \
  --adb <sdk/platform-tools/adb> --serial emulator-5554
```

The Android arguments are optional but recommended. They run the already
shrunk code through D8 and execute it on the selected device via `app_process`.
The temporary device jar is removed afterwards. This is a crypto/shrinker
regression, not a substitute for testing the packaged release UI and storage.

Negative control: pass `--rules` with a copy of the production rules that omits
the two Bouncy Castle provider keep rules. Generation must fail with
`NoSuchAlgorithmException: no such algorithm: RSA for provider BC`. Restoring
the rules must make all five cases pass on both JVM and Android.

The probe removes upstream JAR signature metadata after R8 because those
signatures describe the original bytecode. This mirrors Android packaging;
it does not change application signing or production dependencies.
