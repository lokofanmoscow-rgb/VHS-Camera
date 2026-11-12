// Full-screen quad. vUv: x to the right, y DOWN (0 = top of the picture).
attribute vec2 aPos;
uniform float uFlipY;
varying vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, aPos.y * 0.5 + 0.5);
    gl_Position = vec4(aPos.x, aPos.y * uFlipY, 0.0, 1.0);
}
