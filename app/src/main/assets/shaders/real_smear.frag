#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS: CCD vertical smear. For every column, how much of it is taken by small,
// very bright sources (a lamp, a glint): only blown-out spots no wider than a lamp
// that sit in clearly darker surroundings count, so a white wall, white paper or a
// blown sky does not smear - a sensor only just over its limit does not leak.
uniform sampler2D uTex;   // camera pass output (Y in r)
varying vec2 vUv;
float white(vec2 q) { return smoothstep(0.985, 0.998, texture2D(uTex, q).r); }
float bright(vec2 q) { return smoothstep(0.50, 0.68, texture2D(uTex, q).r); }
void main() {
    float s = 0.0;
    vec2 side = vec2(24.0 / 640.0, 0.0);
    for (int k = 0; k < 120; k++) {
        vec2 q = vec2(vUv.x, (float(k) * 4.0 + 2.0) / 480.0);
        s += white(q) * (1.0 - max(bright(q - side), bright(q + side)));
    }
    s /= 120.0;
    float amount = 5.0 * s * (1.0 - smoothstep(0.035, 0.11, s));
    gl_FragColor = vec4(amount, 0.0, 0.0, 1.0);
}
