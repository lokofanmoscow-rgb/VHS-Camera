#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 1: the camcorder. Turns the camera picture into one video field of
// 640x240 in YIQ. A camcorder records 60 fields a second, each a moment of its own:
// every camera frame becomes one field, its lines the sum of two sensor lines (CCD
// field integration), the second field's pairs one line lower. Tone and colour of a
// 90s CCD camera (hard white clip, crushed blacks, muted greens) and the burned-in
// date. No lag: a CCD has none. Noise is added by the tape passes.
uniform sampler2D uCur;      // camera picture, this frame (960x720)
uniform sampler2D uPrevCam;  // camera picture, the frame before
uniform sampler2D uOsd;      // premultiplied date stamp (960x720)
uniform float uZoom;         // digital zoom on top of the camera's own
uniform float uGain;         // camera gain in stops above daylight
uniform float uField;        // 0 or 1: which field this is
uniform float uShutter;      // 0..0.25: share of the frame before, to stretch a short phone exposure to 1/60 s
varying vec2 vUv;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);
vec3 toYiq(vec3 c) {
    return vec3(dot(c, LUMA), dot(c, vec3(0.596, -0.274, -0.322)), dot(c, vec3(0.211, -0.523, 0.312)));
}

vec3 grab(sampler2D t, vec2 p) {
    vec2 q = 0.5 + (p - 0.5) / uZoom;
    vec2 d = vec2(0.45 / 640.0, 0.5 / 480.0) / uZoom;
    // the two sensor lines of this field line, 960 -> 640 across
    return 0.25 * (texture2D(t, q + vec2(-d.x, -d.y)).rgb + texture2D(t, q + vec2(d.x, -d.y)).rgb +
                   texture2D(t, q + vec2(-d.x, d.y)).rgb + texture2D(t, q + vec2(d.x, d.y)).rgb);
}

void main() {
    // field line k: sensor lines 2k+f and 2k+f+1 of the 480-line frame
    float k = floor(vUv.y * 240.0);
    vec2 p = vec2(vUv.x, (2.0 * k + uField + 1.0) / 480.0);
    vec3 c = grab(uCur, p);
    if (uShutter > 0.0) c = mix(c, grab(uPrevCam, p), uShutter);

    // CCD camera: little latitude. Blacks crush, whites clip hard.
    c = clamp((c - 0.04) / (0.93 - 0.04), 0.0, 1.0);
    vec3 yiq = toYiq(c);
    // muted greens and purples, skin and reds kept; less colour in the dark when the gain is up
    float sat = clamp(1.0 - 0.07 * uGain, 0.55, 1.0);
    yiq.y *= 0.94 * sat;
    yiq.z *= 0.80 * sat;
    // near white the colour goes: a clipped channel drags the others up
    yiq.yz *= 1.0 - smoothstep(0.82, 1.0, yiq.x);

    // date stamp from the camera's character generator, on the lines of this field
    vec2 h = vec2(0.0, 0.5 / 480.0);
    vec4 osd = 0.5 * (texture2D(uOsd, p - h) + texture2D(uOsd, p + h));
    yiq = yiq * (1.0 - osd.a) + vec3(dot(osd.rgb, LUMA), 0.0, 0.0);

    gl_FragColor = vec4(clamp(yiq.x, 0.0, 1.0), clamp(yiq.y * 0.8 + 0.5, 0.0, 1.0), clamp(yiq.z * 0.8 + 0.5, 0.0, 1.0), 1.0);
}
