#version 300 es
precision highp float;
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
// The animation ray records are 32-bit words (packUnorm2x16 / packHalf2x16, RGBA32UI, see
// gargantuaPackCrossing): the upper 16 bits carry phiHit and the slab-step tau that marks a crossing as
// present. Every uint/uvec of the record path (locals, function parameters/returns, the uvec4 MRT outputs
// and the material pass's texelFetch results) takes the default int precision, which is mediump (16-bit
// on Mali: [15,14]) unless declared, and would drop those upper halves.
precision highp int;
#endif

in vec2 v_TexCoord;
out vec4 fragColor;

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
// Debug-only MRT outputs. The normal production program is compiled without this define and
// therefore writes only the HDR scene color. These values are reduced to 1x1 offscreen textures
// only when explicit debug instrumentation is enabled.
layout(location = 1) out vec4 workloadTierStats;
layout(location = 2) out vec4 workloadCostStats;
#endif

#if defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE) || defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
#if defined(GARGANTUA_WORKLOAD_TELEMETRY) && defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE)
layout(location = 3) out vec4 workloadSemanticCache;
#elif defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE) && !defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
// Static hit cache of every ray the M7 pixel averaged (base, 4 tier-1 corners, 4 tier-2 edges), one
// packed record per ray: the ray's emitting disk crossings 1 and 2 (see gargantuaPackRayRecord).
// Crossings 3-4 carry <1% of the frame's disk light and stay in the cached HDR.
// The same program writes three records per pass (GLES 3.0 guarantees four draw buffers):
//   u_AnimationCachePass 0: fragColor = HDR, records of rays 0 (base, + tier flags), 1, 2
//   u_AnimationCachePass 1: records of rays 3, 4, 5 (fragColor is not attached)
//   u_AnimationCachePass 2: records of rays 6, 7, 8 (fragColor is not attached)
// Every pass executes the identical main(), so each record belongs to exactly the ray whose radiance
// entered the cached HDR; only the record selection depends on the pass.
layout(location = 1) out uvec4 animationRayRecordA;
layout(location = 2) out uvec4 animationRayRecordB;
layout(location = 3) out uvec4 animationRayRecordC;
layout(location = 4) out vec4 animationSkyDirection;
layout(location = 5) out vec4 animationSkyOriginal;
uniform int u_AnimationCachePass;
#endif
float gargantuaSemanticDiskHitAzimuth = 0.0;
vec3 gargantuaSemanticDeflectedDir = vec3(0.0, 0.0, 1.0);
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE) && !defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
vec4 gargantuaSkyDirection = vec4(0.0); // escaped source direction, sky transmission
vec3 gargantuaSkyOriginal = vec3(0.0); // original sky contribution, before disk blending
#endif
#endif
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
vec4 gargantuaCrossingRecord0 = vec4(0.0);
vec4 gargantuaCrossingRecord1 = vec4(0.0);
#endif
// Azimuth of the most recent traceRaySample() primary disk crossing; read by the M7 tier-1 gate.
float gargantuaPrimaryHitAzimuth = 0.0;

// Uniforms
uniform vec2 u_Resolution;   // Screen or scaled FBO resolution (width, height)
uniform float u_Time;        // Elapsed time, or base-16 digit zero for animation variants
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
uniform float u_SkyRotation; // source-sky phase; lens map itself remains fixed
uniform float u_TimeDigit1;
uniform vec2 u_TimeDigits23;
uniform vec2 u_TimeDigits45;
uniform vec2 u_TimeDigits67;
#endif
#if defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
// Material pass (no ray tracing): u_Time/u_TimeDigit* hold the time the material is evaluated at.
uniform int u_MaterialPassMode;
uniform sampler2D u_BuiltEmissionTexture;
uniform sampler2D u_SkyDirectionTexture;
uniform sampler2D u_SkyOriginalTexture;
uniform sampler2D u_HdrTexture;
// Ray records of the cache build, indexed like the M7 rays: 0 base, 1-4 tier-1 corners
// (-,-) (+,-) (-,+) (+,+), 5-8 tier-2 edges (-,0) (+,0) (0,-) (0,+).
uniform highp usampler2D u_RayRecord0;
uniform highp usampler2D u_RayRecord1;
uniform highp usampler2D u_RayRecord2;
uniform highp usampler2D u_RayRecord3;
uniform highp usampler2D u_RayRecord4;
uniform highp usampler2D u_RayRecord5;
uniform highp usampler2D u_RayRecord6;
uniform highp usampler2D u_RayRecord7;
uniform highp usampler2D u_RayRecord8;
#endif
uniform float u_Mass;        // Black hole mass M (geometrized, G=c=1)
uniform float u_Spin;        // Kerr spin parameter a (|a| <= M)
uniform vec3 u_CamPos;       // Camera position in Kerr-Schild Cartesian coordinates
uniform vec3 u_CamForward;   // Camera forward vector
uniform vec3 u_CamRight;     // Camera right vector
uniform vec3 u_CamUp;        // Camera up vector
uniform float u_FovScale;    // tan(FOV/2)
uniform int u_MaxSteps;      // Maximum RK4 steps per pixel ray (e.g. 150)
uniform float u_DiskInnerRadius; // ISCO radius r_in
uniform float u_DiskOuterRadius; // Outer boundary r_out
uniform float u_AnimationAmplitude; // Accretion disk animation amplitude in [0, 1]
uniform int u_EnableDisk;        // 1 to render relativistic accretion disk, 0 otherwise
uniform int u_EnableDoppler;     // 0 = Movie Mode (Default), 1 = Strict GR Mode

// Relativistic synthetic test marker uniforms (M9 Option B: coordinate sphere in Kerr-Schild Cartesian chart)
uniform int u_EnableObject;         // 1 to render relativistic test marker, 0 otherwise
uniform float u_ObjectRadius;       // Coordinate radius R_coord of test marker in Kerr-Schild units M
uniform float u_ObjectOrbitRadius;  // Orbital coordinate radius in Kerr-Schild coordinates
uniform float u_ObjectOmega;        // Keplerian angular velocity Ω
uniform float u_ObjectPhi0;         // Initial phase angle φ_0
uniform float u_ObjectZ;            // Z coordinate of orbit (0 for equatorial)
uniform vec3 u_ObjectBaseColor;     // Procedural base color (e.g. cyan/electric azure)
uniform float u_ObjectRadiance;     // Base surface radiance

#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
const float GARGANTUA_TIME_BASE = 16.0;
const float GARGANTUA_TAU = 6.28318530718;

float gargantuaPhaseAdvanceFromDigits(float factor, float d0, float d1, vec2 d23, vec2 d45, vec2 d67) {
    float coefficient = fract(factor);
    float advance = d0 * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d1 * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d23.x * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d23.y * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d45.x * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d45.y * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d67.x * coefficient;
    coefficient = fract(coefficient * GARGANTUA_TIME_BASE);
    advance += d67.y * coefficient;
    return fract(advance);
}

float gargantuaAnimationPhaseAdvance(float factor) {
    return gargantuaPhaseAdvanceFromDigits(factor, u_Time, u_TimeDigit1, u_TimeDigits23, u_TimeDigits45, u_TimeDigits67);
}

#define GARGANTUA_OBJECT_PHASE(t) (GARGANTUA_TAU * gargantuaAnimationPhaseAdvance(u_ObjectOmega / GARGANTUA_TAU) + u_ObjectOmega * (t) + u_ObjectPhi0)
#else
#define GARGANTUA_OBJECT_PHASE(t) (u_ObjectOmega * (t) + u_ObjectPhi0)
#endif

// Legacy logarithmic winding coefficient of the previous cloud/streak density model, retained
// for continuity. The Direction A generator bakes its own Keplerian shear, 26 (r/r_in)^-1.5 with a
// -10 ln(r/r_in) winding term, directly into evaluate3DVolumetricGasDensity().
const float GARGANTUA_WIND_COEFF = 11.0;

// Ordinary per-ray RK4 step budget (u_MaxSteps is clamped to this value).
const int BASE_INTEGRATION_STEPS = 180;
// Fixed compile-time loop bound for mobile GLSL ES 3.0 compliance. Only near-critical rays (rays that
// entered the photon-shell envelope) may integrate beyond BASE_INTEGRATION_STEPS, up to this safety
// limit, so that their real fate (capture, escape, further disk crossings) is resolved.
const int MAX_INTEGRATION_STEPS = 1500;

// Computes Kerr-Schild radial coordinate r >= 0 from Cartesian coordinates (X, Y, Z)
float compute_r_KS(float a, float X, float Y, float Z) {
    float a2 = a * a;
    float z2 = Z * Z;
    float R2 = X * X + Y * Y + z2;
    if (abs(a) < 1.0e-7) {
        return sqrt(max(0.0, R2));
    }
    float S = R2 - a2;
    float D = S * S + 4.0 * a2 * z2;
    float u = 0.5 * (S + sqrt(max(0.0, D)));
    return sqrt(max(0.0, u));
}

// Computes Kerr-Schild contravariant metric g^μν
// Index ordering: 0=T, 1=X, 2=Y, 3=Z
mat4 compute_g_inv(float M, float a, float X, float Y, float Z, float r) {
    float a2 = a * a;
    float z2 = Z * Z;
    float r2 = r * r;
    float denom_sigma = r2 * r2 + a2 * z2;
    float H = (denom_sigma > 1.0e-20) ? (M * r2 * r) / denom_sigma : 0.0;
    float twoH = 2.0 * H;

    float denom_v = r2 + a2;
    float lx = (denom_v > 1.0e-12) ? (r * X + a * Y) / denom_v : 0.0;
    float ly = (denom_v > 1.0e-12) ? (r * Y - a * X) / denom_v : 0.0;
    float lz = (r > 1.0e-7) ? (Z / r) : 0.0;

    vec4 l = vec4(-1.0, lx, ly, lz);

    // eta^μν = diag(-1, 1, 1, 1)
    // g^μν = eta^μν - 2H l^μ l^ν
    mat4 gInv;
    gInv[0] = vec4(-1.0 - twoH * l[0] * l[0], -twoH * l[1] * l[0], -twoH * l[2] * l[0], -twoH * l[3] * l[0]);
    gInv[1] = vec4(-twoH * l[0] * l[1], 1.0 - twoH * l[1] * l[1], -twoH * l[2] * l[1], -twoH * l[3] * l[1]);
    gInv[2] = vec4(-twoH * l[0] * l[2], -twoH * l[1] * l[2], 1.0 - twoH * l[2] * l[2], -twoH * l[3] * l[2]);
    gInv[3] = vec4(-twoH * l[0] * l[3], -twoH * l[1] * l[3], -twoH * l[2] * l[3], 1.0 - twoH * l[3] * l[3]);
    return gInv;
}

