using System;
using System.Runtime.InteropServices;
using System.Text;
using UnityEngine;

namespace CitiesCraft
{
    /// <summary>
    /// Runtime macOS/OpenGL image effect for the CitiesCraft passthrough view.
    /// Cities supplies the camera color/depth; the Minecraft client supplies a
    /// color/depth pair plus transparent hand and GUI images. Minecraft depth
    /// must be linear eye-space distance in Cities world units.
    ///
    /// This deliberately uses Unity's active OnRenderImage destination and its
    /// current GL context. It is not a standalone GL window or a screenshot
    /// overlay. If the required textures or OpenGL entry points are unavailable,
    /// the effect copies the Cities image through unchanged.
    /// </summary>
    [ExecuteInEditMode]
    [RequireComponent(typeof(Camera))]
    public sealed class CitiesNativeCompositor : MonoBehaviour
    {
        public Texture2D MinecraftWorldColor;
        public Texture2D MinecraftLinearDepth;
        public Texture2D MinecraftHand;
        public Texture2D MinecraftGui;
        public float MinecraftNearPlane = 0.05f;
        public float MinecraftFarPlane = 256f;
        public float MinecraftDepthScale = 1f;
        public bool MinecraftOverlayPremultipliedAlpha;
        public bool PassthroughActive = true;

        private const uint GlVertexShader = 0x8B31;
        private const uint GlFragmentShader = 0x8B30;
        private const uint GlCompileStatus = 0x8B81;
        private const uint GlLinkStatus = 0x8B82;
        private const uint GlInfoLogLength = 0x8B84;
        private const uint GlCurrentProgram = 0x8B8D;
        private const uint GlActiveTexture = 0x84E0;
        private const uint GlTexture0 = 0x84C0;
        private const uint GlTexture2D = 0x0DE1;
        private const uint GlTextureBinding2D = 0x8069;
        private const uint GlArrayBuffer = 0x8892;
        private const uint GlArrayBufferBinding = 0x8894;
        private const uint GlStaticDraw = 0x88E4;
        private const uint GlVertexArrayBinding = 0x85B5;
        private const uint GlViewport = 0x0BA2;
        private const uint GlColorWriteMask = 0x0C23;
        private const uint GlDepthWriteMask = 0x0B72;
        private const uint GlDepthTest = 0x0B71;
        private const uint GlBlend = 0x0BE2;
        private const uint GlCullFace = 0x0B44;
        private const uint GlScissorTest = 0x0C11;
        private const uint GlStencilTest = 0x0B90;
        private const uint GlRasterizerDiscard = 0x8C89;
        private const uint GlTriangleStrip = 0x0005;
        private const uint GlFloat = 0x1406;

        private static readonly string VertexSource =
            "#version 150\n" +
            "in vec2 aPosition;\n" +
            "in vec2 aUv;\n" +
            "out vec2 vUv;\n" +
            "void main() { vUv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }\n";

