# Contributing to J Code

Contributions are welcome — bug reports, fixes, and pull requests.

## Pull requests

1. Fork the repo and create a branch off `main`.
2. Build and verify locally (`./gradlew :app:assembleDebug`; see
   [`README.md`](README.md) for the toolchain — JDK 21, NDK r27c, CMake 3.28+).
3. Keep changes focused; match the surrounding code style. The module rule is
   strict: `:core:*` never depends on `:feature:*`.
4. Open the PR against `main` with a clear description of the change and how you
   verified it.

## License of contributions

J Code is licensed under the **MIT License** (see [`LICENSE`](LICENSE)). By submitting a
contribution (a pull request, patch, or any change intentionally submitted for inclusion in the
Work), you agree it is provided under the **same MIT terms** and that you have the right to
submit it. If your employer holds rights to your work,
make sure you have permission to contribute.

## Reporting issues

Open a GitHub issue with steps to reproduce, the device/Android version, and
relevant logs. For anything that may be a security vulnerability, please contact
the Licensor privately rather than filing a public issue.
