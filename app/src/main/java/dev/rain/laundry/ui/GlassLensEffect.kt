package dev.rain.laundry.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Magnify the sampled scene; the rounded bevel bends and splits RGB samples. */
internal const val GLASS_LENS_SHADER = """
    uniform shader content;
    uniform float2 iSize;
    uniform float iDensity;
    uniform float iStrength;
    uniform float iTilt;
    half4 main(float2 xy) {
        float2 center = iSize * 0.5;
        float2 p = xy - center;
        float radius = min(center.x, center.y);
        float2 q = abs(p) - center + radius;
        float2 outside = max(q, float2(0.0));
        float distance = length(outside) + min(max(q.x, q.y), 0.0) - radius;
        float edge = clamp(1.0 + distance / (8.0 * iDensity), 0.0, 1.0);
        float bevel = edge * edge;
        float2 normal;
        if (length(outside) > 0.001) {
            normal = normalize(outside) * sign(p);
        } else {
            normal = q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y));
        }
        float2 uv = center + p / (1.0 + 0.18 * iStrength);
        uv += normal * (5.0 * iDensity * bevel * iStrength);
        float split = 1.25 * iDensity * bevel * iStrength;
        half4 base = content.eval(uv);
        half red = content.eval(uv + normal * split).r;
        half blue = content.eval(uv - normal * split).b;
        half3 color = half3(red, base.g, blue);
        float light = pow(max(dot(normal, normalize(float2(-0.45 + iTilt, -1.0))), 0.0), 3.0);
        color += half3(0.10, 0.12, 0.15) * half(light * bevel * iStrength);
        return half4(clamp(color, half3(0.0), half3(base.a)), base.a);
    }
"""

@RequiresApi(33)
internal class GlassLensEffect {
    private val shader = RuntimeShader(GLASS_LENS_SHADER)
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null
    private var lastWidth = Float.NaN
    private var lastHeight = Float.NaN
    private var lastDensity = Float.NaN
    private var lastStrength = Float.NaN
    private var lastTilt = Float.NaN

    fun configure(width: Float, height: Float, density: Float, strength: Float, tilt: Float): androidx.compose.ui.graphics.RenderEffect {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        val amount = strength.coerceIn(0f, 1f)
        val direction = tilt.coerceIn(-.6f, .6f)
        cached?.let { existing ->
            if (lastWidth == w && lastHeight == h && lastDensity == density && lastStrength == amount && lastTilt == direction) return existing
        }
        shader.setFloatUniform("iSize", w, h)
        shader.setFloatUniform("iDensity", density)
        shader.setFloatUniform("iStrength", amount)
        shader.setFloatUniform("iTilt", direction)
        lastWidth = w; lastHeight = h; lastDensity = density; lastStrength = amount; lastTilt = direction
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect().also { cached = it }
    }
}
