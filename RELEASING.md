# Releasing

The version lives in `version.properties` (`VERSION_NAME=MAJOR.MINOR.PATCH`). `versionCode` is derived from it:
`MAJOR*10000 + MINOR*100 + PATCH` (0.11.0 -> 1100, 1.2.3 -> 10203), so it always increases with the version.
The version is shown at the bottom of the app's start screen.

## Make a release (from the phone, no git needed)
GitHub -> repository -> **Actions** -> **Release** -> **Run workflow** -> choose `patch` (bug fixes), `minor` (new features)
or `major` (big changes), optionally write notes -> Run.
It runs the tests, builds `RemoteControl-vX.Y.Z.apk`, commits the bumped `version.properties`, tags `vX.Y.Z` and
publishes a GitHub Release with the APK and an automatic change list.

## Or tag it yourself
`git tag v1.2.3 && git push --tags` builds and publishes exactly that version.

## Normal pushes
`Build APKs` (build.yml) only tests and builds; it does not publish.

## Note on updating the installed app
Debug APKs are signed with a debug key that GitHub creates fresh on each run, so Android refuses to install a new APK over
an old one ("signatures do not match"): uninstall first. To make updates install in place, sign every build with one fixed
keystore stored as GitHub secrets (ask for this to be added).
