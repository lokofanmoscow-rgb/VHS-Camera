// Pass B: horizontal tape bandwidth filter at the internal tape resolution.
// Luma: low-pass plus the "edge enhancer" of a VCR, which overshoots mostly after
// an edge (to its right), as an analogue peaking circuit does. Bright overshoot
// is limited to a fraction of the headroom left above the picture level, so
// halos never burn out. Chroma: a wider low-pass, delayed slightly to the right.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;
uniform vec2 uInvSize;
varying vec2 vUv;

const float HALO_LIMIT = 0.36;

#define TAP(o, wb, we, wc) c = texture2D(uTex, vUv + vec2(o * uInvSize.x, 0.0)).rgb; l = dot(c, LUMA); base += wb * l; edge += we * l; cs += wc * (c - l);

void main() {
    const vec3 LUMA = vec3(0.299, 0.587, 0.114);
    vec3 c;
    float l;
    float base = 0.0;
    float edge = 0.0;
    vec3 cs = vec3(0.0);
    TAP(-8.0, 0.00000, -0.00164, 0.00236)
    TAP(-7.0, 0.00000, -0.00610, 0.00731)
    TAP(-6.0, 0.00002, -0.02074, 0.01899)
    TAP(-5.0, 0.00041, -0.06153, 0.04148)
    TAP(-4.0, 0.00434, -0.14767, 0.07617)
    TAP(-3.0, 0.02723, -0.24745, 0.11756)
    TAP(-2.0, 0.10116, -0.20039, 0.15254)
    TAP(-1.0, 0.22232, +0.11153, 0.16637)
    TAP(+0.0, 0.28905, +0.40293, 0.15254)
    TAP(+1.0, 0.22232, +0.32097, 0.11756)
    TAP(+2.0, 0.10116, +0.05075, 0.07617)
    TAP(+3.0, 0.02723, -0.08137, 0.04148)
    TAP(+4.0, 0.00434, -0.07124, 0.01899)
    TAP(+5.0, 0.00041, -0.03319, 0.00731)
    TAP(+6.0, 0.00002, -0.01122, 0.00236)
    TAP(+7.0, 0.00000, -0.00300, 0.00064)
    TAP(+8.0, 0.00000, -0.00065, 0.00015)
    float y = base + min(edge, HALO_LIMIT * (1.0 - base));
    gl_FragColor = vec4(clamp(vec3(y) + cs * 1.05, 0.0, 1.0), 1.0);
}
