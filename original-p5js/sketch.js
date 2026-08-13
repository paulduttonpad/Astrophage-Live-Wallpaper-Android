let balls = [];
let backgroundAstrophage = [];

let ballCount = 0;
let backgroundCount = 0;
let maxDistance = 1;
let maxDistanceSq = 1;
let flowTime = 0;

// Cached glow sprites. These replace thousands of expensive shadowBlur calls.
let astrophageSprite;
let bokehSprite;
let sparkSprite;
let astrophageSpriteCanvas;
let bokehSpriteCanvas;
let sparkSpriteCanvas;

// -----------------------------------------------------------------------------
// Tunable appearance/performance settings
// -----------------------------------------------------------------------------
// This version is intentionally more generous with particle counts because the
// rendering path is much cheaper than the shadowBlur/ellipse version.
const FOREGROUND_DENSITY = 38;      // lower = more foreground particles
const FOREGROUND_MIN = 120;
const FOREGROUND_MAX = 420;

const BACKGROUND_DENSITY = 700;     // lower = more deep-background particles
const BACKGROUND_MIN = 600;
const BACKGROUND_MAX = 3000;

const TRAIL_ALPHA = 0.18;           // lower = longer trails, slightly more fill-rate
const INTERACTION_RADIUS = 0.40;    // fraction of screen height
const FLOW_SPEED = 0.002;
const FLOW_RECALC_FRAMES = 5;       // only 1/5 of foreground noise is recalculated/frame
const BACKGROUND_INTERACTION_SKIP = 2;

// Optional FPS display: press F to toggle.
let showFps = false;
let fpsAverage = 60;

function finiteNumber(value, fallback = 0) {
  return Number.isFinite(value) ? value : fallback;
}

function setup() {
  const canvas = createCanvas(windowWidth, windowHeight, P2D);

  // Critical for phones / Retina displays. Without this, a 3x density screen
  // can mean 9x as many pixels to blend every frame.
  pixelDensity(1);
  colorMode(HSB, 360, 100, 100, 1);
  frameRate(60);

  canvas.elt.addEventListener("contextmenu", (event) => event.preventDefault());

  createParticleSprites();
  updateInteractionRadius();
  rebuildParticleCounts();

  background(0);
}

function createParticleSprites() {
  astrophageSprite = makeGlowSprite(96, false, false);
  bokehSprite = makeGlowSprite(128, true, false);
  sparkSprite = makeGlowSprite(48, false, true);

  astrophageSpriteCanvas = astrophageSprite.canvas || astrophageSprite.elt;
  bokehSpriteCanvas = bokehSprite.canvas || bokehSprite.elt;
  sparkSpriteCanvas = sparkSprite.canvas || sparkSprite.elt;
}

function makeGlowSprite(size, bokeh, spark) {
  const g = createGraphics(size, size);
  g.pixelDensity(1);

  const ctx = g.drawingContext;
  const c = size * 0.5;

  ctx.clearRect(0, 0, size, size);

  const radius = size * 0.48;
  const gradient = ctx.createRadialGradient(c, c, 0, c, c, radius);

  if (spark) {
    gradient.addColorStop(0.00, "rgba(255,255,255,1.0)");
    gradient.addColorStop(0.08, "rgba(255,245,245,0.95)");
    gradient.addColorStop(0.24, "rgba(255,70,70,0.70)");
    gradient.addColorStop(0.58, "rgba(255,0,18,0.18)");
    gradient.addColorStop(1.00, "rgba(255,0,0,0.0)");
  } else if (bokeh) {
    gradient.addColorStop(0.00, "rgba(255,235,235,0.55)");
    gradient.addColorStop(0.10, "rgba(255,90,90,0.34)");
    gradient.addColorStop(0.42, "rgba(255,10,25,0.22)");
    gradient.addColorStop(0.72, "rgba(255,0,15,0.10)");
    gradient.addColorStop(1.00, "rgba(255,0,0,0.0)");
  } else {
    gradient.addColorStop(0.00, "rgba(255,255,255,1.0)");
    gradient.addColorStop(0.06, "rgba(255,235,235,0.98)");
    gradient.addColorStop(0.17, "rgba(255,75,75,0.88)");
    gradient.addColorStop(0.40, "rgba(255,0,20,0.45)");
    gradient.addColorStop(0.72, "rgba(255,0,10,0.10)");
    gradient.addColorStop(1.00, "rgba(255,0,0,0.0)");
  }

  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, size, size);

  return g;
}

