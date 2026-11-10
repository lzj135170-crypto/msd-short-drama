<script setup>
defineProps({
    eyebrow: {
        type: String,
        default: 'WELCOME BACK'
    },
    title: {
        type: String,
        required: true
    },
    subtitle: {
        type: String,
        default: ''
    }
})
</script>

<template>
    <main class="auth-page">
        <section class="auth-brand">
            <div class="brand-glow" aria-hidden="true"></div>

            <div class="brand-top">
                <span class="brand-logo">MSD</span>
                <span class="brand-name">码上短剧</span>
            </div>

            <div class="brand-copy">
                <span class="eyebrow">SHORT DRAMA · ALWAYS ON</span>
                <h1>每一段深夜，<br>都有一部短剧陪你。</h1>
                <p>登录后继续追剧，点赞、收藏和观看记录都会为你保留。</p>
            </div>

            <ul class="brand-points">
                <li><span>01</span>精选短剧，即点即看</li>
                <li><span>02</span>积分解锁，支持续看</li>
                <li><span>03</span>点赞收藏，留下足迹</li>
            </ul>

            <p class="brand-foot">码上短剧 · 微服务演示项目</p>
        </section>

        <section class="auth-side">
            <div class="auth-card">
                <header class="auth-head">
                    <span class="eyebrow">{{ eyebrow }}</span>
                    <h2>{{ title }}</h2>
                    <p>{{ subtitle }}</p>
                </header>
                <slot/>
            </div>
            <p class="auth-side-foot">登录即表示你同意本地演示环境的使用约定</p>
        </section>
    </main>
</template>