        private static readonly string FragmentSource =
            "#version 150\n" +
            "uniform sampler2D uCityColor;\n" +
            "uniform sampler2D uCityDepth;\n" +
            "uniform sampler2D uMinecraftColor;\n" +
            "uniform sampler2D uMinecraftDepth;\n" +
            "uniform sampler2D uHand;\n" +
            "uniform sampler2D uGui;\n" +
            "uniform vec4 uZBufferParams;\n" +
            "uniform float uMinecraftNear;\n" +
            "uniform float uMinecraftFar;\n" +
            "uniform float uMinecraftDepthScale;\n" +
            "uniform float uHasHand;\n" +
            "uniform float uHasGui;\n" +
            "uniform float uPremultipliedAlpha;\n" +
            "in vec2 vUv;\n" +
            "out vec4 fragColor;\n" +
            "vec4 over(vec4 base, vec4 layer) { vec3 rgb = uPremultipliedAlpha > 0.5 ? layer.rgb + base.rgb * (1.0 - layer.a) : mix(base.rgb, layer.rgb, layer.a); return vec4(rgb, base.a + layer.a * (1.0 - base.a)); }\n" +
            "void main() {\n" +
            "  vec4 city = texture(uCityColor, vUv);\n" +
            "  float rawCityDepth = texture(uCityDepth, vUv).r;\n" +
            "  float cityDenom = uZBufferParams.z * rawCityDepth + uZBufferParams.w;\n" +
            "  float cityDepth = cityDenom > 0.0 ? 1.0 / cityDenom : 1.0e30;\n" +
            "  vec4 minecraft = texture(uMinecraftColor, vUv);\n" +
            "  float minecraftDepth = texture(uMinecraftDepth, vUv).r * uMinecraftDepthScale;\n" +
            "  bool minecraftValid = minecraft.a > 0.001 && minecraftDepth >= uMinecraftNear * uMinecraftDepthScale && minecraftDepth < uMinecraftFar * uMinecraftDepthScale;\n" +
            "  vec4 color = (minecraftValid && minecraftDepth < cityDepth) ? minecraft : city;\n" +
            "  if (uHasHand > 0.5) color = over(color, texture(uHand, vUv));\n" +
            "  if (uHasGui > 0.5) color = over(color, texture(uGui, vUv));\n" +
            "  fragColor = color;\n" +
            "}\n";

        private Camera _camera;
        private DepthTextureMode _originalDepthMode;
        private bool _depthModeCaptured;
        private uint _program;
        private uint _vertexArray;
        private uint _vertexBuffer;
        private int _aPosition = -1;
        private int _aUv = -1;
        private int _uCityColor = -1;
        private int _uCityDepth = -1;
        private int _uMinecraftColor = -1;
        private int _uMinecraftDepth = -1;
        private int _uHand = -1;
        private int _uGui = -1;
        private int _uZBufferParams = -1;
        private int _uMinecraftNear = -1;
        private int _uMinecraftFar = -1;
        private int _uMinecraftDepthScale = -1;
        private int _uHasHand = -1;
        private int _uHasGui = -1;
        private int _uPremultipliedAlpha = -1;
        private bool _initAttempted;
        private bool _warned;

        public bool IsAvailable
        {
            get { return _program != 0 && _vertexArray != 0 && _vertexBuffer != 0; }
        }

        public void SetMinecraftFrames(Texture2D worldColor, Texture2D linearDepth,
            Texture2D hand, Texture2D gui, float nearPlane, float farPlane)
        {
            MinecraftWorldColor = worldColor;
            MinecraftLinearDepth = linearDepth;
            MinecraftHand = hand;
            MinecraftGui = gui;
            MinecraftNearPlane = nearPlane;
            MinecraftFarPlane = farPlane;
        }

        private void OnEnable()
        {
            _initAttempted = false;
            _camera = GetComponent<Camera>();
            if (_camera != null)
            {
                _originalDepthMode = _camera.depthTextureMode;
                _depthModeCaptured = true;
                _camera.depthTextureMode = _originalDepthMode | DepthTextureMode.Depth;
            }
        }

        private void OnDisable()
        {
            if (_camera != null && _depthModeCaptured)
                _camera.depthTextureMode = _originalDepthMode;
            _depthModeCaptured = false;
            DestroyGlResources();
        }

        private void OnDestroy()
        {
            DestroyGlResources();
        }

