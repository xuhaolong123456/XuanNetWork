import { csrfHeaders } from './auth.js'

async function request(path, options = {}) {
  const response = await fetch(`/api/knowledge${path}`, {
    credentials: 'same-origin',
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) }
  })
  if (response.status === 204) return null
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '知识图谱操作失败，请稍后重试')
    error.status = response.status
    throw error
  }
  return result.data
}

export const listKnowledgeNodes = () => request('/nodes')
export const listKnowledgeEdges = () => request('/edges')

export const createKnowledgeNode = async data => request('/node', {
  method: 'POST', headers: await csrfHeaders(), body: JSON.stringify(data)
})

export const updateKnowledgeNode = async (id, data) => request(`/node/${encodeURIComponent(id)}`, {
  method: 'PUT', headers: await csrfHeaders(), body: JSON.stringify(data)
})

export const deleteKnowledgeNode = async id => request(`/node/${encodeURIComponent(id)}`, {
  method: 'DELETE', headers: await csrfHeaders()
})

export const createKnowledgeEdge = async data => request('/edge', {
  method: 'POST', headers: await csrfHeaders(), body: JSON.stringify(data)
})

export const deleteKnowledgeEdge = async id => request(`/edge/${encodeURIComponent(id)}`, {
  method: 'DELETE', headers: await csrfHeaders()
})
