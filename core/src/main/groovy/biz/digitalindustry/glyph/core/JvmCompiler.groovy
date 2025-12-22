package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.AssignStmt
import biz.digitalindustry.glyph.core.ast.BinaryOp
import biz.digitalindustry.glyph.core.ast.Block
import biz.digitalindustry.glyph.core.ast.BoolLiteral
import biz.digitalindustry.glyph.core.ast.CallExpr
import biz.digitalindustry.glyph.core.ast.ElvisExpr
import biz.digitalindustry.glyph.core.ast.Expr
import biz.digitalindustry.glyph.core.ast.ExprStmt
import biz.digitalindustry.glyph.core.ast.FieldAccess
import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.IfExpr
import biz.digitalindustry.glyph.core.ast.IndexAccess
import biz.digitalindustry.glyph.core.ast.IntLiteral
import biz.digitalindustry.glyph.core.ast.LambdaExpr
import biz.digitalindustry.glyph.core.ast.LiteralPattern
import biz.digitalindustry.glyph.core.ast.MapAllocExpr
import biz.digitalindustry.glyph.core.ast.MapLiteralExpr
import biz.digitalindustry.glyph.core.ast.MatchExpr
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.Pattern
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordFieldPattern
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.RecordPattern
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.SafeFieldAccess
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.SumTypeDecl
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarPattern
import biz.digitalindustry.glyph.core.ast.VarRef
import biz.digitalindustry.glyph.core.ast.VariantPattern
import biz.digitalindustry.glyph.core.ast.WildcardPattern

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Collections
import java.util.Deque
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Objects
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class JvmCompiler {
    void compileToJar(Program program, Path outputJar, String mainClass = 'GlyphMain', ProjectIndex index = null) {
        new TypeChecker(index).check(program)
        Files.createDirectories(outputJar.parent)

        Path workDir = Files.createTempDirectory('glyph-jvm')
        Path srcDir = Files.createDirectories(workDir.resolve('src'))
        Path classesDir = Files.createDirectories(workDir.resolve('classes'))

        String source = new JavaEmitter(program, mainClass).emit()
        Path sourceFile = srcDir.resolve("${mainClass}.java")
        Files.writeString(sourceFile, source)

        def compiler = ToolProvider.getSystemJavaCompiler()
        if (compiler == null) {
            throw new IllegalStateException('No system Java compiler found. Use a JDK, not a JRE.')
        }
        def fileManager = compiler.getStandardFileManager(null, null, null)
        def units = fileManager.getJavaFileObjectsFromFiles([sourceFile.toFile()])
        def options = ['-d', classesDir.toString()]
        def task = compiler.getTask(null, fileManager, null, options, null, units)
        if (!task.call()) {
            throw new IllegalStateException('javac failed to compile generated sources')
        }
        fileManager.close()

        writeJar(classesDir, outputJar, mainClass)
    }

    private static void writeJar(Path classesDir, Path outputJar, String mainClass) {
        Manifest manifest = new Manifest()
        manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, '1.0')
        manifest.mainAttributes.put(new Attributes.Name('Main-Class'), mainClass)

        JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(outputJar), manifest)
        Files.walk(classesDir).each { path ->
            if (!Files.isRegularFile(path)) return
            String entryName = classesDir.relativize(path).toString().replace(File.separatorChar, '/' as char)
            JarEntry entry = new JarEntry(entryName)
            jarOut.putNextEntry(entry)
            Files.copy(path, jarOut)
            jarOut.closeEntry()
        }
        jarOut.close()
    }
}

class JavaEmitter {
    private final Program program
    private final String mainClass
    private final Map<String, RecordDecl> recordDefs
    private final Set<String> functionNames
    private final Map<String, SumTypeDecl> sumTypeDefs
    private final Map<String, VariantMeta> variantConstructors
    private final List<LambdaInfo> lambdaInfos = []
    private final StringBuilder sb = new StringBuilder()
    private final Deque<ScopeContext> scopeStack = new ArrayDeque<>()
    private int indent = 0
    private int tempCounter = 0