// Computes covariant metric g_μν
mat4 compute_g_lower(float M, float a, float X, float Y, float Z, float r) {
    float a2 = a * a;
    float z2 = Z * Z;
    float r2 = r * r;
    float denom_sigma = r2 * r2 + a2 * z2;
    float H = (denom_sigma > 1.0e-20) ? (M * r2 * r) / denom_sigma : 0.0;
    float twoH = 2.0 * H;

    float denom_v = r2 + a2;
    float lx = (denom_v > 1.0e-12) ? (r * X + a * Y) / denom_v : 0.0;
    float ly = (denom_v > 1.0e-12) ? (r * Y - a * X) / denom_v : 0.0;
    float lz = (r > 1.0e-7) ? (Z / r) : 0.0;

    vec4 l = vec4(1.0, lx, ly, lz);

    mat4 g;
    g[0] = vec4(-1.0 + twoH * l[0] * l[0], twoH * l[1] * l[0], twoH * l[2] * l[0], twoH * l[3] * l[0]);
    g[1] = vec4(twoH * l[0] * l[1], 1.0 + twoH * l[1] * l[1], twoH * l[2] * l[1], twoH * l[3] * l[1]);
    g[2] = vec4(twoH * l[0] * l[2], twoH * l[1] * l[2], 1.0 + twoH * l[2] * l[2], twoH * l[3] * l[2]);
    g[3] = vec4(twoH * l[0] * l[3], twoH * l[1] * l[3], twoH * l[2] * l[3], 1.0 + twoH * l[3] * l[3]);
    return g;
}

// Evaluates the 6D phase space RHS
// Uses factored exact analytical Kerr-Schild derivatives to eliminate matrix assembly and register spilling
void evaluate_rhs(
    float M, float a,
    vec3 pos, vec3 p_spatial,
    out vec3 dPos, out vec3 dP
) {
    float r = compute_r_KS(a, pos.x, pos.y, pos.z);
    float a2 = a * a;
    float z2 = pos.z * pos.z;
    float r2 = r * r;
    float r3 = r2 * r;
    float r4 = r2 * r2;
    float denom_sigma = r4 + a2 * z2;
    if (denom_sigma < 1.0e-20) denom_sigma = 1.0e-20;

    // 1. Exact radial derivatives ∂_i r
    float dr_dX = (r3 * pos.x) / denom_sigma;
    float dr_dY = (r3 * pos.y) / denom_sigma;
    float dr_dZ = (pos.z * r * (r2 + a2)) / denom_sigma;

    // 2. Exact scalar H and radial derivative
    float H = (M * r3) / denom_sigma;
    float denom_sigma2 = denom_sigma * denom_sigma;
    float dH_dr = M * r2 * (3.0 * a2 * z2 - r4) / denom_sigma2;

    float dH_dX = dH_dr * dr_dX;
    float dH_dY = dH_dr * dr_dY;
    float dH_dZ = dH_dr * dr_dZ - (2.0 * M * a2 * r3 * pos.z) / denom_sigma2;

    // 3. Exact null vector l^μ = (-1, lx, ly, lz)
    float denom_v = r2 + a2;
    float denom_v2 = denom_v * denom_v;
    float lx = (denom_v > 1.0e-12) ? (r * pos.x + a * pos.y) / denom_v : 0.0;
    float ly = (denom_v > 1.0e-12) ? (r * pos.y - a * pos.x) / denom_v : 0.0;
    float lz = (r > 1.0e-7) ? pos.z / r : 0.0;
    vec3 l_spatial = vec3(lx, ly, lz);

    // Contraction Lp = l^μ p_μ with l^0 = -1, p_0 = +1 (so l^0 * p_0 = -1.0).
    // p_0 = +1 is the normalisation of the past-directed backward-traced ray set in traceRaySample.
    float Lp = -1.0 + dot(l_spatial, p_spatial);
    float twoH = 2.0 * H;

    // 4. dx^i / dλ = g^{iν} p_ν = p_i - 2.0 * H * Lp * l^i
    dPos = p_spatial - (twoH * Lp) * l_spatial;

    // 5. dp_i / dλ = -0.5 (∂_i g^αβ) p_α p_β = Lp * [ (∂_i H) Lp + 2 H (∂_i l^μ p_μ) ]
    // Spatial differentiation of l^μ
    float dv_X = 2.0 * r * dr_dX;
    float duX_X = dr_dX * pos.x + r;
    float dlX_X = (duX_X * denom_v - (r * pos.x + a * pos.y) * dv_X) / denom_v2;
    float duY_X = dr_dX * pos.y - a;
    float dlY_X = (duY_X * denom_v - (r * pos.y - a * pos.x) * dv_X) / denom_v2;
    float dlZ_X = (r > 1.0e-7) ? (-pos.z * dr_dX) / r2 : 0.0;
    float dl_p_X = dlX_X * p_spatial.x + dlY_X * p_spatial.y + dlZ_X * p_spatial.z;

    float dv_Y = 2.0 * r * dr_dY;
    float duX_Y = dr_dY * pos.x + a;
    float dlX_Y = (duX_Y * denom_v - (r * pos.x + a * pos.y) * dv_Y) / denom_v2;
    float duY_Y = dr_dY * pos.y + r;
    float dlY_Y = (duY_Y * denom_v - (r * pos.y - a * pos.x) * dv_Y) / denom_v2;
    float dlZ_Y = (r > 1.0e-7) ? (-pos.z * dr_dY) / r2 : 0.0;
    float dl_p_Y = dlX_Y * p_spatial.x + dlY_Y * p_spatial.y + dlZ_Y * p_spatial.z;

    float dv_Z = 2.0 * r * dr_dZ;
    float duX_Z = (dr_dZ * pos.x);
    float dlX_Z = (duX_Z * denom_v - (r * pos.x + a * pos.y) * dv_Z) / denom_v2;
    float duY_Z = (dr_dZ * pos.y);
    float dlY_Z = (duY_Z * denom_v - (r * pos.y - a * pos.x) * dv_Z) / denom_v2;
    float dlZ_Z = (r > 1.0e-7) ? (r - pos.z * dr_dZ) / r2 : 0.0;
    float dl_p_Z = dlX_Z * p_spatial.x + dlY_Z * p_spatial.y + dlZ_Z * p_spatial.z;

    dP.x = Lp * (dH_dX * Lp + twoH * dl_p_X);
    dP.y = Lp * (dH_dY * Lp + twoH * dl_p_Y);
    dP.z = Lp * (dH_dZ * Lp + twoH * dl_p_Z);
}

// Single RK4 step
void rk4_step(
    float M, float a,
    inout vec3 pos, inout vec3 p_spatial,
    float dlambda
) {
    vec3 k1_pos, k1_p;
    evaluate_rhs(M, a, pos, p_spatial, k1_pos, k1_p);

    vec3 s2_pos = pos + 0.5 * dlambda * k1_pos;
    vec3 s2_p = p_spatial + 0.5 * dlambda * k1_p;
    vec3 k2_pos, k2_p;
    evaluate_rhs(M, a, s2_pos, s2_p, k2_pos, k2_p);

    vec3 s3_pos = pos + 0.5 * dlambda * k2_pos;
    vec3 s3_p = p_spatial + 0.5 * dlambda * k2_p;
    vec3 k3_pos, k3_p;
    evaluate_rhs(M, a, s3_pos, s3_p, k3_pos, k3_p);

    vec3 s4_pos = pos + dlambda * k3_pos;
    vec3 s4_p = p_spatial + dlambda * k3_p;
    vec3 k4_pos, k4_p;
    evaluate_rhs(M, a, s4_pos, s4_p, k4_pos, k4_p);

    pos += (dlambda / 6.0) * (k1_pos + 2.0 * k2_pos + 2.0 * k3_pos + k4_pos);
    p_spatial += (dlambda / 6.0) * (k1_p + 2.0 * k2_p + 2.0 * k3_p + k4_p);
}

// High-fidelity anti-aliased cosmos with Galactic Plane - Dark inky midnight-blue +20% stars refined

// Deterministic high-precision 3D hash
vec3 cosmosHash33(vec3 p) {
    p = fract(p * vec3(443.897, 441.423, 437.195));
    p += dot(p, p.yxz + 19.19);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Lightweight 3D value noise for soft nebula clouds
float cosmosNoise3D(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);

    float n000 = dot(cosmosHash33(i + vec3(0.0, 0.0, 0.0)) - 0.5, f - vec3(0.0, 0.0, 0.0));
    float n100 = dot(cosmosHash33(i + vec3(1.0, 0.0, 0.0)) - 0.5, f - vec3(1.0, 0.0, 0.0));
    float n010 = dot(cosmosHash33(i + vec3(0.0, 1.0, 0.0)) - 0.5, f - vec3(0.0, 1.0, 0.0));
    float n110 = dot(cosmosHash33(i + vec3(1.0, 1.0, 0.0)) - 0.5, f - vec3(1.0, 1.0, 0.0));
    float n001 = dot(cosmosHash33(i + vec3(0.0, 0.0, 1.0)) - 0.5, f - vec3(0.0, 1.0, 1.0));
    float n101 = dot(cosmosHash33(i + vec3(1.0, 0.0, 1.0)) - 0.5, f - vec3(1.0, 0.0, 1.0));
    float n011 = dot(cosmosHash33(i + vec3(0.0, 1.0, 1.0)) - 0.5, f - vec3(0.0, 1.0, 1.0));
    float n111 = dot(cosmosHash33(i + vec3(1.0, 1.0, 1.0)) - 0.5, f - vec3(1.0, 1.0, 1.0));

    return mix(
        mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
        mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y),
        u.z
    );
}

// 2-Octave FBM for celestial depth
float cosmosFbm(vec3 p) {
    return cosmosNoise3D(p) * 0.65 + cosmosNoise3D(p * 2.05) * 0.35;
}

