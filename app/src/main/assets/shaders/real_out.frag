#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 4: playback into a capture card, one field -> a 960x720 frame.
// The field's lines land on every other line of the frame, half a line lower for the
// second field, and the capture fills the lines between (bob), so motion keeps all 60
// moments a second. Each line comes off the tape with its own time-base error: a slow
// wander over a few lines and a little line-to-line jitter, as on a deck without TBC.
// The top lines bend a little (skew); the last lines are torn sideways where the heads
// switch; the deck averages the colour of neighbouring lines. Black borders where the
// capture window is wider than the picture.
uniform sampler2D uY;       // 640x240 luminance, one field
uniform sampler2D uC;       // 160x240 colour
uniform sampler2D uNoise;
uniform vec4 uRand;
uniform vec4 uRand2;
uniform float uWobble;      // 1 = normal; more while the servo locks at the start of a shot
uniform float uField;       // 0 or 1: which field
varying vec2 vUv;

const float BLACK = 0.045;          // set-up: picture black sits above blanking
const float HS_LINES = 5.0;         // field lines torn at the bottom
const float HS_SHIFT = 0.028;       // their shift at the very bottom, picture widths
const float WANDER = 0.0040;        // slow time-base wander, picture widths (about 55 ns rms)
const float JITTER = 0.0026;        // line-to-line jitter (about 40 ns rms)

float lineShift(float fl) {
    // slow wander of the time base, eased over about nine lines
    float t = fl * 0.11;
    float i = floor(t);
    float f = t - i;
    f = f * f * (3.0 - 2.0 * f);
    float w0 = texture2D(uNoise, vec2((i + 0.5) / 256.0 + uRand.x, uRand.y)).r;
    float w1 = texture2D(uNoise, vec2((i + 1.5) / 256.0 + uRand.x, uRand.y)).r;
    float w = mix(w0, w1, f) - 0.5;
    float j = texture2D(uNoise, vec2((fl + 0.5) / 256.0 + uRand.z, uRand.w)).g - 0.5;
    float s = (WANDER * w + JITTER * j) * uWobble;
    // skew at the top of the field
    s -= 0.0035 * pow(max(0.0, 1.0 - fl / 7.0), 2.0);
    // head switching at the bottom of the field
    float k = fl - (240.0 - HS_LINES);
    if (k >= 0.0) {
        float hn = texture2D(uNoise, vec2((fl + 0.5) / 256.0 + uRand2.x, uRand2.y)).b - 0.5;
        s += HS_SHIFT * pow((k + 1.0) / HS_LINES, 1.5) + 0.004 * hn;
    }
    return s;
}

vec2 colourAt(float x, float fl) {
    vec2 a = texture2D(uC, vec2(x, (fl + 0.5) / 240.0)).rg;
    vec2 b = texture2D(uC, vec2(x, (max(fl - 1.0, 0.0) + 0.5) / 240.0)).rg;
    return ((a + b) * 0.5 - 0.5) / 0.8;
}

vec3 lineAt(float fl, float x) {
    float xs = x - lineShift(fl);
    float y = texture2D(uY, vec2(xs, (fl + 0.5) / 240.0)).r;
    vec2 iq = colourAt(xs, fl);
    // the first torn line switches part-way along
    if (fl == 240.0 - HS_LINES) {
        float at = 0.90 + 0.04 * (uRand2.z - 0.5);
        if (x < at) {
            xs = x;
            y = texture2D(uY, vec2(xs, (fl + 0.5) / 240.0)).r;
            iq = colourAt(xs, fl);
        } else {
            y += 0.25 * pow(max(0.0, 1.0 - (x - at) * 60.0), 3.0);
        }
    }
    // outside the recorded line there is nothing but blanking
    float inside = step(0.0, xs) * step(xs, 1.0);
    // the first line of a field is blanking, the very last half blanking and noise
    inside *= step(0.5, fl);
    y *= inside;
    iq *= inside;
    if (fl >= 239.0) {
        y = y * 0.35 + 0.08 * texture2D(uNoise, vec2(x * 2.5 + uRand2.w, fl / 256.0)).r;
    }
    return vec3(y, iq);
}

void main() {
    // frame line of this pixel, and where it falls between the lines of the field
    float r = vUv.y * 480.0 - 0.5;
    float kf = (r - uField) * 0.5;
    float k0 = clamp(floor(kf), 0.0, 239.0);
    float k1 = min(k0 + 1.0, 239.0);
    float f = clamp(kf - k0, 0.0, 1.0);
    vec3 yiq = mix(lineAt(k0, vUv.x), lineAt(k1, vUv.x), f);

    vec3 rgb = vec3(yiq.x + 0.956 * yiq.y + 0.621 * yiq.z,
                    yiq.x - 0.272 * yiq.y - 0.647 * yiq.z,
                    yiq.x - 1.106 * yiq.y + 1.703 * yiq.z);
    rgb = BLACK + (1.0 - BLACK + 0.02) * clamp(rgb, 0.0, 1.0);

    // capture window: black where the line has no picture
    float pic = smoothstep(0.006, 0.013, vUv.x) * (1.0 - smoothstep(0.972, 0.979, vUv.x));
    rgb *= pic;

    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