function draw() {
  // Fade old pixels for the long-exposure trail.
  blendMode(BLEND);
  background(0, 0, 0, TRAIL_ALPHA);

  const attractors = getAttractors();
  const interactionStep = frameCount % BACKGROUND_INTERACTION_SKIP === 0;

  // Update deep field.
  for (let i = 0; i < backgroundAstrophage.length; i++) {
    backgroundAstrophage[i].update(attractors, interactionStep);
  }

  // Update foreground physics.
  for (let i = 0; i < balls.length; i++) {
    const ball = balls[i];
    ball.applyGravity = true;

    applyPetrovaFlow(ball, i);

    for (let j = 0; j < attractors.length; j++) {
      const a = attractors[j];
      interactWithPoint(ball, a.x, a.y, a.direction);
    }

    ball.update();
  }

  // Draw with the native 2D context. 'lighter' is additive blending, but this
  // bypasses most p5 state-management overhead inside the hot particle loops.
  const ctx = drawingContext;
  ctx.save();
  ctx.globalCompositeOperation = "lighter";

  drawBackgroundFast(ctx);

  for (let i = 0; i < balls.length; i++) {
    balls[i].show(ctx);
  }

  ctx.globalAlpha = 1;
  ctx.restore();

  flowTime += FLOW_SPEED;

  if (showFps) drawFps();
}

function drawBackgroundFast(ctx) {
  // Tiny background astrophage are drawn with raw fillRect rather than p5
  // ellipse(). This keeps thousands of particles extremely cheap.
  ctx.fillStyle = "rgb(255, 18, 24)";

  for (let i = 0; i < backgroundAstrophage.length; i++) {
    const p = backgroundAstrophage[i];
    ctx.globalAlpha = p.getAlpha();

    if (p.size === 1) {
      ctx.fillRect(p.x, p.y, 1, 1);
    } else if (p.size === 2) {
      ctx.fillRect(p.x - 0.5, p.y - 0.5, 2, 2);
    } else {
      ctx.fillRect(p.x - 1, p.y - 1, 3, 3);
    }
  }

  // Rare white-hot distant points as a second inexpensive pass.
  ctx.fillStyle = "rgb(255, 245, 245)";
  for (let i = 0; i < backgroundAstrophage.length; i++) {
    const p = backgroundAstrophage[i];
    if (!p.spark) continue;

    ctx.globalAlpha = min(0.75, p.getAlpha() * 1.4);
    ctx.fillRect(p.x, p.y, 1, 1);
  }
}

function getAttractors() {
  const result = [];

  // Mouse only when there is no active touch; avoids duplicate interaction
  // on browsers that synthesise mouse events from touch events.
  if (mouseIsPressed && touches.length === 0) {
    if (Number.isFinite(mouseX) && Number.isFinite(mouseY)) {
      result.push({ x: mouseX, y: mouseY, direction: 1 });
    }
  }

  for (let i = 0; i < touches.length; i++) {
    const touch = touches[i];
    if (!touch) continue;

    const x = Number(touch.x);
    const y = Number(touch.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue;

    result.push({
      x: x,
      y: y,
      direction: i % 2 === 0 ? 1 : -1
    });
  }

  return result;
}

function interactWithPoint(ball, x, y, swirlDirection) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return;

  const dx = x - ball.x;
  const dy = y - ball.y;
  const distanceSq = dx * dx + dy * dy;

  if (!Number.isFinite(distanceSq) || distanceSq > maxDistanceSq) return;

  if (distanceSq < 0.000001) {
    ball.setEnergy(1);
    ball.applyGravity = false;
    return;
  }

  const distance = Math.sqrt(distanceSq);
  const influence = constrain(1 - distance / maxDistance, 0, 1);
  if (influence <= 0) return;

  ball.applyGravity = influence < 0.1;

  const nx = dx / distance;
  const ny = dy / distance;

  const attractionStrength = pow(influence, 1.8) * 2.0;
  ball.applyForceXY(
    nx * attractionStrength,
    ny * attractionStrength
  );

  const direction = swirlDirection === -1 ? -1 : 1;
  const swirlStrength = influence * 0.8 * direction;
  ball.applyForceXY(
    -ny * swirlStrength,
    nx * swirlStrength
  );

  if (distance < 35) {
    const repulsionStrength = (1 - distance / 35) * 2.0;
    ball.applyForceXY(
      -nx * repulsionStrength,
      -ny * repulsionStrength
    );
  }

  ball.setEnergy(influence);
}