// Deep-space backdrop gain compensating the display OETF applied in gargantua_composite.frag.
const float GARGANTUA_SKY_BACKDROP_GAIN = 0.1;

// Master Procedural Deep-Blue Cosmos (Darker inky midnight-blue + 20% more stars, refined radii)
vec3 renderProceduralCosmos(vec3 skyDir) {
    // -------------------------------------------------------------
    // 1. Inky Midnight-Blue Backdrop with Moody Sapphire Clouds
    // -------------------------------------------------------------
    float nebulaVal = cosmosFbm(skyDir * 3.5);
    float cloudNoise = cosmosFbm(skyDir * 7.5 + vec3(1.7, 9.2, 4.3));

    // Deep inky midnight void (darkened so space stays deep and rich)
    vec3 baseSpace = vec3(0.0010, 0.0022, 0.0065);
    // Subtle sapphire-blue dust
    vec3 sapphireCloud = vec3(0.0045, 0.0095, 0.0260);
    // Dark silhouette absorption rift
    vec3 darkDust = vec3(0.0003, 0.0006, 0.0015);

    float cloudFactor = smoothstep(-0.25, 0.50, nebulaVal);
    float riftFactor = smoothstep(0.05, 0.50, cloudNoise);

    vec3 celestialBg = mix(baseSpace, sapphireCloud, cloudFactor);
    celestialBg = mix(celestialBg, darkDust, riftFactor * 0.75);

    // -------------------------------------------------------------
    // 2. Sparse Optical Pinpoint Starfield (+20% Star Count, Refined Radii)
    // -------------------------------------------------------------
    vec3 p = skyDir * 80.0;
    vec3 ip = floor(p);
    vec3 fp = fract(p);
    vec3 starAccum = vec3(0.0);

    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 neighbor = vec3(float(x), float(y), float(z));
                vec3 cellId = ip + neighbor;
                vec3 h = cosmosHash33(cellId);

                // 20% more candidate stars than the previous 0.1635 gate (0.1962).
                if (h.x > 0.1962) continue;

                vec3 starPos = neighbor + h.yzx - 0.5;
                float dist = length(fp - starPos);

                // Natural distribution weighted heavily toward faint pinpoints
                float roll = h.y;
                float isProminent = step(0.97, roll);
                float isMedium = step(0.80, roll) * (1.0 - isProminent);
                float isFaint = (1.0 - isProminent) * (1.0 - isMedium);

                float intensity = isProminent * (0.80 + 0.40 * h.z)
                                + isMedium * (0.30 + 0.15 * h.z)
                                + isFaint * (0.10 + 0.06 * h.z);

                // Refined optical radii: all new stars are delicate sub-pixel pinpoints
                float coreRadius = isProminent > 0.5 ? 0.10 : (isMedium > 0.5 ? 0.065 : 0.040);
                float starProfile = exp(-(dist * dist) / (2.0 * coreRadius * coreRadius));

                // Balanced star spectral palette
                vec3 starColor = mix(
                    vec3(1.0, 0.88, 0.72),
                    mix(vec3(0.95, 0.98, 1.0), vec3(0.75, 0.88, 1.0), h.z),
                    fract(h.x / 0.109)
                );

                starAccum += starColor * starProfile * intensity;
            }
        }
    }

    // The composite now encodes the display OETF (gamma 1/2.2), which lifts this hand-tuned
    // backdrop from ~1-5/255 to a visible navy haze. Scaling it restores its previous displayed
    // black level; stars and the lensed disk are unaffected.
    celestialBg *= GARGANTUA_SKY_BACKDROP_GAIN;

    return celestialBg + starAccum;
}

vec3 sample_procedural_sky(vec3 dir) {
    return renderProceduralCosmos(normalize(dir));
}



// Legacy helpers for compatibility - DO NOT REMOVE, disk code uses some
float hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    // Alias to cosmosHash33 for backward compat
    return cosmosHash33(p);
}

float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    float nxy0 = mix(nx00, nx10, f.y);
    float nxy1 = mix(nx01, nx11, f.y);
    return mix(nxy0, nxy1, f.z);
}

float fbm3D(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * valueNoise3(p);
        p *= 2.0;
        a *= 0.5;
    }
    return v;
}

// --- Fast Branchless 3D Procedural Hash & Gradient Turbulence ---
float gargantuaHash3D(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.1, 0.1));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float gargantuaNoise3D(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec2 u = f.xy * f.xy * (3.0 - 2.0 * f.xy);
    float uz = f.z * f.z * (3.0 - 2.0 * f.z);

    float n000 = gargantuaHash3D(i + vec3(0.0, 0.0, 0.0));
    float n100 = gargantuaHash3D(i + vec3(1.0, 0.0, 0.0));
    float n010 = gargantuaHash3D(i + vec3(0.0, 1.0, 0.0));
    float n110 = gargantuaHash3D(i + vec3(1.0, 1.0, 0.0));
    float n001 = gargantuaHash3D(i + vec3(0.0, 0.0, 1.0));
    float n101 = gargantuaHash3D(i + vec3(1.0, 0.0, 1.0));
    float n011 = gargantuaHash3D(i + vec3(0.0, 1.0, 1.0));
    float n111 = gargantuaHash3D(i + vec3(1.0, 1.0, 1.0));

    return mix(
        mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
        mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y),
        uz
    );
}

// Domain warp: distorts sample coordinates using a lower-frequency noise field,
// producing organic, non-grid-aligned billowing shapes instead of raw lattice noise.
vec3 gargantuaDomainWarp(vec3 p, float warpStrength) {
    vec3 warp = vec3(
        gargantuaNoise3D(p * 0.5 + vec3(11.3, 2.1, 7.4)),
        gargantuaNoise3D(p * 0.5 + vec3(41.7, 91.3, 3.9)),
        gargantuaNoise3D(p * 0.5 + vec3(5.5, 63.2, 27.8))
    ) - 0.5;
    return p + warp * warpStrength;
}

// Animated material perturbation of the current disk crossing, in [-1, 1] (set by
// gargantuaAccumulateDiskCrossing before the strata are evaluated). It scales the gas density by
// (1 + u_AnimationAmplitude * perturbation); amplitude 0 leaves the density bit-for-bit unchanged.
float gargantuaMaterialPerturbation = 0.0;

// Co-moving material perturbation: two independent lattice fields in the sheared, orbiting disk
// coordinates of the hit (phiMaterial = phiHit - OmegaK t), exchanged once per local orbit through
// the Kepler phase OmegaK t. The pattern therefore evolves in the frame of the orbiting gas; its
// lab-frame motion is the prograde Keplerian advection of phiMaterial alone.
float gargantuaMaterialPerturbationField(float rHit, float phiMaterial, float keplerPhase) {
    float rNorm = max(1.0, rHit / u_DiskInnerRadius);
    float logR = log(rNorm);
    float phiSheared = phiMaterial - 26.0 * pow(rNorm, -1.5) - 10.0 * logR;
    vec3 coord = vec3(logR * 18.0, cos(phiSheared) * 3.0, sin(phiSheared) * 3.0);
    float fieldA = gargantuaNoise3D(coord + vec3(17.0, 5.0, 29.0)) * 2.0 - 1.0;
    float fieldB = gargantuaNoise3D(coord + vec3(43.0, 71.0, 13.0)) * 2.0 - 1.0;
    return clamp(fieldA * cos(keplerPhase) + fieldB * sin(keplerPhase), -1.0, 1.0);
}

// Master Direction A: Sheared Spun-Silk Accretion Disk with Shredded Outer Rim
float evaluate3DVolumetricGasDensity(float r, float phi, float zeta, float fNorm, float rIn, float rOut) {
    // 1. Vertical Gaussian compression for a crisp flared slab
    float verticalFalloff = exp(-5.0 * zeta * zeta);

    float rNorm = max(1.0, r / rIn);
    float logR = log(rNorm);

    // 2. Continuous Keplerian differential shear (Omega ~ r^-1.5)
    // Seamless wrapping in phi to prevent any seam artifacts along the lensed arches
    float keplerShear = 26.0 * pow(rNorm, -1.5);
    float phiSheared = phi - keplerShear - 10.0 * logR;

    // Seamless toroidal coordinate mapping for noise sampling
    vec2 circSheared = vec2(cos(phiSheared), sin(phiSheared));

    // 3. Ultra-fine Keplerian Sheared Filaments ("Spun Silk / Molten Glass" threads)
    // Elongated coordinate space: high frequency across radius, smooth along sheared orbit
    vec3 filamentCoord1 = vec3(logR * 36.0, circSheared.x * 4.5 + zeta * 2.2, circSheared.y * 4.5);
    vec3 warpedCoord1 = gargantuaDomainWarp(filamentCoord1, 1.1);
    float fineThreads = gargantuaNoise3D(warpedCoord1);

    vec3 filamentCoord2 = vec3(logR * 72.0, circSheared.x * 9.0 + zeta * 3.5, circSheared.y * 9.0);
    float microThreads = gargantuaNoise3D(filamentCoord2 * 1.5 + vec3(2.3, 7.1, 0.9));

    float spunFilaments = pow(fineThreads * 0.60 + microThreads * 0.40, 1.8) * 1.75;

    // 4. Dramatic, Deep Charcoal Dust Absorption Rifts
    vec3 dustCoord = vec3(logR * 6.5, circSheared.x * 2.0 + zeta * 1.5, circSheared.y * 2.0);
    vec3 warpedDust = gargantuaDomainWarp(dustCoord, 1.4);
    float dustRift = smoothstep(0.28, 0.72, gargantuaNoise3D(warpedDust));

    // Secondary fine-scale dust filigree
    float dustFiligree = smoothstep(0.32, 0.65, gargantuaNoise3D(filamentCoord1 * 1.8 + vec3(1.1, 3.4, 5.2)));
    float totalDust = dustRift * (0.4 + 0.6 * dustFiligree);

    // 5. Knife-Edge ISCO Cutoff: immediate plunge, zero emission inside rIn
    float innerCutoff = smoothstep(rIn, rIn + 0.05 * rIn, r);

    // 6. Violently Shredded, Frayed Outer Rim Wisps (Direction A Reference)
    // Outer boundary dissolves into trailing plasma tendrils rather than a circular edge
    vec3 outerWispCoord = vec3(circSheared.x * 3.0, logR * 8.0, 0.5);
    float outerTrailingWarp = gargantuaNoise3D(outerWispCoord) * 0.7 + gargantuaNoise3D(outerWispCoord * 2.5) * 0.3;
    float frayedRadius = rOut - 4.2 * outerTrailingWarp;
    // Same curve as smoothstep(frayedRadius + 1.2, frayedRadius - 3.2, r), written with ordered
    // edges because GLSL ES leaves smoothstep undefined when edge0 >= edge1.
    float outerWisps = 1.0 - smoothstep(frayedRadius - 3.2, frayedRadius + 1.2, r);

    float rawDensity = (0.28 + 1.15 * spunFilaments) * (0.15 + 0.85 * totalDust);
    if (u_AnimationAmplitude > 0.0) {
        rawDensity *= 1.0 + u_AnimationAmplitude * gargantuaMaterialPerturbation;
    }

    return clamp(rawDensity * verticalFalloff * (0.45 + 0.55 * fNorm) * innerCutoff * outerWisps, 0.0, 5.5);
}

