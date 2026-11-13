// Pass 0: the camera picture. Whatever the phone camera delivers is brought to a
// 960x720 frame (the 4:3 middle of a 720p picture, as on the reference tapes); the
// tape passes and the digital zoom work from this frame, so zoomed shots get as soft
// as on the reference.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform CAM_SAMPLER uCam;
uniform mat4 uTexMatrix;   // SurfaceTexture transform
uniform mat3 uCamXform;    // picture coords (y down) -> camera texture coords
uniform vec2 uInvSize;     // 1 / (960, 720)
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
