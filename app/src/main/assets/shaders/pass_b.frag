// Pass B: horizontal tape bandwidth filter at the internal tape resolution.
// Luma: low-pass plus the wide "edge enhancer" halo of a VCR. Chroma: a wider
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
    TAP(-8.0, -0.00553, 0.00236)
    TAP(-7.0, -0.01344, 0.00731)
    TAP(-6.0, -0.02860, 0.01899)
    TAP(-5.0, -0.05090, 0.04148)
    TAP(-4.0, -0.06528, 0.07617)
    TAP(-3.0, -0.02510, 0.11756)
    TAP(-2.0, +0.12379, 0.15254)
    TAP(-1.0, +0.34020, 0.16637)
    TAP(+0.0, +0.44973, 0.15254)
    TAP(+1.0, +0.34020, 0.11756)
    TAP(+2.0, +0.12379, 0.07617)
    TAP(+3.0, -0.02510, 0.04148)
    TAP(+4.0, -0.06528, 0.01899)
    TAP(+5.0, -0.05090, 0.00731)
    TAP(+6.0, -0.02860, 0.00236)
    TAP(+7.0, -0.01344, 0.00064)
    TAP(+8.0, -0.00553, 0.00015)
    gl_FragColor = vec4(clamp(vec3(ys) + cs * 1.05, 0.0, 1.0), 1.0);
}
