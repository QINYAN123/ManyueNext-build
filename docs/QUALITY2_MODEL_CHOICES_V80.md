# v80 model choices and preparation-only manga fine-tuning

Supersedes the head3-only product selection in v79. User wants both graphs,
in-app evaluation notes/ratings and a future quality-model fine-tune. Formal
training must wait for the user's explicit start instruction.

## Product choices

1. Original Real-ESRGAN4×→2×: original files/ID/runtime/cache/default retained.
2. Native2× output-head candidate: separate ID, asset folder, model runtime
   folder and cache identity. Derived from original backbone; not from scratch.
3. Manyue Lite1–2×: unchanged fallback. Original/OFF remains the mode choice.

Old enum ordinals and stored preferences are preserved. Both quality options
use the same worker capability validation, cooperative cancellation,
look-ahead throughput updates and WebGPU bridge. Adding a new model must not
quietly route it to Canvas or remove cancellation. Model selection invalidates
the current request generation; old images cannot be credited to the new model.

Selected-model conclusions and scores sit directly below the model chips.
The detail dialog explains every score, sample scope and incomplete measurement.
Visual scores are subjective preliminary judgments on the current small sample:
detail4/5 and naturalness4/5 for both quality graphs, detail2/5 and naturalness4/5
for Lite. Scores do not prove information recovery or superiority over official
art. Phone speed, energy and sustained reading are unscored/待测, not zero or
fictional stars. No aggregate score conceals these missing measurements.

Lite's existing checkpoint was verified against packaged trunk/head bytes and
evaluated on the same two private viewing crops, at2×/100%. It preserves the
interpolated original closely while the quality models strengthen lines/text
more visibly. No manga training or asset replacement happened for this review.
Private comic images are not included in source/evidence attachments.

The native2 runtime/arithmetic and three short alternating timing rounds remain
the v79 evidence; some candidate requests were slower. No phone speed or
temperature benefit is proved. Independent Surface presentation and20+page
warm/mixed-cache reading remain pending. v78 is last installed, v79 was not.

## Future training

Prepare licensed, work-separated material and a standalone CUDA pipeline only.
Use current quality64-channel/16-intermediate-convolution backbone weights with
the existing native2 head conversion as seed; no random full-network model.
Retain2× output, FP16 storage and original runtime graph/Native interfaces.
Possible quality improvements do not change the main convolution workload, so
weight fine-tuning alone cannot promise substantial energy reduction.

Starter material: Pepper&Carrot training, independent Duso color validation,
independent Plural Selves black-and-white test. These licensed works are not
claimed representative of all Japanese manga/text/screentones. WEBTOON/Dongman
are suggested visual targets; free viewing is not assumed data-acquisition or
training permission. No bulk scraping or account/DRM access is used.

Losses/paired degradations should improve blur/compression and preserve already
clear examples. Include natural gray/gradient/line checks and constrain neutral
color drift; do not globally erase halftones for a rare title. Full candidate
Android conversion, seam/scroll/heat and independent viewing assessment must
follow training before replacement. No trained weight is accepted merely because
its loss decreases. Pending instructions do not authorize starting an optimizer.
