// Pass C: playback artefacts at the output resolution.
// Colour mis-registration, interlace weave, ghost, tape dropouts, the
// head-switching band at the bottom and the start/stop glitches.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uCur;     // filtered tape frame
uniform sampler2D uPrev;    // previous filtered tape frame (other field)
uniform sampler2D uNoise;
uniform vec4 uRand;         // per-frame random numbers
uniform vec4 uRand2;
uniform float uStatic;      // 0..1 snow + picture wobble (tape start)
uniform vec4 uBand;         // travelling "held lines" band: top, height, x shift, smear
uniform float uWeave;       // 0..1 strength of the field weave
varying vec2 vUv;

const float PX = 1.0 / 960.0;

vec3 tape(sampler2D t, vec2 s) {
    // Red sits ~1.5 px to the left of green, blue ~1.9 px to the right.
    return vec3(texture2D(t, s + vec2(1.5 * PX, 0.0)).r,
                texture2D(t, s).g,
                texture2D(t, s - vec2(1.9 * PX, 0.0)).b);
}

void main() {
    vec2 uv = vUv;
    float row = floor(uv.y * 240.0);
    vec4 rn = texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand.x, uRand.y));
    vec4 rn2 = texture2D(uNoise, vec2((row + 0.5) / 256.0 + uRand.z, uRand.w));

    vec2 s = uv;

    // Picture wobble while the tape is getting up to speed.
    float dx = uStatic * (0.010 * sin(uv.y * 31.0 + uRand.z * 6.2832) +
                          0.022 * (rn.w - 0.5) * step(0.6, rn2.x));

    // Travelling band of held (vertically stretched) lines.
    float inBand = step(uBand.x, uv.y) * step(uv.y, uBand.x + uBand.y) * step(0.0001, uBand.y);
    s.y = mix(s.y, uBand.x + (uv.y - uBand.x) * 0.07, inBand);
    dx += inBand * uBand.z * (0.4 + 1.2 * rn.x);
    s.x = mix(s.x, 0.5 + (s.x - 0.5) * (1.0 - uBand.w), inBand);

    // Head-switching band: the last lines are torn sideways, smeared and streaky.
    float hs = smoothstep(0.9790, 0.9825, uv.y);
    vec4 hn = texture2D(uNoise, vec2((floor(uv.y * 720.0) + 0.5) / 256.0 + uRand.y, uRand.x + 0.37));
    s.y = mix(s.y, 0.9720 + 0.006 * hn.x, hs);
    s.x = mix(s.x, 0.15 + uv.x * 0.12 + 0.60 * hn.y, hs);

    s.x += dx;

    vec3 c = tape(uCur, s);

    // Interlace: every other line still shows a little of the previous field.
    float odd = mod(floor(uv.y * 720.0), 2.0);
    if (odd * uWeave > 0.0) {
        c = mix(c, tape(uPrev, s), uWeave);
    }

    // Faint ghost to the right.
    c = c * 0.955 + 0.075 * texture2D(uCur, s - vec2(12.0 * PX, 0.0)).rgb;

    // Tape dropouts: short bright dashes with colour fringes.
    float prob = 0.0007
               + 0.055 * smoothstep(0.905, 0.955, uv.y) * (1.0 - hs)
               + uStatic * 0.30 * (1.0 - smoothstep(0.25, 0.60, uv.y));
    float on = step(1.0 - prob, rn2.y);
    float len = 0.005 + 0.060 * rn2.z * rn2.z * rn2.z;
    float x0 = rn2.w;
    float fr = fract(uv.y * 240.0);
    float thick = smoothstep(0.0, 0.3, fr) * (1.0 - smoothstep(0.7, 1.0, fr));
    vec3 dd = abs(vec3(uv.x + (3.0 + 8.0 * rn.y) * PX, uv.x, uv.x - (3.0 + 8.0 * rn.z) * PX) - x0);
    vec3 dash = on * thick * (0.60 + 0.40 * rn.x) * (1.0 - smoothstep(len * 0.5, len, dd));
    c = mix(c, vec3(1.0), dash);

    // Snow and the tracking bar at tape start.
    vec4 sn = texture2D(uNoise, uv * vec2(480.0, 360.0) / 256.0 + uRand2.xy);
    float snow = uStatic * smoothstep(0.30, 0.52, uv.y + 0.05 * (rn.x - 0.5));
    c = mix(c, sn.rgb * 1.15, 0.72 * snow);
    float bar = uStatic * (1.0 - smoothstep(0.012, 0.030, abs(uv.y - (0.80 + 0.06 * uRand2.z))));
    vec3 rainbow = 0.5 + 0.5 * cos(6.2832 * (uv.x * 46.0 + rn.x * 3.0 + vec3(0.0, 0.33, 0.67)));
    c = mix(c, rainbow, 0.8 * bar);

    // Fine grain, tape black level and the dark last two lines.
    vec4 g = texture2D(uNoise, uv * vec2(960.0, 720.0) / 256.0 + uRand2.zw);
    c += (g.r - 0.5) * 0.014;
    c *= mix(1.0, 0.55 + 0.65 * hn.z, hs);
    c = 0.056 + 0.930 * clamp(c, 0.0, 1.0);
    c *= 1.0 - 0.45 * smoothstep(0.9965, 1.0, uv.y);

    gl_FragColor = vec4(c, 1.0);
}