    JavaEmitter(Program program, String mainClass) {
        this.program = program
        this.mainClass = mainClass
        this.recordDefs = program.records.collectEntries { [it.name, it] }
        this.functionNames = program.functions.collect { it.name }.toSet()
        this.sumTypeDefs = program.sumTypes?.collectEntries { [it.name, it] } ?: [:]
        Map<String, VariantMeta> variants = [:]
        program.sumTypes?.each { SumTypeDecl sumType ->
            sumType.variants?.eachWithIndex { variant, idx ->
                variants[variant.name] = new VariantMeta(sumType.name, variant.name, idx, variant.fields.collect { it.name })
            }
        }
        this.variantConstructors = variants
    }

    String emit() {
        line('import java.util.*;')
        line('')
        line("public class ${mainClass} {")
        indent++
        emitRuntimeHelpers()
        program.records.each { emitRecordMetadata(it) }
        program.functions.each { emitFunction(it) }
        emitEntryPointBridge()
        emitLambdaClasses()
        indent--
        line('}')
        return sb.toString()
    }

    private void emitRuntimeHelpers() {
        line('static class RecordValue {')
        indent++
        line('final String name;')
        line('final Map<String, Object> fields = new HashMap<>();')
        line('final Set<String> immutable = new HashSet<>();')
        line('RecordValue(String name) { this.name = name; }')
        indent--
        line('}')

        line('static RecordValue recordNew(String name, String[] fieldNames, boolean[] immut, Object[] values) {')
        indent++
        line('RecordValue rv = new RecordValue(name);')
        line('for (int i = 0; i < fieldNames.length; i++) {')
        indent++
        line('rv.fields.put(fieldNames[i], values[i]);')
        line('if (immut[i]) { rv.immutable.add(fieldNames[i]); }')
        indent--
        line('}')
        line('return rv;')
        indent--
        line('}')

        line('static Object recordGet(RecordValue rv, String field) {')
        indent++
        line('return rv.fields.get(field);')
        indent--
        line('}')

        line('static void recordSet(RecordValue rv, String field, Object value) {')
        indent++
        line('if (rv.immutable.contains(field)) { throw new RuntimeException(\"field \" + field + \" is immutable\"); }')
        line('rv.fields.put(field, value);')
        indent--
        line('}')

        line('static class VariantValue {')
        indent++
        line('final String sumType;')
        line('final String variant;')
        line('final int tag;')
        line('final Object[] fields;')
        line('VariantValue(String sumType, String variant, int tag, Object[] fields) {')
        indent++
        line('this.sumType = sumType;')
        line('this.variant = variant;')
        line('this.tag = tag;')
        line('this.fields = fields != null ? fields : new Object[0];')
        indent--
        line('}')
        indent--
        line('}')

        line('static VariantValue variantNew(String sumType, String variant, int tag, Object[] fields) {')
        indent++
        line('return new VariantValue(sumType, variant, tag, fields);')
        indent--
        line('}')

        line('static Object indexGet(Object target, Object index) {')
        indent++
        line('if (target instanceof Object[]) {')
        indent++
        line('int idx = ((Number) index).intValue();')
        line('return ((Object[]) target)[idx];')
        indent--
        line('} else if (target instanceof Map) {')
        indent++
        line('return ((Map<?, ?>) target).get(index);')
        indent--
        line('}')
        line('throw new RuntimeException(\"index access on non-collection\");')
        indent--
        line('}')

        line('static void indexSet(Object target, Object index, Object value) {')
        indent++
        line('if (target instanceof Object[]) {')
        indent++
        line('int idx = ((Number) index).intValue();')
        line('((Object[]) target)[idx] = value;')
        line('return;')
        indent--
        line('} else if (target instanceof Map) {')
        indent++
        line('((Map<Object, Object>) target).put(index, value);')
        line('return;')
        indent--
        line('}')
        line('throw new RuntimeException(\"index assignment on non-collection\");')
        indent--
        line('}')
        line('interface GlyphFunction {')
        indent++
        line('Object call(Object[] args);')
        indent--
        line('}')
    }

    private void emitRecordMetadata(RecordDecl record) {
        line("static final String[] FIELDS_${record.name} = new String[]{${record.fields.collect { '\"' + it.name + '\"' }.join(', ')}};")
        line("static final boolean[] IMMUT_${record.name} = new boolean[]{${record.fields.collect { it.mutability == biz.digitalindustry.glyph.core.ast.Mutability.VAL ? 'true' : 'false' }.join(', ')}};")
    }

