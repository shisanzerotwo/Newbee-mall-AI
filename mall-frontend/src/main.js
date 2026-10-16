import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'

// Vant 按需引入（M1 用到的组件）
import {
  Tabbar, TabbarItem, Search, Button, Tag, List, PullRefresh, NavBar,
  Field, Uploader, RadioGroup, Radio, Toast, Dialog, Badge, Empty,
  ImagePreview, ActionSheet, Cell, CellGroup, Grid, GridItem, Icon
} from 'vant'
import 'vant/lib/index.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

;[
  Tabbar, TabbarItem, Search, Button, Tag, List, PullRefresh, NavBar,
  Field, Uploader, RadioGroup, Radio, Toast, Dialog, Badge, Empty,
  ImagePreview, ActionSheet, Cell, CellGroup, Grid, GridItem, Icon
].forEach(c => app.use(c))

app.mount('#app')
