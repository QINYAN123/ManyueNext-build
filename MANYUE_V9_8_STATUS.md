# Manyue v9.8 build verification

- GitHub Actions run: 36443081293
- Source commit: da557f41dc3d27563788df7fe2df0b6f28349b60
- Native assets: SHA-256 and ARM64 ELF verification passed.
- Manyue JVM unit tests: passed.
- ARM64 release APK: assembled and APK signature verification passed.
- APK contents: Real-CUGAN runner/model and Anime4K runner present.
- v9.8 continuous AI patch: applied after v9.7; covered by the CI unit-test selectors listed above.
- Clean: executed before compilation. Android lint report is published separately; warnings/errors require review.
- Device verification has not been performed; real-device visual behavior and performance remain to be checked.
