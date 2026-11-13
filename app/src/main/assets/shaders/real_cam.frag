#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 1: the camcorder. Turns the camera picture into one interlaced video
// frame of 640x480 in YIQ: two fields, each line the sum of two sensor lines (CCD
// field integration), the second field a moment earlier than the first, the tone and
// colour of a 90s CCD camera (hard white clip, crushed blacks, muted greens), the
// burned-in date, and the lag of a 1/60 s shutter. Noise is added by the tape passes.
uniform sampler2D uCur;      // camera picture, this frame (960x720)
uniform sampler2D uPrevCam;  // camera picture, previous frame
uniform sampler2D uPrevOut;  // this pass, previous frame
uniform sampler2D uOsd;      // premultiplied date stamp (960x720)
uniform float uZoom;         // digital zoom on top of the camera's own
uniform float uGain;         // camera gain in stops above daylight
varying vec2 vUv;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);
vec3 toYiq(vec3 c) {
    return vec3(dot(c, LUMA), dot(c, vec3(0.596, -0.274, -0.322)), dot(c, vec3(0.211, -0.523, 0.312)));
}

vec3 grab(sampler2D t, vec2 p) {
    vec2 q = 0.5 + (p - 0.5) / uZoom;
    vec2 d = vec2(0.25 / 640.0, 1.0 / 480.0) / uZoom;
    // two neighbouring sensor lines (field integration), 960 -> 640 across
    return 0.25 * (texture2D(t, q + vec2(-d.x, 0.0)).rgb + texture2D(t, q + vec2(d.x, 0.0)).rgb +
                   texture2D(t, q + vec2(-d.x, d.y)).rgb + texture2D(t, q + vec2(d.x, d.y)).rgb);
}

void main() {
    vec2 p = vUv;
    float row = floor(p.y * 480.0);
    vec3 c = grab(uCur, p);
    // the odd field was exposed 1/60 s before the even one
    if (mod(row, 2.0) > 0.5) c = 0.5 * (c + grab(uPrevCam, p));

    // CCD camera: little latitude. Blacks crush, whites clip hard.
    c = clamp((c - 0.04) / (0.93 - 0.04), 0.0, 1.0);
    vec3 yiq = toYiq(c);
    // muted greens and purples, skin and reds kept; less colour in the dark when the gain is up
    float sat = clamp(1.0 - 0.07 * uGain, 0.55, 1.0);
    yiq.y *= 0.94 * sat;
    yiq.z *= 0.80 * sat;
    // near white the colour goes: a clipped channel drags the others up
    yiq.yz *= 1.0 - smoothstep(0.82, 1.0, yiq.x);

    // date stamp from the camera's character generator
    vec4 osd = texture2D(uOsd, p);
    yiq = yiq * (1.0 - osd.a) + vec3(dot(osd.rgb, LUMA), 0.0, 0.0);

    // shutter / sensor lag
    vec4 prev = texture2D(uPrevOut, p);
    vec3 prevYiq = vec3(prev.r, (prev.g - 0.5) / 0.8, (prev.b - 0.5) / 0.8);
    float lag = clamp(0.22 + 0.035 * uGain, 0.22, 0.45);
    yiq = mix(yiq, prevYiq, lag);

    gl_FragColor = vec4(clamp(yiq.x, 0.0, 1.0), clamp(yiq.y * 0.8 + 0.5, 0.0, 1.0), clamp(yiq.z * 0.8 + 0.5, 0.0, 1.0), 1.0);
}
