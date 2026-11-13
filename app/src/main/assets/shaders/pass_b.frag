// Pass B: horizontal tape bandwidth filter at the internal tape resolution.
//
// The picture: each scan line is low-passed and run through the "edge enhancer" of a
// VCR, which here is matched to the reference tapes on real scenes: mid-size detail
// (objects 10-50 px wide) is lifted by up to 6 dB, fine detail rolls off from about
// 0.07 cycles per output pixel, and the overshoot sits mostly after an edge (to its
// right). Colour gets the same treatment as brightness.
//
// The on-screen display and the black side borders of the tape frame are laid in
// here, after the picture filter, with the edge response measured on the stamp and
// at the borders of the reference tapes: a soft rise and a bright rim whose height
// is limited to a fraction of the headroom above the picture.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;     // tape frame without the display
uniform sampler2D uOsd;     // premultiplied on-screen display
uniform vec2 uInvSize;
varying vec2 vUv;

const float HALO_LIMIT = 0.36;
const float LEFT = 7.5;          // picture starts / ends here, in tape pixels
const float RIGHT = 473.17;

#define PIC(o, w) pic += w * texture2D(uTex, vUv + vec2(o * uInvSize.x, 0.0)).rgb;
#define EDGE(o, wg, we) m = clamp(px + o - LEFT, 0.0, 1.0) * clamp(RIGHT - px - o, 0.0, 1.0); mg += wg * m; me += we * m; \
    q = texture2D(uOsd, vUv + vec2(o * uInvSize.x, 0.0)); ga += wg * q.a; ea += we * q.a; go += wg * q.rgb; eo += we * q.rgb;

void main() {
    vec3 pic = vec3(0.0);
    PIC(-16.0, +0.00494)
    PIC(-15.0, +0.00879)
    PIC(-14.0, +0.00783)
    PIC(-13.0, -0.00493)
    PIC(-12.0, -0.02162)
    PIC(-11.0, -0.01955)
    PIC(-10.0, +0.00684)
    PIC(-9.0, +0.02813)
    PIC(-8.0, +0.00561)
    PIC(-7.0, -0.06302)
    PIC(-6.0, -0.13878)
    PIC(-5.0, -0.18293)
    PIC(-4.0, -0.20239)
    PIC(-3.0, -0.19537)
    PIC(-2.0, -0.02193)
    PIC(-1.0, +0.42309)
    PIC(+0.0, +0.78423)
    PIC(+1.0, +0.65401)
    PIC(+2.0, +0.27962)
    PIC(+3.0, +0.04881)
    PIC(+4.0, -0.04190)
    PIC(+5.0, -0.09688)
    PIC(+6.0, -0.11619)
    PIC(+7.0, -0.08163)
    PIC(+8.0, -0.02254)
    PIC(+9.0, +0.01252)
    PIC(+10.0, +0.00557)
    PIC(+11.0, -0.01887)
    PIC(+12.0, -0.02799)
    PIC(+13.0, -0.01644)
    PIC(+14.0, -0.00237)
    PIC(+15.0, +0.00290)
    PIC(+16.0, +0.00241)

    float px = vUv.x / uInvSize.x;
    float m;
    float mg = 0.0;      // border mask, low-passed
    float me = 0.0;      // border mask, edge part
    vec4 q;
    float ga = 0.0;      // display coverage, low-passed / edge part
    float ea = 0.0;
    vec3 go = vec3(0.0); // display colour, low-passed / edge part
    vec3 eo = vec3(0.0);
    EDGE(-8.0, 0.00000, -0.00164)
    EDGE(-7.0, 0.00000, -0.00610)
    EDGE(-6.0, 0.00002, -0.02074)
    EDGE(-5.0, 0.00041, -0.06153)
    EDGE(-4.0, 0.00434, -0.14767)
    EDGE(-3.0, 0.02723, -0.24745)
    EDGE(-2.0, 0.10116, -0.20039)
    EDGE(-1.0, 0.22232, +0.11153)
    EDGE(+0.0, 0.28905, +0.40293)
    EDGE(+1.0, 0.22232, +0.32097)
    EDGE(+2.0, 0.10116, +0.05075)
    EDGE(+3.0, 0.02723, -0.08137)
    EDGE(+4.0, 0.00434, -0.07124)
    EDGE(+5.0, 0.00041, -0.03319)
    EDGE(+6.0, 0.00002, -0.01122)
    EDGE(+7.0, 0.00000, -0.00300)
    EDGE(+8.0, 0.00000, -0.00065)

    vec3 base = pic * (mg - ga) + go;
    vec3 edge = pic * (me - ea) + eo;
    vec3 y = base + min(edge, HALO_LIMIT * max(1.0 - base, 0.0));
    gl_FragColor = vec4(clamp(y, 0.0, 1.0), 1.0);
}
