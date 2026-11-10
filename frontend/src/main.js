import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import './styles/base.css'

// 1. 创建应用和 Pinia，先恢复本地会话再挂载路由
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

const auth = useAuthStore(pinia)
auth.restore()

app.use(router)

// 2. 任意受保护接口返回 401 时，清理会话并回到登录页；并行请求只跳一次
let redirecting = false
window.addEventListener('msd:unauthorized', () => {
    const current = router.currentRoute.value
    auth.clear()
    if (current.name === 'login' || current.name === 'register' || redirecting) {
        return
    }
    redirecting = true
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
        .finally(() => {
            redirecting = false
        })
})

app.mount('#app')