        private void OnRenderImage(RenderTexture source, RenderTexture destination)
        {
            Texture cityDepth = Shader.GetGlobalTexture("_CameraDepthTexture");
            if (!PassthroughActive || source == null || MinecraftWorldColor == null || MinecraftLinearDepth == null
                || cityDepth == null || !ValidNearFar(MinecraftNearPlane, MinecraftFarPlane)
                || !FinitePositive(MinecraftDepthScale))
            {
                Graphics.Blit(source, destination);
                return;
            }

            try
            {
                EnsureGlResources();
                if (!IsAvailable)
                {
                    Graphics.Blit(source, destination);
                    return;
                }

                uint[] textureIds = new uint[6];
                textureIds[0] = NativeTextureId(source);
                textureIds[1] = NativeTextureId(cityDepth);
                textureIds[2] = NativeTextureId(MinecraftWorldColor);
                textureIds[3] = NativeTextureId(MinecraftLinearDepth);
                textureIds[4] = NativeTextureId(MinecraftHand == null ? Texture2D.whiteTexture : MinecraftHand);
                textureIds[5] = NativeTextureId(MinecraftGui == null ? Texture2D.whiteTexture : MinecraftGui);
                for (int i = 0; i < textureIds.Length; i++)
                {
                    if (textureIds[i] == 0)
                    {
                        Graphics.Blit(source, destination);
                        return;
                    }
                }

                RenderTexture previousTarget = RenderTexture.active;
                try
                {
                    RenderTexture.active = destination;
                    destinationWidth = destination == null ? Screen.width : destination.width;
                    destinationHeight = destination == null ? Screen.height : destination.height;
                    Vector4 zBufferParams = Shader.GetGlobalVector("_ZBufferParams");
                    if (destinationWidth <= 0 || destinationHeight <= 0 || !ValidZBufferParams(zBufferParams))
                    {
                        Graphics.Blit(source, destination);
                        return;
                    }
                    DrawComposite(textureIds, zBufferParams);
                }
                finally
                {
                    RenderTexture.active = previousTarget;
                }
            }
            catch (Exception exception)
            {
                if (!_warned)
                {
                    Debug.LogWarning("CitiesCraft native compositor unavailable; showing the Cities image only. " + exception.Message);
                    _warned = true;
                }
                Graphics.Blit(source, destination);
            }
        }

        private void EnsureGlResources()
        {
            if (IsAvailable) return;
            if (_initAttempted) return;
            _initAttempted = true;

            uint vertexShader = CompileShader(GlVertexShader, VertexSource);
            uint fragmentShader = CompileShader(GlFragmentShader, FragmentSource);
            if (vertexShader == 0 || fragmentShader == 0)
            {
                if (vertexShader != 0) glDeleteShader(vertexShader);
                if (fragmentShader != 0) glDeleteShader(fragmentShader);
                return;
            }

            _program = glCreateProgram();
            if (_program == 0)
            {
                glDeleteShader(vertexShader);
                glDeleteShader(fragmentShader);
                return;
            }

            glAttachShader(_program, vertexShader);
            glAttachShader(_program, fragmentShader);
            glLinkProgram(_program);
            int linked;
            glGetProgramiv(_program, GlLinkStatus, out linked);
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            if (linked == 0)
            {
                LogProgramError(_program, "link");
                glDeleteProgram(_program);
                _program = 0;
                return;
            }

            _aPosition = GetAttribLocation(_program, "aPosition");
            _aUv = GetAttribLocation(_program, "aUv");
            _uCityColor = GetUniformLocation(_program, "uCityColor");
            _uCityDepth = GetUniformLocation(_program, "uCityDepth");
            _uMinecraftColor = GetUniformLocation(_program, "uMinecraftColor");
            _uMinecraftDepth = GetUniformLocation(_program, "uMinecraftDepth");
            _uHand = GetUniformLocation(_program, "uHand");
            _uGui = GetUniformLocation(_program, "uGui");
            _uZBufferParams = GetUniformLocation(_program, "uZBufferParams");
            _uMinecraftNear = GetUniformLocation(_program, "uMinecraftNear");
            _uMinecraftFar = GetUniformLocation(_program, "uMinecraftFar");
            _uMinecraftDepthScale = GetUniformLocation(_program, "uMinecraftDepthScale");
            _uHasHand = GetUniformLocation(_program, "uHasHand");
            _uHasGui = GetUniformLocation(_program, "uHasGui");
            _uPremultipliedAlpha = GetUniformLocation(_program, "uPremultipliedAlpha");
            if (_aPosition < 0 || _aUv < 0 || _uCityColor < 0 || _uCityDepth < 0
                || _uMinecraftColor < 0 || _uMinecraftDepth < 0 || _uHand < 0 || _uGui < 0
                || _uZBufferParams < 0 || _uMinecraftNear < 0 || _uMinecraftFar < 0
                || _uMinecraftDepthScale < 0 || _uHasHand < 0 || _uHasGui < 0
                || _uPremultipliedAlpha < 0)
            {
                LogProgramError(_program, "uniform lookup");
                glDeleteProgram(_program);
                _program = 0;
                return;
            }

            glGenVertexArrays(1, out _vertexArray);
            glGenBuffers(1, out _vertexBuffer);
            if (_vertexArray == 0 || _vertexBuffer == 0)
            {
                DestroyGlResources();
                return;
            }

            float[] vertices = new float[]
            {
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            };
            int previousVao;
            int previousArrayBuffer;
            glGetIntegerv(GlVertexArrayBinding, out previousVao);
            glGetIntegerv(GlArrayBufferBinding, out previousArrayBuffer);
            try
            {
                glBindVertexArray(_vertexArray);
                glBindBuffer(GlArrayBuffer, _vertexBuffer);
                GCHandle pinnedVertices = GCHandle.Alloc(vertices, GCHandleType.Pinned);
                try
                {
                    glBufferData(GlArrayBuffer, new IntPtr(vertices.Length * sizeof(float)),
                        pinnedVertices.AddrOfPinnedObject(), GlStaticDraw);
                }
                finally { pinnedVertices.Free(); }
                glEnableVertexAttribArray((uint)_aPosition);
                glEnableVertexAttribArray((uint)_aUv);
                glVertexAttribPointer((uint)_aPosition, 2, GlFloat, 0, 4 * sizeof(float), IntPtr.Zero);
                glVertexAttribPointer((uint)_aUv, 2, GlFloat, 0, 4 * sizeof(float), new IntPtr(2 * sizeof(float)));
            }
            finally
            {
                glBindVertexArray((uint)previousVao);
                glBindBuffer(GlArrayBuffer, (uint)previousArrayBuffer);
            }
        }

