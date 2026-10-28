package com.zig.chal.shader

import com.zig.chal.physics.ChalPhysicsConstants
import java.util.Locale

/**
 * Legacy Chal OpenGL ES 3 fallback shader source.
 *
 * This renderer is used only when the XAPK's ARM64/Vulkan backend is unavailable. It is retained
 * for device compatibility, not as a pixel- or physics-equivalent reimplementation: its ray force,
 * coordinate handling, disk transfer, and redshift remain effective visual approximations. The
 * XAPK's compiled SPIR-V is separate and is executed by the native backend instead.
 */
object ChalShaderSource {

    /** JS `Number.prototype.toFixed` equivalent for the interpolation sites below. */
    private fun Double.fixed(decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", this)

    // =========================================================================================
    // Common chunk (`chunks/common.ts`)
    // =========================================================================================

    val COMMON_CHUNK: String = """
  precision highp float;
  
  // Fragment output (WebGL2)
  out vec4 fragColor;
  
  // === UNIFORMS ===
  uniform vec2 u_resolution;
  uniform float u_time;
  uniform float u_mass;
  uniform float u_spin;
  uniform float u_disk_density;
  uniform float u_disk_temp;
  uniform vec2 u_mouse;
  uniform float u_zoom;
  uniform float u_lensing_strength;
  uniform float u_frame_dragging_strength;
  uniform float u_disk_size;
  uniform float u_disk_scale_height;
  uniform int u_maxRaySteps;
  uniform sampler2D u_noiseTex;
  uniform sampler2D u_blueNoiseTex;
  uniform sampler2D u_spectrumLUT;
  uniform float u_debug; // Debug mode toggle

  uniform float u_show_redshift; // Toggle for gravitational redshift overlay
  uniform float u_show_kerr_shadow; // Toggle for Kerr shadow guide
  uniform vec2 u_shadowShift; // Analytical Shadow Extents (min_alpha, max_alpha)
  uniform vec2 u_shadowCurve[64]; // Analytic Critical Curve (64 points)
  uniform float u_shadowCount;    // Actual number of valid points in the curve

  
  // High-Precision Camera State (SAB Synced)
  uniform vec3 u_camPos;
  uniform vec4 u_camQuat;

  // === CONSTANTS ===
#define PI 3.14159265359
#define MAX_DIST ${ChalPhysicsConstants.RayMarching.MAX_DISTANCE.fixed(1)}
#define MIN_STEP ${ChalPhysicsConstants.RayMarching.MIN_STEP.fixed(2)}
#define MAX_STEP ${ChalPhysicsConstants.RayMarching.MAX_STEP.fixed(1)}

  // === HELPER FUNCTIONS ===
  mat2 rot(float a) {
    float s = sin(a), c = cos(a);
    return mat2(c, -s, s, c);
  }

  // ACES Tone Mapping (Narkowicz 2014)
  vec3 aces_tone_mapping(vec3 color) {
    float A = 2.51;
    float B = 0.03;
    float C = 2.43;
    float D = 0.59;
    float E = 0.14;
    return clamp((color * (A * color + B)) / (color * (C * color + D) + E), 0.0, 1.0);
  }


    /**
     * Analytic Shadow Boundary check.
     * Uses the Critical Curve coefficients from Rust to determine if a ray
     * hit the event horizon with infinite sub-pixel precision.
     */
    bool is_shadow(vec2 impactParams, vec2 criticalCurve) {
        // Simple elliptical approximation for now, 
        // will be upgraded to full parametric in Phase 3.
        float dist = length(impactParams / criticalCurve);
        return dist < 1.0;
    }

  // Quaternion Rotation (Phase 5.2)
  vec3 qrot(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
  }
"""

    // =========================================================================================
    // Kerr metric chunk (`chunks/metric.ts`)
    // =========================================================================================

    val METRIC_CHUNK: String = """
    // =========================================================================
    // Kerr Metric Functions
    //
    // References:
    //   Bardeen (1973). "Timelike and null geodesics in the Kerr metric"
    //   Bardeen, Press & Teukolsky (1972). "Rotating Black Holes"
    //   Chandrasekhar (1983). "The Mathematical Theory of Black Holes"
    // =========================================================================

    // --- Horizon, ISCO, Photon Sphere ---

    float kerr_horizon(float M, float a) {
        return M + sqrt(max(0.0, M*M - a*a));
    }

    // Exact ISCO (Bardeen, Press, Teukolsky 1972)
    float kerr_isco(float M, float a) {
        float rs = a / M;
        float absS = abs(clamp(rs, -0.9999, 0.9999));

        float z1 = 1.0 + pow(1.0 - absS * absS, 1.0/3.0) * (pow(1.0 + absS, 1.0/3.0) + pow(1.0 - absS, 1.0/3.0));
        float z2 = sqrt(3.0 * absS * absS + z1 * z1);

        float signOfA = sign(a);
        if (signOfA == 0.0) signOfA = 1.0;

        return M * (3.0 + z2 - signOfA * sqrt((3.0 - z1) * (3.0 + z1 + 2.0 * z2)));
    }

    // Prograde photon sphere (Bardeen 1973)
    float kerr_photon_sphere(float M, float a) {
        float a_star = clamp(a / M, -0.9999, 0.9999);
        float arg = clamp(-a_star, -1.0, 1.0);
        float theta = (2.0 / 3.0) * acos(arg);
        return 2.0 * M * (1.0 + cos(theta));
    }

    // Retrograde photon sphere
    float kerr_photon_sphere_retro(float M, float a) {
        float a_star = clamp(a / M, -0.9999, 0.9999);
        float arg = clamp(a_star, -1.0, 1.0);
        float theta = (2.0 / 3.0) * acos(arg);
        return 2.0 * M * (1.0 + cos(theta));
    }

    float kerr_ergosphere(float M, float a, float r, float cosTheta) {
        return M + sqrt(max(0.0, M * M - a * a * cosTheta * cosTheta));
    }

    // --- Oblate Spheroidal Kerr Coordinate ---
    // In Kerr spacetime, r is NOT Euclidean distance.
    // Solves: r^4 - (rho^2 - a^2)*r^2 - a^2*y^2 = 0
    // where rho = |p| and y is the spin axis component.
    float kerr_r(vec3 p, float a) {
        float a2 = a * a;
        float rho2 = dot(p, p);
        float diff = rho2 - a2;
        float disc = diff * diff + 4.0 * a2 * p.y * p.y;
        float r2 = 0.5 * (diff + sqrt(max(0.0, disc)));
        return sqrt(max(1e-8, r2));
    }

    // --- Chal GLES compatibility approximation ---
    // The native Vulkan backend uses the XAPK renderer. This fallback integrates an effective
    // radial/angular-momentum force plus a frame-drag term; it is not a full Kerr-Schild null-
    // Hamiltonian solver and must not be described as one.
    struct KerrAccelResult {
        vec3 accel;
        float r_k;          // Kerr radial coordinate
        float omega;        // Frame-dragging ZAMO velocity
    };

    KerrAccelResult kerr_geodesic_accel(vec3 p, vec3 v, float M, float a) {
        KerrAccelResult res;
        float a2 = a * a;

        // 1. Kerr radial coordinate (oblate spheroidal)
        float rho2 = dot(p, p);
        float diff = rho2 - a2;
        float disc = diff * diff + 4.0 * a2 * p.y * p.y;
        float r2 = 0.5 * (diff + sqrt(max(0.0, disc)));
        float r_k = sqrt(max(1e-8, r2));
        res.r_k = r_k;

        float sigma = r2 + a2 * (p.y * p.y / max(1e-8, r2));
        // 2. Effective angular-momentum correction used by this visual approximation.
        vec3 L_vec = cross(p, v);
        float Ly = L_vec.y; // Component along spin axis (Y)
        
        float Ly_eff = Ly - a;
        float L2_eff = Ly_eff * Ly_eff + (dot(L_vec, L_vec) - Ly * Ly);
        
        float r_inv = 1.0 / r_k;
        float r2_inv = r_inv * r_inv;
        float r4_inv = r2_inv * r2_inv;
        
        float sigma_ratio = r2 / max(1e-8, sigma);
        vec3 r_hat = -normalize(p);
        
        res.accel = r_hat * (M * r2_inv * sigma_ratio + 3.0 * M * max(0.0, L2_eff) * r4_inv * sigma_ratio);

        // 4. Approximate frame-drag acceleration.
        float r3_p_a2r = r_k * r2 + a2 * r_k;
        float drag_coeff = u_frame_dragging_strength * M * a / max(1e-8, r3_p_a2r);
        res.accel += cross(vec3(0.0, 1.0, 0.0), v) * drag_coeff;

        // 5. Approximate angular frame-drag rate.
        res.omega = u_frame_dragging_strength * M * a / max(1e-8, r3_p_a2r);

        return res;
    }

    // Shadow diagnostic (overlay only)
    float kerr_shadow_radius(float M, float a) {
        float a_star = abs(a / M);
        if (a_star < 0.001) return 3.0 * sqrt(3.0) * M;
        float r_pro = kerr_photon_sphere(M, abs(a));
        float r_retro = kerr_photon_sphere_retro(M, abs(a));
        return sqrt(r_pro * r_retro) * sqrt(3.0);
    }
"""

    // =========================================================================================
    // Noise chunk (`chunks/noise.ts`)
    // =========================================================================================

    val NOISE_CHUNK: String = """
  // Texture-based hash (ALU optimization)
  float hash(vec3 p) {
    // Map 3D coordinate to 2D texture UV using prime stride
    // This avoids expensive fractal arithmetic in the inner loop
    vec2 uv = (p.xy + p.z * 37.0);
    return texture(u_noiseTex, (uv + 0.5) / 256.0).r;
  }

  // 3D noise
  float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i + vec3(0,0,0)), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
  }

  // Fractal Brownian Motion -- standard 4-octave version (used by background nebula)
  float fbm(vec3 p) {
    float f = 0.0;
    float amp = 0.5;
    for(int i = 0; i < 4; i++) {
      f += amp * noise(p);
      p *= 2.0;
      amp *= 0.5;
    }
    return f;
  }

  // Phase 2.3: Adaptive FBM -- exact octave count controlled by caller.
  // Use 1 octave far from ISCO, 2 in the transition zone, 3 near the inner edge.
  // Saves 4-6 texture fetches for the vast majority of disk samples.
  // Called by sample_accretion_disk() based on r/isco ratio.
  float adaptiveFbm(vec3 p, int octaves) {
    float f = 0.0;
    float amp = 0.5;
    // GLSL unrolled manually to 3 max for shader compiler compatibility.
    // (Dynamic loops over uniforms are slow on some drivers.)
    f += amp * noise(p); p *= 2.0; amp *= 0.5; // Octave 1 (always)
    if (octaves >= 2) { f += amp * noise(p); p *= 2.0; amp *= 0.5; } // Octave 2
    if (octaves >= 3) { f += amp * noise(p); } // Octave 3
    return f;
  }
"""

    // =========================================================================================
    // Blackbody chunk (`chunks/blackbody.ts`)
    // =========================================================================================

    val BLACKBODY_CHUNK: String = """
  /**
   * Analytic Blackbody Approximation (Reverted Phase 1.1)
   * Restored to original behavior as per user request.
   * 
   * @param temp Observed temperature (K)
   * @return Linear RGB color (normalized intensity)
   */
  vec3 blackbody(float temp) {
    // Standard Tanner-Helland / Mitchell Charity approximation
    // Adjusted for linear space
    // Clamp to prevent log(0) at Event Horizon (Infinite Redshift)
    float t = max(temp, 1.0) / 100.0;
    float r, g, b;

    if (t <= 66.0) {
        r = 255.0;
        g = 99.4708025861 * log(t) - 161.1195681661;
        
        if (t <= 19.0) {
            b = 0.0;
        } else {
            b = 138.5177312231 * log(t - 10.0) - 305.0447927307;
        }
    } else {
        r = 329.698727446 * pow(t - 60.0, -0.1332047592);
        g = 288.1221695283 * pow(t - 60.0, -0.0755148492);
        b = 255.0;
    }

    // Formula produces sRGB. Convert to Linear for HDR pipeline.
    vec3 srgbCol = vec3(r, g, b) / 255.0;
    return pow(max(srgbCol, 0.0), vec3(2.2));
  }

  // Approximate star color from B-V color index
  vec3 starColor(float bv) {
    float t = clamp(bv, -0.4, 2.0);
    vec3 col;
    if(t < 0.0) col = vec3(0.6, 0.7, 1.0); // O/B
    else if(t < 0.3) col = vec3(0.85, 0.88, 1.0); // A
    else if(t < 0.6) col = vec3(1.0, 0.96, 0.9); // F
    else if(t < 1.0) col = vec3(1.0, 0.85, 0.6); // G/K
    else col = vec3(1.0, 0.6, 0.4); // M
    return col;
  }
"""

    // =========================================================================================
    // Starfield background chunk (`chunks/background.ts`)
    // =========================================================================================

    val BACKGROUND_CHUNK: String = """
  // Starfield background with spectral-class color variation
  vec3 starfield(vec3 dir) {
    vec3 stars = vec3(0.0);
    
    // Large stars (bright, rare)
    vec3 cell = floor(dir * 200.0);
    float starNoise = hash(cell);
    if(starNoise > 0.998) {
      float brightness = pow(starNoise, 10.0) * 2.0;
      float bv = hash(cell + 127.1) * 2.4 - 0.4;
      float twinkle = 0.85 + 0.15 * sin(u_time * (3.0 + hash(cell + 73.7) * 2.0));
      stars = starColor(bv) * brightness * twinkle;
    }
    
    // Small stars (dimmer, more numerous)
    cell = floor(dir * 500.0);
    starNoise = hash(cell);
    if(starNoise > 0.996) {
      float brightness = pow(starNoise, 20.0) * 1.5;
      float bv = hash(cell + 217.3) * 2.4 - 0.4;
      stars += starColor(bv) * brightness;
    }
    
    // Nebula-like background glow
    // CHAL-LOD-BEGIN: a 2-octave adaptive FBM approximation reduces texture fetches for this
    // low-amplitude background tint. This is a Chal fallback optimization, not an XAPK comparison.
    float nebula = adaptiveFbm(dir * 2.0 + u_time * 0.01, 2) * 0.03;
    // CHAL-LOD-END
    stars += vec3(nebula * 0.2, nebula * 0.3, nebula * 0.5) + vec3(0.05, 0.02, 0.05) * length(nebula);
    
    return stars;
  }
"""

    // =========================================================================================
    // Accretion disk + jets chunk (`chunks/disk.ts`)
    // =========================================================================================

    val DISK_CHUNK: String = """
  // Accretion Disk Physics & Rendering
  // Inputs:
  //   p: current ray position (vec3)
  //   ro: ray origin (vec3)
  //   r: current radius from BH center
  //   isco: innermost stable circular orbit
  //   M: black hole mass
  //   a: black hole spin parameter
  //   dt: integration step size (for density integration)
  //   accumulatedColor: (inout)
  //   accumulatedAlpha: (inout)

  void sample_accretion_disk(
      vec3 p, vec3 p_prev, vec3 ro, vec3 v, float r, float isco, float M, float a, float dt, float rs,
      inout vec3 accumulatedColor, inout float accumulatedAlpha
  ) {
      if (u_show_redshift < 0.5) {
          // SCIENTIFIC FIX: Plane-Crossing Detection (Eliminates "holes" from step-skipping)
          // If the ray crossed the equator (p_prev.y * p.y < 0), we force a sample at the intersection
          bool crossedEquator = (p_prev.y * p.y < 0.0);
          
          vec3 sampleP = p;
          if (crossedEquator) {
              float t = abs(p_prev.y) / max(0.0001, abs(p_prev.y) + abs(p.y));
              sampleP = mix(p_prev, p, t);
          }
          
          float sampleR = length(sampleP);
          
          float effectiveScaleHeight = min(u_disk_scale_height, ${ChalPhysicsConstants.Accretion.DISK_HEIGHT_MULTIPLIER.fixed(3)});
          float diskHeight = sampleR * effectiveScaleHeight;

          // SCIENTIFIC FIX: Ensure ISCO creates a hard edge even for retrograde orbits
          float diskInner = isco;
          float diskOuter = max(M * u_disk_size, diskInner * 1.1);

          if((abs(sampleP.y) < diskHeight || crossedEquator) && sampleR > diskInner && sampleR < diskOuter) {

              // Approximate equatorial orbital phase used to animate turbulence.
              float sqrt_M_phase = sqrt(M);
              float signSpinPhase = sign(u_spin + 1e-8);
              float OmegaPhase = (signSpinPhase * sqrt_M_phase) / (sampleR * sqrt(sampleR) + a * sqrt_M_phase);
              
              // Frame-dragged phase rotation
              float rotAngle = OmegaPhase * u_time * ${ChalPhysicsConstants.Accretion.TIME_SCALE.fixed(2)} * 10.0;
              mat2 rotPhase = mat2(cos(rotAngle), -sin(rotAngle), sin(rotAngle), cos(rotAngle));
              
              vec3 noiseP = sampleP;
              noiseP.xz *= rotPhase;
              noiseP *= ${ChalPhysicsConstants.Accretion.TURBULENCE_SCALE.fixed(2)};

              float turbulence = noise(noiseP) * 0.5 + noise(noiseP * ${ChalPhysicsConstants.Accretion.TURBULENCE_DETAIL.fixed(1)}) * 0.25;

              float samplesDiskHeight = sampleR * effectiveScaleHeight;
              float heightFalloff = exp(-abs(sampleP.y) / max(0.001, samplesDiskHeight * ${ChalPhysicsConstants.Accretion.DENSITY_FALLOFF.fixed(2)}));
              float radialFalloff = 1.0 - smoothstep(diskInner, diskOuter, sampleR);

              float baseDensity = turbulence * heightFalloff * radialFalloff;

              if (baseDensity > 0.001) {
                  // Effective disk-emission approximation. Although these expressions borrow
                  // equatorial Kerr circular-orbit terms, the full ray/observer transfer is not
                  // integrated here and must not be presented as exact Kerr radiative transfer.
                  float r2 = sampleR * sampleR;
                  
                  // 1. Exact Keplerian Angular Velocity (Omega = dphi/dt)
                  float sqrt_M = sqrt(M);
                  float signSpin = sign(u_spin + 1e-8);
                  float Omega = (signSpin * sqrt_M) / (sampleR * sqrt(sampleR) + a * sqrt_M);

                  // 2. Exact Metric Components in Equatorial Plane (theta = pi/2)
                  float g_tt = -(1.0 - 2.0 * M / sampleR);
                  float g_tphi = -2.0 * M * a / sampleR;
                  float g_phiphi = r2 + a*a + 2.0 * M * a*a / sampleR;

                  // 3. Exact 4-Velocity Time Component (u^t)
                  // Solves g_mu_nu u^mu u^nu = -1 for circular equatorial orbits
                  float u_t_sq = -(g_tt + 2.0 * Omega * g_tphi + Omega * Omega * g_phiphi);
                  float u_t = 1.0 / sqrt(max(1e-6, u_t_sq));

                  // 4. Conserved Photon Angular Momentum (L_y)
                  // Impact parameter mapping from local frame (cross product of position and ray dir)
                  float L_photon = p.z * v.x - p.x * v.z;

                  // 5. General Relativistic Doppler Factor (delta = E_obs / E_em)
                  // Exact derivation: E_em = -k.u = u_t(1 - Omega * L_photon), E_obs = 1 at infinity
                  float delta = 1.0 / max(0.01, u_t * (1.0 - Omega * L_photon));

#ifdef ENABLE_DOPPLER
                  // Relativistic Beaming (Liouville's Theorem for Specific Intensity)
                  // Bolometric flux I_nu scales as delta^4. We use delta^3 for visual dynamic range stability.
                  float beaming = max(0.01, pow(delta, 3.5));
#else
                  float beaming = 1.0;
#endif
                  // 6. Novikov-Thorne Temperature Profile (Zero-Torque inner boundary)
                  float isco_r = clamp(isco / sampleR, 0.0, 1.0);
                  float nt_factor = max(0.0, 1.0 - sqrt(isco_r));
                  float radialTempGradient = pow(isco_r, 0.75) * pow(nt_factor, 0.25);

                  // Temperature natively shifted by full relativistic Doppler delta
                  // (Replacing the previous Euclidean gravRedshift multiplier)
                  float temperature = u_disk_temp * radialTempGradient * delta;
                  vec3 diskColor = blackbody(temperature) * beaming;
                  float density = baseDensity * u_disk_density * 0.12 * dt;

                  accumulatedColor += diskColor * density * (1.0 - accumulatedAlpha);
                  accumulatedAlpha += density;
              }
          }
      }
  }

  void sample_relativistic_jets(
      vec3 p, vec3 v, float r, float rh, float dt,
      inout vec3 accumulatedColor, inout float accumulatedAlpha
  ) {
      // Jets align with spin axis (Y-axis)
      float jetVerticalPos = abs(p.y);
      if (jetVerticalPos > rh * 1.8 && jetVerticalPos < MAX_DIST * 0.8) {
          float jetRadialDist = length(p.xz);
          float jetWidth = 1.0 + jetVerticalPos * 0.15;

          if (jetRadialDist < jetWidth * 2.0) {
              float radialFalloff = exp(-(jetRadialDist * jetRadialDist) / (jetWidth * 0.5));
              float lengthFalloff = exp(-jetVerticalPos * 0.05);

              float flowCombined = p.y * 2.0 - u_time * 8.0;
              vec3 uvJet = vec3(p.x, flowCombined, p.z);
              float noiseVal = noise(uvJet * 0.5) * 0.6 + noise(uvJet * 1.5) * 0.4;

              float jetDensity = radialFalloff * lengthFalloff * max(0.0, noiseVal - 0.2);

              if (jetDensity > 0.001) {
                  float jetVel = 0.92 * sign(p.y);
                  vec3 jetVelVec = vec3(0.0, jetVel, 0.0);

                  float cosThetaJet = dot(normalize(jetVelVec), -v);
                  float betaJet = abs(jetVel);
                  float gammaJet = 1.0 / sqrt(1.0 - betaJet * betaJet);
                  float deltaJet = 1.0 / (gammaJet * (1.0 - betaJet * cosThetaJet));
                  float beamingJet = pow(deltaJet, 3.5);

                  vec3 baseJetColor = vec3(0.4, 0.7, 1.0);
                  vec3 jetEmission = baseJetColor * jetDensity * 0.05 * beamingJet * dt;

                  accumulatedColor += jetEmission * (1.0 - accumulatedAlpha);
                  accumulatedAlpha += jetDensity * 0.05 * dt;
              }
          }
      }
  }
"""

    // =========================================================================================
    // Vertex shader (`vertex.glsl.ts`)
    // =========================================================================================

    val VERTEX_SHADER: String = """#version 300 es
  in vec2 position;
  void main() {
    gl_Position = vec4(position, 0.0, 1.0);
  }
"""

    // =========================================================================================
    // Main fragment shader (`fragment.glsl.ts`)
    // =========================================================================================

    val FRAGMENT_SHADER: String = """#version 300 es
$COMMON_CHUNK

$METRIC_CHUNK

$NOISE_CHUNK

$BLACKBODY_CHUNK

$BACKGROUND_CHUNK

$DISK_CHUNK

// === MAIN SHADER ===
void main() {
    float minRes = min(u_resolution.x, u_resolution.y);
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / minRes;

    if (u_debug > 0.5) {
        fragColor = vec4(uv.x + 0.5, uv.y + 0.5, 0.0, 1.0);
        return;
    }

    // Camera
    vec3 ro, rd;
    if (length(u_camPos) > 0.001) {
        ro = u_camPos;
        rd = qrot(u_camQuat, normalize(vec3(uv, 1.2)));
    } else {
        ro = vec3(0.0, 0.0, -u_zoom);
        rd = normalize(vec3(uv, 1.5));
        mat2 rx = rot((u_mouse.y - 0.5) * PI);
        mat2 ry = rot((u_mouse.x - 0.5) * PI * 2.0);
        ro.yz *= rx; rd.yz *= rx;
        ro.xz *= ry; rd.xz *= ry;
    }

    // Black hole parameters
    float M = u_mass;
    float rs = M * 2.0;
    float a = u_spin * M;
    float a2 = a * a;

    // Derived quantities
    float rh = kerr_horizon(M, a);
    float rph = kerr_photon_sphere(M, a);
    float isco = kerr_isco(M, a);
    float absA = abs(u_spin);

    // === ANALYTIC PREVIEW (legacy LOW / OFF) ===
#if defined(RAY_QUALITY_LOW) || defined(RAY_QUALITY_OFF)
    vec3 bg = vec3(0.0);
#ifdef ENABLE_STARS
    bg = starfield(rd);
#endif
    float d = length(cross(ro, rd));
    float shadow = 1.0 - smoothstep(rh * 0.9, rh * 1.2, d);
    vec3 glowCol = vec3(0.0);
#ifdef ENABLE_PHOTON_GLOW
    float photonGlowIndicator = exp(-abs(d - rph) * 12.0) * 0.8;
    glowCol = vec3(0.3, 0.6, 1.0) * photonGlowIndicator;
#endif
    vec3 diskColIndicator = vec3(0.0);
#ifdef ENABLE_DISK
    float diskMask = smoothstep(isco * 0.8, isco, d) * (1.0 - smoothstep(isco, isco * 2.0, d));
    diskColIndicator = vec3(1.0, 0.7, 0.3) * diskMask * 0.6;
#endif
    vec3 col = bg * (1.0 - shadow) + glowCol + diskColIndicator;
#ifdef ENABLE_LINEAR_OUTPUT
    fragColor = vec4(col, 1.0);
#else
    fragColor = vec4(pow(col, vec3(0.4545)), 1.0);
#endif
    return;
#endif

    // === CHAL GLES COMPATIBILITY RAYMARCHER ===
    vec3 p = ro;
    vec3 v = rd;

    // Kamikaze protection
    if(length(ro) < rh * 1.5) {
       ro = normalize(ro) * rh * 1.5;
       p = ro;
    }

    vec3 accumulatedColor = vec3(0.0);
    float accumulatedAlpha = 0.0;
    bool hitHorizon = false;
    float maxRedshift = 0.0;

    // Blue noise dithering
    vec2 noiseUV = gl_FragCoord.xy / 256.0;
    float bNoise = texture(u_blueNoiseTex, noiseUV).r;
    float dt_init = MIN_STEP;
    p += v * bNoise * dt_init;

    int photonCrossings = 0;
    float prevY = p.y;
    float impactParam = length(cross(ro, rd));
    bool redshiftInitialized = false;

    int maxSteps = int(min(float(u_maxRaySteps), 500.0));
    vec3 p_prev = p;

    // --- CHAL-LOD-BEGIN (far-field termination) -------------------------------------------
    // This fallback stops outward-moving rays beyond the disk/jet influence region rather than
    // spending the remaining GLES budget integrating empty space. It is a performance heuristic
    // for Chal's approximate marcher; it does not establish parity with the XAPK renderer.
    //
    // Radius tracks the configured disk and approximate jet cone with a conservative margin.
    float escapeRadius = max(60.0, M * u_disk_size * 1.25);
#ifdef ENABLE_JETS
    escapeRadius = max(escapeRadius, 150.0);
#endif
    // --- CHAL-LOD-END ----------------------------------------------------------------------

    // Inner Shadow Culling (Horizon-Safe, Bardeen 1973):
    // A ray with b = |cross(ro,rd)| < rh is captured in ANY Kerr geometry.
    // (rh is the outer event horizon radius, always <= b_crit for all spin values.)
    // This culls only the innermost shadow core -- zero overlap with photon ring
    // or accretion disk since disk inner edge = isco >> rh.
    // Safe margin: 0.9 * rh. Even at maximum spin (a=0.999M), rh = 1.045M while
    // the prograde critical impact param b_pro > 2.0M -- safe margin of 2x.
    if (impactParam < rh * 0.9) {
        hitHorizon = true;
    }

    for(int i = 0; i < maxSteps; i++) {
        p_prev = p;
        float r = length(p);

        // Horizon check (Euclidean distance for fast rejection,
        // Kerr r is only slightly different near horizon)
        if(r < rh * ${ChalPhysicsConstants.RayMarching.HORIZON_THRESHOLD.fixed(2)}) {
            hitHorizon = true;
            break;
        }
        if(r > MAX_DIST) break;
        // CHAL-LOD-BEGIN (far-field termination) -- see the escapeRadius note above.
        if (r > escapeRadius && dot(p, v) > 0.0) break;
        // CHAL-LOD-END

        // Adaptive step size (curvature-aware)
        // Original formula preserved for r <= 30 (disk + strong field region).
        // For r > 30 (proven empty space beyond any disk/gravitational structure),
        // boost step size to accelerate background/star ray traversal.
        // This does NOT affect disk density accumulation (disk sampling gated on
        // r > isco && r < diskOuter, both << 30 for standard parameters) or
        // photon sphere precision (sphereProx clamp still dominates for r ~ rph).
        float distFactor = 1.0 + r * 0.05;
        float dt = clamp((r - rh) * 0.1 * distFactor, MIN_STEP, MAX_STEP * distFactor);
        if (r > 30.0) {
            // Empty-space boost: scale linearly with distance above r=30.
            // At r=60: extra boost = (60-30)*0.08 = 2.4, clamped to MAX_STEP*2.
            float farBoost = (r - 30.0) * 0.08;
            dt = max(dt, MIN_STEP + farBoost);
            dt = min(dt, MAX_STEP * 2.5);
        }

        float sphereProx = abs(r - rph);
        dt = min(dt, MIN_STEP + sphereProx * 0.15);

        float hRefinement = 1.0 - smoothstep(0.0, 0.2, abs(p.y));
        float currentDt = dt * (1.0 - hRefinement * 0.7);

        vec3 accel = vec3(0.0);
        float omega = 0.0;

#ifdef ENABLE_LENSING
        // Compute Kerr geodesic acceleration:
        // - Correct Darwin potential with spin-orbit coupled L_eff
        // - Gravito-magnetic frame-dragging force for D-shape asymmetry
        KerrAccelResult kerr = kerr_geodesic_accel(p, v, M, a);
        accel = kerr.accel * u_lensing_strength;
        omega = kerr.omega;

        // ZAMO velocity rotation: frame-dragging twists the velocity
        // (NOT the position -- rotating position creates artifacts).
        // The ZAMO angular velocity omega = 2Ma/(r^3 + a^2*r).
        mat2 zamo = rot(omega * currentDt);
        v.xz *= zamo;
#endif

        // Velocity-Verlet position step
        p += v * currentDt + 0.5 * accel * currentDt * currentDt;

        float r_new = length(p);

#ifdef ENABLE_LENSING
        // Velocity-Verlet velocity correction
        if (accumulatedAlpha < 0.95) {
            KerrAccelResult kerr_new = kerr_geodesic_accel(p, v, M, a);
            vec3 accel_new = kerr_new.accel * u_lensing_strength;
            v += 0.5 * (accel + accel_new) * currentDt;
        }
#endif
        v = normalize(v);

        // Photon crossing counter (for higher-order ring rendering)
        if(prevY * p.y < 0.0 && r_new < rph * 2.0 && r_new > rh) {
            photonCrossings = min(photonCrossings + 1, 3);
        }

        // Gravitational redshift tracking
        if (u_show_redshift > 0.5) {
            float potential = sqrt(max(0.0, 1.0 - rs / r_new));
            if (!redshiftInitialized) { maxRedshift = potential; redshiftInitialized = true; }
            else maxRedshift = min(maxRedshift, potential);
        }

        prevY = p.y;

        // Accretion disk sampling (uses Euclidean r for disk geometry)
#ifdef ENABLE_DISK
        sample_accretion_disk(p, p_prev, ro, v, r_new, isco, M, a, currentDt, rs, accumulatedColor, accumulatedAlpha);
        if(accumulatedAlpha > 0.99) break;
#endif

        // Relativistic jets
#ifdef ENABLE_JETS
        sample_relativistic_jets(p, v, r, rh, dt, accumulatedColor, accumulatedAlpha);
#endif
    }

    // Gravitational Redshift Overlay
#ifdef ENABLE_REDSHIFT
    if (u_show_redshift > 0.5) {
        float val = maxRedshift;
        if (hitHorizon) val = 0.0;

        vec3 heatmap = mix(vec3(0.0), vec3(1.0, 0.0, 0.0), smoothstep(0.0, 0.3, val));
        heatmap = mix(heatmap, vec3(1.0, 1.0, 0.0), smoothstep(0.3, 0.7, val));
        heatmap = mix(heatmap, vec3(0.0, 0.0, 1.0), smoothstep(0.7, 1.0, val));

        fragColor = vec4(heatmap, 1.0);
        return;
    }
#endif

    // Background
    vec3 background = vec3(0.0);
#ifdef ENABLE_STARS
    background = starfield(v);
#endif

    // Photon ring
    vec3 photonColor = vec3(0.0);
#ifdef ENABLE_PHOTON_GLOW
    if (!hitHorizon) {
        float distToPhotonRing = abs(length(p) - rph);
        float directRing = exp(-distToPhotonRing * 40.0) * 1.8 * u_lensing_strength;
        float higherOrderRing = 0.0;
        if(photonCrossings > 0) {
          float ringSharpness = 60.0 + float(photonCrossings) * 30.0;
          float ringBrightness = exp(-float(photonCrossings) * 1.0) * 1.2;
          higherOrderRing = exp(-distToPhotonRing * ringSharpness) * ringBrightness * u_lensing_strength;
        }
        photonColor = vec3(1.0) * (directRing + higherOrderRing);
    }
#endif

    // Ergosphere
    vec3 ergoColor = vec3(0.0);
    if(absA > 0.1 && !hitHorizon) {
      float rFinal = length(p);
      float cosTheta = p.y / max(rFinal, 0.001);
      float r_ergo = kerr_ergosphere(M, a, rFinal, cosTheta);
      float ergoGlow = exp(-abs(rFinal - r_ergo) * 20.0) * 0.35 * absA;
      ergoColor = vec3(0.3, 0.35, 0.9) * ergoGlow;
    }

    if (hitHorizon) {
        // If we hit the horizon, the background is pitch black.
        // But we STILL see any accumulated disk emission that was in front of it!
        background = vec3(0.0);
    }

    vec3 finalColor = background * (1.0 - accumulatedAlpha) + accumulatedColor + photonColor * (1.0 - accumulatedAlpha) + ergoColor * (1.0 - accumulatedAlpha);

    // Kerr Shadow Guide (diagnostic overlay)
    if (u_show_kerr_shadow > 0.5) {
        vec3 spin_axis = vec3(0.0, 1.0, 0.0);
        vec3 cam_dir = normalize(ro);
        vec3 sky_right = normalize(cross(spin_axis, cam_dir));
        vec3 sky_up = cross(cam_dir, sky_right);
        
        // Ray impact projection in celestial coords (alpha, beta)
        // alpha: horizontal displacement (perpendicular to projected spin axis)
        // beta: vertical displacement (along projected spin axis)
        vec3 impact_vec = cross(cam_dir, rd) * length(ro);
        
        float alpha = -dot(impact_vec, sky_up); 
        float beta = dot(impact_vec, sky_right);
        vec2 p_sky = vec2(alpha, beta);

        float minDist = 1e10;
        int count = int(u_shadowCount);
        
        // Check distance to the analytical boundary polyline
        for (int j = 0; j < 63; j++) {
            if (j >= count - 1) break;
            
            vec2 p1 = u_shadowCurve[j];
            vec2 p2 = u_shadowCurve[j+1];
            
            // Distance to line segment
            vec2 pa = p_sky - p1, ba = p2 - p1;
            float h = clamp(dot(pa, ba)/dot(ba, ba), 0.0, 1.0);
            minDist = min(minDist, length(pa - ba*h));
        }
        
        // Match the first and last point to close the curve
        if (count > 2) {
            vec2 p_first = u_shadowCurve[0];
            vec2 p_last = u_shadowCurve[count - 1];
            vec2 pa_c = p_sky - p_last, ba_c = p_first - p_last;
            float h_c = clamp(dot(pa_c, ba_c)/dot(ba_c, ba_c), 0.0, 1.0);
            minDist = min(minDist, length(pa_c - ba_c*h_c));
        }

        // Visibility: smooth line with thickness proportional to Mass
        float thickness = M * 0.045; // Slightly thinner for precision
        if (minDist < thickness) {
            float edge = 1.0 - smoothstep(thickness * 0.5, thickness, minDist);
            finalColor = mix(finalColor, vec3(0.0, 1.0, 0.0), 1.0 * edge);
        }
    }

    // Tone Mapping & Gamma
#ifndef ENABLE_LINEAR_OUTPUT
    finalColor = aces_tone_mapping(finalColor);
    finalColor = pow(max(finalColor, 0.0), vec3(0.4545));
#endif

    fragColor = vec4(finalColor, 1.0);
}
"""

    // =========================================================================================
    // Post-processing: bloom (`shaders/postprocess/bloom.glsl.ts`)
    // =========================================================================================

    /**
     * Simple vertex shader for full-screen quad. Used for all post-processing passes.
     */
    val BLOOM_VERTEX_SHADER: String = """#version 300 es
  in vec2 position;
  uniform vec2 u_textureScale;
  out vec2 v_texCoord;
  
  void main() {
    // FIX: When u_textureScale is not set, GLSL defaults it to (0,0).
    // This collapses ALL UVs to the origin, making every fragment sample
    // the same texel. Guard against this by treating (0,0) as (1,1).
    vec2 scale = u_textureScale;
    if (scale.x < 0.01) scale = vec2(1.0, 1.0);
    v_texCoord = (position * 0.5 + 0.5) * scale;
    gl_Position = vec4(position, 0.0, 1.0);
  }
"""

    /**
     * Bright pass shader - extracts bright pixels above threshold.
     */
    val BRIGHT_PASS_SHADER: String = """#version 300 es
  precision highp float;
  
  uniform sampler2D u_texture;
  uniform float u_threshold;
  
  in vec2 v_texCoord;
  out vec4 fragColor;
  
  void main() {
    vec4 color = texture(u_texture, v_texCoord);
    
    // Calculate luminance
    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    
    // Extract bright pixels above threshold
    if (luminance > u_threshold) {
      fragColor = color;
    } else {
      fragColor = vec4(0.0);
    }
  }
"""

    /**
     * Gaussian blur shader - separable blur (horizontal or vertical).
     */
    val BLUR_SHADER: String = """#version 300 es
  precision highp float;
  
  uniform sampler2D u_texture;
  uniform vec2 u_resolution;
  uniform vec2 u_direction; // (1,0) for horizontal, (0,1) for vertical
  
  in vec2 v_texCoord;
  out vec4 fragColor;
  
  // 9-tap Gaussian blur weights (GLSL 300 es supports array initializers)
  const float weights[5] = float[5](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
  
  void main() {
    vec2 texelSize = 1.0 / u_resolution;
    vec3 result = texture(u_texture, v_texCoord).rgb * weights[0];
    
    for (int i = 1; i < 5; i++) {
      vec2 offset = u_direction * texelSize * float(i);
      result += texture(u_texture, v_texCoord + offset).rgb * weights[i];
      result += texture(u_texture, v_texCoord - offset).rgb * weights[i];
    }
    
    fragColor = vec4(result, 1.0);
  }
"""

    /**
     * Combine shader - blends bloom with original image.
     */
    val COMBINE_SHADER: String = """#version 300 es
  precision highp float;
  
  uniform sampler2D u_sceneTexture;
  uniform sampler2D u_bloomTexture;
  uniform float u_bloomIntensity;
  
  in vec2 v_texCoord;
  out vec4 fragColor;
  
  // ACES Tone Mapping (Narkowicz 2014)
  vec3 aces_tone_mapping(vec3 color) {
    float A = 2.51;
    float B = 0.03;
    float C = 2.43;
    float D = 0.59;
    float E = 0.14;
    return clamp((color * (A * color + B)) / (color * (C * color + D) + E), 0.0, 1.0);
  }

  void main() {
    vec3 sceneColor = texture(u_sceneTexture, v_texCoord).rgb;
    vec3 bloomColor = texture(u_bloomTexture, v_texCoord).rgb;
    
    // Additive blending in linear space
    vec3 result = sceneColor + bloomColor * u_bloomIntensity;
    
    // FINAL PASS: Apply Tone Mapping & Gamma
    result = aces_tone_mapping(result);
    result = pow(result, vec3(0.4545)); // Gamma 2.2
    
    fragColor = vec4(result, 1.0);
  }
"""

    // =========================================================================================
    // Post-processing: temporal reprojection (`shaders/postprocess/reprojection.glsl.ts`)
    // =========================================================================================

    /**
     * Vertex shader for the reprojection pass.
     */
    val REPROJECTION_VERTEX_SHADER: String = """#version 300 es
  in vec2 position;
  uniform vec2 u_textureScale;
  out vec2 v_texCoord;

  void main() {
    // FIX: Guard against u_textureScale defaulting to (0,0) before it is set.
    vec2 scale = u_textureScale;
    if (scale.x < 0.01) scale = vec2(1.0, 1.0);
    v_texCoord = (position * 0.5 + 0.5) * scale;
    gl_Position = vec4(position, 0.0, 1.0);
  }
"""

    /**
     * Fragment shader for Temporal Reprojection (WebGL2 / GLSL 300 es).
     *
     * Variance-Guided Accumulation: the history sample is clamped to the 3x3 neighbourhood AABB in
     * YCoCg space, and the accumulation weight falls off with local luminance variance so sharp,
     * moving features (photon ring, disk turbulence) do not ghost.
     */
    val REPROJECTION_FRAGMENT_SHADER: String = """#version 300 es
  precision highp float;
  uniform sampler2D u_currentFrame;
  uniform sampler2D u_historyFrame;
  uniform vec2 u_resolution;
  uniform float u_blendFactor; 
  uniform bool u_cameraMoving;
  uniform vec2 u_textureScale;

  in vec2 v_texCoord;
  out vec4 fragColor;

  vec3 RGBToYCoCg(vec3 rgb) {
    float y = dot(rgb, vec3(0.25, 0.5, 0.25));
    const float co = 0.5; // Offset for bias if needed, but relative is fine
    float co_val = dot(rgb, vec3(0.5, 0.0, -0.5));
    float cg_val = dot(rgb, vec3(-0.25, 0.5, -0.25));
    return vec3(y, co_val, cg_val);
  }

  vec3 YCoCgToRGB(vec3 ycocg) {
    float y = ycocg.x;
    float co = ycocg.y;
    float cg = ycocg.z;
    return vec3(y + co - cg, y + cg, y - co - cg);
  }

  void main() {
    vec3 current = texture(u_currentFrame, v_texCoord).rgb;
    
    // Texture is full size, so texel size must be relative to physical dimensions.
    vec2 scale = u_textureScale;
    if (scale.x < 0.01) scale = vec2(1.0, 1.0);
    vec2 texelSize = scale / u_resolution;

    // 3x3 Neighborhood Sampling in YCoCg space
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    
    for(int y = -1; y <= 1; y++) {
      for(int x = -1; x <= 1; x++) {
        vec3 s = RGBToYCoCg(texture(u_currentFrame, v_texCoord + vec2(x, y) * texelSize).rgb);
        m1 += s;
        m2 += s * s;
      }
    }

    vec3 mean = m1 / 9.0;
    vec3 std = sqrt(max(m2 / 9.0 - mean * mean, 0.0));
    
    vec3 boxMin = mean - 1.5 * std;
    vec3 boxMax = mean + 1.5 * std;

    vec3 history = RGBToYCoCg(texture(u_historyFrame, v_texCoord).rgb);
    
    // Clamp history sample to the neighborhood AABB to minimize ghosting
    history = clamp(history, boxMin, boxMax);

    // Variance-Guided Accumulation Weight
    // stdDev.x is the YCoCg luminance standard deviation of the 3x3 neighborhood.
    // High variance = sharp edge / photon ring boundary / rotating disk feature.
    //   → reduce accumulation to prevent ghosting on high-contrast moving features.
    // Low variance = flat empty space / smooth disk interior.
    //   → keep full accumulation for maximum temporal noise suppression.
    // Remap: variance of 0 → weight 1.0 (full blend); variance of 0.15 → weight 0.5.
    float lumaVariance = std.x;
    float varianceWeight = 1.0 - clamp(lumaVariance * 4.0, 0.0, 0.55);

    float alpha = u_cameraMoving ? 0.0 : u_blendFactor * varianceWeight;
    
    vec3 resolved = YCoCgToRGB(mix(RGBToYCoCg(current), history, alpha));
    fragColor = vec4(resolved, 1.0);
  }
"""
}
