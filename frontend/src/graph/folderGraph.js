export function buildFolderGraph(folders) {
  const root = { id: 'root', folderId: null, name: '我的文件', depth: 0, path: ['我的文件'], children: [] }
  const nodes = [root]
  const edges = []
  const levelCounts = new Map([[0, 1]])

  function append(parent, item) {
    const node = {
      id: String(item.id), folderId: item.id, name: item.name,
      depth: parent.depth + 1, path: [...parent.path, item.name], children: []
    }
    parent.children.push(node)
    nodes.push(node)
    edges.push({ from: parent.id, to: node.id })
    levelCounts.set(node.depth, (levelCounts.get(node.depth) || 0) + 1)
    for (const child of item.children || []) append(node, child)
  }

  for (const folder of folders) append(root, folder)

  function countLeaves(node) {
    node.weight = node.children.length
      ? node.children.reduce((total, child) => total + countLeaves(child), 0)
      : 1
    return node.weight
  }
  countLeaves(root)

  const maxDepth = Math.max(...levelCounts.keys())
  const radiusAt = depth => Math.max(depth * 175, (levelCounts.get(depth) || 1) * 88 / (2 * Math.PI))
  const maxRadius = radiusAt(maxDepth)
  const width = Math.max(1400, Math.ceil((maxRadius + 250) * 2))
  const height = Math.max(900, Math.ceil((maxRadius + 200) * 2))
  root.x = width / 2
  root.y = height / 2

  function placeChildren(parent, start, end) {
    let cursor = start
    for (const child of parent.children) {
      const span = (end - start) * child.weight / parent.weight
      const angle = cursor + span / 2
      const radius = radiusAt(child.depth)
      child.x = width / 2 + Math.cos(angle) * radius
      child.y = height / 2 + Math.sin(angle) * radius
      placeChildren(child, cursor, cursor + span)
      cursor += span
    }
  }

  // 单根目录集中在画布上方，多根目录围绕中心分布；父子边始终由真实层级决定。
  const sector = root.children.length === 1 ? [-Math.PI, 0] : [-Math.PI * 1.5, Math.PI / 2]
  placeChildren(root, ...sector)

  return { nodes, edges, width, height, folderCount: nodes.length - 1, maxDepth }
}
