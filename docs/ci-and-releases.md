# Plan: CI and releases

Nothing here is built yet. This is the plan for automating what is currently
done by hand: running the tests, deciding version numbers, and publishing a
release. It is written to be implemented one step at a time, and to be argued
with first — §7 lists what still needs deciding.

Where this says **verified**, it was checked against this repository or the
GitHub API in September 2026. Everything else is an estimate.

## 1. Where things stand

**verified** No `.github/` directory, no branch protection, no pull requests;
everything is pushed straight to `main`. Releases are built on one laptop with
`./gradlew clean :setup:assembleRelease`, tagged `v1.0.0-pre.N`, and published
as a GitHub pre-release carrying one asset, `phone-setup-release-<version>.apk`,
with notes written for a non-technical colleague in the field.

**verified** Until `v1.0.0-pre.4` every release shipped `versionCode = 1`. The
kiosk replaces itself only on a strictly newer version code, so no phone in
service could ever have taken a kiosk update. pre.4 ships version code 2, set by
hand. Getting this right automatically is half the point of this plan.

**verified** The release key lives in a gitignored `keystore.properties` at the
repository root. Without it `assembleRelease` falls back to debug signing
silently, and a debug-signed fleet can never receive production updates. Read
[KEYS.md](../KEYS.md) before touching any of this.

## 2. Goals

1. Every push runs the JVM tests and builds both apps, so a broken build or a
   stale golden fixture shows up in minutes.
2. The instrumented suites run without anyone setting Device Owner by hand, and
   a failure is readable from the run page rather than from a pass/fail count.
3. A release is reproducible: the version numbers, the signature and the
   artifact name follow from the tag, and a release that is not properly signed
   cannot be published.
4. Nothing exotic. GitHub's own actions, one emulator action, and shell. The
   maintaining team works in React Native, not Kotlin.

Not goals: Play Store or AAB publishing, minification and mapping upload,
coverage, dependency bots, self-hosted runners, generating the field notes from
commit messages.

## 3. Workflows

### `ci.yml` — every push and pull request

Runs on `ubuntu-latest` with JDK 17 and Gradle caching:

- `./gradlew :shared:testDebugUnitTest :setup:testDebugUnitTest :setup:assembleDebug`
  — the last target builds `:kiosk:app` and bundles it, so this covers the whole
  build graph.
- `git diff --exit-code -- testdata/` catches a golden fixture regenerated but
  not committed.
- On failure, upload the test reports. Always upload the debug Phone Setup APK,
  so a test build can be fetched without a laptop.

Estimated 3–5 minutes with a warm cache.

### `instrumented.yml` — pushes to `main`, pull requests, manual runs

Two jobs in parallel, one emulator each, using
`reactivecircus/android-emulator-runner` pinned to a commit. API 30, an **AOSP**
image (`target: default`), `x86_64` on Linux runners with KVM enabled.

Gradle Managed Devices were considered and rejected: they boot, install and run
in one step, with no hook in between where `dpm set-device-owner` can run. That
would push emulator setup into the tests themselves, where a failure reads like
a product bug.

- **`owner`** (AVD named `kiosk_aosp_30`): install the kiosk APK, run
  `dpm set-device-owner`, fail the job if it does not report success, then
  `dumpsys battery set ac 1` and `svc power stayon true` — without them the lock
  task tests ANR when the screen sleeps. Then the Device Owner suite.
- **`ui`** (AVD named `kiosk_ui_30`): the launcher tests and the setup app tests,
  as two Gradle invocations — a `package=` runner argument applies to every
  module in one invocation. If `ComposeSmokeTest` fails, the summary says "wrong
  device role", not "UI broken".

Both jobs end by turning the XML under
`**/build/outputs/androidTest-results/connected/debug/` into a Markdown table in
the job summary: test name, failure message, and the **skipped count**. A skip
is not a pass: every Device-Owner test starts with `assumeTrue(isDeviceOwner)`,
so a lost owner would otherwise show as a green run.

