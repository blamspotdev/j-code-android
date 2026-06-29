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

## License of contributions (important)

J Code is distributed under the **Business Source License 1.1**, which converts to
the **Apache License 2.0** on each version's Change Date (see [`LICENSE`](LICENSE)).

By submitting a contribution (a pull request, patch, or any change "intentionally
submitted for inclusion in the Work"), you agree that:

- you have the right to submit it, and
- you license your contribution to the Licensor and to all recipients under the
  **same terms as the project** — i.e. the Business Source License 1.1 with the
  Apache License 2.0 as the Change License — and you grant the Licensor permission
  to **relicense your contribution under the Change License (Apache-2.0) on the
  Change Date**, and to offer it under alternative commercial license terms.

This lightweight inbound=outbound grant (plus the relicensing permission) keeps the
project's licensing coherent and lets the whole work open up to Apache-2.0 on
schedule. If your employer holds rights to your work, make sure you have permission
to contribute. For substantial contributions we may ask you to confirm this in the
PR.

## Reporting issues

Open a GitHub issue with steps to reproduce, the device/Android version, and
relevant logs. For anything that may be a security vulnerability, please contact
the Licensor privately rather than filing a public issue.
