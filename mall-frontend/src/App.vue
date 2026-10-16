<template>
  <router-view />
  <AppTabBar v-if="route.meta.tab" />
</template>

<script setup>
import { useRoute } from 'vue-router'
import AppTabBar from './components/AppTabBar.vue'
const route = useRoute()
</script>
