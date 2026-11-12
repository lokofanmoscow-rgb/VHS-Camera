// Pass A: camera -> "tape frame" at the internal tape resolution.
// Crops/zooms/rotates the camera image, grades it, masks the side borders,
// burns in the on-screen display and adds pre-filter tape noise.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform CAM_SAMPLER uCam;
uniform mat4 uTexMatrix;   // SurfaceTexture transform
uniform mat3 uCamXform;    // picture coords (y down) -> camera texture coords
uniform sampler2D uOsd;    // premultiplied on-screen display
uniform sampler2D uNoise;
uniform vec2 uInvSize;     // 1 / internal size
uniform vec4 uRand;        // per-frame random numbers
uniform float uSoft;       // extra softness in output pixels (grows with digital zoom)
varying vec2 vUv;

const float NOISE_Y = 0.027;
const float NOISE_C = 0.018;

vec3 cam(vec2 p) {
    vec3 n = uCamXform * vec3(p, 1.0);
    return texture2D(uCam, (uTexMatrix * vec4(n.xy, 0.0, 1.0)).xy).rgb;
}

void main() {
    vec2 p = vUv;
    vec2 d = uInvSize * 0.25 + uSoft * vec2(1.0 / 960.0, 1.0 / 720.0);
    vec3 c = 0.25 * (cam(p + vec2(-d.x, -d.y)) + cam(p + vec2(d.x, -d.y)) +
                     cam(p + vec2(-d.x, d.y)) + cam(p + vec2(d.x, d.y)));

    // Camcorder colour: a little more saturation and contrast, lifted blacks.
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(y), c, 1.22);
    c = (c - 0.5) * 1.06 + 0.5;
    c = clamp(c, 0.0, 1.0);
    c = 0.035 + 0.965 * c;

    // On-screen display is part of the recorded signal.
    vec4 osd = texture2D(uOsd, p);
    c = c * (1.0 - osd.a) + osd.rgb;

    // Tape noise, added before the bandwidth filter so that it comes out soft:
    // blobs about two pixels wide and four lines tall.
    float ny = p.y * 160.0 - 0.5;
    float r0 = floor(ny);
    vec2 nq = vec2(p.x / (uInvSize.x * 256.0) + uRand.x, (r0 + 0.5) / 256.0 + uRand.y);
    vec4 n = mix(texture2D(uNoise, nq), texture2D(uNoise, nq + vec2(0.0, 1.0 / 256.0)), ny - r0);
    c += (n.r - 0.5) * NOISE_Y;
    c.r += (n.g - 0.5) * NOISE_C;
    c.b += (n.b - 0.5) * NOISE_C;

    // Black side borders of the tape picture.
    float px = p.x / uInvSize.x;
    c *= clamp(px - 7.5, 0.0, 1.0) * clamp(473.17 - px, 0.0, 1.0);

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
