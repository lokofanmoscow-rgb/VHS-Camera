// Pass B: horizontal tape bandwidth filter at the internal tape resolution.
// Luma: low-pass plus the "edge enhancer" of a VCR, which overshoots mostly after
// an edge (to its right), as an analogue peaking circuit does. Chroma: a wider
// low-pass, delayed slightly to the right.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;
uniform vec2 uInvSize;
varying vec2 vUv;

#define TAP(o, wy, wc) c = texture2D(uTex, vUv + vec2(o * uInvSize.x, 0.0)).rgb; l = dot(c, LUMA); ys += wy * l; cs += wc * (c - l);

void main() {
    const vec3 LUMA = vec3(0.299, 0.587, 0.114);
    vec3 c;
    float l;
    float ys = 0.0;
    vec3 cs = vec3(0.0);
    TAP(-8.0, -0.00196, 0.00236)
    TAP(-7.0, -0.00800, 0.00731)
    TAP(-6.0, -0.02777, 0.01899)
    TAP(-5.0, -0.07702, 0.04148)
    TAP(-4.0, -0.15536, 0.07617)
    TAP(-3.0, -0.18615, 0.11756)
    TAP(-2.0, -0.01793, 0.15254)
    TAP(-1.0, +0.35538, 0.16637)
    TAP(+0.0, +0.62163, 0.15254)
    TAP(+1.0, +0.50772, 0.11756)
    TAP(+2.0, +0.18013, 0.07617)
    TAP(+3.0, -0.03922, 0.04148)
    TAP(+4.0, -0.07976, 0.01899)
    TAP(+5.0, -0.04731, 0.00731)
    TAP(+6.0, -0.01807, 0.00236)
    TAP(+7.0, -0.00515, 0.00064)
    TAP(+8.0, -0.00116, 0.00015)
    gl_FragColor = vec4(clamp(vec3(ys) + cs * 1.05, 0.0, 1.0), 1.0);
}