        private void DrawComposite(uint[] textureIds, Vector4 zBufferParams)
        {
            int previousProgram;
            int previousVao;
            int previousArrayBuffer;
            int previousActiveTexture;
            int[] previousTextureBindings = new int[6];
            int[] previousViewport = new int[4];
            byte[] previousColorMask = new byte[4];
            byte previousDepthMask;
            glGetIntegerv(GlCurrentProgram, out previousProgram);
            glGetIntegerv(GlVertexArrayBinding, out previousVao);
            glGetIntegerv(GlArrayBufferBinding, out previousArrayBuffer);
            glGetIntegerv(GlActiveTexture, out previousActiveTexture);
            GetIntegers(GlViewport, previousViewport);
            GetBooleans(GlColorWriteMask, previousColorMask);
            glGetBooleanv(GlDepthWriteMask, out previousDepthMask);

            bool depthWasEnabled = IsEnabled(GlDepthTest);
            bool blendWasEnabled = IsEnabled(GlBlend);
            bool cullWasEnabled = IsEnabled(GlCullFace);
            bool scissorWasEnabled = IsEnabled(GlScissorTest);
            bool stencilWasEnabled = IsEnabled(GlStencilTest);
            bool discardWasEnabled = IsEnabled(GlRasterizerDiscard);

            for (int i = 0; i < previousTextureBindings.Length; i++)
            {
                glActiveTexture(GlTexture0 + (uint)i);
                glGetIntegerv(GlTextureBinding2D, out previousTextureBindings[i]);
            }

            try
            {
                glViewport(0, 0, destinationWidth, destinationHeight);
                glDisable(GlDepthTest);
                glDisable(GlBlend);
                glDisable(GlCullFace);
                glDisable(GlScissorTest);
                glDisable(GlStencilTest);
                glDisable(GlRasterizerDiscard);
                glColorMask(1, 1, 1, 1);
                glDepthMask(0);

                glUseProgram(_program);
                BindTexture(0, textureIds[0], _uCityColor);
                BindTexture(1, textureIds[1], _uCityDepth);
                BindTexture(2, textureIds[2], _uMinecraftColor);
                BindTexture(3, textureIds[3], _uMinecraftDepth);
                BindTexture(4, textureIds[4], _uHand);
                BindTexture(5, textureIds[5], _uGui);
                glUniform4f(_uZBufferParams, zBufferParams.x, zBufferParams.y, zBufferParams.z, zBufferParams.w);
                glUniform1f(_uMinecraftNear, MinecraftNearPlane);
                glUniform1f(_uMinecraftFar, MinecraftFarPlane);
                glUniform1f(_uMinecraftDepthScale, MinecraftDepthScale);
                glUniform1f(_uHasHand, MinecraftHand == null ? 0f : 1f);
                glUniform1f(_uHasGui, MinecraftGui == null ? 0f : 1f);
                glUniform1f(_uPremultipliedAlpha, MinecraftOverlayPremultipliedAlpha ? 1f : 0f);

                glBindVertexArray(_vertexArray);
                glDrawArrays(GlTriangleStrip, 0, 4);
            }
            finally
            {
                for (int i = 0; i < previousTextureBindings.Length; i++)
                {
                    glActiveTexture(GlTexture0 + (uint)i);
                    glBindTexture(GlTexture2D, (uint)previousTextureBindings[i]);
                }
                glActiveTexture((uint)previousActiveTexture);
                glUseProgram((uint)previousProgram);
                glBindVertexArray((uint)previousVao);
                glBindBuffer(GlArrayBuffer, (uint)previousArrayBuffer);
                glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
                glColorMask(previousColorMask[0], previousColorMask[1], previousColorMask[2], previousColorMask[3]);
                glDepthMask(previousDepthMask);
                RestoreEnabled(GlDepthTest, depthWasEnabled);
                RestoreEnabled(GlBlend, blendWasEnabled);
                RestoreEnabled(GlCullFace, cullWasEnabled);
                RestoreEnabled(GlScissorTest, scissorWasEnabled);
                RestoreEnabled(GlStencilTest, stencilWasEnabled);
                RestoreEnabled(GlRasterizerDiscard, discardWasEnabled);
            }
        }

