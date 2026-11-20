package emu.joric;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/**
 * CRT effect for the Oric screen: barrel curvature, scanlines (each Oric line drawn as a
 * horizontal beam that gets wider on brighter pixels), light glowing sideways and into the
 * gaps between lines, an RGB aperture mask and a vignette. Only used for the Oric screen
 * itself, never for the UI icons or keyboard.
 *
 * The modes and their settings match the CRT view of the OSDK debugger for VS Code
 * (without its strongest curvature).
 *
 * The GLSL is kept here as strings, rather than as asset files, so that it works the
 * same on every platform without having to be preloaded.
 */
public class CrtShader {

    /**
     * The CRT modes, cycled with F8 or the CRT icon.
     */
    public static enum Mode {

        OFF(0f, 0f, 0f, 0f),        // Square pixels.
        FLAT(0f, 1f, 1f, 0f),       // Scanlines and aperture mask, no curvature.
        GENTLE(0.4f, 1f, 1f, 1f);   // A little curvature.

        final float curve;
        final float mask;
        final float scan;
        final float vignette;

        Mode(float curve, float mask, float scan, float vignette) {
            this.curve = curve;
            this.mask = mask;
            this.scan = scan;
            this.vignette = vignette;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Same as the default SpriteBatch vertex shader.
     */
    private static final String VERTEX_SHADER =
            "attribute vec4 " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" +
            "attribute vec4 " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" +
            "attribute vec2 " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" +
            "uniform mat4 u_projTrans;\n" +
            "varying vec4 v_color;\n" +
            "varying vec2 v_texCoords;\n" +
            "\n" +
            "void main() {\n" +
            "    v_color = " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" +
            "    v_color.a = v_color.a * (255.0/254.0);\n" +
            "    v_texCoords = " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" +
            "    gl_Position = u_projTrans * " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec4 v_color;\n" +
            "varying vec2 v_texCoords;\n" +
            "uniform sampler2D u_texture;\n" +
            "uniform vec2 u_textureSize;\n" +   // Size of the whole screen texture, in Oric pixels.
            "uniform vec2 u_screenSize;\n" +    // Size of the part that is drawn (240x224).
            "uniform float u_curve;\n" +        // 0 = flat, 1 = barrel curvature.
            "uniform float u_mask;\n" +         // Aperture mask strength.
            "uniform float u_scan;\n" +         // Scanline strength (already faded for small screens).
            "uniform float u_vignette;\n" +     // Vignette strength.
            "\n" +
            "vec2 curve(vec2 uv) {\n" +
            "    vec2 cc = uv * 2.0 - 1.0;\n" +
            "    vec2 off = abs(cc.yx) / vec2(5.0, 4.0);\n" +
            "    vec2 cv = (cc + cc * off * off) * 0.5 + 0.5;\n" +
            "    return mix(uv, cv, u_curve);\n" +
            "}\n" +
            "\n" +
            // Colour at a position given in Oric pixels. The texture uses linear filtering.
            "vec3 fetch(vec2 pos) {\n" +
            "    return texture2D(u_texture, pos / u_textureSize).rgb;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            // 0..1 across the drawn Oric screen, after the curvature.
            "    vec2 uv = curve(v_texCoords * u_textureSize / u_screenSize);\n" +
            "    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n" +
            "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            // Position in Oric pixels, and the distance (-0.5 to 0.5) from the centre of the beam.
            "    vec2 pos = uv * u_screenSize;\n" +
            "    float line = floor(pos.y);\n" +
            "    float y = line + 0.5;\n" +
            "    float beamDistance = pos.y - y;\n" +
            // Sampling the middle of the line only blends horizontally, like the beam sweeping along it.
            "    vec3 colour = fetch(vec2(pos.x, y));\n" +
            // Light glowing sideways, and into the gap from the lines above and below.
            "    vec3 sideGlow = (fetch(vec2(pos.x - 1.5, y)) + fetch(vec2(pos.x + 1.5, y))) * 0.5;\n" +
            "    vec3 lineGlow = (fetch(vec2(pos.x, y - 1.0)) + fetch(vec2(pos.x, y + 1.0))) * 0.5;\n" +
            // Brighter pixels make a wider beam, as on a real tube.
            "    float luma = dot(colour, vec3(0.299, 0.587, 0.114));\n" +
            "    float width = mix(0.28, 0.42, luma);\n" +
            "    float beam = exp(-(beamDistance * beamDistance) / (2.0 * width * width));\n" +
            "    vec3 col = mix(colour, colour * beam * 1.35, u_scan);\n" +
            "    col += (sideGlow * 0.16 + lineGlow * (1.0 - beam) * 0.10) * u_scan;\n" +
            // RGB aperture mask, one phosphor stripe per screen pixel.
            "    float m = mod(gl_FragCoord.x, 3.0);\n" +
            "    vec3 mask = m < 1.0 ? vec3(1.05, 0.75, 0.75) : (m < 2.0 ? vec3(0.75, 1.05, 0.75) : vec3(0.75, 0.75, 1.05));\n" +
            "    col *= mix(vec3(1.0), mask, u_mask);\n" +
            "    float vig = pow(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.18);\n" +
            "    col *= mix(1.0, clamp(vig, 0.0, 1.0), u_vignette);\n" +
            // Make up for the darkening of the mask.
            "    col *= 1.0 + 0.22 * u_mask;\n" +
            "    gl_FragColor = vec4(col, 1.0) * v_color;\n" +
            "}\n";

    /**
     * Creates the CRT ShaderProgram.
     *
     * @return The ShaderProgram, or null if it could not be compiled (the Oric screen is then drawn without the effect).
     */
    public static ShaderProgram create() {
        ShaderProgram shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            Gdx.app.error("CrtShader", "CRT shader failed to compile: " + shader.getLog());
            shader.dispose();
            return null;
        }
        return shader;
    }

    /**
     * Sets the uniforms for the given mode. Must be called between begin and end of the
     * SpriteBatch that uses the shader.
     *
     * @param shader The CRT ShaderProgram.
     * @param mode The CRT mode.
     * @param textureWidth Width of the whole screen texture, in Oric pixels.
     * @param textureHeight Height of the whole screen texture, in Oric pixels.
     * @param screenWidth Width of the part of the texture that is drawn.
     * @param screenHeight Height of the part of the texture that is drawn.
     * @param pixelsPerLine The height of one Oric line, in screen pixels.
     */
    public static void setUniforms(ShaderProgram shader, Mode mode, int textureWidth, int textureHeight,
            int screenWidth, int screenHeight, float pixelsPerLine) {
        shader.setUniformf("u_textureSize", textureWidth, textureHeight);
        shader.setUniformf("u_screenSize", screenWidth, screenHeight);
        shader.setUniformf("u_curve", mode.curve);
        shader.setUniformf("u_mask", mode.mask);
        shader.setUniformf("u_scan", mode.scan * scanStrength(pixelsPerLine));
        shader.setUniformf("u_vignette", mode.vignette);
    }

    /**
     * Below about 2 screen pixels per Oric line, scanlines would only produce moire
     * patterns, so they fade in between 2 and 4 pixels per line.
     *
     * @param pixelsPerLine The height of one Oric line, in screen pixels.
     *
     * @return The scanline strength, from 0 (none) to 1 (full).
     */
    private static float scanStrength(float pixelsPerLine) {
        return Math.max(0f, Math.min(1f, (pixelsPerLine - 2f) / 2f));
    }
}
