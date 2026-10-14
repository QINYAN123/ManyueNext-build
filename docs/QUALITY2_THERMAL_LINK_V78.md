# v78 WebGPU thermal integration candidate

Baseline v77: 9f43625dae2a3133228a091dad5388ad0af5c230. User reports prolonged reading heat, stutter and screen dimming. System sample outside reader: 36C battery/status LIGHT1, USB charging, brightness225/manual; this is not a reproduced reading slowdown or confirmed display thermal dimming.

Source evidence: thermal reports were wired only through WebtoonRecyclerView. WebGPU readers never acquired ManyueReaderWorkGate or subscribed to PowerManager thermal changes. Restores lifecycle/attachment-scoped status monitoring for WebGPUContinuous and Pager, with epoch guards for stale callbacks, removal on pause/detach/destroy, and initial current-status reading. No frame-pressure heuristic is imported from main-window FPS into the independent Surface.

At Android MODERATE2 only priority<50 distant speculative inference is held; current and nearest priority>=50 continue. At SEVERE3 the existing protection remains. Cooling restores the N+X cursor, no new fixed page limit. Ready display is not hidden by thermal gating. This is a candidate reduction of avoidable distant work, not proof of cooler operation or adequate AI coverage. It does not alter resolution, models, shader, touch/fling physics, border persistence, system brightness or user's120Hz setting. A running Native operation is not preempted by this change.

Diagnostics now export eventOriginMonotonicNs/eventClock: report relative tNs plus origin reconstructs Android MONOTONIC time. Perfetto clock snapshots convert MONOTONIC to BOOTTIME; don't directly combine unlike clocks. This does not create actual Surface presentation timestamps.

Host tests cover lifetime, stale callbacks, unsupported registration/removal, thermal priority/cooling and time-origin reconstruction. Android registration, prolonged heat, brightness, current coverage and120Hz presentation still need candidate device validation. APK remains a benchmark candidate; no full Quality2 PASS. Phone may be unavailable when user takes it away; do not invent device results.

Separate PC experiment authorized: native2x output head with retained64-channel/16-intermediate-conv backbone,12 output channels plus one PixelShuffle2 and a folded small skip convolution, no internal4x candidate tensor/downscale. An analytic5x5 head preserves the old output in the interior/context-padded FP32 checks; a smaller3x3 head is a later bounded distillation experiment. Old model is teacher/control only in host comparisons. This is structural conversion, not manga-specific fine-tuning. Unpadded boundaries, Android arithmetic/tile seams and ordinaryBW/generalization remain open. No candidate is placed in APK before numerical/quality/motion validation. Output-head reduction is only a small part of total backbone computation; no proportional4x->2x inference speed claim.

Sources: https://developer.android.com/games/optimize/adpf/thermal and project's original model param. No root or disabled thermal safeguards.
