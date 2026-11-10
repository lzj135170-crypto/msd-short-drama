package com.msd.drama.subtitle;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT/VTT 字幕解析器。
 *
 * <p>只解析时间轴和文本，不依赖第三方库；无法解析的块直接跳过，不中断整份字幕。</p>
 */
@Component
public class SubtitleParser {

    /** 时间轴：可选小时，毫秒分隔符支持逗号和点。 */
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})\\s*-->\\s*"
                    + "(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})");
    /** VTT 内联标签。 */
    private static final Pattern VTT_TAG = Pattern.compile("<[^>]+>");

    /**
     * 按格式解析字幕。
     *
     * @param format SRT/VTT/ASR
     * @param content 字幕文本
     * @return 按开始时间升序的片段
     */
    public List<SubtitleCue> parse(String format, String content) {
        // 1. 归一化换行和 BOM
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1);
        }
        String type = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        if ("ASR".equals(type)) {
            return List.of();
        }

        // 2. 按空行切块，逐块找时间轴和文本
        Set<String> seen = new LinkedHashSet<>();
        List<SubtitleCue> cues = new ArrayList<>();
        for (String block : normalized.split("\\n\\s*\\n")) {
            SubtitleCue cue = parseBlock(block, seen);
            if (cue != null) {
                cues.add(cue);
            }
        }
        cues.sort((left, right) -> Integer.compare(left.startMs(), right.startMs()));
        return List.copyOf(cues);
    }

    /**
     * 解析单个字幕块。
     */
    private SubtitleCue parseBlock(String block, Set<String> seen) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String[] lines = block.split("\n");
        int timeIndex = -1;
        Matcher matcher = null;
        for (int index = 0; index < lines.length; index += 1) {
            Matcher candidate = TIME_RANGE.matcher(lines[index]);
            if (candidate.find()) {
                timeIndex = index;
                matcher = candidate;
                break;
            }
        }
        if (timeIndex < 0 || matcher == null) {
            return null;
        }

        // 1. 时间轴转毫秒，结束不早于开始
        int startMs = toMillis(matcher.group(1), matcher.group(2), matcher.group(3),
                matcher.group(4));
        int endMs = toMillis(matcher.group(5), matcher.group(6), matcher.group(7),
                matcher.group(8));
        if (startMs < 0 || endMs <= startMs) {
            return null;
        }

        // 2. 拼接时间轴之后的文本，去掉 VTT 标签和首尾空白
        StringBuilder text = new StringBuilder();
        for (int index = timeIndex + 1; index < lines.length; index += 1) {
            String line = VTT_TAG.matcher(lines[index]).replaceAll("").trim();
            if (!line.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(line);
            }
        }
        if (text.isEmpty()) {
            return null;
        }

        // 3. 同一时间轴和文本只保留一次
        String key = startMs + "|" + endMs + "|" + text;
        if (!seen.add(key)) {
            return null;
        }
        return new SubtitleCue(startMs, endMs, text.toString());
    }

    /**
     * 解析时间并转毫秒；小时可省略。
     */
    private int toMillis(String hours, String minutes, String seconds, String millis) {
        try {
            int hour = hours == null ? 0 : Integer.parseInt(hours);
            int minute = Integer.parseInt(minutes);
            int second = Integer.parseInt(seconds);
            int milli = Integer.parseInt(millis);
            return ((hour * 60 + minute) * 60 + second) * 1000 + milli;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
