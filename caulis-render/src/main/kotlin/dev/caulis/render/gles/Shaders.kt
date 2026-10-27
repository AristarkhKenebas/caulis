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
        uniform int transform; // the texture is turned (0..7, wl_output's numbers): read it turned back
        out vec2 uv;
        void main() {
            vec2 pixel = rect.xy + corner * rect.zw;
            gl_Position = vec4(pixel / target * 2.0 - 1.0, 0.0, 1.0);
            vec2 p = source.xy + corner * source.zw;
            if (transform >= 4) p.x = 1.0 - p.x;
            int turn = transform - (transform >= 4 ? 4 : 0);
            if (turn == 1) p = vec2(1.0 - p.y, p.x);
            else if (turn == 2) p = vec2(1.0 - p.x, 1.0 - p.y);
            else if (turn == 3) p = vec2(p.y, 1.0 - p.x);
            uv = p;
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

    // A user's paint (see Paint.Shader), numbered from its first line. (pixel, progress,
    // borderWidth and cornerRadius are set by main before paint is asked, see SHAPE.)
    fun paintCustom(code: String) = """
        uniform float time;
        uniform vec2 size;
        uniform float focused;
        uniform float urgent;
        uniform float grouped;
        uniform vec4 colors[8];
        uniform int colorCount;
        #line 1
        """ + code + """
        vec4 paintAt(vec2 at) { vec4 c = paint(at); return vec4(c.rgb * c.a, c.a); }
        """

    // What a user's paint may read about the pixel and the shape: where the pixel is in the
    // box (pixels from its top left corner), how far along its edge (0..1 clockwise from the
    // top left corner), the border's width and the corners' radius. Set before paint is asked.
    private const val SHAPE = """
        vec2 pixel;
        float progress;
        float borderWidth;
        float cornerRadius;
        // How far along the edge of a box of size s the point p (from its top left) is, 0..1.
        float caulisAlongEdge(vec2 p, vec2 s) {
            float perimeter = 2.0 * (s.x + s.y);
            float top = p.y, right = s.x - p.x, bottom = s.y - p.y, left = p.x;
            float nearest = min(min(top, right), min(bottom, left));
            float d;
            if (nearest == top) d = clamp(p.x, 0.0, s.x);
            else if (nearest == right) d = s.x + clamp(p.y, 0.0, s.y);
            else if (nearest == bottom) d = s.x + s.y + clamp(s.x - p.x, 0.0, s.x);
            else d = 2.0 * s.x + s.y + clamp(s.y - p.y, 0.0, s.y);
            return d / max(perimeter, 1.0);
        }
        void caulisShape(vec4 box, float width, float corner) {
            pixel = gl_FragCoord.xy - box.xy;
            progress = caulisAlongEdge(pixel, box.zw);
            borderWidth = width;
            cornerRadius = corner;
        }
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
        $SHAPE
        """ + paint + """
        void main() {
            caulisShape(outer, border, radius);
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
        $SHAPE
        """ + paint + """
        void main() {
            caulisShape(shape, 0.0, radius);
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
        uniform vec4 tone; // noise, contrast, brightness, vibrancy (only with clipped: the last pass)
        out vec4 color;
        $SAMPLE
        $BOX
        float grain(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
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
            if (clipped) {
                vec3 c = color.a > 0.0 ? color.rgb / color.a : color.rgb;
                float grey = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(vec3(grey), c, 1.0 + tone.w);
                c = (c - 0.5) * tone.y + 0.5;
                c *= tone.z;
                c += (grain(gl_FragCoord.xy) - 0.5) * tone.x;
                color = vec4(clamp(c, 0.0, 1.0) * color.a, color.a) * inside(clip, radius);
            }
        }"""
}
