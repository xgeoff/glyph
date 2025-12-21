package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.AssignStmt
import biz.digitalindustry.glyph.core.ast.BinaryOp
import biz.digitalindustry.glyph.core.ast.Block
import biz.digitalindustry.glyph.core.ast.BoolLiteral
import biz.digitalindustry.glyph.core.ast.ElvisExpr
import biz.digitalindustry.glyph.core.ast.Expr
import biz.digitalindustry.glyph.core.ast.ExprStmt
import biz.digitalindustry.glyph.core.ast.FieldAccess
import biz.digitalindustry.glyph.core.ast.SafeFieldAccess
import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.IfExpr
import biz.digitalindustry.glyph.core.ast.IndexAccess
import biz.digitalindustry.glyph.core.ast.IntLiteral
import biz.digitalindustry.glyph.core.ast.MapAllocExpr
import biz.digitalindustry.glyph.core.ast.MapLiteralExpr
import biz.digitalindustry.glyph.core.ast.MatchExpr
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarRef

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path
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
    private final StringBuilder sb = new StringBuilder()
    private int indent = 0
    private int tempCounter = 0

    JavaEmitter(Program program, String mainClass) {
        this.program = program
        this.mainClass = mainClass
        this.recordDefs = program.records.collectEntries { [it.name, it] }
    }

    String emit() {
        line('import java.util.*;')
        line('')
        line("public class ${mainClass} {")
        indent++
        emitRuntimeHelpers()
        program.records.each { emitRecordMetadata(it) }
        program.functions.each { emitFunction(it) }
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
        emitBlock(fn.body, retType != 'void')
        if (retType != 'void') {
            line('return null;')
        }
        indent--
        line('}')
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
        String type = stmt.type ? javaType(stmt.type) : 'Object'
        line("${prefix}${type} ${stmt.name} = ${emitExpr(stmt.value)};")
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
                return (expr as VarRef).name
            case BinaryOp:
                BinaryOp op = expr as BinaryOp
                String left = emitExpr(op.left)
                String right = emitExpr(op.right)
                return "Long.valueOf(((Number) ${left}).longValue() ${op.op} ((Number) ${right}).longValue())"
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

    private String emitMatchExpr(MatchExpr expr) {
        String tmp = newTemp()
        line("Object ${tmp} = null;")
        String target = emitExpr(expr.target)
        boolean first = true
        expr.cases.each { c ->
            String key = emitExpr(c.key)
            String val = emitExpr(c.value)
            if (first) {
                line("if (Objects.equals(${target}, ${key})) { ${tmp} = ${val}; }")
                first = false
            } else {
                line("else if (Objects.equals(${target}, ${key})) { ${tmp} = ${val}; }")
            }
        }
        line("else { ${tmp} = ${emitExpr(expr.elseExpr)}; }")
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

    private void line(String text) {
        sb.append(' ' * (indent * 4)).append(text).append('\\n')
    }
}
