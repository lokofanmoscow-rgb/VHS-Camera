// Pass C: playback artefacts at the output resolution.
// Colour mis-registration, ghost, line jitter, tape dropouts, the
// head-switching band at the bottom and the start/stop glitches.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uCur;     // filtered tape frame
uniform sampler2D uNoise;
uniform vec4 uRand;         // per-frame random numbers
uniform vec4 uRand2;
uniform float uStatic;      // 0..1 snow + picture wobble (tape start)
uniform vec4 uBand;         // travelling "held lines" band: top, height, x shift, smear
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

    // Head-switching band: the last dozen lines are torn sideways and smeared.
    float hs = smoothstep(0.9805, 0.9835, uv.y);
    vec4 hn = texture2D(uNoise, vec2((floor(uv.y * 360.0) + 0.5) / 256.0 + uRand.y, uRand.x + 0.37));
    s.y = mix(s.y, 0.9740 + 0.005 * hn.x, hs);
    s.x = mix(s.x, 0.12 + uv.x * 0.11 + 0.62 * hn.y, hs);

    // Time-base jitter: every line pair sits a fraction of a pixel off.
    s.x += dx + (hn.w - 0.5) * 0.6 * PX;

    vec3 c = tape(uCur, s);

    // Faint ghost to the right.
    c = c * 0.96 + 0.04 * texture2D(uCur, s - vec2(14.0 * PX, 0.0)).rgb;

    // Tape dropouts: rows of short, soft bright dashes that break up into colours.
    float drow = floor(uv.y * 150.0);
    vec4 dr = texture2D(uNoise, vec2((drow + 0.5) / 256.0 + uRand.z, uRand.w + 0.61));
    float prob = 0.00012
               + 0.15 * smoothstep(0.915, 0.945, uv.y) * (1.0 - smoothstep(0.970, 0.979, uv.y))
               + uStatic * 0.20 * (1.0 - smoothstep(0.25, 0.60, uv.y));
    // Two noise channels give 16 bits, enough to make stray dropouts really rare.
    float rowOn = step(1.0 - prob, (dr.x * 255.0 + dr.y) / 256.0);
    float fr = fract(uv.y * 150.0);
    float thick = smoothstep(0.0, 0.3, fr) * (1.0 - smoothstep(0.7, 1.0, fr));
    float dk = 0.10 + 0.10 * dr.y;                    // dash length scale
    float dv = (drow + 0.5) / 256.0 + uRand.x;
    vec3 du = vec3(uv.x + 7.0 * PX, uv.x, uv.x - 7.0 * PX) * dk + dr.z;
    vec4 d0 = texture2D(uNoise, vec2(du.y, dv));
    vec3 lit = vec3(texture2D(uNoise, vec2(du.x, dv)).r, d0.r, texture2D(uNoise, vec2(du.z, dv)).r);
    vec3 fx = fract(du * 256.0);
    vec3 ends = smoothstep(0.0, 0.3, fx) * (1.0 - smoothstep(0.7, 1.0, fx));
    float cut = 0.84 - 0.14 * uStatic;
    vec3 dash = rowOn * thick * ends * step(cut, lit);
    c = mix(c, vec3(0.78) + 0.22 * d0.gba, dash * (0.85 + 0.15 * dr.w));

    // Snow and the tracking bar at tape start.
    vec4 sn = texture2D(uNoise, uv * vec2(480.0, 360.0) / 256.0 + uRand2.xy);
    float snow = uStatic * smoothstep(0.30, 0.52, uv.y + 0.05 * (rn.x - 0.5));
    c = mix(c, sn.rgb * 1.15, 0.72 * snow);
    float bar = uStatic * (1.0 - smoothstep(0.012, 0.030, abs(uv.y - (0.80 + 0.06 * uRand2.z))));
    vec3 rainbow = 0.5 + 0.5 * cos(6.2832 * (uv.x * 46.0 + rn.x * 3.0 + vec3(0.0, 0.33, 0.67)));
    c = mix(c, rainbow, 0.8 * bar);

    // Fine grain, tape black level and the dark last two lines.
    vec4 g = texture2D(uNoise, uv * vec2(960.0, 720.0) / 256.0 + uRand2.zw);
    c += (g.r - 0.5) * 0.003;
    // The torn lines are duller and a little darker than the picture.
    c = mix(c, vec3(dot(c, vec3(0.299, 0.587, 0.114))), 0.45 * hs);
    c *= mix(1.0, 0.74 + 0.22 * hn.z, hs);
    c = 0.056 + 0.930 * clamp(c, 0.0, 1.0);
    c *= 1.0 - 0.45 * smoothstep(0.9965, 1.0, uv.y);

    gl_FragColor = vec4(c, 1.0);
}
