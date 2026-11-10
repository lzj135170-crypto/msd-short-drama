<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../components/AuthLayout.vue'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'

const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()
const route = useRoute()

const form = reactive({
    phone: '',
    password: ''
})
const loading = ref(false)
const errorMessage = ref('')

const demo = {
    phone: '13800000003',
    password: 'Msd@123456'
}

/**
 * 填入演示账号，方便本地验收。
 */
function fillDemo() {
    form.phone = demo.phone
    form.password = demo.password
    errorMessage.value = ''
}

/**
 * 只允许站内相对路径回跳，避免开放重定向。
 */
function redirectTarget() {
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    return target.startsWith('/') && !target.startsWith('//') ? target : '/'
}

/**
 * 校验表单并登录。
 */
async function submit() {
    errorMessage.value = ''

    // 1. 前端先做基础校验，减少无效请求
    if (!/^1[3-9]\d{9}$/.test(form.phone)) {
        errorMessage.value = '请输入正确的手机号'
        return
    }
    if (!form.password) {
        errorMessage.value = '请输入密码'
        return
    }

    // 2. 调用登录接口，成功后回到目标页面
    loading.value = true
    try {
        await auth.login({
            phone: form.phone,
            password: form.password
        })
        toast.success(`欢迎回来，${auth.displayName}`)
        await router.replace(redirectTarget())
    } catch (error) {
        errorMessage.value = error.message
    } finally {
        loading.value = false
    }
}
</script>

<template>
    <AuthLayout
        eyebrow="WELCOME BACK"
        title="登录码上短剧"
        subtitle="用手机号和密码继续你的追剧进度。"
    >
        <form class="form" novalidate @submit.prevent="submit">
            <label class="field">
                <span>手机号</span>
                <input
                    v-model.trim="form.phone"
                    inputmode="numeric"
                    maxlength="11"
                    autocomplete="username"
                    placeholder="请输入手机号"
                    @input="errorMessage = ''"
                >
            </label>

            <label class="field">
                <span>密码</span>
                <input
                    v-model="form.password"
                    type="password"
                    maxlength="32"
                    autocomplete="current-password"
                    placeholder="请输入密码"
                    @input="errorMessage = ''"
                >
            </label>

            <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

            <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
                {{ loading ? '登录中…' : '登录' }}
            </button>
        </form>

        <div class="demo-box">
            <div class="demo-copy">
                <strong>演示账号</strong>
                <span>13800000003 / Msd@123456</span>
                <small>用户 1003 · 已解锁剧集 · 余额 250 积分</small>
            </div>
            <button class="btn btn-ghost btn-small" type="button" @click="fillDemo">一键填入</button>
        </div>

        <p class="switch-line">
            还没有账号？<RouterLink to="/register">立即注册</RouterLink>
        </p>
    </AuthLayout>
</template>