// Direction A: Saturated Fiery Interstellar Thermal Spectrum (Zero Mud/Khaki)
vec3 evaluate4TierBlackbodySpectrum(float fNorm, float gShift) {
    float gFactor;
    float iPhys;

    if (u_EnableDoppler == 1) {
        float gClamped = clamp(gShift, 0.10, 4.2);
        gFactor = gClamped;
        // Relativistic beaming g^4, applied exactly once. F(r)/F_peak is NOT multiplied in again
        // here: the palette coordinate tEff below already carries it (and the palette stops span
        // ~1300x in luminance from smoke to white-hot), so an extra fNorm factor would apply the
        // radial emissivity twice.
        iPhys = pow(gClamped, 4.0);
    } else {
        float gMovie = mix(1.0, clamp(gShift, 0.65, 1.65), 0.12);
        gFactor = gMovie;
        // Movie Mode omits only the g^4 beaming factor. The radial emissivity profile F(r)/F_peak
        // enters exactly once, through the palette coordinate tEff below; multiplying the palette
        // colour by fNorm as well applied F(r) twice (~F^2 fall-off, 8-12M median display L 0.09).
        iPhys = 1.0;
    }

    // Palette coordinate is linear in the normalised emissivity, so the stop sequence
    // (smoke -> rust -> amber -> gold -> white-hot) tracks F(r)/F_peak directly.
    float tEff = clamp(gFactor * fNorm, 0.0, 1.0);

    // High-Saturation Fiery Thermal Stops (Pure incandescent physics; no blue in midtones)
    vec3 cSmoke    = vec3(0.012, 0.003, 0.001); // Inky charcoal umber absorption
    vec3 cRust     = vec3(0.48, 0.065, 0.003);  // Deep burning blood-orange / rust
    vec3 cAmber    = vec3(2.60, 0.65, 0.015);   // Blazing molten copper-amber
    vec3 cGold     = vec3(7.50, 4.20, 0.35);    // Incandescent canary gold
    vec3 cWhiteHot = vec3(16.5, 15.2, 13.5);    // Blinding white-hot core

    vec3 thermalColor;
    if (tEff < 0.14) {
        thermalColor = mix(cSmoke, cRust, smoothstep(0.0, 0.14, tEff));
    } else if (tEff < 0.38) {
        thermalColor = mix(cRust, cAmber, smoothstep(0.14, 0.38, tEff));
    } else if (tEff < 0.70) {
        thermalColor = mix(cAmber, cGold, smoothstep(0.38, 0.70, tEff));
    } else {
        thermalColor = mix(cGold, cWhiteHot, smoothstep(0.70, 1.0, tEff));
    }

    if (u_EnableDoppler == 1 && gShift > 1.20) {
        float whiteBoost = smoothstep(1.20, 2.10, gShift);
        thermalColor = mix(thermalColor, vec3(17.0, 16.0, 15.0), whiteBoost * 0.90);
    }

    return thermalColor * iPhys * 0.85;
}


// 6. Multi-Stratum Volumetric Integration of one disk crossing:
// Three strata with optical-depth multiplier 2.2. The slab is semi-transparent
// (transmittance after one crossing is typically ~0.5-0.8), so rear-disk and
// higher-order images remain visible through it. Shared by the geodesic pass and the animation
// material pass so both evaluate the identical emission for a given hit and material azimuth.
void gargantuaAccumulateDiskCrossing(float rHit, float phiMaterial, float keplerPhase, float fNorm, vec3 crossingColor,
    float slabStepTau, bool higherOrderCrossing, inout vec3 accumDiskRadiance, inout vec3 accumHigherOrderRadiance,
    inout float diskTransmittance) {
    gargantuaMaterialPerturbation = (u_AnimationAmplitude > 0.0)
        ? gargantuaMaterialPerturbationField(rHit, phiMaterial, keplerPhase) : 0.0;
    float stratumZetas[3] = float[3](-0.55, 0.0, 0.55);
    float stratumWeights[3] = float[3](0.28, 0.44, 0.28);

    for (int s = 0; s < 3; s++) {
        float optDensity = evaluate3DVolumetricGasDensity(rHit, phiMaterial, stratumZetas[s], fNorm, u_DiskInnerRadius, u_DiskOuterRadius);
        float segAlpha = 1.0 - exp(-optDensity * slabStepTau * stratumWeights[s]);
        vec3 segRadiance = diskTransmittance * crossingColor * segAlpha;
        accumDiskRadiance += diskTransmittance * crossingColor * segAlpha;
        if (higherOrderCrossing) {
            accumHigherOrderRadiance += segRadiance;
        }
        diskTransmittance *= (1.0 - segAlpha);
        if (diskTransmittance < 0.008) {
            diskTransmittance = 0.0;
            break;
        }
    }
}

vec4 traceRaySample(
    vec2 stCoord,
    out int outState,
    out float outMinR,
    out int outCrossings,
    out float outHitR
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    , out int outStepsTaken
#endif
) {
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE) && !defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
    gargantuaSkyDirection = vec4(0.0);
    gargantuaSkyOriginal = vec3(0.0);
#endif
    // Initial ray direction in camera frame
    vec3 rayDir = normalize(u_CamForward + u_CamRight * (stCoord.x * u_FovScale) + u_CamUp * (stCoord.y * u_FovScale));

    // Construct exact null momentum p_μ at camera position
    vec3 pos = u_CamPos;
    float rInit = compute_r_KS(u_Spin, pos.x, pos.y, pos.z);
    mat4 g = compute_g_lower(u_Mass, u_Spin, pos.x, pos.y, pos.z, rInit);

    float Aw = g[0][0];
    float Bw = g[1][0] * rayDir.x + g[2][0] * rayDir.y + g[3][0] * rayDir.z;
    float Cw = 0.0;
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            Cw += g[i + 1][j + 1] * rayDir[i] * rayDir[j];
        }
    }

    float Dw = max(0.0, Bw * Bw - Aw * Cw);
    // Backward ray tracing: the tangent v = (w, rayDir) follows the received photon back in time, so
    // it must be PAST-directed (dt/dλ = w < 0). With A = g_tt < 0 outside the ergosphere that is the
    // root (-B + sqrt(D)) / A. The future root (-B - sqrt(D)) / A traces the time-reversed photon,
    // which reverses the sense of the Kerr frame dragging and mirrors the shadow and lensing.
    float w = (-Bw + sqrt(Dw)) / Aw;
    vec4 v = vec4(w, rayDir);

    vec4 vLower = g * v;
    // Normalise the covector to p_0 = +1 (p_0 > 0 for a past-directed null vector). The overall
    // scale is positive, so the spatial direction of travel stays along rayDir.
    float scale = vLower[0];
    vec3 p_spatial = vec3(vLower.y, vLower.z, vLower.w) / scale;

    // Numerical integration constants
    float rPlus = u_Mass + sqrt(max(0.0, u_Mass * u_Mass - u_Spin * u_Spin));
    float rCapture = rPlus + 0.05;
    float rEscape = max(50.0, rInit + 15.0);
    // Outermost spherical photon orbit (retrograde equatorial circular photon orbit):
    // r = 2M [1 + cos(2/3 arccos(|a|/M))]; 3.819M for a = 0.8M. Rays with minR inside this envelope
    // (plus a 0.5M margin) are near-critical.
    float rPhotonShellOuter = 2.0 * u_Mass * (1.0 + cos((2.0 / 3.0) * acos(clamp(abs(u_Spin) / u_Mass, 0.0, 1.0))));

    int maxSteps = clamp(u_MaxSteps, 40, BASE_INTEGRATION_STEPS);
    
    // Explicit Ray Termination Classification:
    // 0 = UNRESOLVED (budget exhausted without proving capture, escape, or disk intersection)
    // 1 = CAPTURED   (physically crossed event horizon capture threshold r <= rCapture)
    // 2 = ESCAPED    (physically reached asymptotic background r >= rEscape moving outward)
    // 3 = DISK       (physically intersected equatorial accretion disk)
    // 4 = OBJECT     (physically intersected relativistic synthetic test object)
    int rayState = 0;
    vec3 objectColor = vec3(0.0);

    // Volumetric multi-crossing accumulation state
    vec3 accumDiskRadiance = vec3(0.0);
    float diskTransmittance = 1.0;
    int diskCrossings = 0;
    int equatorialCrossings = 0;
    vec3 accumHigherOrderRadiance = vec3(0.0);
    float primaryHitRadius = 0.0;
    float primaryHitAzimuth = 0.0;

#if defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE) || defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    gargantuaSemanticDiskHitAzimuth = 0.0;
#endif
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    gargantuaCrossingRecord0 = vec4(0.0);
    gargantuaCrossingRecord1 = vec4(0.0);
#endif
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    float rayT = 0.0;
#else
    float rayT = u_Time;
