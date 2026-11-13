# Android build validation after isolated toolchain approval

This supersedes the earlier worker reports' “Android not compiled / installation not authorized” state. Those reports remain historical implementation records. Machine-readable final evidence is `ANDROID_BUILD_VERIFICATION.json` in the parent task directory.

## Verified results

The final frozen implementation was checked with the isolated JDK 21, Android SDK Platform 37.0 / Build Tools 37.0.0, project-pinned Gradle 9.7.1 and CI-pinned ktlint 1.8.0.

| Check | Result |
|---|---|
| `assembleDebug` | Success; validation APK generated |
| `testDebugUnitTest` | 2,493 tests in 245 suites; 0 failures, errors or skips |
| `checkColorLiterals` | Success |
| `lintDebug` | Success; 0 errors, 269 warnings, 24 hints |
| `compileReleaseKotlin` | Success; not a signed release APK |
| `compileDebugAndroidTestKotlin` | Success; instrumentation tests compiled, not executed |
| ktlint for 28 task-modified/added Kotlin files | Success |
| Repository-wide `ktlintCheck` | Failed on 408 pre-existing findings in 41 untouched files |

The combined Gradle command therefore returned exit 1, solely for repository-wide ktlint. It is not an all-green build. An isolated exact-HEAD source mirror produced 409 findings; after scoped formatting, every remaining current file was byte-identical to HEAD and every remaining diagnostic matched the baseline. No checks, rules or assertions were disabled, no style baseline was added, and unrelated files were not bulk-formatted.

Android lint initially found direct ViewModel construction inside a composable in the pre-existing external-services UI test. The fixture now uses lifecycle `viewModel<McpServersViewModel>()`; lint and instrumentation compilation passed after the correction. ktlint was originally absent and was installed in the isolated toolchain with the official release digest verified.

## Artifact and signing

Validation artifact: `artifacts/hermes-mobile-phase3-validation-2-debug.apk` in the parent task directory.

- Application ID: `com.m57.hermescontrol`
- Version: `1.0.2-dev`, versionCode `2`
- Bytes: `47608455`
- APK SHA-256: `b00e1e7202d920265e3a4e806ef32f3f512141491d77d63b2a54efb1fdde4db1`
- Signature verified using Android SDK `apksigner`.
- Validation certificate SHA-256: `e6e4831783b9e9bd2a35db6d18ef308e99cf3033db9c2f9aeaf433bf22cc24c4`
- Received baseline certificate SHA-256: `ca40054482a6e52b8e63dfdffe886542591e0592388a0d742b5c7ab5aaec1ef6`

The certificates differ. This APK is a validation artifact, NOT an in-place update for the received baseline. The connected test S10's installed APK was independently read and verified to use the received baseline certificate; its app/data were not changed. A matching original keystore must be recovered from the original build environment and used to sign the next APK before deployment. Do not place that key in Git/source ZIPs, replace installed signing identity, uninstall or clear data to work around the mismatch.

The embedded baseline SHA remains `49e2068`; this is an uncommitted working-tree implementation, not that commit alone. The machine-readable report records a changed-source hash ledger, and the review bundle records all included source hashes.

## Unverified / not performed

- Rendered UI, emulator and physical-device execution: not run. `/dev/kvm` is not accessible to this user, and no physical-device installation was authorized.
- Actual provider/model execution for Phase 3: not invoked; deployment and target activation remain separate.
- Production plugin deployment, service restart, app update publication and device installation: not performed.
- Signing-key migration: pending access to the original signing key; certificates/public keys from an APK cannot replace its private key.

## Reproduction

Use the task-local `run_isolated_gradle.py` runner to preserve the received CRLF wrapper and isolate JAVA_HOME, SDK, Gradle cache and Android user home. Final command:

```text
python3 run_isolated_gradle.py --no-daemon --max-workers=1 --console=plain
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
  -Pkotlin.compiler.execution.strategy=in-process
  -PversionCode=2 -PversionName=1.0.2-dev
  assembleDebug testDebugUnitTest ktlintCheck checkColorLiterals lintDebug
  compileReleaseKotlin compileDebugAndroidTestKotlin --continue
```

The line breaks above are display wrapping, not a shell script. The exact invocations and full logs are retained under the parent task's `build-verification/` directory.
