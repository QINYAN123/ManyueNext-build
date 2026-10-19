# Static channel specialization, before candidate measurement

Timestamp pilot: 400x400 color, wall255.3ms, GPU chunks189.6ms +tail18.5ms; first16 Conv174.6ms/PReLU15.0ms. Observation overhead estimate -0.42% is noise, plain bracket265.3→249.1ms; not negative overhead or energy evidence. Thus no more geometry sweep: group32 exact but -18.46%; group64x8 exact but+2.73% below5%.

New hypothesis: original unshaped graph leaves Winograd GEMM channel specializations c/outc=0, reading runtime push constants although weight-defined input/output channels are fixed64. Supply c=16 and outc=16 for eligible64->64 pack4 only; retain dynamic spatial sizes/cstep, original4x4x4 group, FP16-storage/FP32 arithmetic, math and16chunk boundaries. This may allow constant loop/index optimization, no MAC reduction or guaranteed speed. Env MANYUE_GEMM_STATIC_CHANNELS=1, default off; geometry flags explicitly off. No param/weight changes or fake spatial shape hints.

Gates unchanged: rebuilt off versus stock zero RGB; on/off zero RGB, >=5% pilot wall gain and CPU<=+10%; then three alternating rounds color/line-gray/text, full Native seam equivalence and reader/sustained regression before adoption. If numerical gate fails, do not relax it. Retain v92 installed until accepted.
