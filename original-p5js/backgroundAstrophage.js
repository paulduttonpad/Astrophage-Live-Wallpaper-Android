function BackgroundAstrophage(x, y) {
  this.x = x;
  this.y = y;

  // 0 = extremely distant, 1 = nearer background.
  this.depth = pow(random(1), 2.2);
  this.size = this.depth > 0.72 ? 2 : 1;

  if (random(1) < 0.02) {
    this.size = 3;
  }

  const angle = random(TWO_PI);
  const speed = random(0.015, 0.055) * map(this.depth, 0, 1, 0.35, 1.0);
  this.vx = Math.cos(angle) * speed;
  this.vy = Math.sin(angle) * speed;

  this.phase = random(TWO_PI);
  this.wobbleSpeed = random(0.10, 0.28);
  this.wobble = random(0.003, 0.015) * map(this.depth, 0, 1, 0.4, 1.0);

  this.alpha = random(0.12, 0.45) * map(this.depth, 0, 1, 0.55, 1.0);
  this.twinkleOffset = random(TWO_PI);
  this.twinkleSpeed = random(0.004, 0.018);
  this.spark = random(1) < 0.025;

  this.update = function (attractors, interactionStep) {
    // Cheap analytical drift. The previous version called Perlin noise once
    // for every background point every frame; with thousands of particles that
    // cost more CPU than the actual drawing.
    const wobbleAngle = flowTime * this.wobbleSpeed + this.phase;
    this.x += this.vx + Math.cos(wobbleAngle) * this.wobble;
    this.y += this.vy + Math.sin(wobbleAngle * 1.13) * this.wobble;

    // The deep field only reacts every second frame. Visually it is still smooth,
    // while halving the interaction calculations for the largest population.
    if (interactionStep && attractors.length > 0) {
      for (let i = 0; i < attractors.length; i++) {
        const a = attractors[i];
        const dx = a.x - this.x;
        const dy = a.y - this.y;
        const distanceSq = dx * dx + dy * dy;

        if (distanceSq <= 0 || distanceSq > maxDistanceSq) continue;

        const distance = Math.sqrt(distanceSq);
        const influence = 1 - distance / maxDistance;
        if (influence <= 0) continue;

        const nx = dx / distance;
        const ny = dy / distance;
        const attraction = influence * 0.004;
        const swirl = influence * 0.0018 * a.direction;

        this.vx += nx * attraction - ny * swirl;
        this.vy += ny * attraction + nx * swirl;
      }

      const speedSq = this.vx * this.vx + this.vy * this.vy;
      const maxSpeed = 0.14 + this.depth * 0.22;
      if (speedSq > maxSpeed * maxSpeed && speedSq > 0) {
        const scale = maxSpeed / Math.sqrt(speedSq);
        this.vx *= scale;
        this.vy *= scale;
      }
    }

    this.edges();
  };

  this.getAlpha = function () {
    const twinkle = 0.78 + Math.sin(
      frameCount * this.twinkleSpeed + this.twinkleOffset
    ) * 0.22;
    return constrain(this.alpha * twinkle, 0.04, 0.60);
  };

  this.edges = function () {
    const margin = 4;

    if (this.x > width + margin) this.x = -margin;
    else if (this.x < -margin) this.x = width + margin;

    if (this.y > height + margin) this.y = -margin;
    else if (this.y < -margin) this.y = height + margin;
  };
}
