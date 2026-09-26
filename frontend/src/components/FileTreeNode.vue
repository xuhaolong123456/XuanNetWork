<template>
  <div class="tree-node" :style="{ '--tree-depth': depth }">
    <div class="tree-item" :class="{ active: selected }"
         role="treeitem" :aria-expanded="node.type === 'DIRECTORY' ? node.expanded : undefined"
         :aria-selected="selected">
      <button v-if="node.type === 'DIRECTORY'" class="tree-toggle" type="button"
              :aria-label="node.expanded ? `收起${node.name}` : `展开${node.name}`"
              :aria-expanded="node.expanded" :disabled="node.loading"
              @click="emit('toggle', node)">
        <span aria-hidden="true">{{ node.loading ? '·' : (node.expanded ? '⌄' : '›') }}</span>
      </button>
      <span v-else class="tree-toggle-placeholder" aria-hidden="true"></span>
      <button class="tree-entry" type="button" :title="node.name" @click="emit('activate', node)">
        <span class="tree-node-icon" :class="node.type === 'DIRECTORY' ? 'directory' : 'file'" aria-hidden="true">
          {{ node.type === 'DIRECTORY' ? '📁' : '📄' }}
        </span>
        <span class="tree-label">{{ node.name }}</span>
      </button>
    </div>
    <div v-if="node.type === 'DIRECTORY' && node.expanded" class="tree-children" role="group">
      <span v-if="node.loading" class="tree-message" role="status">读取中…</span>
      <button v-else-if="node.error" class="tree-message tree-retry" type="button" @click="emit('retry', node)">
        加载失败，点击重试
      </button>
      <span v-else-if="node.childrenLoaded && !node.children.length" class="tree-message">空文件夹</span>
      <FileTreeNode v-for="child in node.children" v-else :key="child.id" :node="child" :depth="depth + 1"
                    :selected-id="selectedId" @toggle="emit('toggle', $event)"
                    @activate="emit('activate', $event)" @retry="emit('retry', $event)" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

defineOptions({ name: 'FileTreeNode' })

const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  selectedId: { type: [String, Number], default: null }
})

const emit = defineEmits(['toggle', 'activate', 'retry'])
const selected = computed(() => String(props.node.id ?? 'root') === String(props.selectedId ?? 'root'))
</script>
