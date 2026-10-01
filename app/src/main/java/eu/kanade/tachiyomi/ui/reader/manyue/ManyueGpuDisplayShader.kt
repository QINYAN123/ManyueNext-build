package eu.kanade.tachiyomi.ui.reader.manyue

/** Screen-space detail enhancement, not super-resolution or a learned model. */
internal object ManyueGpuDisplayShader {
    // Nine samples in one pass. Work follows the viewport, never the height of a strip image.
    // Keep alpha and RGB channel differences; only apply a bounded common luminance shift.
    val SOURCE = """
        uniform shader content;
        uniform float2 viewportSize;
        uniform float strength;

        float luminance(float3 rgb) {
            return dot(rgb, float3(0.2126, 0.7152, 0.0722));
        }

        float sampleLuma(float2 p, float centerLuma) {
            half4 s = content.eval(clamp(p, float2(0.5), viewportSize - float2(0.5)));
            float a = float(s.a);
            float3 rgb = float3(s.rgb) / max(a, 0.0001);
            return mix(centerLuma, luminance(rgb), clamp(a, 0.0, 1.0));
        }

        half4 main(float2 p) {
            half4 original = content.eval(p);
            float a = float(original.a);
            if (a <= 0.0001 || strength <= 0.0) return original;
            float3 rgb = float3(original.rgb) / a;
            float center = luminance(rgb);
            float n = sampleLuma(p + float2(0.0, -1.0), center);
            float s = sampleLuma(p + float2(0.0, 1.0), center);
            float w = sampleLuma(p + float2(-1.0, 0.0), center);
            float e = sampleLuma(p + float2(1.0, 0.0), center);
            float low = min(center, min(min(n, s), min(w, e)));
            float high = max(center, max(max(n, s), max(w, e)));
            float range = high - low;
            float fine = center - (n + s + w + e) * 0.25;
            float broad = center - (
                sampleLuma(p + float2(0.0, -3.0), center) +
                sampleLuma(p + float2(0.0, 3.0), center) +
                sampleLuma(p + float2(-3.0, 0.0), center) +
                sampleLuma(p + float2(3.0, 0.0), center)
            ) * 0.25;
            // Attenuate high-contrast text edges and small fluctuations in flat skin/sky areas.
            float edgeGain = 1.0 - 0.75 * smoothstep(0.08, 0.40, range);
            float noiseGate = smoothstep(0.003, 0.020, abs(fine));
            float shift = strength * edgeGain * (0.60 * fine * noiseGate + 0.12 * broad);
            float limit = 0.06 * strength;
            shift = clamp(shift, -limit, limit);
            float darkest = min(rgb.r, min(rgb.g, rgb.b));
            float brightest = max(rgb.r, max(rgb.g, rgb.b));
            shift = clamp(shift, -darkest, 1.0 - brightest);
            return half4(half3((rgb + float3(shift)) * a), original.a);
        }
    """.trimIndent()
}
