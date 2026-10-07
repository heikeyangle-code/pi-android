// 生成 `app/src/test/resources/pi-html-fixtures/cases.json` 的**第二步**：拿 App 真正
// pin 住的那个解析器（`org.jetbrains:markdown-jvm:0.7.9` + `GFMFlavourDescriptor`，与
// 库 `multiplatform-markdown-renderer 0.45.0` 的 POM 一致）把每个 case 的节点跨度、
// 节点类型全集、以及"真实消息语料"的节点类型集合补上。
//
// 用法（jar 从 Maven Central 拉，与 tools/run-app-pure-checks.sh 的拉法一致）：
//   java -cp md-0.7.9.jar:kotlin-stdlib.jar HtmlFixtureSpans.java \
//        /tmp/pi-html-pitext.json <repo-root> > cases.json
//
// 为什么跨度必须由**这个**解析器产生：`PiHtml.blockTextAt(content, start, end)` 拿到的
// start/end 就是这里量出来的那两个数。手写跨度等于凭空假设解析器的行为，而这个假设错了
// 只会表现为"手机上少画一段 HTML"，不会报错。
import org.intellij.markdown.IElementType;
import org.intellij.markdown.ast.ASTNode;
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.parser.MarkdownParser;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class HtmlFixtureSpans {

    static final String PARSER = "org.jetbrains:markdown-jvm:0.7.9 + GFMFlavourDescriptor";
    static final String PI_TUI = "1.0.1";

    public static void main(String[] args) throws Exception {
        Path piJson = Path.of(args[0]);
        Path repo = Path.of(args[1]);
        String piRaw = Files.readString(piJson, StandardCharsets.UTF_8);

        List<Map<String, Object>> cases = new ArrayList<>();
        Map<String, Object> root = (Map<String, Object>) new MiniJson(piRaw).parse();
        for (Object o : (List<?>) root.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            String source = (String) c.get("source");
            String piText = (String) c.get("piText");
            cases.add(buildCase(name, source, piText));
        }

        // 真实消息语料：仓库自己的文档（与 `PiLatexCheck` 的 `dollarProse` 用同一类来源）。
        // 每一段是"连续非空行"的一块，长度上限 400 字符，按固定步长抽样，可重复。
        List<Map<String, Object>> corpus = corpus(repo);
        require(corpus.size() >= 100, "反向检查语料不足 100 条：" + corpus.size());

        Set<String> universe = typeUniverse();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("\t\"piTui\": ").append(json(PI_TUI)).append(",\n");
        sb.append("\t\"parser\": ").append(json(PARSER)).append(",\n");
        sb.append("\t\"generatedBy\": ").append(json(
                "app/src/test/resources/pi-html-fixtures/collect-pi-html-pitext.mjs（期望值是 pi 自己的 Markdown.render 输出，不是手抄）"
                + " + app/src/test/resources/pi-html-fixtures/HtmlFixtureSpans.java（跨度与类型全集由 pin 住的解析器量出）")).append(",\n");
        sb.append("\t\"cases\": ").append(jsonArray(cases)).append(",\n");
        // 归一样本与 trim 样本由第一步原样带过来（它们是 pi 的 JS 行为，解析器不参与）。
        sb.append("\t\"normalizeCases\": ").append(jsonArray((List<?>) root.get("normalizeCases"))).append(",\n");
        sb.append("\t\"trimCases\": ").append(jsonArray((List<?>) root.get("trimCases"))).append(",\n");
        sb.append("\t\"jsTrimRanges\": ").append(jsonArray((List<?>) root.get("jsTrimRanges"))).append(",\n");
        sb.append("\t\"corpus\": ").append(jsonArray(corpus)).append(",\n");
        sb.append("\t\"corpusWithHtml\": ").append(jsonArray(CORPUS_WITH_HTML)).append(",\n");
        sb.append("\t\"typeUniverse\": ").append(jsonArray(new ArrayList<>(universe))).append("\n");
        sb.append("}\n");
        System.out.print(sb);
    }

    /** 一条 case：解析器看到的节点跨度 + pi 的输出。 */
    static Map<String, Object> buildCase(String name, String source, String piText) {
        GFMFlavourDescriptor flavour = new GFMFlavourDescriptor();
        ASTNode root = new MarkdownParser(flavour).buildMarkdownTreeFromString(source);
        List<int[]> blocks = new ArrayList<>();
        List<int[]> tags = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        collect(root, blocks, tags, types);

        List<ASTNode> topLevel = new ArrayList<>();
        for (ASTNode child : root.getChildren()) {
            if (!child.getType().getName().equals("EOL")) topLevel.add(child);
        }
        String kind;
        if (blocks.size() == 1 && topLevel.size() == 1 && topLevel.get(0).getType().getName().equals("HTML_BLOCK")) {
            kind = "block-only";
        } else if (!blocks.isEmpty() || !tags.isEmpty()) {
            kind = "html";
        } else {
            kind = "none";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("kind", kind);
        out.put("source", source);
        out.put("piText", piText);
        out.put("blocks", spans(blocks));
        out.put("tags", spans(tags));
        out.put("types", new ArrayList<>(types));
        // 语料那一侧的自证：`blocks`/`tags` 的跨度必须真的落在源里，且 `kind=block-only`
        // 的那一条必须是"解析器眼里除 EOL 外只有这一个块" —— 否则断言强度是假的。
        // 注意块**之前/之后**可以只剩空白：解析器把结尾那个换行留在 HTML_BLOCK 之外
        // （`<div>x</div>\n` 的跨度为 [0,14)），而 `trim()` 会把它去掉，两端一致。
        for (int[] b : blocks) {
            require(b[0] >= 0 && b[1] <= source.length() && b[0] < b[1],
                    name + ": 块级跨度越界 " + Arrays.toString(b));
        }
        for (int[] t : tags) {
            require(t[0] >= 0 && t[1] <= source.length() && t[0] < t[1],
                    name + ": 行内跨度越界 " + Arrays.toString(t));
        }
        if (kind.equals("block-only")) {
            require(blocks.get(0)[0] == 0 && source.substring(blocks.get(0)[1]).isBlank(),
                    name + ": block-only 的块之前/之后还有正文 " + Arrays.toString(blocks.get(0)));
        }
        return out;
    }

    static void collect(ASTNode node, List<int[]> blocks, List<int[]> tags, Set<String> types) {
        String typeName = node.getType().getName();
        types.add(typeName);
        if (typeName.equals("HTML_BLOCK")) {
            blocks.add(new int[] { node.getStartOffset(), node.getEndOffset() });
        }
        if (typeName.equals("HTML_TAG")) {
            tags.add(new int[] { node.getStartOffset(), node.getEndOffset() });
        }
        for (ASTNode child : node.getChildren()) collect(child, blocks, tags, types);
    }

    static List<Object> spans(List<int[]> list) {
        List<Object> out = new ArrayList<>();
        for (int[] s : list) out.add(Arrays.asList(s[0], s[1]));
        return out;
    }

    /**
     * 解析器能产出的**所有**节点类型名。用反射把 jar 里每个 `*Types` 持有者的所有
     * `IElementType` 成员取出来 —— 这份全集是"我们只认这两个名字"那句断言的样本空间：
     * 少一个类型，断言就少覆盖一条路径。
     */
    static Set<String> typeUniverse() throws Exception {
        Set<String> names = new TreeSet<>();
        Set<String> holders = new TreeSet<>();
        URL jar = IElementType.class.getProtectionDomain().getCodeSource().getLocation();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.getFile())) {
            jf.stream().forEach((e) -> {
                String n = e.getName();
                if (n.endsWith("Types.class") && !n.contains("$")) {
                    holders.add(n.substring(0, n.length() - ".class".length()).replace('/', '.'));
                }
            });
        }
        URLClassLoader loader = new URLClassLoader(new URL[] { jar }, HtmlFixtureSpans.class.getClassLoader());
        for (String holder : holders) {
            Class<?> cls = Class.forName(holder, false, loader);
            for (Field f : cls.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && IElementType.class.isAssignableFrom(f.getType())) {
                    names.add(((IElementType) f.get(null)).getName());
                }
            }
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 0 && IElementType.class.isAssignableFrom(m.getReturnType())) {
                    IElementType t = (IElementType) m.invoke(null);
                    if (t != null) names.add(t.getName());
                }
            }
        }
        require(names.contains("HTML_TAG") && names.contains("HTML_BLOCK"),
                "类型全集里没有 HTML_TAG/HTML_BLOCK，说明全集取错了");
        require(names.contains("TEXT") && names.contains("PARAGRAPH"), "类型全集少了 TEXT/PARAGRAPH");
        return names;
    }

    /** 仓库文档里的真实段落，抽成"不含 HTML"的反向样本。 */
    /** 语料里"解析器判成 HTML"的那一堆，由 {@link #corpus(Path)} 侧填。 */
    static final List<Map<String, Object>> CORPUS_WITH_HTML = new ArrayList<>();

    static List<Map<String, Object>> corpus(Path repo) throws Exception {
        List<Path> docs = new ArrayList<>();
        try (var s = Files.list(repo.resolve("docs"))) {
            s.filter((p) -> p.getFileName().toString().endsWith(".md")).sorted().forEach(docs::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        GFMFlavourDescriptor flavour = new GFMFlavourDescriptor();
        MarkdownParser parser = new MarkdownParser(flavour);
        int seen = 0;
        int kept = 0;
        for (Path doc : docs) {
            List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
            StringBuilder chunk = new StringBuilder();
            List<String> chunks = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    if (chunk.length() > 0) { chunks.add(chunk.toString()); chunk.setLength(0); }
                } else {
                    if (chunk.length() > 0) chunk.append('\n');
                    chunk.append(line);
                    if (chunk.length() > 400) { chunks.add(chunk.toString()); chunk.setLength(0); }
                }
            }
            if (chunk.length() > 0) chunks.add(chunk.toString());
            for (String c : chunks) {
                seen++;
                // 固定步长抽样，可重复；跳过空块。
                if (seen % 17 != 0 || c.isBlank()) continue;
                if (kept >= 120) break;
                Set<String> types = new LinkedHashSet<>();
                List<int[]> blocks = new ArrayList<>();
                List<int[]> tags = new ArrayList<>();
                collect(parser.buildMarkdownTreeFromString(c), blocks, tags, types);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", doc.getFileName().toString() + "#" + seen);
                entry.put("source", c);
                entry.put("types", new ArrayList<>(types));
                // 语料按"解析器有没有放进一个我们认领的节点"分成两堆：没有的那一堆是
                // 反向检查的样本（它们必须一个字节都不变），有的那一堆是**证据** ——
                // 真实文档里确实有 `<path>` 这种被解析器判成 HTML_TAG 的写法，而它今天
                // 是会被丢掉的（`/export <path>.jsonl` 会显示成 `/export .jsonl`）。
                boolean claimed = types.contains("HTML_TAG") || types.contains("HTML_BLOCK");
                entry.put("blocks", spans(blocks));
                entry.put("tags", spans(tags));
                if (claimed) CORPUS_WITH_HTML.add(entry); else out.add(entry);
                kept++;
            }
        }
        return out;
    }

    // ---- 极简 JSON（夹具的字段全是字符串/数组/对象，不引第三方）----
    static Map<String, Object> parse(String json) throws Exception {
        return (Map<String, Object>) new MiniJson(json).parse();
    }

    static void require(boolean ok, String message) {
        if (!ok) {
            System.err.println("HtmlFixtureSpans: " + message);
            System.exit(2);
        }
    }

    static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) {
            StringBuilder b = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20 || c == '\u0001' || c == '\u0002') b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            return b.append('"').toString();
        }
        if (o instanceof Map<?, ?> m) {
            return m.entrySet().stream()
                    .map((e) -> json(String.valueOf(e.getKey())) + ": " + json(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (o instanceof List<?> l) {
            return l.stream().map(HtmlFixtureSpans::json).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(o);
    }

    static String jsonArray(List<?> l) {
        return l.stream().map(HtmlFixtureSpans::json).collect(Collectors.joining(",\n\t\t", "[\n\t\t", "\n\t]"));
    }

    /** 只够读 step 1 的输出：对象/数组/字符串/数字。 */
    static class MiniJson {
        final String s; int i;
        MiniJson(String s) { this.s = s; }
        Object parse() {
            skip();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (i > start) return Long.valueOf(s.substring(start, i));
            throw new IllegalStateException("unexpected at " + i);
        }
        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; skip();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                String k = str();
                skip(); i++; // ':'
                skip();
                m.put(k, parse());
                skip();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; skip();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                skip();
                l.add(parse());
                skip();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }
        String str() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\u000C');
                        case '/' -> b.append('/');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }
    }
}