        // Filled immediately before rendering so the effect covers the active
        // output, including when Unity uses the back buffer (destination null).
        private int destinationWidth;
        private int destinationHeight;

        private void BindTexture(int unit, uint texture, int samplerLocation)
        {
            glActiveTexture(GlTexture0 + (uint)unit);
            glBindTexture(GlTexture2D, texture);
            glUniform1i(samplerLocation, unit);
        }

        private static uint NativeTextureId(Texture texture)
        {
            if (texture == null) return 0;
            IntPtr native = texture.GetNativeTexturePtr();
            if (native == IntPtr.Zero) return 0;
            long raw = native.ToInt64();
            if (raw <= 0 || raw > UInt32.MaxValue) return 0;
            return (uint)raw;
        }

        private static bool ValidNearFar(float nearPlane, float farPlane)
        {
            return !Single.IsNaN(nearPlane) && !Single.IsInfinity(nearPlane)
                && !Single.IsNaN(farPlane) && !Single.IsInfinity(farPlane)
                && nearPlane > 0f && farPlane > nearPlane;
        }

        private static bool FinitePositive(float value)
        {
            return !Single.IsNaN(value) && !Single.IsInfinity(value) && value > 0f;
        }

        private static bool ValidZBufferParams(Vector4 value)
        {
            return !Single.IsNaN(value.z) && !Single.IsInfinity(value.z)
                && !Single.IsNaN(value.w) && !Single.IsInfinity(value.w)
                && (value.z != 0f || value.w != 0f);
        }

