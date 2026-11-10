package com.msd.interaction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 收藏/取消收藏请求。
 *
 * @param dramaId 短剧 ID
 */
public record FavoriteRequest(@NotNull @Positive Long dramaId) {
}