    private void emitFunction(FunctionDecl fn) {
        String retType = fn.returnType == 'void' ? 'void' : 'Object'
        String params = fn.params.collect { "Object ${it.name}" }.join(', ')
        line("public static ${retType} ${fn.name}(${params}) {")
        indent++
        pushFunctionScope(fn.params.collect { it.name })
        emitBlock(fn.body, retType != 'void')
        if (retType != 'void') {
            line('return null;')
        }
        popScope()
        indent--
        line('}')
    }

    private void emitEntryPointBridge() {
        FunctionDecl mainFn = program.functions.find { it.name == 'main' && (it.params == null || it.params.isEmpty()) }
        if (mainFn == null) {
            return
        }
        line('public static void main(String[] args) {')
        indent++
        String invocation = mainFn.name + '()'
        if (mainFn.returnType != null && mainFn.returnType != 'void') {
            line("${invocation};")
        } else {
            line("${invocation};")
        }
        indent--
        line('}')
    }

    private void emitLambdaClasses() {
        lambdaInfos.each { info ->
            LambdaExpr expr = info.expr
            line("static final class ${info.className} implements GlyphFunction {")
            indent++
            expr.captures.each { cap ->
                line("private final Object ${cap.name};")
            }
            if (expr.captures) {
                String params = expr.captures.collect { "Object ${it.name}" }.join(', ')
                line("${info.className}(${params}) {")
                indent++
                expr.captures.each { cap ->
                    line("this.${cap.name} = ${cap.name};")
                }
                indent--
                line('}')
            } else {
                line("${info.className}() {}")
            }
            line('@Override')
            line('public Object call(Object[] args) {')
            indent++
            pushLambdaScope(expr)
            expr.params.eachWithIndex { param, idx ->
                line("Object ${param.name} = args[${idx}];")
            }
            emitBlock(expr.body, true)
            line('return null;')
            popScope()
            indent--
            line('}')
            indent--
            line('}')
            line('')
        }
    }

    private void emitBlock(Block block, boolean returnValue) {
        int count = block.statements.size()
        block.statements.eachWithIndex { stmt, idx ->
            boolean isLast = (idx == count - 1)
            switch (stmt) {
                case VarDecl:
                    emitVarDecl(stmt as VarDecl)
                    break
                case AssignStmt:
                    emitAssign(stmt as AssignStmt)
                    break
                case PrintStmt:
                    line("System.out.println(${emitExpr((stmt as PrintStmt).expr)});")
                    break
                case ExprStmt:
                    Expr expr = (stmt as ExprStmt).expr
                    if (returnValue && isLast) {
                        line("return ${emitExpr(expr)};")
                    } else {
                        line("${emitExpr(expr)};")
                    }
                    break
                case ReturnStmt:
                    ReturnStmt r = stmt as ReturnStmt
                    if (r.expr == null) {
                        line('return;')
                    } else {
                        line("return ${emitExpr(r.expr)};")
                    }
                    break
                default:
                    line('// unsupported statement ignored')
            }
        }
    }

    private void emitVarDecl(VarDecl stmt) {
        String prefix = (stmt.mutability == biz.digitalindustry.glyph.core.ast.Mutability.CONST || stmt.mutability == biz.digitalindustry.glyph.core.ast.Mutability.VAL) ? 'final ' : ''
        String type = 'Object'
        if (stmt.type) {
            type = javaType(stmt.type)
        } else if (stmt.value instanceof LambdaExpr) {
            type = 'GlyphFunction'
        }
        String valueExpr = emitExpr(stmt.value)
        if (stmt.type) {
            valueExpr = coerceToDeclaredType(valueExpr, stmt.type)
        }
        line("${prefix}${type} ${stmt.name} = ${valueExpr};")
        declareLocal(stmt.name)
    }

    private void emitAssign(AssignStmt stmt) {
        String value = emitExpr(stmt.value)
        switch (stmt.target) {
            case VarRef:
                line("${(stmt.target as VarRef).name} = ${value};")
                return
            case FieldAccess:
                FieldAccess fa = stmt.target as FieldAccess
                String target = emitExpr(fa.target)
                line("recordSet((RecordValue) ${target}, \"${fa.field}\", ${value});")
                return
            case IndexAccess:
                IndexAccess ia = stmt.target as IndexAccess
                line("indexSet(${emitExpr(ia.target)}, ${emitExpr(ia.index)}, ${value});")
                return
            default:
                line('// unsupported assignment')
        }
    }