        private static uint CompileShader(uint kind, string source)
        {
            uint shader = glCreateShader(kind);
            if (shader == 0) return 0;

            byte[] sourceBytes = Encoding.ASCII.GetBytes(source + "\0");
            GCHandle pinnedSource = GCHandle.Alloc(sourceBytes, GCHandleType.Pinned);
            IntPtr[] sourcePointers = new IntPtr[] { pinnedSource.AddrOfPinnedObject() };
            GCHandle pinnedSourcePointers = GCHandle.Alloc(sourcePointers, GCHandleType.Pinned);
            try
            {
                glShaderSource(shader, 1, pinnedSourcePointers.AddrOfPinnedObject(), IntPtr.Zero);
                glCompileShader(shader);
                int compiled;
                glGetShaderiv(shader, GlCompileStatus, out compiled);
                if (compiled == 0)
                {
                    LogShaderError(shader, kind == GlVertexShader ? "vertex" : "fragment");
                    glDeleteShader(shader);
                    return 0;
                }
                return shader;
            }
            finally
            {
                pinnedSourcePointers.Free();
                pinnedSource.Free();
            }
        }

        private static void LogShaderError(uint shader, string label)
        {
            int length;
            glGetShaderiv(shader, GlInfoLogLength, out length);
            if (length <= 1) return;
            Debug.LogWarning("CitiesCraft " + label + " compositor shader failed: "
                + GetShaderInfoLog(shader, length));
        }

        private static void LogProgramError(uint program, string label)
        {
            int length;
            glGetProgramiv(program, GlInfoLogLength, out length);
            if (length <= 1) return;
            Debug.LogWarning("CitiesCraft compositor program " + label + " failed: "
                + GetProgramInfoLog(program, length));
        }

        private static bool IsEnabled(uint capability)
        {
            return glIsEnabled(capability) != 0;
        }

        private static void RestoreEnabled(uint capability, bool wasEnabled)
        {
            if (wasEnabled) glEnable(capability);
            else glDisable(capability);
        }

        private void DestroyGlResources()
        {
            try
            {
                if (_vertexBuffer != 0) glDeleteBuffers(1, ref _vertexBuffer);
                if (_vertexArray != 0) glDeleteVertexArrays(1, ref _vertexArray);
                if (_program != 0) glDeleteProgram(_program);
            }
            catch (DllNotFoundException)
            {
            }
            catch (EntryPointNotFoundException)
            {
            }
            finally
            {
                _vertexBuffer = 0;
                _vertexArray = 0;
                _program = 0;
                _initAttempted = false;
            }
        }

        private static void GetIntegers(uint name, int[] values)
        {
            GCHandle pinned = GCHandle.Alloc(values, GCHandleType.Pinned);
            try { glGetIntegerv(name, pinned.AddrOfPinnedObject()); }
            finally { pinned.Free(); }
        }

        private static void GetBooleans(uint name, byte[] values)
        {
            GCHandle pinned = GCHandle.Alloc(values, GCHandleType.Pinned);
            try { glGetBooleanv(name, pinned.AddrOfPinnedObject()); }
            finally { pinned.Free(); }
        }

        private static int GetAttribLocation(uint program, string name)
        {
            byte[] nameBytes = Encoding.ASCII.GetBytes(name + "\0");
            GCHandle pinned = GCHandle.Alloc(nameBytes, GCHandleType.Pinned);
            try { return glGetAttribLocation(program, pinned.AddrOfPinnedObject()); }
            finally { pinned.Free(); }
        }

        private static int GetUniformLocation(uint program, string name)
        {
            byte[] nameBytes = Encoding.ASCII.GetBytes(name + "\0");
            GCHandle pinned = GCHandle.Alloc(nameBytes, GCHandleType.Pinned);
            try { return glGetUniformLocation(program, pinned.AddrOfPinnedObject()); }
            finally { pinned.Free(); }
        }

        private static string GetShaderInfoLog(uint shader, int bufferLength)
        {
            byte[] log = new byte[bufferLength];
            int written;
            GCHandle pinned = GCHandle.Alloc(log, GCHandleType.Pinned);
            try { glGetShaderInfoLog(shader, bufferLength, out written, pinned.AddrOfPinnedObject()); }
            finally { pinned.Free(); }
            return Encoding.ASCII.GetString(log, 0, Math.Max(0, Math.Min(written, log.Length))).TrimEnd('\0');
        }

