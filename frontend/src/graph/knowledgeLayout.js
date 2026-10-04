export function layoutKnowledgeGraph(nodes, edges, width, height) {
  if (!nodes.length) return []
  const centerX = width / 2
  const centerY = height / 2
  const radius = Math.min(width * 0.37, height * 0.38, 420)
  const positions = new Map(nodes.map((node, index) => {
    const angle = (index / nodes.length) * Math.PI * 2 - Math.PI / 2
    return [node.id, { ...node, x: centerX + Math.cos(angle) * radius, y: centerY + Math.sin(angle) * radius }]
  }))

  for (let step = 0; step < 70; step += 1) {
    const forces = new Map(nodes.map(node => [node.id, { x: (centerX - positions.get(node.id).x) * 0.004, y: (centerY - positions.get(node.id).y) * 0.004 }]))
    for (let left = 0; left < nodes.length; left += 1) {
      for (let right = left + 1; right < nodes.length; right += 1) {
        const a = positions.get(nodes[left].id)
        const b = positions.get(nodes[right].id)
        const dx = b.x - a.x || 0.01
        const dy = b.y - a.y || 0.01
        const distance = Math.max(20, Math.hypot(dx, dy))
        const force = 950 / (distance * distance)
        forces.get(a.id).x -= dx / distance * force
        forces.get(a.id).y -= dy / distance * force
        forces.get(b.id).x += dx / distance * force
        forces.get(b.id).y += dy / distance * force
      }
    }
    for (const edge of edges) {
      const a = positions.get(edge.source)
      const b = positions.get(edge.target)
      if (!a || !b) continue
      const dx = b.x - a.x
      const dy = b.y - a.y
      const distance = Math.max(1, Math.hypot(dx, dy))
      const force = (distance - 145) * 0.0018
      forces.get(a.id).x += dx / distance * force
      forces.get(a.id).y += dy / distance * force
      forces.get(b.id).x -= dx / distance * force
      forces.get(b.id).y -= dy / distance * force
    }
    const cooling = 1 - step / 85
    for (const node of nodes) {
      const point = positions.get(node.id)
      const force = forces.get(node.id)
      point.x = Math.max(45, Math.min(width - 45, point.x + force.x * cooling))
      point.y = Math.max(45, Math.min(height - 45, point.y + force.y * cooling))
    }
  }
  return [...positions.values()]
}