    private String emitExpr(Expr expr) {
        switch (expr) {
            case IntLiteral:
                return "Long.valueOf(${(expr as IntLiteral).value})"
            case BoolLiteral:
                return (expr as BoolLiteral).value ? 'Boolean.TRUE' : 'Boolean.FALSE'
            case StringLiteral:
                return "\"${escapeString((expr as StringLiteral).value)}\""
            case NullLiteral:
                return "null"
            case VarRef:
                return resolveVarName((expr as VarRef).name)
            case BinaryOp:
                BinaryOp op = expr as BinaryOp
                String left = emitExpr(op.left)
                String right = emitExpr(op.right)
                switch (op.op) {
                    case '+':
                    case '-':
                    case '*':
                    case '/':
                        return "Long.valueOf(((Number) ${left}).longValue() ${op.op} ((Number) ${right}).longValue())"
                    case '<':
                        return "Boolean.valueOf(((Number) ${left}).longValue() < ((Number) ${right}).longValue())"
                    case '<=':
                        return "Boolean.valueOf(((Number) ${left}).longValue() <= ((Number) ${right}).longValue())"
                    case '>':
                        return "Boolean.valueOf(((Number) ${left}).longValue() > ((Number) ${right}).longValue())"
                    case '>=':
                        return "Boolean.valueOf(((Number) ${left}).longValue() >= ((Number) ${right}).longValue())"
                    case '==':
                        return "Boolean.valueOf(Objects.equals(${left}, ${right}))"
                    case '!=':
                        return "Boolean.valueOf(!Objects.equals(${left}, ${right}))"
                    default:
                        throw new IllegalStateException("Unsupported operator ${op.op}")
                }
            case IfExpr:
                return emitIfExpr(expr as IfExpr)
            case TernaryExpr:
                TernaryExpr t = expr as TernaryExpr
                String cond = emitExpr(t.condition)
                return "(((Boolean) ${cond}) ? ${emitExpr(t.ifTrue)} : ${emitExpr(t.ifFalse)})"
            case ElvisExpr:
                ElvisExpr e = expr as ElvisExpr
                String left = emitExpr(e.left)
                String tmp = newTemp()
                line("Object ${tmp} = ${left};")
                line("if (${tmp} == null) { ${tmp} = ${emitExpr(e.right)}; }")
                return tmp
            case MatchExpr:
                return emitMatchExpr(expr as MatchExpr)
            case LambdaExpr:
                return emitLambda(expr as LambdaExpr)
            case CallExpr:
                return emitCallExpr(expr as CallExpr)
            case RecordLiteral:
                return emitRecordLiteral(expr as RecordLiteral)
            case FieldAccess:
                FieldAccess fa = expr as FieldAccess
                return "recordGet((RecordValue) ${emitExpr(fa.target)}, \"${fa.field}\")"
            case SafeFieldAccess:
                SafeFieldAccess sa = expr as SafeFieldAccess
                String tmp = newTemp()
                line("Object ${tmp} = ${emitExpr(sa.target)};")
                return "(${tmp} == null ? null : recordGet((RecordValue) ${tmp}, \"${sa.field}\"))"
            case IndexAccess:
                IndexAccess ia = expr as IndexAccess
                return "indexGet(${emitExpr(ia.target)}, ${emitExpr(ia.index)})"
            case biz.digitalindustry.glyph.core.ast.ArrayAllocExpr:
                return emitArrayAlloc(expr as biz.digitalindustry.glyph.core.ast.ArrayAllocExpr)
            case MapAllocExpr:
                return emitMapAlloc(expr as MapAllocExpr)
            case MapLiteralExpr:
                return emitMapLiteral(expr as MapLiteralExpr)
            default:
                return "null"
        }
    }

    private String emitIfExpr(IfExpr expr) {
        String tmp = newTemp()
        line("Object ${tmp} = null;")
        line("if (((Boolean) ${emitExpr(expr.condition)})) {")
        indent++
        emitBlockValue(expr.thenBlock, tmp)
        indent--
        if (expr.elseBlock != null) {
            line("} else {")
            indent++
            emitBlockValue(expr.elseBlock, tmp)
            indent--
        }
        line("}")
        return tmp
    }

