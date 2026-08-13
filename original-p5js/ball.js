function Ball(x, y, r) {
  // Scalar physics is much cheaper than allocating/updating p5.Vector objects
  // hundreds of times per frame.
  this.x = x;
  this.y = y;
  this.prevX = x;
  this.prevY = y;

  this.vx = random(-0.15, 0.15);
  this.vy = random(-0.15, 0.15);
  this.ax = 0;
  this.ay = 0;

  this.r = finiteNumber(r, 1);
  this.massScale = max(0.01, ((TWO_PI * this.r) * 0.5) * 0.01);

  this.applyGravity = true;

  // 0 = distant, 1 = foreground.
  this.depth = pow(random(1), 1.8);
  this.bokeh = random(1) < 0.12;
  this.spark = random(1) < 0.06;

  this.energy = 0;
  this.pulseOffset = random(TWO_PI);
  this.pulseSpeed = random(0.01, 0.035);

  // Flow is recalculated only every few frames.
  this.flowAngle = random(TWO_PI);
  this.flowSlot = floor(random(FLOW_RECALC_FRAMES));

  this.applyForceXY = function (fx, fy) {
    if (!Number.isFinite(fx) || !Number.isFinite(fy)) return;

    this.ax += fx;
    this.ay += fy;

    const forceMagnitude = Math.hypot(fx, fy);
    if (Number.isFinite(forceMagnitude)) {
      this.energy = max(
        this.energy,
        constrain(forceMagnitude / 2.5, 0, 1)
      );
    }
  };

  this.setEnergy = function (value) {
    if (!Number.isFinite(value)) return;
    this.energy = max(this.energy, constrain(value, 0, 1));
  };

  this.update = function () {
    this.prevX = this.x;
    this.prevY = this.y;

    // Match the feel of the original mass-scaled acceleration without vectors.
    this.vx = (this.vx + this.ax * this.massScale) * 0.992;
    this.vy = (this.vy + this.ay * this.massScale) * 0.992;

    const maxSpeed = 4 + this.energy * 3;
    const speedSq = this.vx * this.vx + this.vy * this.vy;
    const maxSpeedSq = maxSpeed * maxSpeed;

    if (speedSq > maxSpeedSq && speedSq > 0) {
      const scale = maxSpeed / Math.sqrt(speedSq);
      this.vx *= scale;
      this.vy *= scale;
    }

    this.x += this.vx;
    this.y += this.vy;

    if (!Number.isFinite(this.x) || !Number.isFinite(this.y)) {
      this.x = width * 0.5;
      this.y = height * 0.5;
      this.vx = 0;
      this.vy = 0;
    }

    this.ax = 0;
    this.ay = 0;
    this.energy = constrain(this.energy * 0.94, 0, 1);

    this.edges();
  };

  this.show = function (ctx) {
    const pulse = 0.85 + Math.sin(
      frameCount * this.pulseSpeed + this.pulseOffset
    ) * 0.15;

    if (this.bokeh) {
      this.showBokeh(ctx, pulse);
    } else {
      this.showAstrophage(ctx, pulse);
    }
  };

  this.showAstrophage = function (ctx, pulse) {
    let size = this.r * map(this.depth, 0, 1, 0.35, 0.9);
    size *= 1 + this.energy * 0.8;

    // The glow is already baked into the sprite, so there is no per-particle
    // shadowBlur. That is the biggest performance win in this version.
    const drawSize = max(4, size * (7.5 + this.energy * 3.5) * pulse);
    const alpha = constrain(
      0.25 + this.depth * 0.45 + this.energy * 0.30,
      0.15,
      1
    );

    ctx.globalAlpha = alpha;
    ctx.drawImage(
      astrophageSpriteCanvas,
      this.x - drawSize * 0.5,
      this.y - drawSize * 0.5,
      drawSize,
      drawSize
    );

    if (this.spark) {
      const sparkSize = max(2, drawSize * 0.28);
      ctx.globalAlpha = constrain(0.25 + this.energy * 0.55, 0, 0.9);
      ctx.drawImage(
        sparkSpriteCanvas,
        this.x - sparkSize * 0.5,
        this.y - sparkSize * 0.5,
        sparkSize,
        sparkSize
      );
    }
  };

  this.showBokeh = function (ctx, pulse) {
    let size = this.r * map(this.depth, 0, 1, 2.5, 7.0);
    size *= pulse * (1 + this.energy * 0.5);

    const drawSize = max(18, size * 3.1);

    ctx.globalAlpha = constrain(
      0.10 + this.depth * 0.12 + this.energy * 0.12,
      0.07,
      0.40
    );

    ctx.drawImage(
      bokehSpriteCanvas,
      this.x - drawSize * 0.5,
      this.y - drawSize * 0.5,
      drawSize,
      drawSize
    );
  };

  this.edges = function () {
    const margin = max(5, this.r * 5);
    let wrapped = false;

    if (this.x > width + margin) {
      this.x = -margin;
      wrapped = true;
    } else if (this.x < -margin) {
      this.x = width + margin;
      wrapped = true;
    }

    if (this.y > height + margin) {
      this.y = -margin;
      wrapped = true;
    } else if (this.y < -margin) {
      this.y = height + margin;
      wrapped = true;
    }

    if (wrapped) {
      this.prevX = this.x;
      this.prevY = this.y;
    }
  };
}
