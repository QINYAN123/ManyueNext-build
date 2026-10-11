# Manyue WebGPU display adapter

Kotlin sources are pinned to mpreg-ca/webgpuviewer tag47, commit
740a5eb1fcc646455c2c19ea6ce15b4b71c43402 (MIT; see LICENSE.upstream).
upstream.json records unmodified source hashes. Local changes add successful-frame
page feedback and correct the failed-frame status used by that feedback.

The Gradle transform strips only the upstream viewer Kotlin classes from its
verified Maven AAR, retaining the existing JNI and AndroidX WebGPU bytes. Models,
Manyue Native inference binaries and original images are not changed. No runtime
binary or signing key needs to be added to the source repository.

Feedback confirms encoded draws submitted with a presentation request; it does
not claim physical display scanout completion. Viewport and result generations
must still be checked by the host.
