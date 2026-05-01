package com.zhanghaidong.reviewer.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.zhanghaidong.reviewer.dto.FileContext;
import com.zhanghaidong.reviewer.dto.MethodContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 用 JavaParser 解析 Java 源码,提取改动方法的上下文
 *
 * @author 张海东
 */
@Slf4j
@Component
public class JavaContextExtractor {

    private final JavaParser javaParser = new JavaParser();
    private final SkillRouter skillRouter;
    public JavaContextExtractor(SkillRouter skillRouter) {
        this.skillRouter = skillRouter;
    }
    /**
     * 从完整 Java 源码中提取上下文
     *
     * @param filePath      文件路径(用于日志)
     * @param fullSource    Java 文件完整源码
     * @param changedLines  diff 中改动的新文件行号集合
     */
    public FileContext extract(String filePath, String fullSource, Set<Integer> changedLines) {
        FileContext ctx = new FileContext();
        ctx.setFilePath(filePath);

        if (fullSource == null || fullSource.isBlank()) {
            log.warn("源码为空,跳过 AST 提取: {}", filePath);
            return ctx;
        }

        ParseResult<CompilationUnit> parseResult = javaParser.parse(fullSource);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            log.warn("JavaParser 解析失败: {}, 错误数={}",
                    filePath, parseResult.getProblems().size());
            return ctx;
        }
        CompilationUnit cu = parseResult.getResult().get();

        // 提取类信息(只取第一个顶层类,内部类先不处理)
        Optional<ClassOrInterfaceDeclaration> classOpt =
                cu.findFirst(ClassOrInterfaceDeclaration.class);

        if (classOpt.isEmpty()) {
            log.debug("文件中没有找到类声明: {}", filePath);
            ctx.setSkillName(skillRouter.route(ctx, filePath, fullSource));
            return ctx;
        }

        ClassOrInterfaceDeclaration clazz = classOpt.get();
        ctx.setClassName(clazz.getNameAsString());

        // 类上的注解
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            ctx.getClassAnnotations().add("@" + ann.getNameAsString());
        }

        // 类的字段
        for (FieldDeclaration field : clazz.getFields()) {
            String fieldStr = formatField(field);
            ctx.getFields().add(fieldStr);
        }

        // 所有方法签名 + 命中改动的方法体
        for (MethodDeclaration method : clazz.getMethods()) {
            String sig = method.getDeclarationAsString(false, false, true);
            ctx.getAllMethodSignatures().add(sig);

            // 判断该方法是否与改动行号有交集
            if (isMethodChanged(method, changedLines)) {
                MethodContext mc = new MethodContext();
                mc.setSignature(sig);
                mc.setStartLine(method.getBegin().map(p -> p.line).orElse(-1));
                mc.setEndLine(method.getEnd().map(p -> p.line).orElse(-1));
                mc.setBody(method.toString());
                ctx.getChangedMethods().add(mc);
            }
        }

        ctx.setSkillName(skillRouter.route(ctx, filePath, fullSource));

        log.info("AST 提取完成: file={}, class={}, fields={}, allMethods={}, changedMethods={}, skill={}",
                filePath, ctx.getClassName(), ctx.getFields().size(),
                ctx.getAllMethodSignatures().size(), ctx.getChangedMethods().size(),
                ctx.getSkillName());
        return ctx;
    }

    /**
     * 判断方法的行号区间是否覆盖任何一行改动
     */
    private boolean isMethodChanged(MethodDeclaration method, Set<Integer> changedLines) {
        if (changedLines == null || changedLines.isEmpty()) return false;
        if (method.getBegin().isEmpty() || method.getEnd().isEmpty()) return false;

        int start = method.getBegin().get().line;
        int end = method.getEnd().get().line;

        for (Integer line : changedLines) {
            if (line >= start && line <= end) return true;
        }
        return false;
    }

    /**
     * 把 FieldDeclaration 格式化成可读字符串
     * 例如 "@Autowired private SetmealMapper setmealMapper"
     */
    private String formatField(FieldDeclaration field) {
        StringBuilder sb = new StringBuilder();
        for (AnnotationExpr ann : field.getAnnotations()) {
            sb.append("@").append(ann.getNameAsString()).append(" ");
        }
        sb.append(field.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .reduce((a, b) -> a + " " + b)
                .orElse(""));
        if (!field.getModifiers().isEmpty()) sb.append(" ");
        sb.append(field.getElementType()).append(" ");
        sb.append(field.getVariables().stream()
                .map(v -> v.getNameAsString())
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));
        return sb.toString().trim();
    }
}