Keep the AVD names as they are, so `tools/test.sh` finds them by name unchanged.
[handshake-and-api-matrix.md](handshake-and-api-matrix.md) plans two extensions
to this workflow: a matrix of API levels for `owner`, and a third job that runs
the setup wizard's own provisioning flow on a fresh emulator.
`tools/test.sh` needs subcommands (`owner`, `ui`, `setup`) so CI can run one
role; with no argument it should behave exactly as it does today.

Estimated 20–30 minutes for `owner`, 15–25 for `ui`. Free on public repositories.

### Flaky tests

[CONTRIBUTING](../CONTRIBUTING.md) documents a framework NullPointerException
inside `system_server` that fails one `LockTaskTest` test per whole-suite run and
reproduces on unmodified `main`; the test passes when run alone. Lock task also
degrades the longer an emulator has been up, which CI avoids by always booting
fresh.

Policy: after a failure, reboot the emulator and rerun **only** the failed
classes, plus any class that never reported (an instrumentation crash takes out
everything after it). Failing twice fails the job. The summary marks anything
that passed only on the retry, so a test that always needs one gets noticed
rather than hidden. This encodes CONTRIBUTING's own rule: one failure is
evidence of nothing, a consistent failure of the same test is.

A single retry can hide a real intermittent bug. The "passed on retry" column is
the mitigation, and it should be read.

## 4. Version numbers from counters-api

[counters-api](https://github.com/digidem/counters-api), deployed at
counters.awana.digital, increments a counter atomically and returns the new
value. Use it for the version code:

- **Increment once per release**, in the release script or the release job:
  `PUT /simple-phone/versionCode/inc` with the bearer token, then pass the result
  to Gradle as `-PversionCode=N`, alongside `-PversionName=1.0.0-pre.4` derived
  from the tag. Gradle only reads the two properties.
- **Never inside Gradle.** It would fire on every configuration, sync and test
  run, and every build would need the network and the token.
- **One counter for both apps.** They are separate packages; both only need their
  own numbers to keep increasing. Set the counter above anything already shipped
  before the first use — pre.4 is version code 2.
- **Ordinary builds** get version code 1 and a version name like
  `0.0.0-dev+<commit>`, and never touch the counter. A locally built release can
  then never replace the kiosk on a phone in the field, because 1 is never
  strictly newer.
- **Record the number with the release**, since it can no longer be derived from
  the tag: in the release notes and in the annotated tag message.

Compared with deriving a version code from the tag arithmetically, a counter
cannot collide between branches, has no digit limits, and does not care if a
draft is deleted and redone.

**The risk to know about:** counters-api has a single shared token that can also
`set` or `delete` any counter, including other projects'. Anything holding it
could reset this counter below a version already on phones, and those phones
would then silently refuse every update until the counter climbed past it again.
Two mitigations, both cheap: the release script refuses to publish unless the new
version code is higher than the one in the previous release's APK (read with
`aapt2 dump badging`), and only the release step ever holds the token. Per
namespace tokens in counters-api would be the real fix, and are a change to that
repository.

### Where the version name comes from

Today `versionName` is a literal in `kiosk/app/build.gradle.kts` and
`setup/build.gradle.kts`; the tag is not read by the build at all. It should come
from the tag, with the leading `v` stripped. It is worth getting right because it
is what identifies a build everywhere it matters: Android's app info screen, the
kiosk's own "About this phone" screen, `kioskVersion` in every setup report the
trainer's phone receives, and the Sentry release name
(`org.awana.kiosk@<versionName>+<versionCode>`).

## 5. Releasing

Trigger: pushing a tag `v*`, which matches what is done by hand today.

Safeguards, all in one `tools/verify-release-apk.sh` used by both the local
script and CI:

1. `apksigner verify --print-certs` on the Phone Setup APK: exactly one signer,
   SHA-256 equal to `8766564a627e0cccac979be06ed3fb7203358df1a8b78ca66798f56850f85d42`.
   This is the check that catches a debug-signed build. The digest is not a
   secret; it travels in every setup QR code.
2. The same check on `assets/kiosk.apk` inside it.
3. Both APKs carry the expected package name, version name and version code.
4. The new version code is higher than the previous release's.
5. The tag is an ancestor of `main`, and `ci.yml` passed for that commit.
6. A Gradle property `-PrequireReleaseSigning=true` that makes `assembleRelease`
   **fail** without `keystore.properties` instead of falling back to debug. CI
   and the release script always pass it; local builds are unaffected.

