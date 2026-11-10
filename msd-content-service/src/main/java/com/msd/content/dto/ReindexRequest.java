package com.msd.content.dto;

/**
 * 索引重建请求。
 *
 * @param allowEmptyCatalog 短剧目录为空时是否仍继续重建；null 视为 false
 */
public record ReindexRequest(
        Boolean allowEmptyCatalog
) {
}
