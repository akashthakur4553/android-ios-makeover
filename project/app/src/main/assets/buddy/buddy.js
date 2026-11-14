/*
 * Buddy — a cute chibi 3D character built procedurally with three.js (no downloads, no paid tools).
 * The look comes from an "avatar spec" (JSON) the AI fills in from a person's photo.
 * API (called from Android):  buddy.play(name) · buddy.setTalking(bool) · buddy.setThinking(bool)
 *                             buddy.setListening(bool) · buddy.setSpec(json) · buddy.enter()
 */
(function () {
  'use strict';

  // ------------------------------------------------------------------ default spec (overridden by setSpec)
  const DEFAULT_SPEC = { // neutral starter look; the real look comes from the user's photo via setSpec()
    skin: '#e0a882', blush: '#ff8fa3',
    hair: { color: '#3b2a20', style: 'short', lean: 0 },
    beard: { style: 'none', color: '#3b2a20' }, mustache: false,
    eyes: { color: '#3a2618' }, brows: { color: '#3b2a20', thickness: 1 },
    earring: 'none', earringColor: '#e3c46b', glasses: false,
    top: { type: 'tshirt', color: '#5b8def' },
    bottom: { type: 'jeans', color: '#33415e' },
    shoes: { color: '#f4f4f4', sole: '#d9d9d9' },
    watch: 'none', watchColor: '#151515', build: 'slim', lashes: false,
  };
  let spec = JSON.parse(JSON.stringify(DEFAULT_SPEC));

  // ------------------------------------------------------------------ renderer / scene
  const host = window.BUDDY_HOST || document.body;
  const isBody = host === document.body;
  const W = () => (isBody ? innerWidth : host.clientWidth) || innerWidth, Hh = () => (isBody ? innerHeight : host.clientHeight) || innerHeight;
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(W(), Hh());
  renderer.setClearColor(0x000000, 0);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  host.appendChild(renderer.domElement);
  if (isBody) renderer.domElement.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;'; // never clipped by a 0-height body

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(28, W() / Hh(), 0.1, 100);
  function frameCamera() {
    camera.aspect = W() / Hh();
    // keep the whole body in view on any aspect ratio
    const dist = Math.max(7.0, 5.2 / Math.min(1, camera.aspect * 1.25));
    camera.position.set(0, 1.3, dist);
    camera.lookAt(0, 1.15, 0);
    camera.updateProjectionMatrix();
  }
  frameCamera();

  scene.add(new THREE.HemisphereLight(0xffffff, 0x8d84a8, 0.5));
  const keyLight = new THREE.DirectionalLight(0xfff4ea, 0.62);
  keyLight.position.set(2.2, 5, 4.5);
  keyLight.castShadow = true;
  keyLight.shadow.mapSize.set(1024, 1024);
  Object.assign(keyLight.shadow.camera, { left: -3, right: 3, top: 4, bottom: -1, near: 0.5, far: 15 });
  scene.add(keyLight);
  const rim = new THREE.DirectionalLight(0xffd3ec, 0.35);
  rim.position.set(-3, 2.5, -3);
  scene.add(rim);

  const ground = new THREE.Mesh(new THREE.PlaneGeometry(12, 12), new THREE.ShadowMaterial({ opacity: 0.2 }));
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);

  // toon ramp (3 soft bands = "cartoon" look)
  const ramp = new THREE.DataTexture(new Uint8Array([150, 150, 150, 255, 205, 205, 205, 255, 240, 240, 240, 255]), 3, 1, THREE.RGBAFormat);
  ramp.minFilter = ramp.magFilter = THREE.NearestFilter;
  ramp.needsUpdate = true;
  const toon = (c, extra) => new THREE.MeshToonMaterial(Object.assign({ color: new THREE.Color(c), gradientMap: ramp }, extra || {}));
  const OUTLINE = new THREE.MeshBasicMaterial({ color: 0x2b1d22, side: THREE.BackSide });

  function mesh(geo, mat, opts) {
    const m = new THREE.Mesh(geo, mat);
    m.castShadow = true;
    if (opts && opts.outline !== false) {
      const o = new THREE.Mesh(geo, OUTLINE);
      const s = opts && opts.outlineScale || 1.045;
      o.scale.set(s, s, s);
      m.add(o);
    }
    return m;
  }

  // ------------------------------------------------------------------ face texture (eyes/brows/mouth drawn every frame)
  const faceCanvas = document.createElement('canvas');
  faceCanvas.width = faceCanvas.height = 512;
  const fx = faceCanvas.getContext('2d');
  const faceTex = new THREE.CanvasTexture(faceCanvas);
  faceTex.anisotropy = 4;

  const face = {
    eye: 'normal', blink: 0, lookX: 0, lookY: 0, brow: 0, browUp: 0,
    mouth: 'smile', open: 0, blush: 0.35, tears: 0, wink: false,
  };

  function ellipse(x, y, rx, ry, fill) { fx.beginPath(); fx.ellipse(x, y, Math.max(0.5, rx), Math.max(0.5, ry), 0, 0, Math.PI * 2); fx.fillStyle = fill; fx.fill(); }
  function heart(x, y, s, fill) {
    fx.save(); fx.translate(x, y); fx.scale(s, s); fx.beginPath();
    fx.moveTo(0, 6); fx.bezierCurveTo(-14, -4, -8, -16, 0, -8); fx.bezierCurveTo(8, -16, 14, -4, 0, 6);
    fx.fillStyle = fill; fx.fill(); fx.restore();
  }

  function drawEye(cx, cy, side) {
    const e = face.eye;
    fx.lineCap = 'round'; fx.lineJoin = 'round';
    if (e === 'happy' || (face.wink && side > 0)) { // ^ ^
      fx.lineWidth = 9; fx.strokeStyle = '#2a1a14';
      fx.beginPath(); fx.moveTo(cx - 30, cy + 8); fx.quadraticCurveTo(cx, cy - 26, cx + 30, cy + 8); fx.stroke(); return;
    }
    if (e === 'closed' || e === 'cry') { // > <  /  ‿
      fx.lineWidth = 9; fx.strokeStyle = '#2a1a14'; fx.beginPath();
      if (e === 'cry') { fx.moveTo(cx - 28 * side, cy - 14); fx.lineTo(cx + 18 * side, cy); fx.lineTo(cx - 28 * side, cy + 14); }
      else { fx.moveTo(cx - 28, cy); fx.quadraticCurveTo(cx, cy + 18, cx + 28, cy); }
      fx.stroke(); return;
    }
    if (e === 'heart') { heart(cx, cy, 3.2, '#ff3d77'); return; }
    let ry = 52, rx = 39;
    if (e === 'wide') { ry = 50; rx = 38; }
    if (e === 'sleepy') ry = 14;
    if (e === 'angry') ry = 34;
    const open = Math.max(0.06, 1 - face.blink);
    ry *= open;
    ellipse(cx, cy, rx, ry, '#ffffff');
    if (ry > 6) {
      fx.save(); fx.beginPath(); fx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2); fx.clip();
      const ix = cx + face.lookX * 10, iy = cy + 6 + face.lookY * 8;
      ellipse(ix, iy, rx * 0.8, 44, spec.eyes.color);
      ellipse(ix, iy + 4, rx * 0.44, 26, '#0c0807');
      ellipse(ix - 12, iy - 18, 12, 14, '#ffffff');
      ellipse(ix + 11, iy + 14, 5, 5, 'rgba(255,255,255,0.85)');
      fx.restore();
      // upper lid line
      fx.lineWidth = 7; fx.strokeStyle = '#21140f'; fx.beginPath();
      fx.ellipse(cx, cy, rx + 2, ry + 2, 0, Math.PI * 1.08, Math.PI * 1.92); fx.stroke();
      // lashes flick
      if (spec.lashes) { fx.lineWidth = 5; fx.beginPath(); fx.moveTo(cx + side * (rx - 2), cy - ry * 0.55); fx.lineTo(cx + side * (rx + 10), cy - ry * 0.85); fx.stroke(); }
    }
    if (e === 'sad' || face.tears > 0) { // shiny wet eyes
      ellipse(cx, cy + ry * 0.75, rx * 0.8, 5, 'rgba(120,190,255,0.75)');
    }
  }

  function drawFace() {
    fx.clearRect(0, 0, 512, 512);
    const lx = 152, rxx = 360, ey = 282;
    // blush
    if (face.blush > 0.02) {
      const a = Math.min(0.75, face.blush);
      [[112, 330], [400, 330]].forEach(([x, y]) => {
        const g = fx.createRadialGradient(x, y, 4, x, y, 46);
        g.addColorStop(0, hexA(spec.blush, a)); g.addColorStop(1, hexA(spec.blush, 0));
        fx.fillStyle = g; fx.fillRect(x - 50, y - 50, 100, 100);
      });
    }
    drawBeard();
    drawEye(lx, ey, -1);
    drawEye(rxx, ey, 1);
    // brows
    const th = 13 * (spec.brows.thickness || 1);
    fx.lineCap = 'round'; fx.strokeStyle = spec.brows.color;
    [[lx, -1], [rxx, 1]].forEach(([x, s]) => {
      const tilt = face.brow * 16; // + angry (inner down) / - sad (inner up)
      const y = ey - 74 - face.browUp * 14;
      fx.lineWidth = th; fx.beginPath();
      fx.moveTo(x - 40 * s * -1, y - 2 + (s < 0 ? 0 : 0));
      fx.moveTo(x - 38, y + (s < 0 ? -tilt * 0.2 : tilt));
      fx.quadraticCurveTo(x, y - 12, x + 38, y + (s < 0 ? tilt : -tilt * 0.2));
      fx.stroke();
    });
    // mustache
    if (spec.mustache && spec.beard.style !== 'none') {
      fx.fillStyle = spec.beard.color;
      fx.beginPath(); fx.moveTo(256, 404);
      fx.quadraticCurveTo(200, 396, 176, 436); fx.quadraticCurveTo(220, 418, 256, 424);
      fx.quadraticCurveTo(292, 418, 336, 436); fx.quadraticCurveTo(312, 396, 256, 404); fx.fill();
    }
    drawMouth(256, 446);
    // tears streams
    if (face.tears > 0.05) {
      fx.strokeStyle = 'rgba(110,190,255,0.85)'; fx.lineWidth = 10; fx.lineCap = 'round';
      [lx, rxx].forEach(x => { fx.beginPath(); fx.moveTo(x, ey + 34); fx.lineTo(x + (x < 256 ? -6 : 6), ey + 34 + 80 * face.tears); fx.stroke(); });
    }
    faceTex.needsUpdate = true;
  }

  function drawBeard() {
    const st = spec.beard.style;
    if (!st || st === 'none') return;
    const col = spec.beard.color;
    fx.save();
    fx.beginPath();
    // jaw-shaped beard that stays inside the face texture (no hard side edges)
    // full beard: sideburns (top corners) -> across the cheeks -> mustache corners, then the whole jaw
    fx.moveTo(0, 200); fx.lineTo(28, 200);
    fx.quadraticCurveTo(52, 300, 112, 338); fx.quadraticCurveTo(160, 362, 196, 380);
    fx.quadraticCurveTo(226, 372, 256, 372); fx.quadraticCurveTo(286, 372, 316, 380);
    fx.quadraticCurveTo(352, 362, 400, 338); fx.quadraticCurveTo(460, 300, 484, 200);
    fx.lineTo(512, 200); fx.lineTo(512, 512); fx.lineTo(0, 512); fx.closePath();
    fx.closePath();
    fx.clip();
    if (st === 'stubble') { fx.fillStyle = hexA(col, 0.35); fx.fillRect(0, 0, 512, 512); }
    else {
      fx.fillStyle = col; fx.fillRect(0, 0, 512, 512);
      // stubble texture so it reads as hair, not paint
      fx.fillStyle = 'rgba(255,255,255,0.05)';
      for (let i = 0; i < 420; i++) { const x = (i * 97) % 512, y = 200 + ((i * 53) % 310); fx.fillRect(x, y, 2, 6); }
    }
    fx.restore();
    // soft top edge
    fx.save(); fx.strokeStyle = hexA(col, st === 'stubble' ? 0.15 : 0.5); fx.lineWidth = 8; fx.filter = 'blur(3px)';
    fx.beginPath(); fx.moveTo(28, 200); fx.quadraticCurveTo(52, 300, 112, 338); fx.quadraticCurveTo(160, 362, 196, 380); fx.quadraticCurveTo(256, 366, 316, 380); fx.quadraticCurveTo(352, 362, 400, 338); fx.quadraticCurveTo(460, 300, 484, 200); fx.stroke();
    fx.restore();

  }

  function drawMouth(cx, cy) {
    const m = face.mouth;
    const open = Math.min(1, face.open);
    fx.lineCap = 'round'; fx.lineJoin = 'round';
    const bearded = spec.beard.style === 'full' || spec.beard.style === 'short';
    const inside = '#5a1f2a', tongue = '#ff7b8a', line = bearded ? '#e9a19c' : '#3a1a1a';
    if (open > 0.08 || m === 'grin' || m === 'o') {
      let w = m === 'o' ? 16 + 10 * open : 28 + 14 * open, h = m === 'o' ? 16 + 14 * open : 8 + 26 * open;
      if (m === 'grin') { w = 46; h = 20 + 22 * Math.max(open, 0.6); }
      fx.beginPath();
      if (m === 'grin' || m === 'smile') { // D shape
        fx.moveTo(cx - w, cy - 4); fx.quadraticCurveTo(cx, cy - 10, cx + w, cy - 4); fx.quadraticCurveTo(cx + w * 0.8, cy + h * 1.4, cx, cy + h * 1.4); fx.quadraticCurveTo(cx - w * 0.8, cy + h * 1.4, cx - w, cy - 4);
      } else fx.ellipse(cx, cy + h * 0.4, w, h, 0, 0, Math.PI * 2);
      fx.fillStyle = inside; fx.fill();
      fx.save(); fx.clip(); ellipse(cx, cy + h * 1.3, w * 0.7, h * 0.6, tongue);
      if (m === 'grin' || m === 'smile') { fx.fillStyle = '#ffffff'; fx.fillRect(cx - w, cy - 10, w * 2, 9); }
      fx.restore();
      fx.lineWidth = 5; fx.strokeStyle = line; fx.stroke();
      return;
    }
    fx.lineWidth = bearded ? 10 : 8; fx.strokeStyle = line; fx.beginPath();
    if (m === 'frown') { fx.moveTo(cx - 26, cy + 12); fx.quadraticCurveTo(cx, cy - 10, cx + 26, cy + 12); }
    else if (m === 'flat') { fx.moveTo(cx - 22, cy + 4); fx.lineTo(cx + 22, cy + 4); }
    else if (m === 'wavy') { fx.moveTo(cx - 28, cy + 6); fx.quadraticCurveTo(cx - 14, cy - 6, cx, cy + 6); fx.quadraticCurveTo(cx + 14, cy + 18, cx + 28, cy + 6); }
    else if (m === 'cat') { fx.moveTo(cx - 26, cy); fx.quadraticCurveTo(cx - 13, cy + 16, cx, cy + 2); fx.quadraticCurveTo(cx + 13, cy + 16, cx + 26, cy); }
    else { fx.moveTo(cx - 28, cy - 2); fx.quadraticCurveTo(cx, cy + 24, cx + 28, cy - 2); } // smile
    fx.stroke();
  }

  function hexA(hex, a) {
    const c = new THREE.Color(hex);
    return `rgba(${Math.round(c.r * 255)},${Math.round(c.g * 255)},${Math.round(c.b * 255)},${a})`;
  }

  // ------------------------------------------------------------------ character construction
  const B = {}; // bones
  let character = null;
  let eyeAnchors = [];

  function group(name, parent, x, y, z) { const g = new THREE.Group(); g.name = name; g.position.set(x || 0, y || 0, z || 0); if (parent) parent.add(g); B[name] = g; return g; }

  function build() {
    if (character) scene.remove(character);
    for (const k in B) delete B[k];
    const S = spec;
    const wide = S.build === 'broad' ? 1.15 : S.build === 'regular' ? 1.07 : 1.0;
    const skin = toon(S.skin), topM = toon(S.top.color), botM = toon(S.bottom.color), shoeM = toon(S.shoes.color), soleM = toon(S.shoes.sole || '#dddddd');
    const hairM = toon(S.hair.color), beardM = toon(S.beard.color || S.hair.color);

    const root = group('root', null);
    character = root;
    const hips = group('hips', root, 0, 0.8, 0);

    // ---- legs
    ['L', 'R'].forEach((side, i) => {
      const sx = (i === 0 ? 1 : -1) * 0.125 * wide;
      const leg = group('leg' + side, hips, sx, 0, 0);
      const shortLeg = S.bottom.type === 'shorts' || S.bottom.type === 'skirt';
      const thigh = mesh(new THREE.CapsuleGeometry(0.11 * wide, 0.24, 6, 14), S.bottom.type === 'skirt' ? skin : botM);
      thigh.position.y = -0.17; leg.add(thigh);
      if (S.bottom.type === 'cargo') { // side pockets
        const pocket = mesh(new THREE.BoxGeometry(0.05, 0.13, 0.12), toon(new THREE.Color(S.bottom.color).multiplyScalar(0.85)), { outlineScale: 1.12 });
        pocket.position.set(Math.sign(sx) * 0.105 * wide, -0.2, 0.01); leg.add(pocket);
      }
      const knee = group('knee' + side, leg, 0, -0.36, 0);
      const shin = mesh(new THREE.CapsuleGeometry(0.097 * wide, 0.22, 6, 14), shortLeg ? skin : botM);
      shin.position.y = -0.16; knee.add(shin);
      const foot = group('foot' + side, knee, 0, -0.33, 0);
      const shoe = mesh(new THREE.SphereGeometry(0.12, 18, 12), shoeM); shoe.scale.set(1.0, 0.72, 1.55); shoe.position.set(0, -0.025, 0.06); foot.add(shoe);
      const sole = mesh(new THREE.CylinderGeometry(0.115, 0.12, 0.03, 18), soleM, { outline: false }); sole.scale.set(1, 1, 1.6); sole.position.set(0, -0.085, 0.06); foot.add(sole);
      const lace = mesh(new THREE.BoxGeometry(0.07, 0.012, 0.06), toon('#cfcfcf'), { outline: false }); lace.position.set(0, 0.045, 0.12); lace.rotation.x = -0.4; foot.add(lace);
    });
    // pelvis / skirt
    const pelvis = mesh(new THREE.SphereGeometry(0.23 * wide, 20, 14), botM); pelvis.scale.set(1.1, 0.55, 0.85); pelvis.position.y = 0.02; hips.add(pelvis);
    if (S.bottom.type === 'skirt') { const sk = mesh(new THREE.ConeGeometry(0.36, 0.38, 24, 1, true), botM); sk.position.y = -0.12; hips.add(sk); }

    // ---- torso
    const spine = group('spine', hips, 0, 0.05, 0);
    const torso = mesh(new THREE.CapsuleGeometry(0.22 * wide, 0.26, 8, 20), topM); torso.scale.set(1.12, 1, 0.82); torso.position.y = 0.21; spine.add(torso);
    const hem = mesh(new THREE.TorusGeometry(0.245 * wide, 0.018, 8, 28), toon(new THREE.Color(S.top.color).multiplyScalar(0.9)), { outline: false });
    hem.rotation.x = Math.PI / 2; hem.scale.set(1.12, 0.82, 1); hem.position.y = 0.05; spine.add(hem);
    if (S.top.type === 'hoodie') { const hood = mesh(new THREE.TorusGeometry(0.2, 0.07, 10, 24), topM); hood.rotation.x = Math.PI / 2.4; hood.position.set(0, 0.46, -0.06); spine.add(hood); }
    const chest = group('chest', spine, 0, 0.42, 0);
    const collar = mesh(new THREE.TorusGeometry(0.105, 0.022, 8, 22), toon(new THREE.Color(S.top.color).multiplyScalar(0.88)), { outline: false });
    collar.rotation.x = Math.PI / 2; collar.position.y = 0.035; chest.add(collar);
    if (S.top.type === 'shirt') { [1, -1].forEach(s => { const c = mesh(new THREE.BoxGeometry(0.1, 0.02, 0.08), toon('#ffffff')); c.position.set(0.06 * s, 0.05, 0.08); c.rotation.z = 0.5 * s; chest.add(c); }); }

    // ---- arms
    ['L', 'R'].forEach((side, i) => {
      const s = i === 0 ? 1 : -1;
      const arm = group('arm' + side, chest, s * 0.27 * wide, -0.02, 0);
      const sleeve = mesh(new THREE.CapsuleGeometry(0.076, 0.1, 6, 14), topM); sleeve.position.y = -0.08; arm.add(sleeve);
      const upper = mesh(new THREE.CapsuleGeometry(0.062, 0.17, 6, 12), skin); upper.position.y = -0.17; arm.add(upper);
      const elbow = group('elbow' + side, arm, 0, -0.27, 0);
      const fore = mesh(new THREE.CapsuleGeometry(0.058, 0.16, 6, 12), skin); fore.position.y = -0.1; elbow.add(fore);
      const hand = group('hand' + side, elbow, 0, -0.23, 0);
      const palm = mesh(new THREE.SphereGeometry(0.072, 14, 10), skin); palm.scale.set(0.95, 1.1, 0.8); hand.add(palm);
      const thumb = mesh(new THREE.SphereGeometry(0.03, 8, 6), skin, { outline: false }); thumb.position.set(-s * 0.055, 0.02, 0.03); hand.add(thumb);
      const watchSide = S.watch === 'left' ? 'L' : S.watch === 'right' ? 'R' : null;
      if (watchSide === side) {
        const w = mesh(new THREE.CylinderGeometry(0.066, 0.066, 0.045, 16), toon(S.watchColor || '#151515'), { outlineScale: 1.06 }); w.position.y = -0.19; elbow.add(w);
        const dial = mesh(new THREE.BoxGeometry(0.06, 0.05, 0.02), toon('#2a2a2a'), { outline: false }); dial.position.set(0, -0.19, 0.065); elbow.add(dial);
      }
    });

    // ---- head
    const neck = group('neck', chest, 0, 0.05, 0);
    const neckM = mesh(new THREE.CylinderGeometry(0.075, 0.085, 0.14, 14), skin, { outline: false }); neckM.position.y = 0.05; neck.add(neckM);
    const head = group('head', neck, 0, 0.1, 0);
    const skull = mesh(new THREE.SphereGeometry(0.5, 40, 30), skin, { outlineScale: 1.025 }); skull.scale.set(1, 0.96, 0.93); skull.position.y = 0.47; head.add(skull);
    const H = skull; // attach face things to skull so they inherit its shape
    // beard
    if (S.beard.style === 'full') { // sideburns + outer cheeks beyond the face texture
      [-1, 1].forEach(sd => {
        const g = new THREE.SphereGeometry(0.507, 12, 16, sd < 0 ? Math.PI / 2 - 0.75 - 0.42 : Math.PI / 2 + 0.75, 0.42, 1.16, 1.2);
        const m = new THREE.Mesh(g, beardM); m.castShadow = true; H.add(m);
      });
    }
    if (S.beard.style === 'full' || S.beard.style === 'short') { // beard continues under the chin
      const under = new THREE.Mesh(new THREE.SphereGeometry(0.508, 32, 10, Math.PI / 2 - 0.62, 1.24, 2.33, 0.42), beardM); under.castShadow = true; H.add(under);
    }
    // face (canvas texture on a front shell)
    const fg = new THREE.SphereGeometry(0.512, 48, 32, Math.PI / 2 - 0.75, 1.5, 0.55, 1.8);
    const faceMesh = new THREE.Mesh(fg, new THREE.MeshBasicMaterial({ map: faceTex, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2 }));
    H.add(faceMesh);
    // nose (tiny, cute)
    const nose = mesh(new THREE.SphereGeometry(0.045, 12, 10), toon(new THREE.Color(S.skin).multiplyScalar(0.93)), { outline: false }); nose.scale.set(1, 0.8, 0.9); nose.position.set(0, -0.06, 0.505); H.add(nose);
    // ears + earring
    [1, -1].forEach(s => {
      const ear = mesh(new THREE.SphereGeometry(0.1, 14, 10), skin); ear.scale.set(0.55, 1, 0.75); ear.position.set(s * 0.49, -0.03, 0.02); H.add(ear);
      const wantRing = S.earring === 'both' || (S.earring === 'left' && s > 0) || (S.earring === 'right' && s < 0);
      if (wantRing) { const ring = mesh(new THREE.TorusGeometry(0.028, 0.009, 8, 16), toon(S.earringColor || '#e3c46b', { emissive: new THREE.Color(S.earringColor || '#e3c46b').multiplyScalar(0.25) }), { outline: false }); ring.position.set(s * 0.515, -0.12, 0.03); ring.rotation.y = Math.PI / 2; H.add(ring); }
    });
    // glasses
    if (S.glasses) {
      const gm = toon(typeof S.glasses === 'string' ? S.glasses : '#111111');
      [-0.2, 0.2].forEach(x => { const r = new THREE.Mesh(new THREE.TorusGeometry(0.11, 0.013, 8, 24), gm); r.position.set(x, 0.05, 0.5); H.add(r); });
      const br = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.012, 0.012), gm); br.position.set(0, 0.07, 0.52); H.add(br);
    }
    buildHair(H, S, hairM);
    eyeAnchors = [new THREE.Vector3(-0.2, -0.02, 0.48), new THREE.Vector3(0.2, -0.02, 0.48)].map(v => ({ v, parent: H }));

    root.traverse(o => { if (o.isMesh) o.castShadow = true; });
    scene.add(root);
    setRest();
  }

  function buildHair(H, S, hairM) {
    const st = S.hair.style || 'short';
    if (st === 'bald') return;
    // back + sides cap (leaves the face window open)
    const back = mesh(new THREE.SphereGeometry(0.522, 36, 24, Math.PI / 2 + 1.0, Math.PI * 2 - 2.0, 0.0, st === 'long' ? 2.4 : 1.32), hairM, { outlineScale: 1.02 });
    H.add(back);
    const top = mesh(new THREE.SphereGeometry(0.53, 36, 18, 0, Math.PI * 2, 0, 0.95), hairM, { outlineScale: 1.02 }); top.position.y = 0.02; H.add(top);
    // fringe band across the forehead
    const fringe = mesh(new THREE.SphereGeometry(0.535, 30, 10, Math.PI / 2 - 0.9, 1.8, 0.55, 0.42), hairM, { outline: false }); H.add(fringe);
    const tuft = (x, y, z, sx, sy, sz, rx, ry, rz) => {
      const t = mesh(new THREE.SphereGeometry(0.1, 20, 14), hairM, { outlineScale: 1.03 }); t.scale.set(sx, sy, sz); t.position.set(x, y, z); t.rotation.set(rx || 0, ry || 0, rz || 0); H.add(t); return t;
    };
    if (st === 'messy') { // tall messy textured hair, swept up & back with a lean (built from strand clumps)
      const lean = S.hair.lean === undefined ? -0.35 : S.hair.lean; // + = towards the character's left
      let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
      const up = new THREE.Vector3(0, 1, 0);
      const clump = (theta, phi, len, rad, liftUp, liftBack) => {
        const dir = new THREE.Vector3(Math.sin(theta) * Math.sin(phi), Math.cos(theta), Math.sin(theta) * Math.cos(phi)).normalize();
        const grow = dir.clone().multiplyScalar(0.55).add(new THREE.Vector3(lean * 0.6 + (rnd() - 0.5) * 0.5, liftUp, -liftBack)).normalize();
        // soft rounded tuft (wavy volume, not anime spikes), slightly flattened and curled back
        const c = mesh(new THREE.CapsuleGeometry(rad, len * 0.55, 6, 10), hairM, { outlineScale: 1.05 });
        c.scale.set(1.15, 1, 0.72);
        c.quaternion.setFromUnitVectors(up, grow);
        c.rotateX(-0.35 - rnd() * 0.3); c.rotateZ((rnd() - 0.5) * 0.5);
        c.position.copy(dir.multiplyScalar(0.47)).add(grow.clone().multiplyScalar(len * 0.42));
        H.add(c);
      };
      // crown + top: tall upward/back strands
      for (let i = 0; i < 34; i++) {
        const theta = 0.12 + rnd() * 0.85, phi = (rnd() - 0.5) * Math.PI * 1.6;
        clump(theta, phi, 0.2 + rnd() * 0.14, 0.085 + rnd() * 0.035, 0.8 + rnd() * 0.4, 0.35 + rnd() * 0.3);
      }
      // front quiff: lifts up from the hairline and flips back
      for (let i = 0; i < 10; i++) {
        const theta = 0.55 + rnd() * 0.3, phi = (rnd() - 0.5) * 1.3;
        clump(theta, phi, 0.24 + rnd() * 0.12, 0.095 + rnd() * 0.03, 1.4, 0.7);
      }
      // a couple of loose strands falling to one side (the messy bit)
      for (let i = 0; i < 3; i++) clump(0.62 + rnd() * 0.1, -0.5 + rnd() * 0.4, 0.18, 0.05, 0.25, -0.3);
      // volume under the strands so no scalp shows through
      const vol = mesh(new THREE.SphereGeometry(0.2, 18, 12), hairM, { outlineScale: 1.03 }); vol.scale.set(2.3, 1.15, 2.2); vol.position.set(lean * 0.05, 0.43, 0.02); H.add(vol);
    } else if (st === 'quiff') { // smooth, voluminous swept-up quiff
      tuft(0.0, 0.47, 0.24, 2.9, 1.25, 1.9, -0.65);          // front wave
      tuft(0.02, 0.6, 0.05, 3.0, 1.35, 2.6, -0.25);          // crown volume
      tuft(-0.2, 0.52, 0.12, 1.9, 1.15, 2.2, -0.45, 0, 0.35);
      tuft(0.22, 0.5, 0.12, 1.9, 1.1, 2.2, -0.45, 0, -0.35);
      tuft(0.08, 0.56, 0.33, 1.6, 0.8, 1.2, -1.05, 0, -0.25); // flick over forehead
      tuft(-0.12, 0.62, 0.2, 1.3, 0.75, 1.1, -0.9, 0.2, 0.4); // messy strand
    } else if (st === 'curly') {
      for (let i = 0; i < 26; i++) { const a = i / 26 * Math.PI * 2, r = 0.45; tuft(Math.cos(a) * r * 0.9, 0.3 + Math.sin(i * 1.7) * 0.12, Math.sin(a) * r * 0.8 - 0.05, 1.2, 1.2, 1.2); }
    } else if (st === 'bun') {
      tuft(0, 0.62, -0.18, 1.9, 1.7, 1.9);
    } else if (st === 'ponytail' || st === 'long') {
      const tail = mesh(new THREE.CapsuleGeometry(0.11, st === 'long' ? 0.5 : 0.35, 6, 12), hairM); tail.position.set(0, st === 'long' ? -0.25 : 0.05, -0.45); tail.rotation.x = 0.3; H.add(tail);
    } else { // short
      tuft(0, 0.5, 0.18, 2.4, 0.9, 1.8, -0.4);
    }
  }

  // ------------------------------------------------------------------ pose system
  const BONES = ['root', 'hips', 'spine', 'chest', 'neck', 'head', 'armL', 'elbowL', 'handL', 'armR', 'elbowR', 'handR', 'legL', 'kneeL', 'footL', 'legR', 'kneeR', 'footR'];
  const REST = { armL: [0, 0, 0.14], armR: [0, 0, -0.14], elbowL: [-0.1, 0, 0], elbowR: [-0.1, 0, 0] };
  const restPos = { root: new THREE.Vector3(), hips: new THREE.Vector3(0, 0.8, 0) };
  function setRest() { if (B.hips) restPos.hips.copy(B.hips.position); }
  function blankPose() {
    const p = {};
    BONES.forEach(b => { const r = REST[b] || [0, 0, 0]; p[b] = [r[0], r[1], r[2]]; });
    p.pos = [0, 0, 0]; // root offset
    p.hipY = 0;
    return p;
  }
  const add = (p, b, x, y, z, w) => { w = w === undefined ? 1 : w; p[b][0] += x * w; p[b][1] += y * w; p[b][2] += z * w; };
  const ease = t => t < 0 ? 0 : t > 1 ? 1 : t * t * (3 - 2 * t);
  const pulse = (t, a, b) => ease((t - a) / 0.25) * (1 - ease((t - b) / 0.25)); // 0→1 between a and b

  // ------------------------------------------------------------------ actions
  const A = {
    wave: { dur: 2.4, face: { eye: 'happy', mouth: 'grin', blush: 0.55 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.1);
      add(p, 'armR', -0.3, 0, -2.5, k * w); add(p, 'elbowR', 0, 0, -0.5 + Math.sin(t * 13) * 0.45, k * w);
      add(p, 'head', 0, 0, 0.12, k * w); add(p, 'chest', 0, 0, 0.05, k * w); } },
    namaste: { dur: 2.6, face: { eye: 'happy', mouth: 'smile', blush: 0.5 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.3), bow = pulse(t, 0.5, 1.8);
      ['L', 'R'].forEach((s, i) => { const d = i === 0 ? 1 : -1; add(p, 'arm' + s, -0.95, 0, -0.4 * d, k * w); add(p, 'elbow' + s, -1.45, 0.75 * d, 0, k * w); });
      add(p, 'spine', 0.18, 0, 0, bow * w); add(p, 'head', 0.25, 0, 0, bow * w); } },
    walk: { loop: true, fn(t, p, w) {
      const s = Math.sin(t * 7.5), c = Math.cos(t * 7.5);
      add(p, 'legL', s * 0.55, 0, 0, w); add(p, 'legR', -s * 0.55, 0, 0, w);
      add(p, 'kneeL', Math.max(0, -c) * 0.8, 0, 0, w); add(p, 'kneeR', Math.max(0, c) * 0.8, 0, 0, w);
      add(p, 'armL', -s * 0.5, 0, 0, w); add(p, 'armR', s * 0.5, 0, 0, w);
      add(p, 'elbowL', -0.3, 0, 0, w); add(p, 'elbowR', -0.3, 0, 0, w);
      p.hipY += Math.abs(c) * 0.035 * w; add(p, 'spine', 0.06, s * 0.08, 0, w); } },
    talk: { loop: true, fn(t, p, w) {
      add(p, 'head', Math.sin(t * 3.1) * 0.06, Math.sin(t * 1.7) * 0.1, Math.sin(t * 2.3) * 0.05, w);
      const g = (Math.sin(t * 1.3) + 1) / 2;
      add(p, 'armR', -0.45 * g, 0, -0.25 * g, w); add(p, 'elbowR', -0.9 * g, 0, 0, w);
      add(p, 'armL', -0.25 * (1 - g), 0, 0.2 * (1 - g), w); add(p, 'elbowL', -0.6 * (1 - g), 0, 0, w); } },
    think: { loop: true, face: { eye: 'normal', mouth: 'cat', lookX: 0.8, lookY: -0.9, brow: -0.2, browUp: 0.6 }, fn(t, p, w) {
      add(p, 'armR', -1.25, 0, 0.25, w); add(p, 'elbowR', -2.05, 0.3, 0, w); add(p, 'handR', 0.2, 0, 0, w);
      add(p, 'head', -0.05, 0.15, 0.18, w); add(p, 'footL', Math.max(0, Math.sin(t * 6)) * -0.25, 0, 0, w);
      if (Math.random() < 0.012) emit('❓', headTop(0.2), [0, 0.4, 0], 1.2, 0.35); } },
    listen: { loop: true, face: { eye: 'normal', mouth: 'smile', browUp: 0.5, blush: 0.4 }, fn(t, p, w) {
      add(p, 'armL', -0.6, 0, 1.6, w); add(p, 'elbowL', -1.9, 0, 0, w); // hand to ear
      add(p, 'head', 0.02, -0.15, -0.18, w); add(p, 'spine', 0, 0, -0.05, w); } },
    jump: { dur: 1.3, face: { eye: 'wide', mouth: 'grin', blush: 0.5 }, fn(t, p, w) {
      const crouch = pulse(t, 0, 0.28) + pulse(t, 0.85, 1.05) * 0.8;
      const air = t > 0.28 && t < 0.92 ? Math.sin((t - 0.28) / 0.64 * Math.PI) : 0;
      p.pos[1] += air * 0.65 * w; p.hipY -= crouch * 0.12 * w;
      add(p, 'kneeL', crouch * 1.1, 0, 0, w); add(p, 'kneeR', crouch * 1.1, 0, 0, w); add(p, 'legL', -crouch * 0.6 - air * 0.3, 0, 0, w); add(p, 'legR', -crouch * 0.6 - air * 0.3, 0, 0, w);
      add(p, 'armL', 0, 0, air * 2.6, w); add(p, 'armR', 0, 0, -air * 2.6, w); add(p, 'kneeL', air * 0.6, 0, 0, w); add(p, 'kneeR', air * 0.6, 0, 0, w);
      if (t > 0.5 && t < 0.52) emit('✨', headTop(0.3), [0, 0.6, 0], 1, 0.4); } },
    dance: { dur: 6.5, face: { eye: 'happy', mouth: 'grin', blush: 0.6 }, fn(t, p, w) {
      const b = t * Math.PI * 2 * 2.1, s = Math.sin(b), part = Math.floor(t / 1.6) % 4;
      p.hipY += Math.abs(Math.sin(b)) * 0.06 * w; add(p, 'hips', 0, s * 0.25, 0, w); add(p, 'spine', 0, 0, s * 0.12, w); add(p, 'head', 0, -s * 0.2, Math.sin(b / 2) * 0.15, w);
      add(p, 'kneeL', Math.max(0, s) * 0.5, 0, 0, w); add(p, 'kneeR', Math.max(0, -s) * 0.5, 0, 0, w);
      if (part === 0) { add(p, 'armL', 0, 0, 2.4 + s * 0.3, w); add(p, 'armR', 0, 0, -2.4 + s * 0.3, w); }
      else if (part === 1) { add(p, 'armL', -1.4, 0, 0.3, w); add(p, 'elbowL', -1.6 + s * 0.6, 0, 0, w); add(p, 'armR', -1.4, 0, -0.3, w); add(p, 'elbowR', -1.6 - s * 0.6, 0, 0, w); }
      else if (part === 2) { add(p, 'armL', s * 0.9, 0, 0.9, w); add(p, 'armR', -s * 0.9, 0, -0.9, w); add(p, 'root', 0, s * 0.6, 0, w); }
      else { add(p, 'armR', -0.3, 0, -2.7 + Math.sin(b * 2) * 0.25, w); add(p, 'armL', 0, 0, 0.9, w); add(p, 'elbowL', -1.4, 0, 0, w); }
      if (Math.random() < 0.05) emit(Math.random() < 0.5 ? '🎵' : '🎶', headTop(0.15).add(new THREE.Vector3((Math.random() - 0.5) * 1.4, 0, 0)), [0, 0.7, 0], 1.4, 0.35); } },
    laugh: { dur: 2.8, face: { eye: 'happy', mouth: 'grin', open: 1, blush: 0.8 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.5), sh = Math.sin(t * 28) * 0.05;
      add(p, 'head', -0.28 + sh, 0, sh, k * w); add(p, 'chest', -0.08, 0, sh * 0.8, k * w); p.hipY += Math.abs(Math.sin(t * 14)) * 0.025 * k * w;
      add(p, 'armL', -0.7, 0, 0.3, k * w); add(p, 'elbowL', -1.5, 0, 0, k * w); add(p, 'armR', -0.6, 0, -0.35, k * w); add(p, 'elbowR', -1.5, 0, 0, k * w);
      if (Math.random() < 0.06) emit('😆', headTop(0.25).add(new THREE.Vector3((Math.random() - 0.5), 0, 0)), [0, 0.5, 0], 1.1, 0.3); } },
    cry: { dur: 3.8, face: { eye: 'cry', mouth: 'wavy', open: 0.4, brow: -1, tears: 1, blush: 0.5 }, fn(t, p, w) {
      const k = pulse(t, 0, 3.5), sob = Math.sin(t * 9) * 0.05;
      add(p, 'head', 0.28 + sob, 0, 0, k * w); add(p, 'spine', 0.12, 0, 0, k * w); add(p, 'chest', sob, 0, 0, k * w);
      add(p, 'armL', -2.0, 0, -0.5, k * w); add(p, 'elbowL', -1.7, 0, 0, k * w); add(p, 'armR', -2.0, 0, 0.5, k * w); add(p, 'elbowR', -1.7, 0, 0, k * w);
      if (Math.random() < 0.18) { const e = eyeWorld(Math.random() < 0.5 ? 0 : 1); emit('💧', e, [(Math.random() - 0.5) * 0.6, 0.3, 0.4], 0.9, 0.16, -2.2); } } },
    sad: { dur: 3.5, face: { eye: 'sad', mouth: 'frown', brow: -1, blush: 0.15, lookY: 0.7 }, fn(t, p, w) {
      const k = pulse(t, 0, 3.2);
      add(p, 'spine', 0.22, 0, 0, k * w); add(p, 'head', 0.35, 0, Math.sin(t * 1.5) * 0.06, k * w); p.hipY -= 0.03 * k * w;
      add(p, 'armL', 0.1, 0, -0.1, k * w); add(p, 'armR', 0.1, 0, 0.1, k * w); add(p, 'legR', 0, 0.3, 0, k * w);
      if (t < 0.1) emit('☁️', headTop(0.45), [0, 0.05, 0], 3.0, 0.55, 0); if (Math.random() < 0.08 && t < 3) emit('💧', headTop(0.25).add(new THREE.Vector3((Math.random() - 0.5) * 0.5, 0, 0)), [0, -0.6, 0], 0.7, 0.1, -1); } },
    angry: { dur: 2.8, face: { eye: 'angry', mouth: 'flat', brow: 1.2, blush: 0.9 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.5), stomp = Math.max(0, Math.sin(t * 9));
      add(p, 'armL', 0.2, 0, 0.35, k * w); add(p, 'armR', 0.2, 0, -0.35, k * w); add(p, 'elbowL', -0.3, 0, 0, k * w); add(p, 'elbowR', -0.3, 0, 0, k * w);
      add(p, t % 0.7 < 0.35 ? 'legL' : 'legR', -stomp * 0.5, 0, 0, k * w); add(p, 'head', 0.1, Math.sin(t * 16) * 0.12, 0, k * w);
      if (Math.random() < 0.05) emit('💢', headTop(0.25).add(new THREE.Vector3(0.35, 0, 0)), [0, 0.3, 0], 0.8, 0.35); } },
    surprised: { dur: 1.8, face: { eye: 'wide', mouth: 'o', open: 0.8, browUp: 1.3, blush: 0.3 }, fn(t, p, w) {
      const k = pulse(t, 0, 1.5), hop = t < 0.35 ? Math.sin(t / 0.35 * Math.PI) : 0;
      p.pos[1] += hop * 0.25 * w; p.pos[2] -= 0.15 * k * w;
      add(p, 'armL', -0.4, 0, 1.5, k * w); add(p, 'armR', -0.4, 0, -1.5, k * w); add(p, 'elbowL', -1.4, 0, 0, k * w); add(p, 'elbowR', -1.4, 0, 0, k * w); add(p, 'head', -0.12, 0, 0, k * w);
      if (t < 0.05) emit('❗', headTop(0.35), [0, 0.5, 0], 1.0, 0.45, 0); } },
    love: { dur: 2.8, face: { eye: 'heart', mouth: 'smile', blush: 1 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.5);
      add(p, 'armL', -1.0, 0, -0.55, k * w); add(p, 'elbowL', -1.3, 0.5, 0, k * w); add(p, 'armR', -1.0, 0, 0.55, k * w); add(p, 'elbowR', -1.3, -0.5, 0, k * w);
      add(p, 'hips', 0, Math.sin(t * 3) * 0.2, 0, k * w); add(p, 'head', 0, 0, Math.sin(t * 3) * 0.12, k * w);
      if (Math.random() < 0.1) emit('💖', headTop(0).add(new THREE.Vector3((Math.random() - 0.5) * 1.2, -0.2, 0.2)), [0, 0.7, 0], 1.3, 0.3); } },
    shy: { dur: 2.8, face: { eye: 'normal', mouth: 'cat', blush: 1, lookX: -0.8, lookY: 0.6 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.5);
      add(p, 'armL', 0.5, 0, -0.1, k * w); add(p, 'armR', 0.5, 0, 0.1, k * w); add(p, 'elbowL', -0.4, 0, 0, k * w); add(p, 'elbowR', -0.4, 0, 0, k * w);
      add(p, 'head', 0.2, -0.25, 0.15, k * w); add(p, 'legR', 0, 0.4 + Math.sin(t * 4) * 0.2, 0, k * w); add(p, 'kneeR', 0.3, 0, 0, k * w); } },
    sleepy: { dur: 3.5, face: { eye: 'sleepy', mouth: 'o', open: 0.3, blush: 0.2 }, fn(t, p, w) {
      const k = pulse(t, 0, 3.2), yawn = pulse(t, 0.3, 1.4);
      add(p, 'head', 0.3 - yawn * 0.5, 0, 0.2, k * w); add(p, 'armR', -0.9 * yawn, 0, -0.2, w); add(p, 'elbowR', -2.0 * yawn, 0, 0, w);
      face.open = Math.max(face.open, yawn);
      if (Math.random() < 0.04) emit('💤', headTop(0.2).add(new THREE.Vector3(0.35, 0, 0)), [0.15, 0.4, 0], 1.6, 0.35, 0); } },
    clap: { dur: 2.2, face: { eye: 'happy', mouth: 'grin', blush: 0.6 }, fn(t, p, w) {
      const k = pulse(t, 0, 1.9), c = (Math.sin(t * 16) + 1) / 2;
      add(p, 'armL', -1.25, 0, -0.35 * c, k * w); add(p, 'armR', -1.25, 0, 0.35 * c, k * w); add(p, 'elbowL', -0.9, 0.4, 0, k * w); add(p, 'elbowR', -0.9, -0.4, 0, k * w);
      p.hipY += Math.abs(Math.sin(t * 8)) * 0.03 * k * w;
      if (Math.random() < 0.06) emit('👏', headTop(-0.2), [0, 0.5, 0.2], 0.8, 0.3); } },
    thumbsup: { dur: 2.2, face: { eye: 'happy', wink: true, mouth: 'grin', blush: 0.5 }, fn(t, p, w) {
      const k = pulse(t, 0, 1.9);
      add(p, 'armR', -1.3, 0, -0.3, k * w); add(p, 'elbowR', -0.9, 0, 0, k * w); add(p, 'head', 0, 0, -0.12, k * w);
      if (t < 0.05) emit('👍', headTop(0.2).add(new THREE.Vector3(-0.45, 0, 0)), [0, 0.3, 0], 1.4, 0.45, 0); } },
    yes: { dur: 1.4, face: { eye: 'happy', mouth: 'smile' }, fn(t, p, w) { add(p, 'head', Math.sin(t * 13) * 0.22 * (1 - t / 1.4), 0, 0, w); } },
    no: { dur: 1.4, face: { eye: 'normal', mouth: 'flat', brow: -0.4 }, fn(t, p, w) { add(p, 'head', 0, Math.sin(t * 13) * 0.3 * (1 - t / 1.4), 0, w); } },
    spin: { dur: 1.6, face: { eye: 'happy', mouth: 'grin', blush: 0.5 }, fn(t, p, w) {
      add(p, 'root', 0, ease(t / 1.2) * Math.PI * 2, 0, w); add(p, 'armL', 0, 0, 1.3, w); add(p, 'armR', 0, 0, -1.3, w); p.pos[1] += Math.sin(Math.min(1, t / 1.2) * Math.PI) * 0.2 * w;
      if (Math.random() < 0.1) emit('✨', headTop(-0.3).add(new THREE.Vector3((Math.random() - 0.5) * 1.2, 0, 0)), [0, 0.3, 0], 0.9, 0.25); } },
    happy: { dur: 2.0, face: { eye: 'happy', mouth: 'grin', blush: 0.7 }, fn(t, p, w) {
      const hop = Math.max(0, Math.sin(t * 2 * Math.PI * 1.25));
      p.pos[1] += hop * 0.18 * w; add(p, 'armL', 0, 0, 0.9 + hop * 0.6, w); add(p, 'armR', 0, 0, -0.9 - hop * 0.6, w); add(p, 'head', 0, 0, Math.sin(t * 6) * 0.1, w);
      if (Math.random() < 0.08) emit('✨', headTop(0.1).add(new THREE.Vector3((Math.random() - 0.5) * 1.3, 0, 0)), [0, 0.5, 0], 1.0, 0.28); } },
    excited: { dur: 2.0, face: { eye: 'wide', mouth: 'grin', open: 0.7, blush: 0.8 }, fn(t, p, w) {
      const hop = Math.abs(Math.sin(t * 2 * Math.PI * 2.2));
      p.pos[1] += hop * 0.22 * w; add(p, 'armL', 0, 0, 2.6, w); add(p, 'armR', 0, 0, -2.6, w); add(p, 'elbowL', 0, 0, Math.sin(t * 20) * 0.3, w); add(p, 'elbowR', 0, 0, -Math.sin(t * 20) * 0.3, w);
      if (Math.random() < 0.1) emit('⭐', headTop(0.2).add(new THREE.Vector3((Math.random() - 0.5) * 1.4, 0, 0)), [0, 0.6, 0], 1.0, 0.3); } },
    watch: { dur: 2.6, face: { eye: 'normal', mouth: 'cat', lookX: 0.6, lookY: 0.9 }, fn(t, p, w) { // checks his smartwatch
      const k = pulse(t, 0, 2.3), s = spec.watch === 'right' ? 'R' : 'L', d = s === 'L' ? 1 : -1;
      add(p, 'arm' + s, -1.1, 0, -0.5 * d, k * w); add(p, 'elbow' + s, -1.5, 0.9 * d, 0, k * w); add(p, 'head', 0.35, 0.25 * d, 0, k * w); } },
    stretch: { dur: 2.6, face: { eye: 'closed', mouth: 'o', open: 0.5 }, fn(t, p, w) {
      const k = pulse(t, 0, 2.3); add(p, 'armL', 0, 0, 2.9, k * w); add(p, 'armR', 0, 0, -2.9, k * w); add(p, 'spine', -0.12, 0, Math.sin(t * 2) * 0.1, k * w); p.pos[1] += 0.03 * k * w; } },
  };

  // ------------------------------------------------------------------ particles (emoji sprites)
  const particles = [];
  const emojiTex = {};
  function texFor(e) {
    if (emojiTex[e]) return emojiTex[e];
    const c = document.createElement('canvas'); c.width = c.height = 128;
    const g = c.getContext('2d'); g.font = '100px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(e, 64, 72);
    return (emojiTex[e] = new THREE.CanvasTexture(c));
  }
  function emit(e, pos, vel, life, size, gravity) {
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: texFor(e), transparent: true, depthWrite: false }));
    s.position.copy(pos); s.scale.setScalar(size || 0.3);
    scene.add(s);
    particles.push({ s, v: new THREE.Vector3(vel[0], vel[1], vel[2]), life: life || 1, age: 0, g: gravity === undefined ? 0 : gravity, size: size || 0.3 });
  }
  function headTop(dy) { const v = new THREE.Vector3(0, 1.05 + (dy || 0), 0.1); return B.head ? B.head.localToWorld(v) : v; }
  function eyeWorld(i) { const a = eyeAnchors[i]; return a ? a.parent.localToWorld(a.v.clone()) : new THREE.Vector3(); }
  function stepParticles(dt) {
    for (let i = particles.length - 1; i >= 0; i--) {
      const q = particles[i]; q.age += dt;
      q.v.y += q.g * dt; q.s.position.addScaledVector(q.v, dt);
      const f = q.age / q.life;
      q.s.material.opacity = f < 0.15 ? f / 0.15 : 1 - Math.max(0, (f - 0.6) / 0.4);
      q.s.scale.setScalar(q.size * (0.6 + 0.4 * Math.min(1, f * 4)));
      if (q.age >= q.life) { scene.remove(q.s); q.s.material.dispose(); particles.splice(i, 1); }
    }
  }

  // ------------------------------------------------------------------ state machine
  let action = null, actionT = 0, actionW = 0;
  let talking = false, talkLevel = 0, thinking = false, listening = false;
  let walkTarget = null, walkFrom = 0, walkT = 0, walkDur = 1, walkDone = null;
  let facing = 0, facingTarget = 0;
  let idleTimer = 0, blinkTimer = 2, blinkPhase = -1;
  let lookTarget = { x: 0, y: 0 };

  function play(name) {
    if (name === 'walk') { walkAcross(); return; }
    if (!A[name]) return;
    action = name; actionT = 0; idleTimer = 0;
    if (window.Android && Android.onAction) Android.onAction(name);
  }

  function walkTo(x, dur, done) {
    walkFrom = character.position.x; walkTarget = x; walkT = 0; walkDur = dur; walkDone = done;
    facingTarget = x > walkFrom ? Math.PI / 2 : -Math.PI / 2;
  }
  function walkAcross() { const x = character.position.x; walkTo(x > 0 ? -0.9 : 0.9, 1.3, () => walkTo(0, 1.0, () => { facingTarget = 0; })); }

  function enter() {
    character.position.x = -3.4; facing = facingTarget = Math.PI / 2;
    walkTo(0, 1.7, () => { facingTarget = 0; setTimeout(() => play(Math.random() < 0.5 ? 'wave' : 'namaste'), 350); });
  }

  // ------------------------------------------------------------------ main loop
  const clock = new THREE.Clock();
  let tAll = 0;
  function frame() {
    requestAnimationFrame(frame);
    const dt = Math.min(0.05, clock.getDelta()); tAll += dt;
    if (!character) return;
    const p = blankPose();

    // idle breathing / sway
    const br = Math.sin(tAll * 2.2);
    add(p, 'chest', br * 0.025, 0, 0); add(p, 'head', -br * 0.02, Math.sin(tAll * 0.6) * 0.06, Math.sin(tAll * 0.9) * 0.03);
    add(p, 'armL', 0, 0, br * 0.03); add(p, 'armR', 0, 0, -br * 0.03); p.hipY += br * 0.006;

    // face defaults
    Object.assign(face, { eye: 'normal', mouth: 'smile', open: 0, blush: 0.35, brow: 0, browUp: 0, tears: 0, wink: false });

    // walking (moves the whole character)
    let walking = false;
    if (walkTarget !== null) {
      walkT += dt; const f = Math.min(1, walkT / walkDur);
      character.position.x = walkFrom + (walkTarget - walkFrom) * ease(f);
      walking = f < 1;
      if (f >= 1) { const d = walkDone; walkTarget = null; walkDone = null; if (d) d(); }
    }
    facing += (facingTarget - facing) * Math.min(1, dt * 8);
    if (walking) A.walk.fn(tAll, p, 1);

    // listening / thinking loops (lower priority than one-shot actions)
    const loopName = action ? null : thinking ? 'think' : listening ? 'listen' : null;
    if (loopName) { A[loopName].fn(tAll, p, 1); Object.assign(face, A[loopName].face || {}); }

    // one-shot action with fade in/out
    if (action) {
      const a = A[action]; actionT += dt;
      const fadeOut = a.dur ? Math.min(1, (a.dur - actionT) / 0.3) : 1;
      actionW = Math.min(1, actionT / 0.2) * Math.max(0, fadeOut);
      a.fn(actionT, p, actionW);
      if (a.face && actionW > 0.3) Object.assign(face, a.face);
      if (a.dur && actionT >= a.dur) { action = null; }
    }

    // talking: mouth + gestures layered on top
    if (talking) {
      talkLevel = 0.35 + 0.65 * Math.abs(Math.sin(tAll * 15) * Math.sin(tAll * 4.3 + 1));
      if (!action) A.talk.fn(tAll, p, 0.8);
      if (face.mouth !== 'o') { face.open = Math.max(face.open, talkLevel); if (face.mouth === 'frown' || face.mouth === 'flat' || face.mouth === 'cat') face.mouth = 'smile'; }
    }

    // random idle fidgets
    if (!action && !talking && !thinking && !listening && !walking) {
      idleTimer += dt;
      if (idleTimer > 9 + Math.random() * 6) { idleTimer = 0; play(['watch', 'stretch', 'yes', 'shy', 'happy', 'think'][Math.floor(Math.random() * 6)]); }
    }

    // blink
    blinkTimer -= dt;
    if (blinkTimer <= 0 && blinkPhase < 0) blinkPhase = 0;
    if (blinkPhase >= 0) { blinkPhase += dt; face.blink = Math.sin(Math.min(1, blinkPhase / 0.14) * Math.PI); if (blinkPhase > 0.14) { blinkPhase = -1; face.blink = 0; blinkTimer = 2 + Math.random() * 3.5; } }
    // eyes follow the touch / wander
    if (face.lookX === undefined || (!action && !thinking)) { face.lookX = lookTarget.x + Math.sin(tAll * 0.7) * 0.15; face.lookY = lookTarget.y; }

    apply(p);
    drawFace();
    stepParticles(dt);
    renderer.render(scene, camera);
  }

  function apply(p) {
    BONES.forEach(b => { const o = B[b]; if (!o) return; o.rotation.set(p[b][0], p[b][1], p[b][2]); });
    character.rotation.y += facing;
    character.position.y = p.pos[1];
    character.position.z = p.pos[2];
    B.hips.position.y = restPos.hips.y + p.hipY;
    // keep feet planted when hips drop: bend knees a little (cheap IK feel)
    const drop = -Math.min(0, p.hipY);
    if (drop > 0) { B.kneeL.rotation.x += drop * 4; B.kneeR.rotation.x += drop * 4; B.legL.rotation.x -= drop * 2; B.legR.rotation.x -= drop * 2; }
  }

  // ------------------------------------------------------------------ touch: tap = cute reaction, drag = spin him around
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2();
  let downX = 0, downY = 0, downT = 0, dragging = false, baseFacing = 0;
  renderer.domElement.addEventListener('pointerdown', e => { downX = e.clientX; downY = e.clientY; downT = performance.now(); dragging = false; baseFacing = facingTarget; });
  renderer.domElement.addEventListener('pointermove', e => {
    const rc = renderer.domElement.getBoundingClientRect();
    lookTarget.x = ((e.clientX - rc.left) / rc.width - 0.5) * 2; lookTarget.y = ((e.clientY - rc.top) / rc.height - 0.4) * 1.5;
    if (e.buttons && Math.abs(e.clientX - downX) > 12) { dragging = true; facingTarget = baseFacing + (e.clientX - downX) / 90; }
  });
  renderer.domElement.addEventListener('pointerup', e => {
    if (dragging) { setTimeout(() => { facingTarget = 0; }, 900); return; }
    if (performance.now() - downT > 600) return;
    const rc = renderer.domElement.getBoundingClientRect();
    ndc.set(((e.clientX - rc.left) / rc.width) * 2 - 1, -((e.clientY - rc.top) / rc.height) * 2 + 1);
    ray.setFromCamera(ndc, camera);
    const hit = character && ray.intersectObject(character, true)[0];
    if (!hit) { if (window.Android && Android.onTapEmpty) Android.onTapEmpty(); return; }
    const local = B.head.worldToLocal(hit.point.clone());
    const onHead = local.y > 0.75;
    const r = onHead ? ['love', 'shy', 'happy'] : ['laugh', 'surprised', 'jump', 'spin', 'happy', 'shy'];
    play(r[Math.floor(Math.random() * r.length)]);
    if (window.Android && Android.onPoke) Android.onPoke(onHead ? 'head' : 'body');
  });

  const onResize = () => { renderer.setSize(W(), Hh()); frameCamera(); };
  addEventListener('resize', onResize);
  if (window.ResizeObserver) new ResizeObserver(onResize).observe(host);

  // ------------------------------------------------------------------ public API
  window.buddy = {
    play,
    enter,
    setTalking(v) { talking = !!v; if (v) { thinking = false; listening = false; } },
    setThinking(v) { thinking = !!v; if (v) listening = false; },
    setListening(v) { listening = !!v; },
    setSpec(json) {
      try { const s = typeof json === 'string' ? JSON.parse(json) : json; spec = deepMerge(JSON.parse(JSON.stringify(DEFAULT_SPEC)), s); build(); } catch (e) { console.log('bad spec', e); }
    },
    actions: () => Object.keys(A).concat(['walk']),
  };
  function deepMerge(a, b) { for (const k in b) { if (b[k] && typeof b[k] === 'object' && !Array.isArray(b[k]) && a[k] && typeof a[k] === 'object') deepMerge(a[k], b[k]); else a[k] = b[k]; } return a; }

  build();
  frame();
  const qs = new URLSearchParams(location.search);
  if (window.Android && Android.onReady) Android.onReady();
  else if (qs.get('act')) { setTimeout(() => play(qs.get('act')), 50); if (qs.get('talk')) talking = true; }
  else enter();
})();