    private void emitBlockValue(Block block, String targetVar) {
        int count = block.statements.size()
        block.statements.eachWithIndex { stmt, idx ->
            boolean isLast = (idx == count - 1)
            switch (stmt) {
                case VarDecl:
                    emitVarDecl(stmt as VarDecl)
                    break
                case AssignStmt:
                    emitAssign(stmt as AssignStmt)
                    break
                case PrintStmt:
                    line("System.out.println(${emitExpr((stmt as PrintStmt).expr)});")
                    break
                case ExprStmt:
                    Expr expr = (stmt as ExprStmt).expr
                    if (isLast) {
                        line("${targetVar} = ${emitExpr(expr)};")
                    } else {
                        line("${emitExpr(expr)};")
                    }
                    break
                case ReturnStmt:
                    ReturnStmt r = stmt as ReturnStmt
                    if (r.expr == null) {
                        line('return;')
                    } else {
                        line("return ${emitExpr(r.expr)};")
                    }
                    break
                default:
                    line('// unsupported statement ignored')
            }
        }
    }

    private String emitCallExpr(CallExpr expr) {
        List<String> args = expr.arguments.collect { emitExpr(it) }
        boolean lexical = hasLexicalBinding(expr.callee)
        if (!lexical && functionNames.contains(expr.callee)) {
            return "${expr.callee}(${args.join(', ')})"
        }
        if (!lexical && variantConstructors.containsKey(expr.callee)) {
            return emitVariantConstructor(expr.callee, args)
        }
        String argsArray = newTemp()
        line("Object[] ${argsArray} = new Object[]{${args.join(', ')}};")
        String result = newTemp()
        String callee = resolveVarName(expr.callee)
        line("Object ${result} = ((GlyphFunction) ${callee}).call(${argsArray});")
        return result
    }

    private String emitVariantConstructor(String name, List<String> args) {
        VariantMeta meta = variantConstructors[name]
        if (meta == null) {
            return 'null'
        }
        if (meta.fieldNames.size() != args.size()) {
            throw new IllegalStateException("Constructor ${name} expects ${meta.fieldNames.size()} argument(s)")
        }
        String values = args.isEmpty() ? 'new Object[]{}' : "new Object[]{${args.join(', ')}}"
        return "variantNew(\"${meta.sumType}\", \"${meta.variantName}\", ${meta.tag}, ${values})"
    }

    private String emitLambda(LambdaExpr expr) {
        String className = registerLambda(expr)
        String ctorArgs = expr.captures.collect { resolveVarName(it.name) }.join(', ')
        if (ctorArgs) {
            return "new ${className}(${ctorArgs})"
        }
        return "new ${className}()"
    }

    private String registerLambda(LambdaExpr expr) {
        String className = "Lambda${lambdaInfos.size()}"
        lambdaInfos << new LambdaInfo(className, expr)
        return className
    }

    private String emitMatchExpr(MatchExpr expr) {
        String tmp = newTemp()
        line("Object ${tmp} = null;")
        String subject = newTemp()
        line("Object ${subject} = ${emitExpr(expr.target)};")
        boolean first = true
        expr.cases.each { c ->
            PatternEmitResult result = buildPatternEmit(c.pattern, subject)
            if (first) {
                line("if (${result.condition}) {")
                first = false
            } else {
                line("else if (${result.condition}) {")
            }
            indent++
            pushAnonymousScope()
            result.bindings.each {
                String unique = declareUniqueLocal(it.name)
                line("Object ${unique} = ${it.expression};")
            }
            line("${tmp} = ${emitExpr(c.value)};")
            popScope()
            indent--
            line("}")
        }
        if (expr.elseExpr != null) {
            line("else {")
            indent++
            pushAnonymousScope()
            line("${tmp} = ${emitExpr(expr.elseExpr)};")
            popScope()
            indent--
            line("}")
        }
        return tmp
    }

    private String emitRecordLiteral(RecordLiteral expr) {
        RecordDecl record = recordDefs[expr.typeName]
        if (record == null) {
            return 'null'
        }
        String values = record.fields.collect { field ->
            emitExpr(expr.fields[field.name])
        }.join(', ')
        return "recordNew(\"${record.name}\", FIELDS_${record.name}, IMMUT_${record.name}, new Object[]{${values}})"
    }

