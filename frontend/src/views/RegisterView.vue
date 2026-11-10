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
    nickname: '',
    password: '',
    confirmPassword: ''
})
const loading = ref(false)
const errorMessage = ref('')

/**
 * 只允许站内相对路径回跳，避免开放重定向。
 */
function redirectTarget() {
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    return target.startsWith('/') && !target.startsWith('//') ? target : '/'
}

/**
 * 校验表单并注册，注册成功后自动登录。
 */
async function submit() {
    errorMessage.value = ''

    // 1. 手机号格式
    if (!/^1[3-9]\d{9}$/.test(form.phone)) {
        errorMessage.value = '请输入正确的手机号'
        return
    }

    // 2. 密码长度和字符类型与后端保持一致
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(form.password)) {
        errorMessage.value = '密码需为 8-32 位，且同时包含字母和数字'
        return
    }

    // 3. 两次密码一致
    if (form.password !== form.confirmPassword) {
        errorMessage.value = '两次输入的密码不一致'
        return
    }

    // 4. 调用注册接口，后端会直接返回登录 Token
    loading.value = true
    try {
        await auth.register({
            phone: form.phone,
            password: form.password,
            nickname: form.nickname.trim() || undefined
        })
        toast.success('注册成功，已自动登录')
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
        eyebrow="JOIN US"
        title="注册新账号"
        subtitle="手机号就是账号，注册后立即开始追剧。"
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
                <span>昵称（可选）</span>
                <input
                    v-model.trim="form.nickname"
                    maxlength="20"
                    autocomplete="nickname"
                    placeholder="不填则使用「剧迷 + 手机号后四位」"
                    @input="errorMessage = ''"
                >
            </label>

            <div class="field-grid">
                <label class="field">
                    <span>密码</span>
                    <input
                        v-model="form.password"
                        type="password"
                        maxlength="32"
                        autocomplete="new-password"
                        placeholder="8-32 位，含字母和数字"
                        @input="errorMessage = ''"
                    >
                </label>

                <label class="field">
                    <span>确认密码</span>
                    <input
                        v-model="form.confirmPassword"
                        type="password"
                        maxlength="32"
                        autocomplete="new-password"
                        placeholder="再次输入密码"
                        @input="errorMessage = ''"
                    >
                </label>
            </div>

            <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

            <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
                {{ loading ? '注册中…' : '注册并登录' }}
            </button>
        </form>

        <p class="hint-box">
            新账号可观看免费剧集；积分解锁请使用演示账号 13800000003。
        </p>

        <p class="switch-line">
            已经有账号？<RouterLink to="/login">返回登录</RouterLink>
        </p>
    </AuthLayout>
</template>
