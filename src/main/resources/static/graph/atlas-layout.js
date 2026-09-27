// Radial atlas layout: the layers are concentric orbits. Packages are arc segments on the inner orbit (fixed angles,
// sized by how many classes they hold), an open package fans its classes out on the next orbit, an open class fans its
// members on the outer orbit. Opening something never moves a package; only fans of open nodes are added.

export const RING = { package: 220, class: 380, file: 380, member: 520 };
const MIN_ARC = { class: 24, member: 21 };
const TAU = Math.PI * 2;
const FAN_GAP = 0.05;
const MAX_FAN = TAU / 3;

const polar = (angle, r) => [Math.cos(angle) * r, Math.sin(angle) * r];

/**
 * Evenly spaced angles for `n` children centred on `center`, at least `minWidth` wide and at most a third of the
 * circle (so a fan always faces one side; very large ones get denser and show all their labels when zoomed in).
 */
function fan(center, n, ring, minArc, minWidth) {
  const width = Math.min(Math.max(minWidth, (minArc / ring) * n), MAX_FAN);
  return { center, width, start: center - width / 2, step: width / n };
}

/** Pushes overlapping fans on one orbit apart, keeping their order around the circle. */
function separate(fans) {
  fans.sort((a, b) => a.center - b.center);
  for (let pass = 0; pass < 4; pass++) {
    for (let k = 1; k < fans.length; k++) {
      const prev = fans[k - 1];
      const cur = fans[k];
      const overlap = prev.start + prev.width + FAN_GAP - cur.start;
      if (overlap > 0) { prev.start -= overlap / 2; cur.start += overlap / 2; }
    }
  }
}

/**
 * @param root        project node
 * @param childrenOf  node → visible children (only for open nodes)
 * @param groupOf     package node → group key (packages sharing a key are bundled together)
 * @returns {{place: Map<string, {x, y, angle, ring, span}>, groups: Map<string, {x, y}>, groupOfId: Map<string, string>, extent: number}}
 */
export function layoutAtlas(root, childrenOf, groupOf) {
  const place = new Map();
  const groups = new Map();
  const groupOfId = new Map();
  place.set(root.id, { x: 0, y: 0, angle: -Math.PI / 2, ring: 0, span: TAU });
  const packages = childrenOf(root).slice().sort((a, b) => {
    const ga = groupOf(a);
    const gb = groupOf(b);
    return ga < gb ? -1 : ga > gb ? 1 : String(a.name).localeCompare(String(b.name));
  });
  let extent = RING.package;
  if (!packages.length) return { place, groups, groupOfId, extent };

  // inner orbit: one arc per package, length by weight, a small gap between neighbours
  const weights = packages.map((p) => 1 + Math.sqrt(p.childCount || 0));
  const total = weights.reduce((a, b) => a + b, 0);
  const gap = packages.length > 1 ? Math.min(0.03, (TAU * 0.16) / packages.length) : 0;
  const free = TAU - gap * packages.length;
  let a = -Math.PI / 2 + gap / 2;
  const members = new Map();
  packages.forEach((p, k) => {
    const span = (free * weights[k]) / total;
    const angle = a + span / 2;
    const [x, y] = polar(angle, RING.package);
    place.set(p.id, { x, y, angle, ring: RING.package, span });
    a += span + gap;
    const g = groupOf(p);
    if (!members.has(g)) members.set(g, []);
    members.get(g).push(angle);
    groupOfId.set(p.id, g);
  });
  // bundling points: packages of one group meet half way to the centre
  for (const [g, angles] of members) {
    if (angles.length < 2) continue;
    const mid = Math.atan2(angles.reduce((s, t) => s + Math.sin(t), 0), angles.reduce((s, t) => s + Math.cos(t), 0));
    const spread = Math.max(...angles) - Math.min(...angles);
    // a tight group meets just inside the orbit, a spread-out one closer to the centre
    const [x, y] = polar(mid, RING.package * Math.max(0.3, Math.cos(Math.min(spread, Math.PI) / 2) * 0.8));
    groups.set(g, { x, y });
  }
  for (const [id, g] of groupOfId) if (!groups.has(g)) groupOfId.delete(id);

  // the next orbits: fans of open nodes, one orbit per layer
  let parents = packages;
  for (const kind of ["class", "member"]) {
    const fans = [];
    for (const parent of parents) {
      const kids = childrenOf(parent);
      if (!kids.length) continue;
      const at = place.get(parent.id);
      const ring = RING[kind];
      fans.push({ parent, kids, ring, ...fan(at.angle, kids.length, ring, MIN_ARC[kind], (at.span || 0) * 0.9) });
    }
    if (!fans.length) break;
    separate(fans);
    const next = [];
    for (const f of fans) {
      f.kids.forEach((child, k) => {
        const angle = f.start + f.step * (k + 0.5);
        const [x, y] = polar(angle, f.ring);
        place.set(child.id, { x, y, angle, ring: f.ring, span: f.step });
        next.push(child);
      });
      extent = Math.max(extent, f.ring);
    }
    parents = next;
  }
  return { place, groups, groupOfId, extent };
}
