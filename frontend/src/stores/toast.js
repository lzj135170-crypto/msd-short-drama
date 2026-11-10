import { defineStore } from 'pinia'

let sequence = 0

export const useToastStore = defineStore('toast', {
    state: () => ({
        items: []
    }),

    actions: {
        /**
         * 追加一条提示，4.2 秒后自动消失。
         */
        push(message, type = 'error') {
            const text = String(message || '').trim()
            if (!text) {
                return
            }
            const id = ++sequence
            this.items.push({ id, message: text, type })
            window.setTimeout(() => this.dismiss(id), 4200)
        },

        success(message) {
            this.push(message, 'success')
        },

        dismiss(id) {
            this.items = this.items.filter((item) => item.id !== id)
        }
    }
})
