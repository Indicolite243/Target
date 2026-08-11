import { createApp } from 'vue'
import { createPinia } from 'pinia' // 1. 导入Pinia
import ElementPlus from 'element-plus' // 如果你用Element Plus
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router' // 路由文件

// 2. 先创建Pinia实例
const pinia = createPinia()

// 3. 创建Vue应用
const app = createApp(App)

// 4. 优先注册Pinia（必须在router和mount之前！）
app.use(pinia)
app.use(ElementPlus) // 注册UI库
app.use(router) // 注册路由

// 5. 最后挂载应用
app.mount('#app')