    private String emitArrayAlloc(biz.digitalindustry.glyph.core.ast.ArrayAllocExpr expr) {
        return "new Object[((Number) ${emitExpr(expr.size)}).intValue()]"
    }

    private String emitMapAlloc(MapAllocExpr expr) {
        String tmp = newTemp()
        line("Map<Object, Object> ${tmp} = new HashMap<>();")
        return tmp
    }

    private String emitMapLiteral(MapLiteralExpr expr) {
        String tmp = newTemp()
        line("Map<Object, Object> ${tmp} = new HashMap<>();")
        expr.entries.each { entry ->
            line("${tmp}.put(${emitExpr(entry.key)}, ${emitExpr(entry.value)});")
        }
        return tmp
    }

    private PatternEmitResult buildPatternEmit(Pattern pattern, String subject) {
        List<String> conditions = []
        List<PatternBinding> bindings = []
        appendPatternComponents(pattern, subject, conditions, bindings)
        String condition = conditions.isEmpty() ? "true" : conditions.join(' && ')
        return new PatternEmitResult(condition, bindings)
    }

    private void appendPatternComponents(Pattern pattern,
                                         String subject,
                                         List<String> conditions,
                                         List<PatternBinding> bindings) {
        switch (pattern) {
            case WildcardPattern:
                return
            case VarPattern:
                bindings << new PatternBinding((pattern as VarPattern).name, subject)
                return
            case LiteralPattern:
                conditions << "Objects.equals(${subject}, ${emitExpr((pattern as LiteralPattern).literal)})"
                return
            case RecordPattern:
                String casted = "((RecordValue) ${subject})"
                conditions << "${subject} instanceof RecordValue"
                conditions << "Objects.equals(${casted}.name, \"${pattern.typeName}\")"
                (pattern as RecordPattern).fields.each { RecordFieldPattern fieldPattern ->
                    String fieldExpr = "recordGet(${casted}, \"${fieldPattern.field}\")"
                    appendPatternComponents(fieldPattern.pattern, fieldExpr, conditions, bindings)
                }
                return
            case VariantPattern:
                VariantPattern vp = pattern as VariantPattern
                VariantMeta meta = variantConstructors[vp.variantName]
                String castVariant = "((VariantValue) ${subject})"
                conditions << "${subject} instanceof VariantValue"
                if (vp.typeName) {
                    conditions << "Objects.equals(${castVariant}.sumType, \"${vp.typeName}\")"
                }
                if (meta != null) {
                    conditions << "${castVariant}.tag == ${meta.tag}"
                } else {
                    conditions << "false"
                    return
                }
                if (meta.fieldNames.size() != vp.fields.size()) {
                    conditions << "false"
                    return
                }
                vp.fields.eachWithIndex { Pattern child, int idx ->
                    String fieldExpr = "${castVariant}.fields[${idx}]"
                    appendPatternComponents(child, fieldExpr, conditions, bindings)
                }
                return
            default:
                throw new IllegalStateException("Unsupported pattern ${pattern?.class?.simpleName}")
        }
    }

    private String javaType(String glyphType) {
        if (glyphType == null) return 'Object'
        String base = glyphType.endsWith('?') ? glyphType.substring(0, glyphType.length() - 1) : glyphType
        if (base == 'int') return 'long'
        if (base == 'bool') return 'boolean'
        if (base == 'string') return 'String'
        if (base == 'long') return 'long'
        if (base == 'float') return 'float'
        if (base == 'double') return 'double'
        if (base == 'char') return 'int'
        if (base == 'bytes') return 'byte[]'
        if (base.startsWith('[')) return 'Object'
        return 'Object'
    }

    private String escapeString(String s) {
        s.replace('\\\\', '\\\\\\\\')
         .replace('\"', '\\\\\"')
         .replace('\\n', '\\\\n')
         .replace('\\r', '\\\\r')
         .replace('\\t', '\\\\t')
    }

    private String newTemp() {
        tempCounter++
        return "_tmp${tempCounter}"
    }

    private void pushFunctionScope(List<String> params) {
        Set<String> locals = new LinkedHashSet<>()
        if (params) {
            params.findAll { it != null }.each { locals.add(it) }
        }
        scopeStack.push(new ScopeContext(locals, Collections.emptyMap(), new LinkedHashMap<>()))
    }

