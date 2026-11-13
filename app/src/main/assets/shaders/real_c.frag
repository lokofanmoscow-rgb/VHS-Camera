#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 3: colour on tape (colour-under, SP), at 160 samples a line: about
// 250 kHz of bandwidth all told, smeared and a little late; AM noise in soft
// streaks along the line, a little phase noise, now and then a line without colour.
uniform sampler2D uTex;     // camera pass output, one field
uniform sampler2D uNoise;
uniform vec4 uRand;
uniform float uGain;
uniform float uColour;      // 0..1: colour lock at the start of a shot
varying vec2 vUv;

const float CHROMA_NOISE = 0.034;

vec2 pick(float x) {
    vec2 c = texture2D(uTex, vec2(x, vUv.y)).gb;
    return (c - 0.5) / 0.8;
}

// smooth noise along the line: a random value every 1/rate samples, eased between
vec2 streak(float t, float row, vec2 off) {
    float i = floor(t);
    float f = t - i;
    f = f * f * (3.0 - 2.0 * f);
    vec4 a = texture2D(uNoise, vec2((i + 0.5) / 256.0, (row + 0.5) / 256.0) + off);
    vec4 b = texture2D(uNoise, vec2((i + 1.5) / 256.0, (row + 0.5) / 256.0) + off);
    return mix(a.rb, b.rb, f) - 0.5;
}

void main() {
    float x = vUv.x;
    vec2 c = vec2(0.0);
    c += 0.12920 * (pick(x + 1.150 / 160.0 - 0.25 / 160.0) + pick(x + 1.150 / 160.0 + 0.25 / 160.0));
    c += 0.14076 * (pick(x + 0.150 / 160.0 - 0.25 / 160.0) + pick(x + 0.150 / 160.0 + 0.25 / 160.0));
    c += 0.10224 * (pick(x - 0.850 / 160.0 - 0.25 / 160.0) + pick(x - 0.850 / 160.0 + 0.25 / 160.0));
    c += 0.06188 * (pick(x - 1.850 / 160.0 - 0.25 / 160.0) + pick(x - 1.850 / 160.0 + 0.25 / 160.0));
    c += 0.03371 * (pick(x - 2.850 / 160.0 - 0.25 / 160.0) + pick(x - 2.850 / 160.0 + 0.25 / 160.0));
    c += 0.01714 * (pick(x - 3.850 / 160.0 - 0.25 / 160.0) + pick(x - 3.850 / 160.0 + 0.25 / 160.0));
    c += 0.00830 * (pick(x - 4.850 / 160.0 - 0.25 / 160.0) + pick(x - 4.850 / 160.0 + 0.25 / 160.0));
    c += 0.00388 * (pick(x - 5.850 / 160.0 - 0.25 / 160.0) + pick(x - 5.850 / 160.0 + 0.25 / 160.0));
    c += 0.00176 * (pick(x - 6.850 / 160.0 - 0.25 / 160.0) + pick(x - 6.850 / 160.0 + 0.25 / 160.0));
    c += 0.00078 * (pick(x - 7.850 / 160.0 - 0.25 / 160.0) + pick(x - 7.850 / 160.0 + 0.25 / 160.0));
    c += 0.00034 * (pick(x - 8.850 / 160.0 - 0.25 / 160.0) + pick(x - 8.850 / 160.0 + 0.25 / 160.0));

    float row = floor(vUv.y * 240.0);
    // AM noise: soft streaks along the line, every line its own
    float t = x * 160.0 * 0.4;
    vec2 n = streak(t, row, uRand.yx) + 0.5 * streak(t * 2.2, row, uRand.zw);
    c += n * CHROMA_NOISE * (1.0 + 0.25 * uGain);
    // phase noise: the hue wanders a little from line to line
    float ph = (texture2D(uNoise, vec2((row + 0.5) / 256.0, uRand.z)).r - 0.5) * 0.06;
    c = vec2(c.x * cos(ph) - c.y * sin(ph), c.x * sin(ph) + c.y * cos(ph));
    // chroma loss on the odd line
    float loss = step(0.9992, texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand.x, 0.71)).g);
    c *= (1.0 - loss) * uColour;

    gl_FragColor = vec4(clamp(c * 0.8 + 0.5, 0.0, 1.0), 0.0, 1.0);
}
