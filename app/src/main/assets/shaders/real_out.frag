#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 4: playback into a capture card, 640x480 interlaced -> 960x720.
// Each line comes off the tape with its own small time-base error; the top lines
// bend a little (skew); the last lines of each field are torn sideways where the
// heads switch; the deck averages the colour of neighbouring lines of a field.
// Black borders where the capture window is wider than the picture.
uniform sampler2D uY;       // 640x480 luminance
uniform sampler2D uC;       // 160x480 colour
uniform sampler2D uNoise;
uniform vec4 uRand;
uniform vec4 uRand2;
uniform float uWobble;      // 1 = normal; more while the servo locks at the start of a shot
varying vec2 vUv;

const float BLACK = 0.045;          // set-up: picture black sits above blanking
const float HS_LINES = 5.0;         // field lines torn at the bottom
const float HS_SHIFT = 0.028;       // their shift at the very bottom, picture widths

float lineShift(float row) {
    float fl = floor(row * 0.5);
    // slow wobble of the time base, the same along a few lines, drifting in time
    float w = texture2D(uNoise, vec2(fl / 256.0 * 0.11 + uRand.x, uRand.y)).r - 0.5;
    float j = texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand.z, uRand.w)).g - 0.5;
    float s = (0.0016 * w + 0.0005 * j) * uWobble;
    // skew at the top of the field
    s -= 0.0035 * pow(max(0.0, 1.0 - fl / 7.0), 2.0);
    // head switching at the bottom of the field
    float k = fl - (240.0 - HS_LINES);
    if (k >= 0.0) {
        float hn = texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand2.x, uRand2.y)).b - 0.5;
        s += HS_SHIFT * pow((k + 1.0) / HS_LINES, 1.5) + 0.004 * hn;
    }
    return s;
}

vec2 colourAt(float x, float row) {
    vec2 a = texture2D(uC, vec2(x, (row + 0.5) / 480.0)).rg;
    vec2 b = texture2D(uC, vec2(x, (max(row - 2.0, 0.0) + 0.5) / 480.0)).rg;
    return ((a + b) * 0.5 - 0.5) / 0.8;
}

vec3 lineAt(float row, float x) {
    float xs = x - lineShift(row);
    float y = texture2D(uY, vec2(xs, (row + 0.5) / 480.0)).r;
    vec2 iq = colourAt(xs, row);
    float fl = floor(row * 0.5);
    // the first torn line switches part-way along
    if (fl == 240.0 - HS_LINES) {
        float at = 0.90 + 0.04 * (uRand2.z - 0.5);
        if (x < at) {
            xs = x;
            y = texture2D(uY, vec2(xs, (row + 0.5) / 480.0)).r;
            iq = colourAt(xs, row);
        } else {
            y += 0.25 * pow(max(0.0, 1.0 - (x - at) * 60.0), 3.0);
        }
    }
    // outside the recorded line there is nothing but blanking
    float inside = step(0.0, xs) * step(xs, 1.0);
    y *= inside;
    iq *= inside;
    // the very last line of a field is half blanking and noise
    if (fl >= 239.0) {
        y = y * 0.35 + 0.08 * texture2D(uNoise, vec2(x * 2.5 + uRand2.w, row / 256.0)).r;
    }
    return vec3(y, iq);
}

void main() {
    float r = vUv.y * 480.0 - 0.5;
    float r0 = clamp(floor(r), 0.0, 479.0);
    float r1 = min(r0 + 1.0, 479.0);
    float f = clamp(r - r0, 0.0, 1.0);
    vec3 yiq = mix(lineAt(r0, vUv.x), lineAt(r1, vUv.x), f);

    vec3 rgb = vec3(yiq.x + 0.956 * yiq.y + 0.621 * yiq.z,
                    yiq.x - 0.272 * yiq.y - 0.647 * yiq.z,
                    yiq.x - 1.106 * yiq.y + 1.703 * yiq.z);
    rgb = BLACK + (1.0 - BLACK + 0.02) * clamp(rgb, 0.0, 1.0);

    // capture window: black where the line has no picture
    float pic = smoothstep(0.006, 0.013, vUv.x) * (1.0 - smoothstep(0.986, 0.992, vUv.x));
    rgb *= pic;

    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
