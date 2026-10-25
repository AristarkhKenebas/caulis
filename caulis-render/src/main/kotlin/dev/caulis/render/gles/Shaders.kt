package dev.caulis.render.gles

/** The GLSL programs of [GlesRenderer]. */
internal object Shaders {

    // Pixel rectangle -> OpenGL coordinates. Pixel row 0 goes to framebuffer row 0, so
    // reading the framebuffer back gives the top row first, and gl_FragCoord.xy is the
    // pixel position (+0.5) in the shaders below.
    const val VERTEX = """#version 300 es
        layout(location = 0) in vec2 corner;
        uniform vec4 rect;    // x, y, width, height in pixels
        uniform vec2 target;  // size of the render target in pixels
        uniform vec4 source;  // the part of the texture to show: u, v, width, height
        out vec2 uv;
        void main() {
            vec2 pixel = rect.xy + corner * rect.zw;
            gl_Position = vec4(pixel / target * 2.0 - 1.0, 0.0, 1.0);
            uv = source.xy + corner * source.zw;
        }"""

    // Signed distance from p to a rounded box (x, y, width, height): < 0 inside.
    private const val BOX = """
        float box(vec2 p, vec4 r, float radius) {
            vec2 hs = r.zw * 0.5;
            vec2 q = abs(p - r.xy - hs) - hs + radius;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        }
        // 1 inside, 0 outside, a one pixel soft edge in between.
        float inside(vec4 r, float radius) { return clamp(0.5 - box(gl_FragCoord.xy, r, radius), 0.0, 1.0); }
        """

    const val TEXTURE = """#version 300 es
        precision highp float;
        in vec2 uv;
        uniform sampler2D image;
        uniform float alpha;
        uniform bool opaque;
        uniform bool clipped;
        uniform vec4 clip;
        uniform float radius;
        out vec4 color;
        $BOX
        void main() {
            vec4 pixel = texture(image, uv);
            if (opaque) pixel.a = 1.0;
            color = pixel * alpha;
            if (clipped) color *= inside(clip, radius);
        }"""

    // A user's effect (see Renderer.drawTextureEffect) around their `effect` function.
    fun effect(code: String) = """#version 300 es
        precision highp float;
        in vec2 uv;
        uniform sampler2D image;
        uniform float alpha;
        uniform float progress;
        uniform vec4 from;
        uniform vec4 to;
        uniform vec2 size;
        out vec4 caulisColor;
        vec4 window(vec2 p) {
            if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) return vec4(0.0);
            return texture(image, p);
        }
        #line 1
        """ + code + """
        void main() { caulisColor = effect(uv) * alpha; }"""

    const val COLOR = """#version 300 es
        precision mediump float;
        uniform vec4 color;
        out vec4 fragment;
        void main() { fragment = color; }"""

    // What fills a rounded box or a shadow: `paintAt(at)` (at: 0..1 in the box) gives a
    // premultiplied colour. One colour; a gradient; or a user's `paint` function.
    const val PAINT_SOLID = """
        uniform vec4 color;
        vec4 paintAt(vec2 at) { return color; }
        """

    const val PAINT_GRADIENT = """
        uniform vec4 colors[8];
        uniform int count;
        uniform float angle;
        uniform bool conic;
        vec4 premultiplied(vec4 c) { return vec4(c.rgb * c.a, c.a); }
        vec4 paintAt(vec2 at) {
            vec2 d = at - 0.5;
            float n = float(count);
            if (conic) {
                // Around the middle, the last colour going back into the first.
                float x = fract((atan(d.y, d.x) - angle) / 6.2831853) * n;
                int i = int(floor(x)) % count;
                return premultiplied(mix(colors[i], colors[(i + 1) % count], fract(x)));
            }
            vec2 dir = vec2(cos(angle), sin(angle));
            float t = clamp(dot(d, dir) / (abs(dir.x) * 0.5 + abs(dir.y) * 0.5) * 0.5 + 0.5, 0.0, 1.0);
            float x = t * (n - 1.0);
            int i = min(int(floor(x)), count - 2);
            return premultiplied(mix(colors[i], colors[i + 1], x - float(i)));
        }
        """

    // A user's paint (see Paint.Shader), numbered from its first line.
    fun paintCustom(code: String) = """
        uniform float time;
        uniform vec2 size;
        #line 1
        """ + code + """
        vec4 paintAt(vec2 at) { vec4 c = paint(at); return vec4(c.rgb * c.a, c.a); }
        """