function applyPetrovaFlow(ball, index) {
  // Perlin noise is relatively expensive. Recalculate only a slice of the
  // foreground each frame; the cached angle remains smooth in between.
  if ((frameCount + ball.flowSlot) % FLOW_RECALC_FRAMES === 0) {
    const noiseScale = 0.002;
    let n = noise(
      ball.x * noiseScale,
      ball.y * noiseScale,
      flowTime + ball.depth * 10
    );

    n = finiteNumber(n, 0.5);
    ball.flowAngle = n * TWO_PI * 3;
  }

  const strength = 0.05 + ball.depth * 0.08;
  ball.applyForceXY(
    Math.cos(ball.flowAngle) * strength,
    Math.sin(ball.flowAngle) * strength
  );
}

function deviceShaken() {
  for (let i = 0; i < balls.length; i++) {
    const ball = balls[i];

    if (random(1) < 0.5) {
      ball.applyForceXY(random(-10, 10), random(-10, 10));
    } else {
      let dx = ball.x - width * 0.5;
      let dy = ball.y - height * 0.5;
      const distance = Math.hypot(dx, dy);

      if (Number.isFinite(distance) && distance > 0) {
        dx /= distance;
        dy /= distance;
        ball.applyForceXY(dx * 2, dy * 2);
      }
    }

    ball.setEnergy(random(0.5, 1));
  }

  return false;
}

function updateInteractionRadius() {
  maxDistance = max(1, height * INTERACTION_RADIUS);
  maxDistanceSq = maxDistance * maxDistance;
}

function rebuildParticleCounts() {
  ballCount = constrain(
    floor((width / FOREGROUND_DENSITY) * (height / FOREGROUND_DENSITY)),
    FOREGROUND_MIN,
    FOREGROUND_MAX
  );

  backgroundCount = constrain(
    floor((width * height) / BACKGROUND_DENSITY),
    BACKGROUND_MIN,
    BACKGROUND_MAX
  );

  while (balls.length < ballCount) {
    const radius = random(1) < 0.80
      ? random(0.7, 2.5)
      : random(2, 5);

    balls.push(new Ball(random(width), random(height), radius));
  }

  while (balls.length > ballCount) balls.pop();

  while (backgroundAstrophage.length < backgroundCount) {
    backgroundAstrophage.push(
      new BackgroundAstrophage(random(width), random(height))
    );
  }

  while (backgroundAstrophage.length > backgroundCount) {
    backgroundAstrophage.pop();
  }
}

function drawFps() {
  fpsAverage = fpsAverage * 0.92 + frameRate() * 0.08;

  const ctx = drawingContext;
  ctx.save();
  ctx.globalCompositeOperation = "source-over";
  ctx.globalAlpha = 0.85;
  ctx.fillStyle = "black";
  ctx.fillRect(8, 8, 150, 48);
  ctx.globalAlpha = 1;
  ctx.fillStyle = "white";
  ctx.font = "14px monospace";
  ctx.fillText(`FPS: ${fpsAverage.toFixed(1)}`, 16, 28);
  ctx.fillText(`Particles: ${balls.length + backgroundAstrophage.length}`, 16, 47);
  ctx.restore();
}

function keyPressed() {
  if (key === "f" || key === "F") {
    showFps = !showFps;
    return false;
  }
}

function windowResized() {
  resizeCanvas(windowWidth, windowHeight);
  updateInteractionRadius();
  rebuildParticleCounts();
  background(0);
}

// Prevent mobile browsers from scrolling/zooming while interacting with canvas.
function touchStarted() { return false; }
function touchMoved() { return false; }
function touchEnded() { return false; }