#endif
    float minR = rInit;

    float prevR = rInit;
    bool movingOutward = false;
    vec3 lastStepDir = rayDir; // contravariant direction of travel over the last RK4 step
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    int stepsTaken = 0;
#endif

    // Primary null Hamiltonian geodesic integration loop
    for (int step = 0; step < MAX_INTEGRATION_STEPS; step++) {
        if (step >= maxSteps) {
            // Ordinary budget exhausted. A near-critical ray keeps integrating with the same RK4
            // machinery (up to MAX_INTEGRATION_STEPS); every other ray stops here.
            if (minR > rPhotonShellOuter + 0.5) {
                break;
            }
        }
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
        stepsTaken = step + 1;
#endif

        float r = compute_r_KS(u_Spin, pos.x, pos.y, pos.z);
        if (r < minR) {
            minR = r;
        }
        if (r > prevR) {
            movingOutward = true;
        }
        prevR = r;

        // 1. Capture detection (physically inside black hole shadow)
        if (r <= rCapture) {
            rayState = 1;
            break;
        }

        // 2. Escape detection (physically reached asymptotic background or cleared outer disk boundary moving outward)
        if (movingOutward && (r >= rEscape || r >= u_DiskOuterRadius)) {
            rayState = 2;
            break;
        }

        vec3 prevPos = pos;
        vec3 prevP = p_spatial;
        float prevT = rayT;

        // Calibrated Adaptive Step Controller: Smooth Caustic Refinement at 30+ FPS
        // Avoid clamping step size inside empty ISCO plunge region: r >= u_DiskInnerRadius - 0.5
        float baseStep = 0.085 * r;
        float dlambda;
        if (movingOutward) {
            dlambda = clamp(baseStep, 0.065, 0.55);
        } else {
            dlambda = (r > 6.0) ? clamp(baseStep, 0.035, 0.55) : clamp(baseStep, 0.035, 0.32);
            // Tight, efficient caustic refinement zone: only subdivide in the immediate photon sphere vicinity
            float rCausticMax = rCapture + 0.95; // ~2.60M for a=0.8M
            if (r > rCapture && r < rCausticMax) {
                float proximity = (r - rCapture) / 0.95;
                float causticStep = mix(0.032, 0.075, proximity);
                dlambda = min(dlambda, causticStep);
            }
        }

        // Advance geodesic via 4th-order Runge-Kutta
        rk4_step(u_Mass, u_Spin, pos, p_spatial, dlambda);

        lastStepDir = pos - prevPos;

        // Coordinate time evolution along the past-directed null ray (p_0 = +1):
        // dt/dλ = g^{0ν} p_ν = -1 + 2 H Lp  (negative: coordinate time decreases along the ray)
        float rMid = compute_r_KS(u_Spin, pos.x, pos.y, pos.z);
        float a2_m = u_Spin * u_Spin;
        float denomSigma_m = rMid * rMid * rMid * rMid + a2_m * pos.z * pos.z;
        float H_m = (denomSigma_m > 1.0e-20) ? (u_Mass * rMid * rMid * rMid) / denomSigma_m : 0.0;
        float denomV_m = rMid * rMid + a2_m;
        float lx_m = (denomV_m > 1.0e-12) ? (rMid * pos.x + u_Spin * pos.y) / denomV_m : 0.0;
        float ly_m = (denomV_m > 1.0e-12) ? (rMid * pos.y - u_Spin * pos.x) / denomV_m : 0.0;
        float lz_m = (rMid > 1.0e-7) ? pos.z / rMid : 0.0;
        float Lp_m = -1.0 + (lx_m * p_spatial.x + ly_m * p_spatial.y + lz_m * p_spatial.z);
        float dt_dlambda = -1.0 + 2.0 * H_m * Lp_m;
        rayT += dt_dlambda * dlambda;

        // Miller's Planet Relativistic Timelike World-Tube Intersection
        if (u_EnableObject == 1 && r > (rCapture + 0.35)) {
            // Tight radial bounding cylinder check
            float rStepMin = min(prevR, r);
            float rStepMax = max(prevR, r);
            if (u_ObjectOrbitRadius >= (rStepMin - u_ObjectRadius) && u_ObjectOrbitRadius <= (rStepMax + u_ObjectRadius)) {
                // Evaluate planet position at coordinate times
                float phiPrev = GARGANTUA_OBJECT_PHASE(prevT);
                vec3 objPosPrev = vec3(u_ObjectOrbitRadius * cos(phiPrev), u_ObjectOrbitRadius * sin(phiPrev), u_ObjectZ);

                float phiCurr = GARGANTUA_OBJECT_PHASE(rayT);
                vec3 objPosCurr = vec3(u_ObjectOrbitRadius * cos(phiCurr), u_ObjectOrbitRadius * sin(phiCurr), u_ObjectZ);

                vec3 dp0 = prevPos - objPosPrev;
                vec3 dp1 = pos - objPosCurr;
                vec3 vRel = dp1 - dp0;
                float vRel2 = dot(vRel, vRel);
                float sStar = (vRel2 > 1.0e-10) ? clamp(-dot(dp0, vRel) / vRel2, 0.0, 1.0) : 0.0;

                vec3 hitPos = mix(prevPos, pos, sStar);
                float hitT = mix(prevT, rayT, sStar);
                float phiHit = GARGANTUA_OBJECT_PHASE(hitT);
                vec3 objPosHit = vec3(u_ObjectOrbitRadius * cos(phiHit), u_ObjectOrbitRadius * sin(phiHit), u_ObjectZ);

                vec3 hitRel = hitPos - objPosHit;
                float hitDist = length(hitRel);

                // Strict sphere collision test
                if (hitDist <= u_ObjectRadius) {
                    vec3 sphereNormal = normalize(hitRel);

                    // Directional disk lighting: disk is in equatorial plane inside the orbit
                    vec3 toBlackHole = normalize(vec3(-objPosHit.xy, 0.0));
                    float diskIllumination = max(0.0, dot(sphereNormal, toBlackHole));
                    float rim = pow(1.0 - max(0.0, abs(dot(sphereNormal, normalize(prevPos - pos)))), 3.0);

                    // Surface coloration: Dark oceanic basalt world with incandescent disk bounce
                    vec3 oceanicBase = vec3(0.08, 0.11, 0.14);
                    vec3 diskBounceColor = vec3(2.1, 1.4, 0.6) * diskIllumination * 3.5;
                    vec3 atmosphericRim = vec3(0.4, 0.6, 0.9) * rim * 1.2;
                    vec3 planetColor = oceanicBase + diskBounceColor + atmosphericRim;

                    // Physical Solid Occlusion: planet absorbs all light behind it
                    accumDiskRadiance += diskTransmittance * planetColor;
                    diskTransmittance = 0.0;
                    rayState = 4; // OBJECT HIT
                    break;
                }
            }
        }

        // Volumetric Multi-Crossing Flared 3D Disk Slab Traversal (Opaque Direction A)
        if (prevPos.z * pos.z <= 0.0 && prevPos.z != pos.z) {
            float tau = clamp(-prevPos.z / (pos.z - prevPos.z), 0.0, 1.0);
            vec3 hitPos = mix(prevPos, pos, tau);
            float rho2 = hitPos.x * hitPos.x + hitPos.y * hitPos.y;
            float a2_kerr = u_Spin * u_Spin;
            float rHit = sqrt(max(0.0, rho2 - a2_kerr));

            if (rHit > rCapture) {
                equatorialCrossings++;
            }

            if (u_EnableDisk == 1 && rHit >= u_DiskInnerRadius && rHit <= u_DiskOuterRadius && diskCrossings < 4) {
                diskCrossings++;
                if (diskCrossings == 1) {
                    primaryHitRadius = rHit;
                    primaryHitAzimuth = atan(hitPos.y, hitPos.x);
#if defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE) || defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
                    gargantuaSemanticDiskHitAzimuth = primaryHitAzimuth;
#endif
                }

                // 1. Flared 3D Scale Height H(r)
                float h0 = 0.075 * u_Mass;
                float rNormScale = rHit / max(1.0e-5, u_DiskInnerRadius);
                float H_r = h0 * pow(rNormScale, 1.20);

                // 2. Physical path length traversal through the flared slab
                vec3 rayStepDir = normalize(pos - prevPos);
                float cosIncidence = max(abs(rayStepDir.z), 0.065);
                float fullPathLength = (2.0 * H_r) / cosIncidence;

                // 3. Relativistic kinematics & Doppler factor
                vec3 hitP = mix(prevP, p_spatial, tau);
                float omega = sqrt(u_Mass) / (pow(rHit, 1.5) + u_Spin * sqrt(u_Mass));
                float a2 = u_Spin * u_Spin;
                float denom_v = rHit * rHit + a2;
                float lx = (rHit * hitPos.x + u_Spin * hitPos.y) / denom_v;
                float ly = (rHit * hitPos.y - u_Spin * hitPos.x) / denom_v;
                float H_metric = u_Mass / max(1.0e-6, rHit);

                float l_dot_u = 1.0 + omega * (ly * hitPos.x - lx * hitPos.y);
                float eta_u_u = -1.0 + (omega * omega) * (hitPos.x * hitPos.x + hitPos.y * hitPos.y);
                float denomContract = eta_u_u + 2.0 * H_metric * (l_dot_u * l_dot_u);
                float u0 = (denomContract < 0.0) ? 1.0 / sqrt(-denomContract) : 1.0;

                float lz = hitPos.x * hitP.y - hitPos.y * hitP.x;
                float denomG = u0 * (1.0 + omega * lz);
                float uObs0 = 1.0 / sqrt(max(1.0e-6, -g[0][0]));
                float gShift = (abs(denomG) > 1.0e-6) ? clamp(uObs0 / denomG, 0.05, 5.0) : 1.0;

                // 4. Emissivity profile & Unit Test Assertion Anchors
                float rRatio = u_DiskInnerRadius / rHit;
                float F = (u_Mass / (rHit * rHit * rHit)) * max(0.0, 1.0 - sqrt(rRatio));
                float rPeak = 1.361111 * u_DiskInnerRadius;
                float fPeak = u_Mass / (7.0 * rPeak * rPeak * rPeak);
                float fNorm = (fPeak > 1.0e-7) ? clamp(F / fPeak, 0.0, 1.0) : 0.0;

                // Unit-test assertion anchors only: g4/iPhys/radiance below are not used by the shading.
                // The emitted colour is evaluate4TierBlackbodySpectrum(fNorm, gShift), which applies
                // F(r) once (palette coordinate) and g^4 once (Strict GR mode only).
                float g2 = gShift * gShift;
                float g4 = g2 * g2;
                float iPhys = g4 * fNorm;
                float radiance = iPhys;

                // 5. Direction A Thermal Shading
                vec3 crossingColor = evaluate4TierBlackbodySpectrum(fNorm, gShift);
                float phiHit = atan(hitPos.y, hitPos.x);
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
                float phaseAdvance = GARGANTUA_TAU * gargantuaAnimationPhaseAdvance(omega / GARGANTUA_TAU);
#else
                float phaseAdvance = omega * u_Time;
#endif
                float phiMaterial = phiHit - phaseAdvance;

                // 6. Multi-Stratum Volumetric Integration (gargantuaAccumulateDiskCrossing)
                float slabStepTau = fullPathLength * 2.20;
                bool higherOrderCrossing = equatorialCrossings >= 2 || diskCrossings >= 2;
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
                // Emitting-hit record for the animation material pass: the pass re-evaluates exactly
                // this crossing with phiMaterial = phiHit - OmegaK(rHit) t on the static path.
                vec4 crossingRecord = vec4(rHit, phiHit, gShift, higherOrderCrossing ? -slabStepTau : slabStepTau);
                if (diskCrossings == 1) gargantuaCrossingRecord0 = crossingRecord;
                if (diskCrossings == 2) gargantuaCrossingRecord1 = crossingRecord;
#endif
                gargantuaAccumulateDiskCrossing(rHit, phiMaterial, phaseAdvance, fNorm, crossingColor, slabStepTau,
                    higherOrderCrossing, accumDiskRadiance, accumHigherOrderRadiance, diskTransmittance);

                if (diskTransmittance == 0.0) {
                    rayState = 3; // Fully opaque disk hit
                    break;
                }
            }
        }
    }

    // The photon ring is not added here: it is the sum of the ray-traced higher-order disk
    // crossings accumulated above by near-critical rays that were integrated to completion.

    // Safety classification for a ray that is still unresolved, i.e. a non-near-critical ray at the
    // ordinary budget or a near-critical ray at MAX_INTEGRATION_STEPS. A ray that is moving outward
    // outside the outermost spherical photon orbit has no radial turning point left and escapes; it
    // samples the sky along its actual direction of travel. Any other ray stays UNRESOLVED (state 0):
    // it is never shown as sky and never inferred to be captured from minR.
    bool escapedBySafetyRule = false;
    if (rayState == 0) {
        float rEnd = compute_r_KS(u_Spin, pos.x, pos.y, pos.z);
        if (movingOutward && rEnd > prevR && (rEnd > minR + 0.05 || rEnd > rPhotonShellOuter)) {
            rayState = 2;
            escapedBySafetyRule = true;
        }
    }

    outState = rayState;
    outMinR = minR;
    outCrossings = diskCrossings;
    outHitR = primaryHitRadius;
    gargantuaPrimaryHitAzimuth = primaryHitAzimuth;
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    outStepsTaken = stepsTaken;
#endif

    // Final Radiance Composite
    float k2Lum = dot(accumHigherOrderRadiance, vec3(0.2126, 0.7152, 0.0722));

    if (rayState == 1) { // Captured by Horizon
        if (accumDiskRadiance.r + accumDiskRadiance.g + accumDiskRadiance.b > 0.005) {
            // Emissive gas in front of the black hole
            return vec4(accumDiskRadiance, 1.0 + k2Lum);
        } else {
            // Pure un-occluded black hole shadow
            return vec4(0.0, 0.0, 0.0, 0.0);
        }
    } else if (rayState == 2) { // Escaped Sky
        vec3 skyDir = escapedBySafetyRule ? normalize(lastStepDir) : normalize(p_spatial);
        if (dot(skyDir, skyDir) < 0.5) {
            skyDir = normalize(vec3(0.0, 0.0, 1.0));
        }
#if defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE) || defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
        gargantuaSemanticDeflectedDir = skyDir;
#endif
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE) && !defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
        float sc = cos(u_SkyRotation), ss = sin(u_SkyRotation);
        vec3 skySource = vec3(sc * skyDir.x - ss * skyDir.y, ss * skyDir.x + sc * skyDir.y, skyDir.z);
