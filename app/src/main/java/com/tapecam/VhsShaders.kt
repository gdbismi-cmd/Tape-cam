package com.tapecam

/**
 * GLSL for the tape look. Everything happens in one pass:
 *  - per-line horizontal jitter, slow wobble and a rolling tracking band
 *  - head-switching noise at the bottom of the frame
 *  - YIQ split: soft luma, smeared + shifted chroma (colour bleed)
 *  - sharpening "ringing", warm grade, lifted blacks
 *  - grain, white dropouts, scanlines, vignette
 *  - camcorder on-screen display composited on top
 */
object VhsShaders {

    const val VERTEX = """
attribute vec4 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = aUv;
}
"""

    const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;

uniform samplerExternalOES uCam;
uniform sampler2D uOsd;
uniform mat4 uCamMatrix;
uniform float uTime;
uniform float uIntensity;
uniform float uGlitch;
uniform vec2 uRes;
varying vec2 vUv;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 cam(vec2 uv) {
    uv = clamp(uv, 0.001, 0.999);
    vec2 t = (uCamMatrix * vec4(uv, 0.0, 1.0)).xy;
    return texture2D(uCam, t).rgb;
}

vec3 toYiq(vec3 c) {
    return vec3(dot(c, vec3(0.299, 0.587, 0.114)),
                dot(c, vec3(0.596, -0.274, -0.322)),
                dot(c, vec3(0.211, -0.523, 0.312)));
}

vec3 toRgb(vec3 c) {
    return vec3(c.x + 0.956 * c.y + 0.621 * c.z,
                c.x - 0.272 * c.y - 0.647 * c.z,
                c.x - 1.106 * c.y + 1.703 * c.z);
}

void main() {
    vec2 uv = vUv;
    float I = uIntensity;
    float line = floor(uv.y * uRes.y * 0.5);
    float frame = floor(uTime * 30.0);

    // --- timebase errors -------------------------------------------------
    float jitter = (hash(vec2(line, frame)) - 0.5) * 0.0025 * I;
    jitter += sin(uv.y * 6.0 + uTime * 1.3) * 0.0015 * I;

    // rolling tracking band
    float bandY = 1.0 - fract(uTime * 0.07);
    float band = smoothstep(0.06, 0.0, abs(uv.y - bandY)) * (0.25 + uGlitch) * I;
    jitter += band * (hash(vec2(line, uTime)) - 0.5) * 0.06;

    // glitch bursts (TRACK button / random)
    float burst = step(0.82, hash(vec2(floor(uv.y * 18.0), floor(uTime * 15.0)))) * uGlitch;
    jitter += burst * (hash(vec2(line * 0.1, uTime)) - 0.5) * 0.15;

    // head-switching noise at the bottom edge
    float hs = smoothstep(0.035, 0.0, uv.y) * I;
    jitter += hs * 0.04 * (hash(vec2(line, frame)) + 0.3);

    uv.x += jitter;

    // --- limited bandwidth: soft luma, smeared colour --------------------
    float px = 1.0 / uRes.x;
    float lb = mix(1.0, 2.5, I);
    float Y = toYiq(cam(uv)).x * 0.5
            + 0.25 * (toYiq(cam(uv + vec2(px * lb, 0.0))).x
                    + toYiq(cam(uv - vec2(px * lb, 0.0))).x);

    vec2 iq = vec2(0.0);
    float shift = 0.006 * I + px * 2.0;
    for (int k = 0; k < 5; k++) {
        float o = (float(k) - 2.0) * 0.004 * I + shift;
        iq += toYiq(cam(uv + vec2(o, 0.0))).yz;
    }
    iq /= 5.0;

    // sharpening ringing / edge halo
    float Yleft = toYiq(cam(uv - vec2(px * 4.0, 0.0))).x;
    Y += (Y - Yleft) * 0.25 * I;

    vec3 col = toRgb(vec3(Y, iq * mix(1.0, 0.8, I)));

    // --- grade -------------------------------------------------------------
    col = mix(col, col * vec3(1.06, 1.0, 0.9) + vec3(0.03, 0.02, 0.04), I);
    col = mix(col, smoothstep(-0.05, 1.05, col), 0.5 * I);

    // --- tape noise --------------------------------------------------------
    float n = hash(floor(uv * uRes) + fract(uTime) * 100.0);
    col += (n - 0.5) * 0.08 * I;

    float drop = step(0.9985 - 0.003 * uGlitch,
                      hash(vec2(floor(uv.y * uRes.y), floor(uv.x * 40.0) + frame)));
    col = mix(col, vec3(1.0), drop * I * 0.8);

    col += band * (hash(uv * uRes + uTime) - 0.3) * 0.5;
    col = mix(col, vec3(hash(uv * uRes + uTime)), hs * 0.6);

    // --- display -----------------------------------------------------------
    float sl = 0.5 + 0.5 * sin(vUv.y * uRes.y * 3.14159);
    col *= mix(1.0, 0.86 + 0.14 * sl, I);

    vec2 d = vUv - 0.5;
    col *= 1.0 - dot(d, d) * 0.6 * I;

    // --- camcorder OSD (premultiplied alpha) --------------------------------
    vec2 ouv = vec2(vUv.x + jitter * 0.5, 1.0 - vUv.y);
    vec4 o = texture2D(uOsd, ouv);
    col = col * (1.0 - o.a * 0.95) + o.rgb * 0.95;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
}
