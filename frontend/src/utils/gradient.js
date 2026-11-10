/**
 * FNV-1a：同一个种子必须得到同一套封面配色。
 */
export function hashSeed(seed) {
    const text = String(seed ?? '')
    let hash = 2166136261
    for (let index = 0; index < text.length; index += 1) {
        hash ^= text.charCodeAt(index)
        hash = Math.imul(hash, 16777619)
    }
    return hash >>> 0
}

/**
 * 由种子推导封面颜色，避免依赖远程图片。
 */
export function coverPalette(seed) {
    const hash = hashSeed(seed)
    const hue = hash % 360
    const hue2 = (hue + 28 + ((hash >>> 9) % 70)) % 360
    return {
        angle: 108 + ((hash >>> 17) % 58),
        from: `hsl(${hue} 62% 38%)`,
        via: `hsl(${hue2} 70% 30%)`,
        to: `hsl(${(hue2 + 42) % 360} 55% 14%)`,
        glow: `hsla(${hue2}, 85%, 62%, 0.45)`,
        mark: `hsl(${(hue + 12) % 360} 90% 72%)`
    }
}