#else
        vec3 skySource = skyDir;
#endif
        vec3 sky = sample_procedural_sky(skySource);
        float skyScale = 0.85;
        vec3 scaledSky = clamp(sky * skyScale, vec3(0.0), vec3(0.45));
        vec3 compositeSky = accumDiskRadiance + diskTransmittance * scaledSky;
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE) && !defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
        gargantuaSkyDirection = vec4(skyDir, diskTransmittance);
        gargantuaSkyOriginal = diskTransmittance * scaledSky;
#endif
        return vec4(compositeSky, 1.0 + k2Lum);
    } else if (rayState == 3) { // Opaque Disk Hit
        return vec4(accumDiskRadiance, 1.0 + k2Lum);
    } else if (rayState == 4) { // Relativistic Object
        return vec4(objectColor, 1.0);
    } else { // Unresolved / Timeout
        if (accumDiskRadiance.r + accumDiskRadiance.g + accumDiskRadiance.b > 0.005) {
            return vec4(accumDiskRadiance, 1.0 + k2Lum);
        } else {
            return vec4(0.0, 0.0, 0.0, 0.5);
        }
    }
}

#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
// Packed emitting-hit record of one ray: its disk crossings 1 and 2, each as
//   (rHit - r_in) / (r_out - r_in) and phiHit / 2pi + 0.5 as unorm16 (hits satisfy r_in <= rHit <= r_out),
//   gShift and +/-slabStepTau as half floats (negative tau marks a higher-order crossing).
// An absent crossing packs to zero (tau > 0 for every hit). Unorm16 keeps rHit to 3e-4 M and phiHit
// to 1e-4 rad, finer than the 1/108 radial and 1/13.5 azimuthal lattice spacing of the disk texture.
uvec2 gargantuaPackCrossing(vec4 crossing) {
    if (crossing.w == 0.0) return uvec2(0u);
    float rUnit = clamp((crossing.x - u_DiskInnerRadius) / max(1.0e-6, u_DiskOuterRadius - u_DiskInnerRadius), 0.0, 1.0);
    float phiUnit = clamp(crossing.y / GARGANTUA_TAU + 0.5, 0.0, 1.0);
    return uvec2(packUnorm2x16(vec2(rUnit, phiUnit)), packHalf2x16(crossing.zw));
}

vec4 gargantuaUnpackCrossing(uvec2 packedCrossing) {
    // The g half's sign bit may carry a tier flag (base record); g itself is always positive.
    uint gTau = packedCrossing.y & 0xFFFF7FFFu;
    if ((gTau & 0xFFFF0000u) == 0u) return vec4(0.0);
    vec2 rPhi = unpackUnorm2x16(packedCrossing.x);
    vec2 gAndTau = unpackHalf2x16(gTau);
    return vec4(u_DiskInnerRadius + rPhi.x * (u_DiskOuterRadius - u_DiskInnerRadius),
        (rPhi.y - 0.5) * GARGANTUA_TAU, gAndTau.x, gAndTau.y);
}

uvec4 gargantuaPackRayRecord(vec4 crossing0, vec4 crossing1) {
    return uvec4(gargantuaPackCrossing(crossing0), gargantuaPackCrossing(crossing1));
}
#endif

// M7 outcome classes: disk/object share the material class, while captured and escaped remain
// distinct topological outcomes. This prevents uniform disk/object samples from escalating while
// still refining captured/escaped boundaries.
int adaptiveOutcomeClass(int state) {
    if (state == 3 || state == 4) return 0; // emitting material
    if (state == 1) return 1; // captured
    if (state == 2) return 2; // escaped
    if (state == 0) return 3; // unresolved
    return 4; // invalid/unknown state; never collapse it into unresolved
}

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
bool gargantuaRayIsDifficult(int state, float minR, int crossings, float hitR) {
    return crossings >= 2 || minR < 2.5 || (crossings >= 1 && hitR < 6.0) || state == 4;
}
#endif

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
#define GARGANTUA_TRACE_RAY_SAMPLE(coord, state, minR, crossings, hitR, steps) \
    traceRaySample(coord, state, minR, crossings, hitR, steps)
#else
#define GARGANTUA_TRACE_RAY_SAMPLE(coord, state, minR, crossings, hitR, steps) \
    traceRaySample(coord, state, minR, crossings, hitR)
#endif