    // A filled rounded box, or (border > 0) a frame along its inside.
    fun rounded(paint: String) = """#version 300 es
        precision highp float;
        uniform vec4 outer;
        uniform float radius;
        uniform float border;
        uniform float alpha;
        out vec4 fragment;
        $BOX
        """ + paint + """
        void main() {
            float a = inside(outer, radius);
            if (border > 0.0) {
                vec4 hole = outer + vec4(border, border, -2.0 * border, -2.0 * border);
                a *= 1.0 - inside(hole, max(radius - border, 0.0));
            }
            fragment = paintAt((gl_FragCoord.xy - outer.xy) / outer.zw) * a * alpha;
        }"""

    // The shadow of a rounded box, a gaussian blur computed directly: exact along x, a few
    // samples along y (Evan Wallace, "Fast rounded rectangle shadows", MIT/CC0).
    fun shadow(paint: String) = """#version 300 es
        precision highp float;
        uniform vec4 shape;   // the box casting the shadow
        uniform float radius;
        uniform float sigma;
        uniform bool cut;
        uniform vec4 cutOut;
        uniform float cutRadius;
        uniform float alpha;
        out vec4 fragment;
        $BOX
        float gaussian(float x, float s) { return exp(-(x * x) / (2.0 * s * s)) / (2.5066283 * s); }
        vec2 erf(vec2 x) {
            vec2 s = sign(x), a = abs(x);
            x = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
            x *= x;
            return s - s / (x * x);
        }
        float alongX(float x, float y, float s, float corner, vec2 hs) {
            float delta = min(hs.y - corner - abs(y), 0.0);
            float curved = hs.x - corner + sqrt(max(0.0, corner * corner - delta * delta));
            vec2 integral = 0.5 + 0.5 * erf((x + vec2(-curved, curved)) * (sqrt(0.5) / s));
            return integral.y - integral.x;
        }
        """ + paint + """
        void main() {
            vec2 hs = shape.zw * 0.5;
            vec2 p = gl_FragCoord.xy - shape.xy - hs;
            float low = p.y - hs.y;
            float high = p.y + hs.y;
            float start = clamp(-3.0 * sigma, low, high);
            float end = clamp(3.0 * sigma, low, high);
            float dy = (end - start) / 4.0;
            float y = start + dy * 0.5;
            float value = 0.0;
            for (int i = 0; i < 4; i++) {
                value += alongX(p.x, p.y - y, sigma, radius, hs) * gaussian(y, sigma) * dy;
                y += dy;
            }
            if (cut) value *= 1.0 - inside(cutOut, cutRadius);
            fragment = paintAt((gl_FragCoord.xy - shape.xy) / shape.zw) * value * alpha;
        }"""

    // Dual Kawase blur (Marius Bjørge, "Bandwidth-efficient rendering", SIGGRAPH 2015):
    // halve the picture a few times, then double it back, sampling around each pixel.
    // [bounds] keeps the samples inside the part being blurred.
    private const val SAMPLE = """
        uniform sampler2D image;
        uniform vec2 halfpixel;
        uniform float offset;
        uniform vec4 bounds; // u, v min and max
        vec4 at(vec2 p) { return texture(image, clamp(p, bounds.xy, bounds.zw)); }
        """

    const val BLUR_DOWN = """#version 300 es
        precision highp float;
        in vec2 uv;
        out vec4 color;
        $SAMPLE
        void main() {
            vec2 o = halfpixel * offset;
            vec4 sum = at(uv) * 4.0;
            sum += at(uv - o);
            sum += at(uv + o);
            sum += at(uv + vec2(o.x, -o.y));
            sum += at(uv - vec2(o.x, -o.y));
            color = sum / 8.0;
        }"""

    const val BLUR_UP = """#version 300 es
        precision highp float;
        in vec2 uv;
        uniform bool clipped;
        uniform vec4 clip;
        uniform float radius;
        out vec4 color;
        $SAMPLE
        $BOX
        void main() {
            vec2 o = halfpixel * offset;
            vec4 sum = at(uv + vec2(-o.x * 2.0, 0.0));
            sum += at(uv + vec2(-o.x, o.y)) * 2.0;
            sum += at(uv + vec2(0.0, o.y * 2.0));
            sum += at(uv + vec2(o.x, o.y)) * 2.0;
            sum += at(uv + vec2(o.x * 2.0, 0.0));
            sum += at(uv + vec2(o.x, -o.y)) * 2.0;
            sum += at(uv + vec2(0.0, -o.y * 2.0));
            sum += at(uv + vec2(-o.x, -o.y)) * 2.0;
            color = sum / 12.0;
            if (clipped) color *= inside(clip, radius);
        }"""
}
