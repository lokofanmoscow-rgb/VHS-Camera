// Pass 0: the camcorder's own picture. Whatever the phone camera delivers is brought
// down to the 640x480 that a consumer camcorder resolves; the tape passes work from
// this small frame, so the picture is soft and the digital zoom gets softer still.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform CAM_SAMPLER uCam;
uniform mat4 uTexMatrix;   // SurfaceTexture transform
uniform mat3 uCamXform;    // picture coords (y down) -> camera texture coords
uniform vec2 uInvSize;     // 1 / (640, 480)
varying vec2 vUv;

vec3 cam(vec2 p) {
    vec3 n = uCamXform * vec3(p, 1.0);
    return texture2D(uCam, (uTexMatrix * vec4(n.xy, 0.0, 1.0)).xy).rgb;
}

void main() {
    vec2 d = uInvSize * 0.25;
    vec3 c = 0.25 * (cam(vUv + vec2(-d.x, -d.y)) + cam(vUv + vec2(d.x, -d.y)) +
                     cam(vUv + vec2(-d.x, d.y)) + cam(vUv + vec2(d.x, d.y)));
    gl_FragColor = vec4(c, 1.0);
}
