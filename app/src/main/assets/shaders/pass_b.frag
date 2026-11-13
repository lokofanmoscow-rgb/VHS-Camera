// Pass B: horizontal tape bandwidth filter at the internal tape resolution.
// Each scan line is low-passed and then run through the "edge enhancer" of a VCR,
// which overshoots mostly after an edge (to its right), as an analogue peaking
// circuit does. Bright overshoot is limited to a fraction of the headroom left
// above the picture level, so halos never burn out. Colour gets the same treatment
// as brightness (measured on the reference tapes: coloured edges are as crisp, and
// overshoot the same way).
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;
uniform vec2 uInvSize;
varying vec2 vUv;

const float HALO_LIMIT = 0.36;

#define TAP(o, wb, we) c = texture2D(uTex, vUv + vec2(o * uInvSize.x, 0.0)).rgb; base += wb * c; edge += we * c;

void main() {
    vec3 c;
    vec3 base = vec3(0.0);
    vec3 edge = vec3(0.0);
    TAP(-8.0, 0.00000, -0.00164)
    TAP(-7.0, 0.00000, -0.00610)
    TAP(-6.0, 0.00002, -0.02074)
    TAP(-5.0, 0.00041, -0.06153)
    TAP(-4.0, 0.00434, -0.14767)
    TAP(-3.0, 0.02723, -0.24745)
    TAP(-2.0, 0.10116, -0.20039)
    TAP(-1.0, 0.22232, +0.11153)
    TAP(+0.0, 0.28905, +0.40293)
    TAP(+1.0, 0.22232, +0.32097)
    TAP(+2.0, 0.10116, +0.05075)
    TAP(+3.0, 0.02723, -0.08137)
    TAP(+4.0, 0.00434, -0.07124)
    TAP(+5.0, 0.00041, -0.03319)
    TAP(+6.0, 0.00002, -0.01122)
    TAP(+7.0, 0.00000, -0.00300)
    TAP(+8.0, 0.00000, -0.00065)
    vec3 y = base + min(edge, HALO_LIMIT * (1.0 - base));
    gl_FragColor = vec4(clamp(y, 0.0, 1.0), 1.0);
}
