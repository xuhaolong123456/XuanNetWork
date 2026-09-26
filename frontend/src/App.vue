<template>
  <main class="app-shell" :class="{ 'drive-shell': $route.meta.fullscreen }">
    <section class="brand-panel">
      <span class="brand-mark">K</span>
      <div>
        <p class="eyebrow">NETWORK DISK</p>
        <h1>卡码网盘</h1>
      </div>
      <p class="brand-copy">让文件井然有序，让每一次访问都轻松可靠。</p>
    </section>
    <section class="form-panel">
      <RouterView />
    </section>
  </main>
</template>
