// Pass A: camera picture -> "tape frame" at the internal tape resolution.
// Zooms into the 960x720 camera picture, grades it and adds pre-filter tape noise.
// (The on-screen display and the side borders are laid in by pass B.)
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uVga;    // the 960x720 camera picture (bilinear)
uniform float uZoom;       // digital zoom, 1 = whole picture
uniform sampler2D uNoise;
uniform vec2 uInvSize;     // 1 / internal size
uniform vec4 uRand;        // per-frame random numbers
varying vec2 vUv;

const float NOISE_Y = 0.027;
const float NOISE_C = 0.018;

void main() {
    vec2 p = vUv;
    vec3 c = texture2D(uVga, 0.5 + (p - 0.5) / uZoom).rgb;

    // Camcorder colour: a little more saturation and contrast, lifted blacks.
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(y), c, 1.22);
    c = (c - 0.5) * 1.06 + 0.5;
    c = clamp(c, 0.0, 1.0);
    c = 0.035 + 0.965 * c;

    // Tape noise, added before the bandwidth filter so that it comes out soft:
    // blobs about two pixels wide and four lines tall.
    float ny = p.y * 160.0 - 0.5;
    float r0 = floor(ny);
    vec2 nq = vec2(p.x / (uInvSize.x * 256.0) + uRand.x, (r0 + 0.5) / 256.0 + uRand.y);
    vec4 n = mix(texture2D(uNoise, nq), texture2D(uNoise, nq + vec2(0.0, 1.0 / 256.0)), ny - r0);
    c += (n.r - 0.5) * NOISE_Y;
    c.r += (n.g - 0.5) * NOISE_C;
    c.b += (n.b - 0.5) * NOISE_C;

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
