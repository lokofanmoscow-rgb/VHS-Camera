#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 2: luminance on tape (SP). One line at a time: the camera's detail
// circuit and the deck's record/playback response together, about 250 TV lines with
// the crisp outline of a VCR's sharpness circuit, trailing slightly to the right;
// the camera's vertical detail from the neighbouring lines of the field; CCD
// smear from bright lights; FM noise, line by line; dropouts.
uniform sampler2D uTex;     // camera pass output, one field (640x240)
uniform sampler2D uSmear;   // 640x1 smear amount
uniform sampler2D uNoise;
uniform vec4 uRand;
uniform float uGain;
varying vec2 vUv;

const float TAPE_NOISE = 0.040;   // VHS SP, about 40 dB, as measured on a real capture
const float V_DETAIL = 0.30;

float hash(vec2 q) { return texture2D(uNoise, q).r; }

void main() {
    float y = 0.0;
    y += -0.00115 * texture2D(uTex, vec2(vUv.x + -6.0 / 640.0, vUv.y)).r;
    y += +0.05558 * texture2D(uTex, vec2(vUv.x + -5.0 / 640.0, vUv.y)).r;
    y += +0.02533 * texture2D(uTex, vec2(vUv.x + -4.0 / 640.0, vUv.y)).r;
    y += -0.11331 * texture2D(uTex, vec2(vUv.x + -3.0 / 640.0, vUv.y)).r;
    y += -0.14525 * texture2D(uTex, vec2(vUv.x + -2.0 / 640.0, vUv.y)).r;
    y += +0.18922 * texture2D(uTex, vec2(vUv.x + -1.0 / 640.0, vUv.y)).r;
    y += +0.57837 * texture2D(uTex, vec2(vUv.x + 0.0 / 640.0, vUv.y)).r;
    y += +0.44516 * texture2D(uTex, vec2(vUv.x + 1.0 / 640.0, vUv.y)).r;
    y += +0.05178 * texture2D(uTex, vec2(vUv.x + 2.0 / 640.0, vUv.y)).r;
    y += -0.10766 * texture2D(uTex, vec2(vUv.x + 3.0 / 640.0, vUv.y)).r;
    y += -0.03949 * texture2D(uTex, vec2(vUv.x + 4.0 / 640.0, vUv.y)).r;
    y += +0.03855 * texture2D(uTex, vec2(vUv.x + 5.0 / 640.0, vUv.y)).r;
    y += +0.01393 * texture2D(uTex, vec2(vUv.x + 6.0 / 640.0, vUv.y)).r;
    y += +0.00671 * texture2D(uTex, vec2(vUv.x + 7.0 / 640.0, vUv.y)).r;
    y += +0.00226 * texture2D(uTex, vec2(vUv.x + 8.0 / 640.0, vUv.y)).r;
    y += -0.00005 * texture2D(uTex, vec2(vUv.x + 9.0 / 640.0, vUv.y)).r;
    // vertical detail (1H delay lines of the camera, within the field)
    float yc = texture2D(uTex, vUv).r;
    float yu = texture2D(uTex, vUv - vec2(0.0, 1.0 / 240.0)).r;
    float yd = texture2D(uTex, vUv + vec2(0.0, 1.0 / 240.0)).r;
    y += V_DETAIL * (yc - 0.5 * (yu + yd));

    // CCD smear: a vertical streak through bright lights. It shows when a light is
    // many stops over the sensor's limit: a lamp in a dim room, where the camera has
    // turned its gain up; in daylight a clipped white detail is only just over it.
    y += texture2D(uSmear, vec2(vUv.x, 0.5)).r * mix(0.12, 1.0, smoothstep(0.8, 2.8, uGain));

    // noise: FM noise of the tape plus the camera's own, more of it in the dark and
    // when the gain is up; every line on its own, a few samples long
    float row = floor(vUv.y * 240.0);
    vec2 rq = vec2(vUv.x * 640.0 / 256.0 * 0.42, (row + 0.5) / 256.0);
    float n = (texture2D(uNoise, rq + uRand.xy).r + texture2D(uNoise, rq * 1.7 + uRand.zw).g) - 1.0;
    float ccd = 0.015 * exp2(0.55 * uGain);
    float lum = clamp(y, 0.0, 1.0);
    y += n * (TAPE_NOISE + ccd * (0.45 + (1.0 - lum)));

    // dropouts: a short white streak now and then
    vec4 dr = texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand.w, uRand.x * 0.37));
    float on = step(0.99985, (dr.r * 255.0 + dr.g) / 256.0);
    float len = 0.006 + 0.03 * dr.b;
    float dd = abs(vUv.x - dr.a);
    y = mix(y, 0.92 + 0.08 * n, on * (1.0 - smoothstep(len * 0.6, len, dd)));

    gl_FragColor = vec4(clamp(y, 0.0, 1.0), 0.0, 0.0, 1.0);
}
