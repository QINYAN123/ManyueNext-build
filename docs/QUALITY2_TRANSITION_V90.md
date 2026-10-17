# Quality2 v90 candidate

Baseline: v89 commit `d17679289dd1f8b7789cc13f36aeced1d9f16d6c`, APK SHA-256 `5057f80895dba776e6a3c7c1cfeed20c13bee98c6ad5c4a420f7f1eb0fa9359e`.

## Reproduced correctness issue

On an explicit next-chapter jump followed by the first pan, continuous read-through could report the prior chapter's transition as its last real page. A v89 device trace selected the new chapter's page zero, then old page 68, then new page zero; the previously unread chapter became read. Transition geometry alone does not prove that the prior chapter was actually read. The candidate distinguishes a real forward crossing or a real prior-chapter image anchor from entering the previous transition after an explicit jump. Queued delivery must retain the same selection epoch and page ownership. Natural forward completion, real backward reading and ordinary image-page progress remain required regression cases.

## Isolated heat experiment

A default-OFF benchmark command `severehead` permits a paced test of exactly thermal status 3 and explicit bottom-visible priority 120. It does not enable priority 100 or prefetch, and status 4 or above remains blocked. Enable starts with an eight-second cooldown. Every actual Native wrapper call, including subsequently cancelled work, counts toward a start interval of at least eight seconds and a rest after completion of at least twice its measured runtime. Cache hits do not consume a ticket. Reader owner changes disable the trial; stale completion cannot refill a new owner's budget. The final Native entrance also rechecks current pressure after a possible pause/resume.

The motivating v89 warm new-chapter trace had two valid exposures without AI during approximately 19.46 and 11.55 seconds, held by the application thermal gate. This establishes a scheduling hypothesis, not an energy improvement. The trial remains OFF pending matched device evidence. WebGPU does not currently feed the existing frame-pressure policy; preserving that policy in host tests does not prove actual WebGPU jank protection.

No model weights, Native binaries, network structure, output sizes, shader, sampling, detail filter, user preference or system thermal settings change. Runtime-wrapper cadence is not measured GPU duty or watts. Source tests and a successful APK build do not establish sustained throughput, temperature, power, visible scanout or subjective smoothness.

ARM64 benchmark uses package `app.mihon.benchmark` and the existing benchmark certificate. Device scope and acceptance belong to the separate artifact report; no formal Quality2 performance PASS is implied here.
