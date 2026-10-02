package com.delrogue.grooverider.ui.cloud.gl

/**
 * The Observatory's shaders: the web app's WebGL shaders (docs/index.html,
 * FS_SCENE and friends) moved to GLSL ES 3.00. The maths is unchanged.
 */
internal object Shaders {

    /** One oversized triangle covering the viewport. */
    const val VS_QUAD = """#version 300 es
in vec2 aP; out vec2 vUv;
void main(){ vUv = aP*0.5+0.5; gl_Position = vec4(aP, 0., 1.); }"""

    /** The water: caustics sheared by the chaos current, two aurora veils, then trails and bloom. */
    const val FS_SCENE = """#version 300 es
precision highp float;
uniform vec2 uRes; uniform float uT; uniform vec3 uCh; uniform float uLevel;
uniform sampler2D uTrail; uniform sampler2D uBloom; in vec2 vUv; out vec4 oColor;
float caustic(vec2 uv, float t){
  vec2 p = mod(uv*6.28318530718, 6.28318530718) - 250.0;
  vec2 i = p; float c = 1.0;
  for (int n=0;n<4;n++){
    float tt = t*(1.0 - 3.5/float(n+1));
    i = p + vec2(cos(tt-i.x)+sin(tt+i.y), sin(tt-i.y)+cos(tt+i.x));
    c += 1.0/length(vec2(p.x/(sin(i.x+tt)/0.005), p.y/(cos(i.y+tt)/0.005)));
  }
  c = 1.17 - pow(c/4.0, 1.4);
  return pow(abs(c), 8.0);
}
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233)))*43758.5453); }
void main(){
  float asp = uRes.x/uRes.y; vec2 p = vec2((vUv.x-0.5)*asp, vUv.y-0.5);
  // the water column: near-black, a breath of teal toward the surface
  vec3 col = vec3(0.004,0.008,0.010) + vec3(0.0,0.040,0.050)*pow(vUv.y, 2.0);
  // caustics -- sunlight through water -- sheared by the chaos current
  vec2 cuv = p*0.5 + vec2(uCh.x*0.07, uCh.y*0.05 + uT*0.003);
  float ca = caustic(cuv, uT*0.21 + uCh.z*0.7)*0.7 + caustic(cuv*1.9+3.1, uT*0.16+11.0)*0.35;
  float shaft = smoothstep(-0.6, 0.55, p.y + 0.22*sin(p.x*1.3 + uT*0.05 + uCh.x*1.5));
  vec3 aqua = mix(vec3(0.04,0.30,0.34), vec3(0.40,0.27,0.10), smoothstep(-0.2,1.0,p.x*0.7+uCh.y*0.6)*0.6);
  col += aqua*(ca*(0.42 + 0.5*uLevel) + sqrt(ca)*0.05)*shaft;
  // aurora veils, one cyan one amber, leaning with the attractor
  float v1 = sin(p.x*1.7 + uT*0.033 + uCh.x*1.3 + sin(p.y*2.3+uT*0.047)*0.9);
  float v2 = sin(p.x*1.1 - uT*0.021 + uCh.y*1.6 + sin(p.y*1.7-uT*0.031)*1.1 + 2.0);
  col += vec3(0.06,0.34,0.38)*smoothstep(0.25,1.0,v1)*smoothstep(-0.55,0.5,p.y)*(0.15+0.06*uCh.z);
  col += vec3(0.34,0.20,0.06)*smoothstep(0.45,1.0,v2)*smoothstep(0.5,-0.5,p.y)*0.10;
  col *= smoothstep(1.3, 0.2, length(p*vec2(0.78,1.05)));
  col += texture(uTrail, vUv).rgb*0.5 + texture(uBloom, vUv).rgb*1.2;
  col = 1.0 - exp(-col*1.35);
  col += (hash(gl_FragCoord.xy + fract(uT)) - 0.5)/255.0;
  oColor = vec4(col, 1.0);
}"""

    /** Trails: last frame, a little dimmer and drifted. */
    const val FS_FADE = """#version 300 es
precision mediump float;
uniform sampler2D uTex; uniform float uDecay; uniform float uFloor; uniform vec2 uDrift; in vec2 vUv; out vec4 oColor;
void main(){ oColor = vec4(max(texture(uTex, vUv+uDrift).rgb*uDecay - uFloor, 0.0), 1.0); }"""

    /** One direction of a separable blur, for the bloom. */
    const val FS_BLUR = """#version 300 es
precision mediump float;
uniform sampler2D uTex; uniform vec2 uDir; in vec2 vUv; out vec4 oColor;
void main(){
  vec3 c = texture(uTex, vUv).rgb*0.227;
  c += (texture(uTex, vUv+uDir*1.385).rgb + texture(uTex, vUv-uDir*1.385).rgb)*0.316;
  c += (texture(uTex, vUv+uDir*3.231).rgb + texture(uTex, vUv-uDir*3.231).rgb)*0.070;
  oColor = vec4(c, 1.0);
}"""

    /** Motes are point sprites; aShape = size, stretch, angle. */
    const val VS_MOTE = """#version 300 es
in vec2 aPos; in vec4 aCol; in vec3 aShape; uniform float uPx; out vec4 vCol; out vec2 vSh;
void main(){ gl_Position = vec4(aPos, 0., 1.); gl_PointSize = max(1.0, aShape.x*uPx); vCol = aCol; vSh = aShape.yz; }"""

    const val FS_MOTE = """#version 300 es
precision mediump float;
in vec4 vCol; in vec2 vSh; uniform float uGain; out vec4 oColor;
void main(){
  vec2 p = gl_PointCoord*2.0-1.0; p.y = -p.y;
  float c = cos(vSh.y), s = sin(vSh.y);
  p = vec2(c*p.x + s*p.y, -s*p.x + c*p.y);
  vec2 q = vec2(p.x, p.y*(1.0+vSh.x*3.2));                    // fry are slender, ova are round
  float r = length(q);
  float body = smoothstep(1.0, 0.45, r)*0.17;                  // translucent flesh
  float rim  = smoothstep(0.62, 0.84, r)*smoothstep(0.98, 0.84, r)*0.46;   // membrane
  vec2 e = q - vec2(0.42*step(0.01, vSh.x), 0.0);             // nucleus / eye, toward the head
  float nuc = exp(-dot(e,e)*(13.0+30.0*vSh.x));
  float a = (body + rim + nuc*0.85)*vCol.a*uGain;
  oColor = vec4(vCol.rgb*a + vec3(0.9,0.95,1.0)*nuc*vCol.a*uGain*0.3, 1.0);
}"""

    /** The spectral tide: a scrolling texture, newest column at the right, melting into the water above it. */
    const val FS_TIDE = """#version 300 es
precision mediump float;
uniform sampler2D uTide; uniform float uHead; uniform float uColumns; in vec2 vUv; out vec4 oColor;
void main(){
  float u = uHead + (vUv.x*(uColumns-1.0) + 0.5)/uColumns;
  vec3 c = texture(uTide, vec2(u, vUv.y)).rgb;
  float lit = max(c.r, max(c.g, c.b));
  oColor = vec4(c, 0.75 * smoothstep(1.0, 0.45, vUv.y) * (0.25 + 0.75*smoothstep(0.03, 0.4, lit)));
}"""
}
