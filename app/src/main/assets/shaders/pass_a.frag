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

    // Tape noise, added before the bandwidth filter so that it comes out soft.
    vec4 n = texture2D(uNoise, p / (uInvSize * 256.0) + uRand.xy);
    vec4 n2 = texture2D(uNoise, p / (uInvSize * 1024.0) + uRand.zw);
    c += (n.r - 0.5) * 0.036;
    c.r += (n2.g - 0.5) * 0.030;
    c.b += (n2.b - 0.5) * 0.036;

    // Black side borders of the tape picture.
    float inside = step(0.0153, p.x) * step(p.x, 0.9839);
    c *= inside;

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