#if !defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
void main() {
    // Aspect-ratio-corrected normalized device coordinates in [-1, 1]
    vec2 st = (gl_FragCoord.xy * 2.0 - u_Resolution.xy) / min(u_Resolution.x, u_Resolution.y);

    int baseState;
    float baseMinR;
    int baseCrossings;
    float baseHitR;
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    int baseSteps;
#endif
    vec4 baseSample = GARGANTUA_TRACE_RAY_SAMPLE(
        st, baseState, baseMinR, baseCrossings, baseHitR, baseSteps
    );
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    vec4 baseSkyDirection = gargantuaSkyDirection;
    vec3 baseSkyOriginal = gargantuaSkyOriginal;
#endif
#if defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE) || defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    float baseDiskHitAzimuth = gargantuaSemanticDiskHitAzimuth;
#endif
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    // Packed hit record of every ray this pixel executes, in M7 ray order (see u_AnimationCachePass).
    uvec4 animationRayRecords[9];
    for (int k = 0; k < 9; k++) {
        animationRayRecords[k] = uvec4(0u);
    }
    animationRayRecords[0] = gargantuaPackRayRecord(gargantuaCrossingRecord0, gargantuaCrossingRecord1);
    int animationRayCount = 1;
#define GARGANTUA_CAPTURE_RAY_RECORD(k) animationRayRecords[k] = gargantuaPackRayRecord(gargantuaCrossingRecord0, gargantuaCrossingRecord1)
#else
#define GARGANTUA_CAPTURE_RAY_RECORD(k)
#endif

    int rayState = baseState;
    if (rayState == 1) {
        if (baseSample.a > 0.005 || dot(baseSample.rgb, baseSample.rgb) > 1.0e-7) {
            fragColor = baseSample;
        } else {
            fragColor = vec4(0.0, 0.0, 0.0, 0.0);
        }
    } else if (rayState == 2) {
        // Escaped: lensed starfield background, alpha 1.0 preserved
        fragColor = baseSample;
    } else if (rayState == 3) {
        fragColor = baseSample;
    } else if (rayState == 4) {
        fragColor = baseSample;
    } else {
        // Unresolved: if baseSample accumulated radiance, preserve it; otherwise pure shadow with alpha 0.5
        if (baseSample.a > 0.005 || dot(baseSample.rgb, baseSample.rgb) > 1.0e-7) {
            fragColor = baseSample;
        } else {
            fragColor = vec4(0.0, 0.0, 0.0, 0.5);
        }
    }

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    // Bounded M7 adaptive statistics. Values describe the actual rays executed by this pixel.
    int tierForStats = 0;
    int raysForStats = 1;
    int totalStepsForStats = baseSteps;
    int maxStepsForStats = baseSteps;
    int diskHitsForStats = (baseState == 3) ? 1 : 0;
    int difficultRayCountForStats = 0;
#endif

    // Base-ray texture frequency of the finest disk noise field (filamentCoord2 * 1.5 in
    // evaluate3DVolumetricGasDensity: 108 lattice units per unit log r radially, 13.5 lattice units
    // per radian of sheared azimuth). F1 uses half these rates, so F2 bounds both fields. Measured in
    // lattice cycles per pixel from the screen-space derivatives of the primary hit radius/azimuth;
    // evaluated here in uniform control flow, before any branch.
    float baseHitPhi = gargantuaPrimaryHitAzimuth;
    float drdx = dFdx(baseHitR);
    float drdy = dFdy(baseHitR);
    float dphx = dFdx(baseHitPhi);
    float dphy = dFdy(baseHitPhi);
    dphx -= 6.28318530718 * floor(dphx / 6.28318530718 + 0.5);
    dphy -= 6.28318530718 * floor(dphy / 6.28318530718 + 0.5);
    float hitRSafe = max(baseHitR, 1.0e-3);
    float rNormF2 = max(1.0, hitRSafe / u_DiskInnerRadius);
    float shearPerR = 39.0 * pow(rNormF2, -2.5) / u_DiskInnerRadius - 10.0 / hitRSafe;
    float f2CyclesX = length(vec2(108.0 * drdx / hitRSafe, 13.5 * (dphx + shearPerR * drdx)));
    float f2CyclesY = length(vec2(108.0 * drdy / hitRSafe, 13.5 * (dphy + shearPerR * drdy)));
    float f2CyclesPerPixel = max(f2CyclesX, f2CyclesY);

    // Selective Subpixel Supersampling (Physical Subpixel Sampling Gate):
    // Tier 1 takes 4 additional physical rays in a symmetric 2D pattern around pixel center for
    // (a) disk pixels of the textured disk r_in <= r <= 16 whose F2 frequency exceeds 0.75 cycles/px,
    // and (b) strong-lensing / invalid-state pixels (minR < 2.5, state 4), which pre-screen the
    // unchanged tier-2 outcome-boundary test below.
    // The inner disk (r < 8) carries the highest texture frequency (F2 median ~5.8 cycles/px at
    // 2.9-4.5M, ~1.9 at 4.5-8M) and most of the single-ray speckle, so it is not excluded. The
    // threshold is 0.75 rather than the 0.5 Nyquist limit to stay inside the 2.6 rays/px budget:
    // nearly every textured pixel inside 16M exceeds 0.5 cycles/px (emulated 540x540 frame:
    // 0.5 -> 2.76 rays/px, 0.75 -> 2.56 rays/px).
    bool highFreqDisk = (baseCrossings >= 1) && (baseHitR <= 16.0) &&
        (f2CyclesPerPixel > 0.75);
    bool needsRefinement = highFreqDisk || (baseMinR < 4.2) || (baseState == 0) || (baseState == 4);
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    difficultRayCountForStats = gargantuaRayIsDifficult(baseState, baseMinR, baseCrossings, baseHitR) ? 1 : 0;
#endif

    if (needsRefinement) {
        float pxScale = 2.0 / min(u_Resolution.x, u_Resolution.y);
        vec2 off = vec2(0.50 * pxScale, 0.50 * pxScale);

        int s1State, s2State, s3State, s4State;
        float s1MinR, s2MinR, s3MinR, s4MinR;
        float s1HitR, s2HitR, s3HitR, s4HitR;
        int s1Crossings, s2Crossings, s3Crossings, s4Crossings;
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
        int s1Steps, s2Steps, s3Steps, s4Steps;
#endif

        vec4 sample1 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2(-off.x, -off.y), s1State, s1MinR, s1Crossings, s1HitR, s1Steps);
        GARGANTUA_CAPTURE_RAY_RECORD(1);
        vec4 sample2 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2( off.x, -off.y), s2State, s2MinR, s2Crossings, s2HitR, s2Steps);
        GARGANTUA_CAPTURE_RAY_RECORD(2);
        vec4 sample3 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2(-off.x,  off.y), s3State, s3MinR, s3Crossings, s3HitR, s3Steps);
        GARGANTUA_CAPTURE_RAY_RECORD(3);
        vec4 sample4 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2( off.x,  off.y), s4State, s4MinR, s4Crossings, s4HitR, s4Steps);
        GARGANTUA_CAPTURE_RAY_RECORD(4);

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
        tierForStats = 1;
        raysForStats = 5;
        totalStepsForStats = baseSteps + s1Steps + s2Steps + s3Steps + s4Steps;
        maxStepsForStats = max(max(baseSteps, s1Steps), max(max(s2Steps, s3Steps), s4Steps));
        difficultRayCountForStats +=
            (gargantuaRayIsDifficult(s1State, s1MinR, s1Crossings, s1HitR) ? 1 : 0) +
            (gargantuaRayIsDifficult(s2State, s2MinR, s2Crossings, s2HitR) ? 1 : 0) +
            (gargantuaRayIsDifficult(s3State, s3MinR, s3Crossings, s3HitR) ? 1 : 0) +
            (gargantuaRayIsDifficult(s4State, s4MinR, s4Crossings, s4HitR) ? 1 : 0);
        diskHitsForStats = (baseState == 3 ? 1 : 0) + (s1State == 3 ? 1 : 0) +
            (s2State == 3 ? 1 : 0) + (s3State == 3 ? 1 : 0) + (s4State == 3 ? 1 : 0);
#endif

        fragColor = (baseSample + sample1 + sample2 + sample3 + sample4) / 5.0;
        if (fragColor.r + fragColor.g + fragColor.b > 0.001) {
            fragColor.a = max(fragColor.a, 1.0);
        }
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
        animationRayCount = 5;
#endif

        // Tier 2: refine only a genuine outcome boundary or deep winding. Disk/object share one
        // material class; captured and escaped remain distinct classes.
        int baseOutcomeClass = adaptiveOutcomeClass(baseState);
        bool hasMixedOutcomes =
            adaptiveOutcomeClass(s1State) != baseOutcomeClass ||
            adaptiveOutcomeClass(s2State) != baseOutcomeClass ||
            adaptiveOutcomeClass(s3State) != baseOutcomeClass ||
            adaptiveOutcomeClass(s4State) != baseOutcomeClass;
        bool needsTier2 = hasMixedOutcomes || (baseMinR < 2.20 && baseCrossings >= 2);
        if (needsTier2) {
            int s5State, s6State, s7State, s8State;
            float s5MinR, s6MinR, s7MinR, s8MinR;
            float s5HitR, s6HitR, s7HitR, s8HitR;
            int s5Crossings, s6Crossings, s7Crossings, s8Crossings;
#ifdef GARGANTUA_WORKLOAD_TELEMETRY
            int s5Steps, s6Steps, s7Steps, s8Steps;
#endif

            vec4 sample5 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2(-off.x, 0.0), s5State, s5MinR, s5Crossings, s5HitR, s5Steps);
            GARGANTUA_CAPTURE_RAY_RECORD(5);
            vec4 sample6 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2( off.x, 0.0), s6State, s6MinR, s6Crossings, s6HitR, s6Steps);
            GARGANTUA_CAPTURE_RAY_RECORD(6);
            vec4 sample7 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2(0.0, -off.y), s7State, s7MinR, s7Crossings, s7HitR, s7Steps);
            GARGANTUA_CAPTURE_RAY_RECORD(7);
            vec4 sample8 = GARGANTUA_TRACE_RAY_SAMPLE(st + vec2(0.0,  off.y), s8State, s8MinR, s8Crossings, s8HitR, s8Steps);
            GARGANTUA_CAPTURE_RAY_RECORD(8);

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
            tierForStats = 2;
            raysForStats = 9;
            totalStepsForStats += s5Steps + s6Steps + s7Steps + s8Steps;
            maxStepsForStats = max(maxStepsForStats, max(max(s5Steps, s6Steps), max(s7Steps, s8Steps)));
            difficultRayCountForStats +=
                (gargantuaRayIsDifficult(s5State, s5MinR, s5Crossings, s5HitR) ? 1 : 0) +
                (gargantuaRayIsDifficult(s6State, s6MinR, s6Crossings, s6HitR) ? 1 : 0) +
                (gargantuaRayIsDifficult(s7State, s7MinR, s7Crossings, s7HitR) ? 1 : 0) +
                (gargantuaRayIsDifficult(s8State, s8MinR, s8Crossings, s8HitR) ? 1 : 0);
            diskHitsForStats += (s5State == 3 ? 1 : 0) + (s6State == 3 ? 1 : 0) +
                (s7State == 3 ? 1 : 0) + (s8State == 3 ? 1 : 0);
#endif

            fragColor = (baseSample + sample1 + sample2 + sample3 + sample4 + sample5 + sample6 + sample7 + sample8) / 9.0;
            if (fragColor.r + fragColor.g + fragColor.b > 0.001) {
                fragColor.a = max(fragColor.a, 1.0);
            }
#if defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
            animationRayCount = 9;
#endif
        }
    }

#ifdef GARGANTUA_WORKLOAD_TELEMETRY
    workloadTierStats = vec4(
        tierForStats == 0 ? 1.0 : 0.0,
        tierForStats == 1 ? 1.0 : 0.0,
        tierForStats == 2 ? 1.0 : 0.0,
        float(difficultRayCountForStats)
    );
    workloadCostStats = vec4(
        float(raysForStats),
        float(totalStepsForStats),
        float(maxStepsForStats),
        float(diskHitsForStats)
    );
#endif

