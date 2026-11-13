#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// REAL VHS, pass 3: colour on tape (colour-under, SP), at 160 samples a line: about
// 400 kHz of bandwidth, smeared and delayed to the right; AM noise in long streaks,
// a little phase noise, now and then a line without colour.
uniform sampler2D uTex;     // camera pass output
uniform sampler2D uNoise;
uniform vec4 uRand;
uniform float uGain;
uniform float uColour;      // 0..1: colour lock at the start of a shot
varying vec2 vUv;

const float CHROMA_NOISE = 0.048;

vec2 pick(float x) {
    vec2 c = texture2D(uTex, vec2(x, vUv.y)).gb;
    return (c - 0.5) / 0.8;
}

void main() {
    float x = vUv.x;
    vec2 c = vec2(0.0);
    c += 0.25822 * (pick(x - 0.450 / 160.0 - 0.25 / 160.0) + pick(x - 0.450 / 160.0 + 0.25 / 160.0));
    c += 0.15321 * (pick(x - 1.450 / 160.0 - 0.25 / 160.0) + pick(x - 1.450 / 160.0 + 0.25 / 160.0));
    c += 0.06060 * (pick(x - 2.450 / 160.0 - 0.25 / 160.0) + pick(x - 2.450 / 160.0 + 0.25 / 160.0));
    c += 0.01998 * (pick(x - 3.450 / 160.0 - 0.25 / 160.0) + pick(x - 3.450 / 160.0 + 0.25 / 160.0));
    c += 0.00593 * (pick(x - 4.450 / 160.0 - 0.25 / 160.0) + pick(x - 4.450 / 160.0 + 0.25 / 160.0));
    c += 0.00164 * (pick(x - 5.450 / 160.0 - 0.25 / 160.0) + pick(x - 5.450 / 160.0 + 0.25 / 160.0));
    c += 0.00043 * (pick(x - 6.450 / 160.0 - 0.25 / 160.0) + pick(x - 6.450 / 160.0 + 0.25 / 160.0));

    float row = floor(vUv.y * 480.0);
    // AM noise: long streaks along the line
    vec2 nq = vec2(vUv.x * 160.0 / 256.0 * 0.35, (row + 0.5) / 256.0);
    vec4 n1 = texture2D(uNoise, nq + uRand.yx);
    vec4 n2 = texture2D(uNoise, nq * 2.3 + uRand.zw);
    float amp = CHROMA_NOISE * (1.0 + 0.25 * uGain);
    c += (vec2(n1.r + n2.g, n1.b + n2.a) - 1.0) * amp;
    // phase noise: the hue wanders a little from line to line
    float ph = (texture2D(uNoise, vec2((row + 0.5) / 256.0, uRand.z)).r - 0.5) * 0.06;
    c = vec2(c.x * cos(ph) - c.y * sin(ph), c.x * sin(ph) + c.y * cos(ph));
    // chroma loss on the odd line
    float loss = step(0.9992, texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand.x, 0.71)).g);
    c *= (1.0 - loss) * uColour;

    gl_FragColor = vec4(clamp(c * 0.8 + 0.5, 0.0, 1.0), 0.0, 1.0);
}