The workflow publishes a **draft** pre-release with the asset, a `SHA256SUMS`
file, and a body listing the commits since the last tag under the existing
"New:" / "Fixes:" headings. Gregor rewrites it for the field and publishes.
Drafts do not notify anyone, so a half-written release is invisible. Generated
notes are wrong for this audience, and there are no pull requests to summarise.

Do not attach the kiosk APK as a separate asset: it is inside the Phone Setup
APK, and a loose copy invites installing it by hand on an unmanaged phone.

### Where the signing key lives

**Option A, now: sign locally, let CI verify.** `tools/release.sh` on a
custodian's machine builds, runs the checks above, and creates the draft. A
workflow re-checks every published asset. The key never leaves its custodians;
the cost is that releases need that laptop.

**Option B, later: the key in a GitHub `release` environment.** Keystore and
password as environment secrets, the environment restricted to `v*` tags with a
required reviewer, and a tag ruleset so only maintainers can create those tags.
Pull requests never see the secrets. The cost is that a copy of the key exists in
GitHub's secret store; the risk is a compromised maintainer account, not losing
the key, since GitHub holds a copy rather than the original.

Option B should wait until the KEYS.md custody table lists at least two verified
offline holders. Option A's verification script is reused unchanged by B, so
nothing is wasted by starting with A.

Sentry releases: skip for now. Minification is off, so there are no mappings to
upload, and the SDK already tags events with the version. Revisit if
minification is turned on.

## 6. Order of work

Each step is one commit, small enough to review, with something to check at the
end of it.

1. `ci: run the JVM tests and build on every push`. Check: green on `main`, a
   second run hits the Gradle cache, and a deliberately broken golden fixture
   fails.
2. `test(tools): let test.sh run one emulator role`. Check: locally with both
   emulators; no-argument behaviour unchanged.
3. `ci: run the Device Owner suite on an emulator`. Check: same pass count as
   locally, zero skipped.
4. `ci: run the launcher and setup UI suites on a clean emulator`. Check:
   `ComposeSmokeTest` passes.
5. `ci: retry failed instrumented classes once after a reboot`. Check: run it a
   dozen times on `main` and record what needed a retry. That data decides
   question 2 below.
6. `build: take the version name and code from the release tag`. Check: a build
   with `-PreleaseTag` and `-PversionCode` reports them in `aapt2 dump badging`;
   an ordinary build reports `0.0.0-dev+<commit>` and 1.
7. `build: fail a release build that is not release-signed`. Check: moving
   `keystore.properties` aside makes `assembleRelease -PrequireReleaseSigning=true`
   fail with a message naming KEYS.md.
8. `tools: add verify-release-apk.sh and release.sh`, including the counters-api
   increment. Check: run the verifier against the published pre.4 asset, then cut
   the next release with the script. This is option A, working.
9. `ci: verify every published release asset`. Check: a publish goes green; an
   old version code on a scratch draft goes red.
10. Repository settings: the `release` environment, the tag ruleset, and `main`
    requiring `ci / unit`.
11. `ci: build and sign releases from the tag` — only if option B is chosen and
    custody is recorded. Check with a throwaway tag, then delete it.
12. `docs: describe CI and the release procedure` in CONTRIBUTING and KEYS.md,
    and fold this plan into them once it is real.

## 7. What still needs deciding

1. **Where the release key lives** — option A now, option B later, or A
   permanently. B needs the KEYS.md custody table filled in first.
2. **Do the instrumented jobs block merges?** Suggested: informational at first,
   then require `ui`, and require `owner` only once its retry rate is known to be
   low. A required check that is flaky teaches people to press re-run without
   reading.
3. **Pull requests, or keep pushing to `main`?** Suggested: keep pushing for now;
   the release tag is the gate that matters.
4. **The counters-api namespace and counter name.** Suggested
   `simple-phone/versionCode`, and the token supplied through the environment
   rather than a file in the repository.
5. **Who holds the counters-api token**, and whether counters-api should grow per
   namespace tokens before this depends on it.
