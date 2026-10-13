# Quality2 detail cleanup (v74)

Baseline e3a20816c8a6d00e0d5792a756951e8232b9b570 / v73.

The optional viewport detail effect was reported to cause severe scrolling lag. It adds a full-viewport sampling pass on every rendered frame; reducing its strength does not remove the sampling work. v74 removes its everyday settings controls and disables a previously saved enabled flag once on upgrade. The saved strength is retained for benchmark-only comparisons. AI model, output scale, cache identity, source files, manga data and comfort-scale preferences are unchanged. The old shader/control code remains available to the shell-only benchmark receiver for controlled diagnosis; it is not an additional everyday enhancement choice.

A standalone image-space replacement was evaluated on Magic V2 before app integration. Its bounded shared-luminance adjustment uses three rows of scratch, preserves alpha/borders and only modifies opaque neighborhoods. The scalar CPU version failed its cost gate. An ARM NEON version passed the prospective standalone cost gate and matched 24 synthetic fixture/strength comparisons within one byte (observed maximum zero). This is a new effect, not an exact substitute for the screen-space shader or recovery of missing real texture.

However, ordinary color/BW visual benefit was weak, including matched normal-size color comparison and detail crops at strength100. It was therefore rejected for app integration. There is no new sharpening kernel or JNI library in this APK. Independent prototype sources/evidence are preserved outside the product; private comic pixels/device identifiers are not public attachments.

Post-release fling analysis covered twelve previous traces and six fresh variable forward-motion runs. Those CPU coordinate trajectories contained no reverse over1px or height correction; physical judder, input-to-photon latency and complete Quality2 performance improvement remain unproven. Retiring the additional pass does not establish that all reader lag is fixed. This is a conditional cleanup candidate, not full Quality2 PASS.