        private static string GetProgramInfoLog(uint program, int bufferLength)
        {
            byte[] log = new byte[bufferLength];
            int written;
            GCHandle pinned = GCHandle.Alloc(log, GCHandleType.Pinned);
            try { glGetProgramInfoLog(program, bufferLength, out written, pinned.AddrOfPinnedObject()); }
            finally { pinned.Free(); }
            return Encoding.ASCII.GetString(log, 0, Math.Max(0, Math.Min(written, log.Length))).TrimEnd('\0');
        }

        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glCreateShader")]
        private static extern uint glCreateShader(uint type);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glShaderSource")]
        private static extern void glShaderSource(uint shader, int count, IntPtr strings, IntPtr lengths);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glCompileShader")]
        private static extern void glCompileShader(uint shader);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetShaderiv")]
        private static extern void glGetShaderiv(uint shader, uint name, out int value);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetShaderInfoLog")]
        private static extern void glGetShaderInfoLog(uint shader, int maxLength, out int length, IntPtr infoLog);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDeleteShader")]
        private static extern void glDeleteShader(uint shader);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glCreateProgram")]
        private static extern uint glCreateProgram();
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glAttachShader")]
        private static extern void glAttachShader(uint program, uint shader);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glLinkProgram")]
        private static extern void glLinkProgram(uint program);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetProgramiv")]
        private static extern void glGetProgramiv(uint program, uint name, out int value);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetProgramInfoLog")]
        private static extern void glGetProgramInfoLog(uint program, int maxLength, out int length, IntPtr infoLog);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDeleteProgram")]
        private static extern void glDeleteProgram(uint program);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetAttribLocation")]
        private static extern int glGetAttribLocation(uint program, IntPtr name);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetUniformLocation")]
        private static extern int glGetUniformLocation(uint program, IntPtr name);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glUniform1i")]
        private static extern void glUniform1i(int location, int value);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glUniform1f")]
        private static extern void glUniform1f(int location, float value);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glUniform4f")]
        private static extern void glUniform4f(int location, float x, float y, float z, float w);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glUseProgram")]
        private static extern void glUseProgram(uint program);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGenVertexArrays")]
        private static extern void glGenVertexArrays(int count, out uint arrays);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glBindVertexArray")]
        private static extern void glBindVertexArray(uint array);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDeleteVertexArrays")]
        private static extern void glDeleteVertexArrays(int count, ref uint arrays);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGenBuffers")]
        private static extern void glGenBuffers(int count, out uint buffers);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glBindBuffer")]
        private static extern void glBindBuffer(uint target, uint buffer);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glBufferData")]
        private static extern void glBufferData(uint target, IntPtr size, IntPtr data, uint usage);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDeleteBuffers")]
        private static extern void glDeleteBuffers(int count, ref uint buffers);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glEnableVertexAttribArray")]
        private static extern void glEnableVertexAttribArray(uint index);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glVertexAttribPointer")]
        private static extern void glVertexAttribPointer(uint index, int size, uint type, byte normalized, int stride, IntPtr pointer);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDrawArrays")]
        private static extern void glDrawArrays(uint mode, int first, int count);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetIntegerv")]
        private static extern void glGetIntegerv(uint name, out int value);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetIntegerv")]
        private static extern void glGetIntegerv(uint name, IntPtr values);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetBooleanv")]
        private static extern void glGetBooleanv(uint name, out byte value);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glGetBooleanv")]
        private static extern void glGetBooleanv(uint name, IntPtr values);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glIsEnabled")]
        private static extern byte glIsEnabled(uint capability);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glEnable")]
        private static extern void glEnable(uint capability);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDisable")]
        private static extern void glDisable(uint capability);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glActiveTexture")]
        private static extern void glActiveTexture(uint texture);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glBindTexture")]
        private static extern void glBindTexture(uint target, uint texture);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glViewport")]
        private static extern void glViewport(int x, int y, int width, int height);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glColorMask")]
        private static extern void glColorMask(byte red, byte green, byte blue, byte alpha);
        [DllImport("/System/Library/Frameworks/OpenGL.framework/OpenGL", EntryPoint = "glDepthMask")]
        private static extern void glDepthMask(byte enabled);
    }
}