    private void pushAnonymousScope() {
        scopeStack.push(new ScopeContext(new LinkedHashSet<>(), Collections.emptyMap(), new LinkedHashMap<>()))
    }

    private void pushLambdaScope(LambdaExpr expr) {
        Set<String> locals = new LinkedHashSet<>()
        expr.params.each { locals.add(it.name) }
        Map<String, String> captures = new LinkedHashMap<>()
        expr.captures.each { captures[it.name] = "this.${it.name}".toString() }
        scopeStack.push(new ScopeContext(locals, captures, new LinkedHashMap<>()))
    }

    private void popScope() {
        if (!scopeStack.isEmpty()) {
            scopeStack.pop()
        }
    }

    private void declareLocal(String name) {
        if (!scopeStack.isEmpty() && name != null) {
            scopeStack.peek().locals.add(name)
        }
    }

    private String declareUniqueLocal(String baseName) {
        if (!scopeStack.isEmpty() && baseName != null) {
            ScopeContext ctx = scopeStack.peek()
            String candidate = baseName
            int counter = 0
            while (isNameInAnyScope(candidate)) {
                candidate = "${baseName}_${counter++}"
            }
            ctx.locals.add(candidate)
            ctx.renames[baseName] = candidate
            return candidate
        }
        return baseName
    }

    private boolean isNameInAnyScope(String name) {
        for (ScopeContext ctx : scopeStack) {
            if (ctx.locals.contains(name)) {
                return true
            }
        }
        return false
    }

    private boolean hasLexicalBinding(String name) {
        if (name == null) {
            return false
        }
        for (ScopeContext ctx : scopeStack) {
            if (ctx.locals.contains(name) || ctx.captureRefs.containsKey(name)) {
                return true
            }
        }
        return false
    }

    private String resolveVarName(String name) {
        if (name == null) {
            return null
        }
        for (ScopeContext ctx : scopeStack) {
            if (ctx.renames.containsKey(name)) {
                return ctx.renames[name]
            }
            if (ctx.locals.contains(name)) {
                return name
            }
            if (ctx.captureRefs.containsKey(name)) {
                return ctx.captureRefs.get(name)
            }
        }
        return name
    }

    private String coerceToDeclaredType(String expr, String glyphType) {
        if (glyphType == null) {
            return expr
        }
        String base = glyphType.endsWith('?') ? glyphType.substring(0, glyphType.length() - 1) : glyphType
        switch (base) {
            case 'string':
                return "(String) ${expr}"
            case 'bool':
                return "((Boolean) ${expr}).booleanValue()"
            case 'int':
            case 'long':
                return "((Number) ${expr}).longValue()"
            case 'float':
                return "((Number) ${expr}).floatValue()"
            case 'double':
                return "((Number) ${expr}).doubleValue()"
            case 'char':
                return "((Number) ${expr}).intValue()"
            default:
                return expr
        }
    }

    private void line(String text) {
        sb.append(' ' * (indent * 4)).append(text).append('\n')
    }

    private static class PatternEmitResult {
        final String condition
        final List<PatternBinding> bindings

        PatternEmitResult(String condition, List<PatternBinding> bindings) {
            this.condition = condition
            this.bindings = bindings ?: []
        }
    }

    private static class PatternBinding {
        final String name
        final String expression

        PatternBinding(String name, String expression) {
            this.name = name
            this.expression = expression
        }
    }
    
    private static class LambdaInfo {
        final String className
        final LambdaExpr expr

        LambdaInfo(String className, LambdaExpr expr) {
            this.className = className
            this.expr = expr
        }
    }

    private static class ScopeContext {
        final Set<String> locals
        final Map<String, String> captureRefs
        final Map<String, String> renames

        ScopeContext(Set<String> locals, Map<String, String> captureRefs, Map<String, String> renames) {
            this.locals = locals ?: new LinkedHashSet<>()
            this.captureRefs = captureRefs ?: [:]
            this.renames = renames ?: [:]
        }
    }

    private static class VariantMeta {
        final String sumType
        final String variantName
        final int tag
        final List<String> fieldNames

        VariantMeta(String sumType, String variantName, int tag, List<String> fieldNames) {
            this.sumType = sumType
            this.variantName = variantName
            this.tag = tag
            this.fieldNames = fieldNames ?: Collections.emptyList()
        }
    }
}