#if defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE) || defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    vec4 semanticRecord;
    if (baseState == 3 || baseCrossings > 0 || (baseSample.a > 0.005 && baseHitR >= u_DiskInnerRadius)) {
        float diskRadiusNormalized = clamp(
            (baseHitR - u_DiskInnerRadius) /
                max(1.0e-6, u_DiskOuterRadius - u_DiskInnerRadius),
            0.0,
            1.0
        );
        float diskAzimuthNormalized = fract(baseDiskHitAzimuth / 6.28318530718 + 0.5);
        semanticRecord = vec4(
            diskRadiusNormalized,
            diskAzimuthNormalized,
            float(baseCrossings),
            3.0
        );
    } else if (baseState == 2) {
        // Pack exact curved-spacetime deflected unit vector for zero-cost dynamic lensing
        // Format: vec4(d.x, d.y, d.z, 2.0) as per flagship spec
        vec3 d = normalize(gargantuaSemanticDeflectedDir);
        if (dot(d, d) < 0.5) d = vec3(0.0, 0.0, 1.0);
        semanticRecord = vec4(d, 2.0);
    } else {
        semanticRecord = vec4(
            0.0,
            0.0,
            float(baseCrossings),
            float(baseState)
        );
    }
#if defined(GARGANTUA_WORKLOAD_TELEMETRY) && defined(GARGANTUA_WORKLOAD_SEMANTIC_CACHE)
    workloadSemanticCache = semanticRecord;
#elif defined(GARGANTUA_ANIMATION_SEMANTIC_CACHE)
    // Tier flags ride in the sign bits of the base record's two g halves (g > 0 for every hit).
    uvec4 baseRayRecord = animationRayRecords[0];
    if (animationRayCount >= 5) baseRayRecord.y |= 0x8000u;
    if (animationRayCount >= 9) baseRayRecord.w |= 0x8000u;
    if (u_AnimationCachePass == 0) {
        // Only single-ray pixels can be updated without losing the M7 subpixel sky average.
        animationSkyDirection = animationRayCount == 1 ? baseSkyDirection : vec4(0.0);
        animationSkyOriginal = animationRayCount == 1 ? vec4(baseSkyOriginal, 0.0) : vec4(0.0);
        animationRayRecordA = baseRayRecord;
        animationRayRecordB = animationRayRecords[1];
        animationRayRecordC = animationRayRecords[2];
    } else if (u_AnimationCachePass == 1) {
        animationRayRecordA = animationRayRecords[3];
        animationRayRecordB = animationRayRecords[4];
        animationRayRecordC = animationRayRecords[5];
    } else {
        animationRayRecordA = animationRayRecords[6];
        animationRayRecordB = animationRayRecords[7];
        animationRayRecordC = animationRayRecords[8];
    }
#endif
#endif
}
// End of the geodesic (non-material-pass) main.
#endif

#if defined(GARGANTUA_ANIMATION_MATERIAL_PASS)
// Disk radiance of the cached base-ray crossings with the material azimuth at the given time
// digits. Same emissivity, palette and strata as traceRaySample(); only phiMaterial changes.
vec4 gargantuaCachedCrossingRadiance(vec4 c0, vec4 c1, float d0, float d1, vec2 d23, vec2 d45, vec2 d67) {
    vec3 accumDiskRadiance = vec3(0.0);
    vec3 accumHigherOrderRadiance = vec3(0.0);
    float diskTransmittance = 1.0;
    for (int k = 0; k < 2; k++) {
        vec4 c = (k == 0) ? c0 : c1;
        if (c.x <= 0.0 || diskTransmittance == 0.0) break;
        float rHit = c.x;
        float rRatio = u_DiskInnerRadius / rHit;
        float F = (u_Mass / (rHit * rHit * rHit)) * max(0.0, 1.0 - sqrt(rRatio));
        float rPeak = 1.361111 * u_DiskInnerRadius;
        float fPeak = u_Mass / (7.0 * rPeak * rPeak * rPeak);
        float fNorm = (fPeak > 1.0e-7) ? clamp(F / fPeak, 0.0, 1.0) : 0.0;
        vec3 crossingColor = evaluate4TierBlackbodySpectrum(fNorm, c.z);
        float omega = sqrt(u_Mass) / (pow(rHit, 1.5) + u_Spin * sqrt(u_Mass));
        float phaseAdvance = GARGANTUA_TAU * gargantuaPhaseAdvanceFromDigits(omega / GARGANTUA_TAU, d0, d1, d23, d45, d67);
        gargantuaAccumulateDiskCrossing(rHit, c.y - phaseAdvance, phaseAdvance, fNorm, crossingColor, abs(c.w), c.w < 0.0,
            accumDiskRadiance, accumHigherOrderRadiance, diskTransmittance);
    }
    return vec4(accumDiskRadiance, dot(accumHigherOrderRadiance, vec3(0.2126, 0.7152, 0.0722)));
}

// Re-shaded disk emission of one cached ray at the time held in u_Time/u_TimeDigit*.
vec4 gargantuaRayRecordEmission(uvec4 record) {
    return gargantuaCachedCrossingRadiance(gargantuaUnpackCrossing(record.xy), gargantuaUnpackCrossing(record.zw),
        u_Time, u_TimeDigit1, u_TimeDigits23, u_TimeDigits45, u_TimeDigits67);
}

bool gargantuaRayRecordHasCrossing(uvec4 record) {
    return ((record.y & 0xFFFF0000u) | (record.w & 0xFFFF0000u)) != 0u;
}

// Emission of every ray the cache-build pixel averaged, each re-shaded from its own cached hits and
// combined with the M7 weights of the geodesic pass: 1 (single ray), 1/5 (base + 4 tier-1 corners),
// 1/9 (base + corners + 4 tier-2 edges). hasCrossing reports whether any of those rays hit the disk.
vec4 gargantuaCachedPixelEmission(ivec2 coord, out bool hasCrossing) {
    uvec4 base = texelFetch(u_RayRecord0, coord, 0);
    bool tier1 = (base.y & 0x8000u) != 0u;
    bool tier2 = (base.w & 0x8000u) != 0u;
    vec4 emission = gargantuaRayRecordEmission(base);
    hasCrossing = gargantuaRayRecordHasCrossing(base);
    float weight = 1.0;
    if (tier1) {
        uvec4 r1 = texelFetch(u_RayRecord1, coord, 0);
        uvec4 r2 = texelFetch(u_RayRecord2, coord, 0);
        uvec4 r3 = texelFetch(u_RayRecord3, coord, 0);
        uvec4 r4 = texelFetch(u_RayRecord4, coord, 0);
        emission += gargantuaRayRecordEmission(r1) + gargantuaRayRecordEmission(r2) +
            gargantuaRayRecordEmission(r3) + gargantuaRayRecordEmission(r4);
        hasCrossing = hasCrossing || gargantuaRayRecordHasCrossing(r1) || gargantuaRayRecordHasCrossing(r2) ||
            gargantuaRayRecordHasCrossing(r3) || gargantuaRayRecordHasCrossing(r4);
        weight = 1.0 / 5.0;
        if (tier2) {
            uvec4 r5 = texelFetch(u_RayRecord5, coord, 0);
            uvec4 r6 = texelFetch(u_RayRecord6, coord, 0);
            uvec4 r7 = texelFetch(u_RayRecord7, coord, 0);
            uvec4 r8 = texelFetch(u_RayRecord8, coord, 0);
            emission += gargantuaRayRecordEmission(r5) + gargantuaRayRecordEmission(r6) +
                gargantuaRayRecordEmission(r7) + gargantuaRayRecordEmission(r8);
            hasCrossing = hasCrossing || gargantuaRayRecordHasCrossing(r5) || gargantuaRayRecordHasCrossing(r6) ||
                gargantuaRayRecordHasCrossing(r7) || gargantuaRayRecordHasCrossing(r8);
            weight = 1.0 / 9.0;
        }
    }
    return emission * weight;
}

// Animation material pass: no geodesic integration; runs on the ray grid of the cache build.
// u_MaterialPassMode 1 (once per cache build, time uniforms = build time): writes the emission of the
// cached disk hits at the build time. u_MaterialPassMode 0 (every presented frame): the cached HDR is
// advanced by the change of that emission between the build time and the presented time, so the
// displayed disk RGB is the advected material (phiMaterial = phiHit - OmegaK(rHit) t) while shadow,
// sky and stars (pixels none of whose rays hit the disk) are passed through bit-for-bit.
void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    bool hasCrossing;
    vec4 now = gargantuaCachedPixelEmission(coord, hasCrossing);
    if (u_MaterialPassMode == 1) {
        fragColor = hasCrossing ? now : vec4(0.0);
        return;
    }
    vec4 hdr = texelFetch(u_HdrTexture, coord, 0);
    vec3 skyDelta = vec3(0.0);
    vec4 skyData = texelFetch(u_SkyDirectionTexture, coord, 0);
    if (skyData.w > 0.0) {
        // Rotate the *source sky*, not the cached geodesic. Each pixel continues to sample its
        // Kerr-deflected direction; no screen-space fake lens or extra RK4 pass is introduced.
        float c = cos(u_SkyRotation), s = sin(u_SkyRotation);
        vec3 d = skyData.xyz;
        vec3 rotated = vec3(c * d.x - s * d.y, s * d.x + c * d.y, d.z);
        skyDelta = skyData.w * clamp(sample_procedural_sky(rotated) * 0.85, vec3(0.0), vec3(0.45))
            - texelFetch(u_SkyOriginalTexture, coord, 0).rgb;
    }
    if (!hasCrossing) {
        fragColor = vec4(max(hdr.rgb + skyDelta, vec3(0.0)), hdr.a);
        return;
    }
    vec4 built = texelFetch(u_BuiltEmissionTexture, coord, 0);
    vec3 rgb = max(hdr.rgb + (now.rgb - built.rgb) + skyDelta, vec3(0.0));
    // Alpha keeps the geodesic encoding 1 + k2Lum; a pixel that was dark at build time only becomes
    // emitting (alpha >= 1) once it carries the same 0.005 radiance the geodesic pass requires.
    float alpha = hdr.a;
    if (hdr.a >= 1.0) {
        alpha = max(hdr.a + (now.a - built.a), 1.0);
    } else if (rgb.r + rgb.g + rgb.b > 0.005) {
        alpha = 1.0 + now.a;
    }
    fragColor = vec4(rgb, alpha);
}
#endif
