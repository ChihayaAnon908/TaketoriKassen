package com.taketori.kassen.paper.editor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * weapons.yml 的"保留注释"文本级编辑器。
 *
 * <p><b>为什么不用 YamlConfiguration.save()</b>：那会把文件里所有注释与空行冲掉，
 * 而 weapons.yml 的注释本身就是使用说明（每个参数干什么用、单位是什么）。
 * 所以这里按缩进定位到目标行，只替换冒号后面的值，注释原样保留。</p>
 *
 * <p>定位方式：逐行扫描并维护一个"缩进栈"，把每行的 key 按层级压栈，
 * 于是 {@code weapons.iroha_sword.skills.left.params.damage} 这种路径就能唯一确定一行。
 * 数组下标不用写进路径里——路径从武器 id 开始（顶层 {@code weapons:} 会自动忽略）。</p>
 *
 * <p>纯文件操作、零 Bukkit 依赖，所以能用 {@code tools/WeaponYamlEditorTest.java} 离线回归。</p>
 */
public final class WeaponYamlEditor {

    /** 编辑结果。 */
    public record Result(boolean ok, String message) {

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern BOOLEAN = Pattern.compile("(?i)true|false");

    private final File file;

    public WeaponYamlEditor(File file) {
        this.file = file;
    }

    public File file() {
        return file;
    }

    /** 读取路径当前的值（原样，可能带引号）；找不到返回 null。 */
    public String currentValue(List<String> path) {
        List<String> lines = readLines();
        if (lines == null) {
            return null;
        }
        int index = indexOfPath(lines, path);
        return index < 0 ? null : rawValueOf(lines.get(index));
    }

    /**
     * 设置路径的值：已存在就替换（保留行内注释），不存在就在父块末尾按缩进新增。
     *
     * @param path    从武器 id 开始的路径，例如 [iroha_sword, skills, left, params, damage]
     * @param value   新值（字符串形式；数字/布尔不加引号，其余自动加单引号）
     * @param comment 新增时写在上一行的注释（可为 null）
     */
    public Result set(List<String> path, String value, String comment) {
        if (path == null || path.isEmpty()) {
            return Result.fail("路径为空");
        }
        List<String> lines = readLines();
        if (lines == null) {
            return Result.fail("读不到 weapons.yml（文件不存在或没有权限）");
        }
        String rendered = render(value);

        int index = indexOfPath(lines, path);
        if (index >= 0) {
            lines.set(index, replaceValue(lines.get(index), rendered));
            return writeLines(lines)
                    ? Result.ok("已改为 " + rendered)
                    : Result.fail("写入失败（检查文件权限）");
        }

        List<String> parent = path.subList(0, path.size() - 1);
        int parentIndex = indexOfPath(lines, parent);
        if (parentIndex < 0) {
            return Result.fail("weapons.yml 里找不到 " + String.join(".", parent)
                    + "，这一项需要手动添加");
        }
        int parentIndent = indentOf(lines.get(parentIndex));
        int insertAt = blockEnd(lines, parentIndex, parentIndent);
        String indent = " ".repeat(parentIndent + 2);
        String key = path.get(path.size() - 1);
        if (comment != null && !comment.isBlank()) {
            lines.add(insertAt, indent + "# " + comment);
            insertAt++;
        }
        lines.add(insertAt, indent + key + ": " + rendered);
        return writeLines(lines)
                ? Result.ok("已新增 " + key + ": " + rendered)
                : Result.fail("写入失败（检查文件权限）");
    }

    // ---------------------------------------------------------------- 定位

    /** 路径对应的行号；找不到返回 -1。 */
    private int indexOfPath(List<String> lines, List<String> path) {
        List<String> keys = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String key = keyOf(lines.get(i));
            if (key == null) {
                continue;
            }
            int indent = indentOf(lines.get(i));
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            keys.add(key);
            indents.add(indent);
            // 路径可以写成从武器 id 开始（推荐），也可以带上最外层的 weapons:
            if (keys.equals(path)
                    || (keys.size() == path.size() + 1 && keys.subList(1, keys.size()).equals(path))) {
                return i;
            }
        }
        return -1;
    }

    /** 父块的插入位置（父块最后一行之后）。 */
    private int blockEnd(List<String> lines, int startIndex, int parentIndent) {
        int last = startIndex + 1;
        for (int i = startIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                continue;   // 空行不算块结束，也不作为插入点
            }
            if (indentOf(line) <= parentIndent) {
                break;
            }
            last = i + 1;
        }
        return last;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** 该行的 key；空行、注释、列表项返回 null。 */
    private static String keyOf(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
            return null;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return trimmed.substring(0, colon).trim();
    }

    private static String rawValueOf(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String rest = line.substring(colon + 1);
        int hash = commentIndex(rest);
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        return rest.trim();
    }

    /** 替换值、保留行内注释与缩进。 */
    private static String replaceValue(String line, String rendered) {
        int colon = line.indexOf(':');
        String prefix = line.substring(0, colon + 1);
        String rest = line.substring(colon + 1);
        int hash = commentIndex(rest);
        String comment = hash >= 0 ? rest.substring(hash) : "";
        StringBuilder builder = new StringBuilder(prefix).append(' ').append(rendered);
        if (!comment.isEmpty()) {
            builder.append(' ').append(comment);
        }
        return builder.toString();
    }

    /** 行内注释的起点（跳过引号内的 #）。 */
    private static int commentIndex(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    /** 值渲染：数字/布尔/列表/已带引号的原样输出，其余加单引号。 */
    private static String render(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "''";
        }
        String trimmed = value.trim();
        if (NUMBER.matcher(trimmed).matches() || BOOLEAN.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (trimmed.startsWith("[") || trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            return trimmed;
        }
        return "'" + trimmed.replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------- 文件

    private List<String> readLines() {
        if (!file.exists()) {
            return null;
        }
        try {
            return new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return null;
        }
    }

    private boolean writeLines(List<String> lines) {
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
