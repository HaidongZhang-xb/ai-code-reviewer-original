package com.zhanghaidong.reviewer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
/**
 * Patch 位置解析器
 *
 * 作用: 把"新文件行号"换算成"diff 文本里的 position 序号"
 * Gitee 行级评论 API 需要 position 而非 lineNumber
 *
 * Patch 格式示例:
 * <pre>
 * @@ -10,5 +10,7 @@           ← 第 1 行(position=1)
 *  context line              ← 第 2 行(对应新文件第 10 行)
 *  context line              ← 第 3 行(对应新文件第 11 行)
 * +added line               ← 第 4 行(对应新文件第 12 行)
 * +added line               ← 第 5 行(对应新文件第 13 行)
 *  context line              ← 第 6 行(对应新文件第 14 行)
 * -removed line             ← 第 7 行(对应旧文件,新文件无对应行号)
 * </pre>
 *
 * @author 张海东
 */
@Slf4j
@Component
public class PatchPositionResolver {

    /**
     * 计算从「新文件行号」到「diff position」的映射表
     *
     * @param patchText 标准 unified diff 文本
     * @return Map<新文件行号, diff position>
     */
    public Map<Integer, Integer> buildLineToPositionMap(String patchText) {
        Map<Integer, Integer> map = new HashMap<>();
        if (patchText == null || patchText.isBlank()) {
            return map;
        }

        String[] lines = patchText.split("\n");
        int position = 0;       // diff 中的位置(从 1 开始,跟 GitLab/Gitee 约定一致)
        int newFileLine = 0;    // 当前对应的新文件行号

        for (String line : lines) {
            position++;

            if (line.startsWith("@@")) {
                // hunk header: @@ -10,5 +12,7 @@
                // 解析 +12 这部分,作为新文件起始行号-1(下一行才是 12)
                Integer newStart = parseNewStart(line);
                if (newStart == null) {
                    log.warn("无法解析 hunk header: {}", line);
                    continue;
                }
                newFileLine = newStart - 1;
                continue;
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                // 新增行
                newFileLine++;
                map.put(newFileLine, position);
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // 删除行,新文件没有对应行号,跳过
            } else if (line.startsWith(" ")) {
                // 上下文行
                newFileLine++;
                map.put(newFileLine, position);
            }
            // 其他(\ No newline at end of file 等)不处理
        }

        return map;
    }

    /**
     * 解析 hunk header,返回新文件起始行号
     * 例如 "@@ -10,5 +12,7 @@" → 12
     */
    private Integer parseNewStart(String hunkHeader) {
        try {
            int plusIdx = hunkHeader.indexOf('+');
            if (plusIdx < 0) return null;
            int spaceIdx = hunkHeader.indexOf(' ', plusIdx);
            if (spaceIdx < 0) spaceIdx = hunkHeader.length();
            String segment = hunkHeader.substring(plusIdx + 1, spaceIdx); // 例如 "12,7"
            String startStr = segment.contains(",") ? segment.substring(0, segment.indexOf(",")) : segment;
            return Integer.parseInt(startStr.trim());
        } catch (Exception e) {
            log.warn("解析 hunk header 失败: {}", hunkHeader, e);
            return null;
        }
    }

    /**
     * 直接查询某个文件行号对应的 position,找不到返回 null
     */
    public Integer resolvePosition(String patchText, int newFileLine) {
        Map<Integer, Integer> map = buildLineToPositionMap(patchText);
        return map.get(newFileLine);
    }
    /**
     * 用 snippet 在 patch 中精确定位 position
     *
     * 策略:
     *  1. 在 patch 的"新增行(+)"和"上下文行( )"中搜索 snippet
     *  2. 优先匹配新增行(+),因为评审一般针对新代码
     *  3. trim 后比较,容忍前后空格差异
     *  4. 找不到则降级到 LLM 给的 lineNumber
     *
     * @param patchText  patch 文本
     * @param snippet    LLM 给出的代码片段
     * @return 命中的 (newFileLine, position),都找不到返回 null
     */
    public LineAndPosition resolveBySnippet(String patchText, String snippet) {
        if (patchText == null || snippet == null || snippet.isBlank()) {
            return null;
        }

        String target = snippet.trim();
        // 防止 snippet 太短匹配错(比如 "}" 这种),要求至少 10 个非空白字符
        if (target.replaceAll("\\s+", "").length() < 10) {
            log.debug("snippet 太短,跳过反查: {}", snippet);
            return null;
        }

        String[] lines = patchText.split("\n");
        int position = 0;
        int newFileLine = 0;

        LineAndPosition firstAddedHit = null;  // 优先记录新增行(+)的命中
        LineAndPosition firstContextHit = null; // 退而求其次记录上下文行的命中

        for (String line : lines) {
            position++;

            if (line.startsWith("@@")) {
                Integer newStart = parseNewStart(line);
                if (newStart != null) newFileLine = newStart - 1;
                continue;
            }

            boolean isAdded = line.startsWith("+") && !line.startsWith("+++");
            boolean isContext = line.startsWith(" ");
            boolean isRemoved = line.startsWith("-") && !line.startsWith("---");

            if (isAdded || isContext) {
                newFileLine++;
                // 去掉行首的 + 或 空格
                String content = line.substring(1).trim();
                if (!content.isEmpty() && content.contains(target)) {
                    LineAndPosition hit = new LineAndPosition(newFileLine, position);
                    if (isAdded && firstAddedHit == null) {
                        firstAddedHit = hit;
                    } else if (isContext && firstContextHit == null) {
                        firstContextHit = hit;
                    }
                }
            }
            // removed 行不参与新文件行号计数
        }

        LineAndPosition result = firstAddedHit != null ? firstAddedHit : firstContextHit;
        if (result != null) {
            log.debug("snippet 匹配成功: snippet=[{}] -> line={}, position={}",
                    target, result.line, result.position);
        }
        return result;
    }

    /**
     * 简单值对象
     */
    public record LineAndPosition(int line, int position) {}
    /**
     * 提取 patch 中被改动的所有新文件行号
     * 包括: 新增行(+) 和 删除行所在的位置(用最近的上下文行号近似表达)
     *
     * @return 改动行号集合
     */
    public Set<Integer> extractChangedLines(String patchText) {
        Set<Integer> changed = new HashSet<>();
        if (patchText == null || patchText.isBlank()) return changed;

        String[] lines = patchText.split("\n");
        int newFileLine = 0;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                Integer newStart = parseNewStart(line);
                if (newStart != null) newFileLine = newStart - 1;
                continue;
            }

            boolean isAdded = line.startsWith("+") && !line.startsWith("+++");
            boolean isContext = line.startsWith(" ");
            boolean isRemoved = line.startsWith("-") && !line.startsWith("---");

            if (isAdded) {
                newFileLine++;
                changed.add(newFileLine);   // 新增行算改动
            } else if (isContext) {
                newFileLine++;              // 上下文行,不算改动但要计数
            } else if (isRemoved) {
                // 删除行不增加行号,但把"前一行"标记为受影响
                // 这样 JavaContextExtractor 能定位到删除发生的位置
                if (newFileLine > 0) changed.add(newFileLine);
            }
        }
        return changed;
    }
}