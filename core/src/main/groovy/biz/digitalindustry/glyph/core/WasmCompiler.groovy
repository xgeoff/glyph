package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.ArrayAllocExpr
import biz.digitalindustry.glyph.core.ast.AssignStmt
import biz.digitalindustry.glyph.core.ast.BinaryOp
import biz.digitalindustry.glyph.core.ast.BoolLiteral
import biz.digitalindustry.glyph.core.ast.CallExpr
import biz.digitalindustry.glyph.core.ast.Expr
import biz.digitalindustry.glyph.core.ast.ExprStmt
import biz.digitalindustry.glyph.core.ast.Block
import biz.digitalindustry.glyph.core.ast.FieldAccess
import biz.digitalindustry.glyph.core.ast.ElvisExpr
import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.IfExpr
import biz.digitalindustry.glyph.core.ast.IndexAccess
import biz.digitalindustry.glyph.core.ast.IntLiteral
import biz.digitalindustry.glyph.core.ast.MapAllocExpr
import biz.digitalindustry.glyph.core.ast.MapLiteralExpr
import biz.digitalindustry.glyph.core.ast.MatchCase
import biz.digitalindustry.glyph.core.ast.MatchExpr
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.SafeFieldAccess
import biz.digitalindustry.glyph.core.ast.Statement
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarRef
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * WASM compiler capable of emitting modules for the current Glyph feature set.
 */
class WasmCompiler {

    void compile(ProjectIndex index, Program entryProgram, Path wasmOutput, boolean emitWat = false) {
        if (index == null) throw new IllegalArgumentException('ProjectIndex required')
        if (entryProgram == null) throw new IllegalArgumentException('Entry program required')
        if (wasmOutput == null) throw new IllegalArgumentException('Output path required')

        TypeChecker checker = new TypeChecker(index)
        index.programsByFile.values().each { checker.check(it) }

        WasmModuleWriter writer = new WasmModuleWriter(index, entryProgram)
        byte[] moduleBytes = writer.buildModule()
        Files.createDirectories(wasmOutput.parent)
        Files.write(wasmOutput, moduleBytes)
        if (emitWat) {
            Path wat = wasmOutput.resolveSibling(wasmOutput.fileName.toString().replace('.wasm', '.wat'))
            Files.writeString(wat, writer.buildWat())
        }
    }

    void compileToWasm(Program entryProgram, Path wasmOutput, ProjectIndex index) {
        compile(index, entryProgram, wasmOutput, false)
    }

    void compileToWat(Program entryProgram, Path watOutput, ProjectIndex index) {
        compile(index, entryProgram, watOutput, true)
    }
}

class WasmModuleWriter {
    static final byte VAL_I32 = (byte) 0x7f

    private final ProjectIndex index
    private final Program entryProgram
    private final SymbolResolver resolver
    private final RecordLayoutCache recordLayouts = new RecordLayoutCache()
    private final WasmStringPool stringPool = new WasmStringPool()
    private final WasmTypeSectionBuilder typeSection = new WasmTypeSectionBuilder()
    private final List<RuntimeImport> runtimeImports
    private final Map<String, RuntimeImport> runtimeImportMap
    private final List<FunctionContext> functionContexts
    private final Map<FunctionDecl, FunctionContext> contextByDecl
    private final FunctionContext entryContext

    WasmModuleWriter(ProjectIndex index, Program entryProgram) {
        this.index = index
        this.entryProgram = entryProgram
        this.resolver = new SymbolResolver(index)
        Tuple2<List<FunctionContext>, Map<FunctionDecl, FunctionContext>> tuple = buildFunctionContexts()
        this.functionContexts = tuple.v1
        this.contextByDecl = tuple.v2
        this.runtimeImports = RuntimeImports.all(VAL_I32)
        this.runtimeImportMap = runtimeImports.collectEntries { [(it.name): it] }
        this.entryContext = locateEntryContext()
        assignFunctionIndices()
    }

    byte[] buildModule() {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        writeHeader(out)

        List<byte[]> typeEntries = buildTypeEntries()
        writeSection(out, 1) { writeVec(it, typeEntries) }

        List<byte[]> imports = buildImports()
        writeSection(out, 2) { writeVec(it, imports) }

        writeSection(out, 3) {
            List<byte[]> indices = functionContexts.collect { encU32(it.typeIndex) }
            writeVec(it, indices)
        }

        writeSection(out, 5) {
            byte[] limits = [(byte) 0x00, (byte) 0x01] as byte[]
            writeVec(it, [limits])
        }

        List<ExportSpec> exportSpecs = collectExports()
        writeSection(out, 7) {
            List<byte[]> exports = exportSpecs.collect { exportEntry(it.name, it.kind, it.index) }
            writeVec(it, exports)
        }

        List<WasmFunctionBody> bodies = emitFunctionBodies()
        writeSection(out, 10) { writeVec(it, bodies.collect { it.binary }) }

        List<byte[]> dataSegments = stringPool.buildSegments()
        if (!dataSegments.isEmpty()) {
            writeSection(out, 11) { writeVec(it, dataSegments) }
        }

        return out.toByteArray()
    }

    String buildWat() {
        StringBuilder sb = new StringBuilder()
        sb.appendLine('(module')
        runtimeImports.each {
            sb.appendLine(formatImportWat(it))
        }
        sb.appendLine('  (memory $memory 1)')
        collectExports().each { ExportSpec spec ->
            if (spec.kind == 0x02) {
                sb.appendLine("  (export \"${spec.name}\" (memory $memory))")
            } else {
                sb.appendLine("  (export \"${spec.name}\" (func ${spec.context.watName}))")
            }
        }

        emitFunctionBodies().each { body ->
            sb.appendLine("  (func ${body.watName}${body.watSignature}")
            body.localDecls.each { sb.appendLine("    ${it}") }
            body.watInstructions.each { sb.appendLine("    ${it}") }
            sb.appendLine('  )')
        }

        stringPool.entries.each { entry ->
            String escaped = entry.bytes.collect { String.format("\\%02x", it & 0xff) }.join('')
            sb.appendLine("  (data (i32.const ${entry.offset}) \"${escaped}\")")
        }
        sb.appendLine(')')
        return sb.toString()
    }

    private List<ExportSpec> collectExports() {
        List<ExportSpec> specs = []
        specs << new ExportSpec('memory', (byte) 0x02, 0, null)
        functionContexts.each { ctx ->
            specs << new ExportSpec(ctx.decl.name ?: ctx.fqn, (byte) 0x00, ctx.functionIndex, ctx)
        }
        if (entryContext && entryContext.decl?.name?.equalsIgnoreCase('main')) {
            specs << new ExportSpec('_start', (byte) 0x00, entryContext.functionIndex, entryContext)
        }
        return specs
    }

    private void writeHeader(ByteArrayOutputStream out) {
        out.write([0x00, 0x61, 0x73, 0x6d] as byte[])
        out.write([0x01, 0x00, 0x00, 0x00] as byte[])
    }

    private List<byte[]> buildTypeEntries() {
        runtimeImports.each { RuntimeImport runtimeImport ->
            runtimeImport.typeIndex = typeSection.register(runtimeImport.params, runtimeImport.results)
        }
        functionContexts.each { ctx ->
            byte[] params = ctx.paramTypes as byte[]
            byte[] results = ctx.returnKind == GlyphValueKind.VOID ? [] as byte[] : [VAL_I32] as byte[]
            ctx.typeIndex = typeSection.register(params, results)
        }
        return typeSection.entries
    }

    private List<byte[]> buildImports() {
        runtimeImports.eachWithIndex { RuntimeImport runtimeImport, int idx ->
            runtimeImport.functionIndex = idx
        }
        runtimeImports.collect { runtimeImport ->
            ByteArrayOutputStream b = new ByteArrayOutputStream()
            writeString(b, RuntimeImports.MODULE)
            writeString(b, runtimeImport.name)
            b.write((byte) 0x00)
            writeU32(b, runtimeImport.typeIndex)
            b.toByteArray()
        }
    }

    private List<WasmFunctionBody> emitFunctionBodies() {
        List<WasmFunctionBody> bodies = []
        functionContexts.each { ctx ->
            WasmFunctionEmitter emitter = new WasmFunctionEmitter(
                    ctx,
                    stringPool,
                    contextByDecl,
                    recordLayouts,
                    runtimeImportMap
            )
            bodies << emitter.emitBody()
        }
        bodies
    }

    private Tuple2<List<FunctionContext>, Map<FunctionDecl, FunctionContext>> buildFunctionContexts() {
        List<Program> programs = index.programsByFile.entrySet()
                .collect()
                .sort { it.key.toString() }
                .collect { it.value }

        List<FunctionContext> contexts = []
        Map<FunctionDecl, FunctionContext> byDecl = [:]
        programs.each { Program program ->
            ResolvedSymbols symbols = resolver.resolve(program)
            String pkg = symbols.packageName ?: ''
            program.functions.each { FunctionDecl fn ->
                String fqn = ProjectIndexer.qualify(pkg, fn.name)
                FunctionContext ctx = new FunctionContext(fqn, fn, symbols, pkg)
                contexts << ctx
                byDecl[fn] = ctx
            }
        }
        contexts.sort { it.fqn }
        return new Tuple2<>(contexts, byDecl)
    }

    private FunctionContext locateEntryContext() {
        String pkg = entryProgram.packageDecl?.name ?: ''
        String entryFqn = ProjectIndexer.qualify(pkg, 'main')
        FunctionContext ctx = functionContexts.find { it.fqn == entryFqn }
        if (!ctx) throw new IllegalStateException("main function not found for ${entryFqn}")
        return ctx
    }

    private void assignFunctionIndices() {
        int importCount = runtimeImports.size()
        functionContexts.eachWithIndex { ctx, idx -> ctx.functionIndex = importCount + idx }
    }

    private static void writeSection(ByteArrayOutputStream out, int id, Closure<ByteArrayOutputStream> bodyWriter) {
        ByteArrayOutputStream body = new ByteArrayOutputStream()
        bodyWriter.call(body)
        byte[] payload = body.toByteArray()
        out.write((byte) id)
        writeU32(out, payload.length)
        out.write(payload)
    }

    static void writeVec(ByteArrayOutputStream out, List<byte[]> entries) {
        writeU32(out, entries.size())
        entries.each { out.write(it) }
    }

    private static byte[] exportEntry(String name, int kind, int index) {
        ByteArrayOutputStream b = new ByteArrayOutputStream()
        writeString(b, name)
        b.write((byte) kind)
        writeU32(b, index)
        return b.toByteArray()
    }

    static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes('UTF-8')
        writeU32(out, bytes.length)
        out.write(bytes)
    }

    static void writeU32(ByteArrayOutputStream out, int value) {
        int remaining = value
        while (true) {
            int b = remaining & 0x7f
            remaining >>>= 7
            if (remaining != 0) b |= 0x80
            out.write((byte) b)
            if (remaining == 0) break
        }
    }

    private static byte[] encU32(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        writeU32(out, value)
        return out.toByteArray()
    }

    private static String formatImportWat(RuntimeImport runtimeImport) {
        String params = runtimeImport.params.collect { "(param ${watType(it)})" }.join(' ')
        String results = runtimeImport.results.collect { "(result ${watType(it)})" }.join(' ')
        String signature = [params, results].findAll { it }.join(' ')
        return "  (import \"${RuntimeImports.MODULE}\" \"${runtimeImport.name}\" (func \$${runtimeImport.name}${signature ? ' ' + signature : ''}))"
    }

    private static String watType(byte type) {
        switch (type) {
            case (byte) 0x7f: return 'i32'
            case (byte) 0x7e: return 'i64'
            case (byte) 0x7d: return 'f32'
            case (byte) 0x7c: return 'f64'
            default: return 'i32'
        }
    }
}

class WasmFunctionEmitter {
    private final FunctionContext context
    private final WasmStringPool stringPool
    private final Map<FunctionDecl, FunctionContext> contextByDecl
    private final RecordLayoutCache recordLayouts
    private final Map<String, RuntimeImport> runtimeImports

    WasmFunctionEmitter(FunctionContext context,
                        WasmStringPool stringPool,
                        Map<FunctionDecl, FunctionContext> contextByDecl,
                        RecordLayoutCache recordLayouts,
                        Map<String, RuntimeImport> runtimeImports) {
        this.context = context
        this.stringPool = stringPool
        this.contextByDecl = contextByDecl
        this.recordLayouts = recordLayouts
        this.runtimeImports = runtimeImports
    }

    WasmFunctionBody emitBody() {
        WasmInstructionEmitter emitter = new WasmInstructionEmitter(context.watName)
        LocalBindings locals = LocalBindings.fromParams(context)

        context.decl.body?.statements?.each { Statement stmt ->
            emitStatement(stmt, emitter, locals)
        }
        emitter.end()

        byte[] instructions = emitter.toByteArray()

        ByteArrayOutputStream body = new ByteArrayOutputStream()
        int localCount = locals.localCount
        if (localCount > 0) {
            WasmModuleWriter.writeU32(body, 1)
            WasmModuleWriter.writeU32(body, localCount)
            body.write((byte) 0x7f)
        } else {
            WasmModuleWriter.writeU32(body, 0)
        }
        body.write(instructions)

        byte[] wrapped = wrapSize(body.toByteArray())
        List<String> localDecls = locals.localDecls()
        return new WasmFunctionBody(
                wrapped,
                context.watName,
                context.watSignature,
                localDecls,
                emitter.instructions
        )
    }

    private void emitStatement(Statement stmt, WasmInstructionEmitter emitter, LocalBindings locals) {
        switch (stmt) {
            case VarDecl:
                WasmValueType exprType = emitExpr(stmt.value, emitter, locals)
                WasmValueType declaredType = stmt.type ? WasmValueType.fromTypeName(stmt.type) : exprType
                LocalBinding binding = locals.declare(stmt.name, declaredType)
                emitter.localSet(binding.index, binding.name)
                break
            case AssignStmt:
                if (stmt.target instanceof VarRef) {
                    VarRef ref = stmt.target as VarRef
                    ensureLocal(ref.name, locals, stmt.pos?.line)
                    WasmValueType assignType = emitExpr(stmt.value, emitter, locals)
                    LocalBinding assignBinding = locals.binding(ref.name)
                    emitter.localSet(assignBinding.index, assignBinding.name)
                    break
                }
                if (stmt.target instanceof IndexAccess) {
                    emitIndexAssignment(stmt.target as IndexAccess, stmt.value, emitter, locals)
                    break
                }
                throw new IllegalStateException('Assignments currently limited to locals or index expressions')
            case PrintStmt:
                WasmValueType resultType = emitExpr(stmt.expr, emitter, locals)
                if (resultType.kind == GlyphValueKind.STRING) {
                    emitter.callImport(runtimeImports['print_str'])
                } else {
                    emitter.callImport(runtimeImports['print_i32'])
                }
                break
            case ExprStmt:
                WasmValueType type = emitExpr(stmt.expr, emitter, locals)
                if (type.kind != GlyphValueKind.VOID) {
                    emitter.drop()
                }
                break
            case ReturnStmt:
                handleReturn(stmt, emitter, locals)
                break
            default:
                throw new IllegalStateException("Unsupported statement ${stmt?.class?.simpleName}")
        }
    }

    private void handleReturn(ReturnStmt stmt, WasmInstructionEmitter emitter, LocalBindings locals) {
        if (context.returnValueType.kind == GlyphValueKind.VOID) {
            if (stmt.expr != null) {
                throw new IllegalStateException("void function cannot return a value: ${context.fqn}")
            }
            emitter.returnOp()
            return
        }
        WasmValueType returnType = emitExpr(stmt.expr, emitter, locals)
        emitter.returnOp()
    }

    private WasmValueType emitExpr(Expr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        switch (expr) {
            case IntLiteral:
                emitter.i32Const((expr.value as Number).intValue())
                return WasmValueType.i32()
            case BoolLiteral:
                emitter.i32Const(expr.value ? 1 : 0)
                return WasmValueType.i32()
            case StringLiteral:
                int offset = stringPool.intern(expr.value)
                emitter.i32Const(offset)
                return WasmValueType.stringType()
            case VarRef:
                ensureLocal(expr.name, locals, expr.pos?.line)
                LocalBinding binding = locals.binding(expr.name)
                emitter.localGet(binding.index, binding.name)
                return binding.type
            case BinaryOp:
                emitExpr(expr.left, emitter, locals)
                emitExpr(expr.right, emitter, locals)
                emitter.binary(expr.op)
                return WasmValueType.i32()
            case CallExpr:
                return emitCall(expr, emitter, locals)
            case RecordLiteral:
                return emitRecordLiteral(expr, emitter, locals)
            case FieldAccess:
                return emitFieldAccess(expr, emitter, locals, false)
            case SafeFieldAccess:
                return emitFieldAccess(expr, emitter, locals, true)
            case ArrayAllocExpr:
                return emitArrayAlloc(expr, emitter, locals)
            case IndexAccess:
                return emitIndexAccess(expr, emitter, locals)
            case MapAllocExpr:
                return emitMapAlloc(expr, emitter, locals)
            case MapLiteralExpr:
                return emitMapLiteral(expr, emitter, locals)
            case TernaryExpr:
                return emitTernary(expr, emitter, locals)
            case ElvisExpr:
                return emitElvis(expr, emitter, locals)
            case MatchExpr:
                return emitMatch(expr, emitter, locals)
            case IfExpr:
                return emitIfExpr(expr, emitter, locals)
            case NullLiteral:
                emitter.i32Const(0)
                return WasmValueType.pointer(null)
            default:
                throw new IllegalStateException("Unsupported expression ${expr?.class?.simpleName}")
        }
    }

    private WasmValueType emitCall(CallExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        FunctionDecl target = context.symbols.function(expr.callee)
        if (!target) throw new IllegalStateException("Unknown function ${expr.callee}")
        FunctionContext targetCtx = contextByDecl[target]
        expr.arguments.each { emitExpr(it, emitter, locals) }
        emitter.call(targetCtx.functionIndex, targetCtx.watName)
        return targetCtx.returnValueType
    }

    private WasmValueType emitRecordLiteral(RecordLiteral expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        RecordLayout layout = layoutForRecord(expr.typeName)
        RuntimeImport malloc = runtimeImports['glyph_malloc']
        emitter.i32Const(layout.totalSize)
        emitter.callImport(malloc)
        LocalBinding tmp = locals.declareTemp(WasmValueType.pointer(expr.typeName), '_rec')
        emitter.localSet(tmp.index, tmp.name)
        layout.fieldsByName.each { String fieldName, FieldLayout fieldLayout ->
            Expr fieldExpr = expr.fields[fieldName]
            if (fieldExpr == null) {
                throw new IllegalStateException("Missing field ${fieldName} for record literal ${expr.typeName}")
            }
            emitter.localGet(tmp.index, tmp.name)
            if (fieldLayout.offset != 0) {
                emitter.i32Const(fieldLayout.offset)
                emitter.binary('+')
            }
            emitExpr(fieldExpr, emitter, locals)
            emitter.storeI32()
        }
        emitter.localGet(tmp.index, tmp.name)
        return WasmValueType.pointer(expr.typeName)
    }

    private WasmValueType emitArrayAlloc(ArrayAllocExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        WasmValueType elementType = WasmValueType.fromTypeName(expr.elementType)
        WasmValueType sizeType = emitExpr(expr.size, emitter, locals)
        if (sizeType.kind != GlyphValueKind.I32) {
            throw new IllegalStateException("Array size must be int in ${context.fqn}")
        }
        LocalBinding length = locals.declareTemp(WasmValueType.i32(), '_len')
        emitter.localSet(length.index, length.name)

        emitter.localGet(length.index, length.name)
        emitter.i32Const(bytesFor(elementType))
        emitter.binary('*')
        emitter.i32Const(HeapLayout.POINTER_SIZE)
        emitter.binary('+')
        RuntimeImport malloc = runtimeImports['glyph_malloc']
        emitter.callImport(malloc)

        WasmValueType arrayType = WasmValueType.arrayPointer(expr.elementType)
        LocalBinding arrayPtr = locals.declareTemp(arrayType, '_arr')
        emitter.localSet(arrayPtr.index, arrayPtr.name)

        emitter.localGet(arrayPtr.index, arrayPtr.name)
        emitter.localGet(length.index, length.name)
        emitter.storeI32()

        emitter.localGet(arrayPtr.index, arrayPtr.name)
        return arrayType
    }

    private WasmValueType emitIndexAccess(IndexAccess expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        WasmValueType targetType = emitExpr(expr.target, emitter, locals)
        WasmValueType indexType = emitExpr(expr.index, emitter, locals)
        if (targetType.arrayElementType) {
            if (indexType.kind != GlyphValueKind.I32) {
                throw new IllegalStateException("Array index must be int in ${context.fqn}")
            }
            RuntimeImport getter = runtimeImports['array_get']
            emitter.callImport(getter)
            return WasmValueType.fromTypeName(targetType.arrayElementType)
        }
        if (targetType.mapValueType) {
            RuntimeImport getter = runtimeImports['map_get']
            emitter.callImport(getter)
            return WasmValueType.fromTypeName(targetType.mapValueType)
        }
        throw new IllegalStateException("Index access on unsupported target in ${context.fqn}")
    }

    private void emitIndexAssignment(IndexAccess access,
                                     Expr value,
                                     WasmInstructionEmitter emitter,
                                     LocalBindings locals) {
        WasmValueType targetType = emitExpr(access.target, emitter, locals)
        WasmValueType indexType = emitExpr(access.index, emitter, locals)
        if (targetType.arrayElementType) {
            if (indexType.kind != GlyphValueKind.I32) {
                throw new IllegalStateException("Array index must be int in ${context.fqn}")
            }
            emitExpr(value, emitter, locals)
            RuntimeImport setter = runtimeImports['array_set']
            emitter.callImport(setter)
            return
        }
        if (targetType.mapValueType) {
            emitExpr(value, emitter, locals)
            RuntimeImport setter = runtimeImports['map_put']
            emitter.callImport(setter)
            return
        }
        throw new IllegalStateException("Index assignment on unsupported target in ${context.fqn}")
    }

    private WasmValueType emitMapAlloc(MapAllocExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        if (expr.capacity != null) {
            WasmValueType capType = emitExpr(expr.capacity, emitter, locals)
            if (capType.kind != GlyphValueKind.I32) {
                throw new IllegalStateException("Map capacity must be int in ${context.fqn}")
            }
            emitter.drop()
        }
        RuntimeImport mapNew = runtimeImports['map_new']
        emitter.callImport(mapNew)
        return WasmValueType.mapPointer(expr.keyType, expr.valueType)
    }

    private WasmValueType emitMapLiteral(MapLiteralExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        RuntimeImport mapNew = runtimeImports['map_new']
        emitter.callImport(mapNew)
        WasmValueType mapType = WasmValueType.mapPointer(expr.keyType, expr.valueType)
        LocalBinding mapBinding = locals.declareTemp(mapType, '_map')
        emitter.localSet(mapBinding.index, mapBinding.name)
        expr.entries.each { entry ->
            emitter.localGet(mapBinding.index, mapBinding.name)
            emitExpr(entry.key, emitter, locals)
            emitExpr(entry.value, emitter, locals)
            emitter.callImport(runtimeImports['map_put'])
        }
        emitter.localGet(mapBinding.index, mapBinding.name)
        return mapType
    }

    private WasmValueType emitIfExpr(IfExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        if (!expr.thenBlock || !expr.elseBlock) {
            throw new IllegalStateException("if expression must include then/else blocks in ${context.fqn}")
        }
        WasmValueType conditionType = emitExpr(expr.condition, emitter, locals)
        if (conditionType.kind != GlyphValueKind.I32) {
            throw new IllegalStateException("If condition must be bool/integer in ${context.fqn}")
        }
        emitter.ifOp(true)
        WasmValueType thenType = emitBlockExpr(expr.thenBlock, emitter, locals)
        emitter.elseOp()
        WasmValueType elseType = emitBlockExpr(expr.elseBlock, emitter, locals)
        emitter.end()
        return thenType ?: elseType
    }

    private WasmValueType emitBlockExpr(Block block,
                                        WasmInstructionEmitter emitter,
                                        LocalBindings locals) {
        if (!block?.statements || block.statements.isEmpty()) {
            throw new IllegalStateException("Expression block must end with an expression in ${context.fqn}")
        }
        int lastIndex = block.statements.size() - 1
        WasmValueType result = null
        block.statements.eachWithIndex { Statement stmt, int idx ->
            boolean isLastExpr = idx == lastIndex && stmt instanceof ExprStmt
            if (isLastExpr) {
                result = emitExpr((stmt as ExprStmt).expr, emitter, locals)
            } else {
                emitStatement(stmt, emitter, locals)
            }
        }
        if (result == null) {
            throw new IllegalStateException("Expression block must yield a value in ${context.fqn}")
        }
        return result
    }

    private WasmValueType emitTernary(TernaryExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        WasmValueType conditionType = emitExpr(expr.condition, emitter, locals)
        if (conditionType.kind != GlyphValueKind.I32) {
            throw new IllegalStateException("Ternary condition must be bool in ${context.fqn}")
        }
        emitter.ifOp(true)
        WasmValueType trueType = emitExpr(expr.ifTrue, emitter, locals)
        emitter.elseOp()
        WasmValueType falseType = emitExpr(expr.ifFalse, emitter, locals)
        emitter.end()
        return trueType ?: falseType
    }

    private WasmValueType emitElvis(ElvisExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        WasmValueType leftType = emitExpr(expr.left, emitter, locals)
        LocalBinding temp = locals.declareTemp(leftType, '_elvis')
        emitter.localSet(temp.index, temp.name)
        emitter.localGet(temp.index, temp.name)
        emitter.ifOp(true)
        emitter.localGet(temp.index, temp.name)
        emitter.elseOp()
        emitExpr(expr.right, emitter, locals)
        emitter.end()
        return leftType
    }

    private WasmValueType emitMatch(MatchExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        if (!expr.elseExpr) {
            throw new IllegalStateException("match expression requires else branch in ${context.fqn}")
        }
        WasmValueType targetType = emitExpr(expr.target, emitter, locals)
        LocalBinding temp = locals.declareTemp(targetType, '_match')
        emitter.localSet(temp.index, temp.name)
        return emitMatchChain(expr.cases ?: [], 0, temp, targetType, expr.elseExpr, emitter, locals)
    }

    private WasmValueType emitMatchChain(List<MatchCase> cases,
                                         int idx,
                                         LocalBinding temp,
                                         WasmValueType targetType,
                                         Expr elseExpr,
                                         WasmInstructionEmitter emitter,
                                         LocalBindings locals) {
        if (idx >= cases.size()) {
            if (!elseExpr) {
                throw new IllegalStateException("match expression requires else branch in ${context.fqn}")
            }
            return emitExpr(elseExpr, emitter, locals)
        }
        MatchCase current = cases[idx]
        emitMatchCondition(current, temp, targetType, emitter, locals)
        emitter.ifOp(true)
        WasmValueType caseType = emitExpr(current.value, emitter, locals)
        emitter.elseOp()
        WasmValueType nextType = emitMatchChain(cases, idx + 1, temp, targetType, elseExpr, emitter, locals)
        emitter.end()
        return caseType ?: nextType
    }

    private void emitMatchCondition(MatchCase matchCase,
                                    LocalBinding temp,
                                    WasmValueType targetType,
                                    WasmInstructionEmitter emitter,
                                    LocalBindings locals) {
        emitter.localGet(temp.index, temp.name)
        WasmValueType keyType = emitExpr(matchCase.key, emitter, locals)
        if (isStringType(targetType) && isStringType(keyType)) {
            emitter.callImport(runtimeImports['str_eq'])
        } else {
            emitter.i32Eq()
        }
    }

    private WasmValueType emitFieldAccess(Object expr,
                                          WasmInstructionEmitter emitter,
                                          LocalBindings locals,
                                          boolean safe) {
        Expr targetExpr = expr.target as Expr
        String fieldName = expr.field
        WasmValueType targetType = emitExpr(targetExpr, emitter, locals)
        if (!targetType.recordType) {
            throw new IllegalStateException("Field access target is not a record in ${context.fqn}")
        }
        RecordLayout layout = layoutForRecord(targetType.recordType)
        FieldLayout fieldLayout = layout.field(fieldName)

        LocalBinding base = locals.declareTemp(targetType, '_recBase')
        emitter.localSet(base.index, base.name)

        if (safe) {
            emitter.localGet(base.index, base.name)
            if (fieldLayout.offset != 0) {
                emitter.i32Const(fieldLayout.offset)
                emitter.binary('+')
            }
            emitter.loadI32()
            emitter.i32Const(0)
            emitter.localGet(base.index, base.name)
            emitter.select()
        } else {
            emitter.localGet(base.index, base.name)
            if (fieldLayout.offset != 0) {
                emitter.i32Const(fieldLayout.offset)
                emitter.binary('+')
            }
            emitter.loadI32()
        }
        return fieldLayout.type
    }

    private RecordLayout layoutForRecord(String typeName) {
        String normalized = WasmValueType.normalizeRecordName(typeName)
        RecordDecl decl = context.symbols.record(normalized)
        if (!decl) {
            throw new IllegalStateException("Unknown record ${typeName} in ${context.fqn}")
        }
        recordLayouts.layoutFor(decl)
    }

    private static int bytesFor(WasmValueType type) {
        if (!type) {
            return HeapLayout.POINTER_SIZE
        }
        if (type.kind == GlyphValueKind.I32) {
            return 4
        }
        return HeapLayout.POINTER_SIZE
    }

    private static boolean isStringType(WasmValueType type) {
        type != null && type.kind == GlyphValueKind.STRING
    }

    private static byte[] wrapSize(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        WasmModuleWriter.writeU32(out, content.length)
        out.write(content)
        return out.toByteArray()
    }

    private static void ensureLocal(String name, LocalBindings locals, Integer line) {
        if (!locals.has(name)) {
            String suffix = line ? " at line ${line}" : ''
            throw new IllegalStateException("Unknown local '${name}'${suffix}")
        }
    }
}

class WasmInstructionEmitter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream()
    final List<String> instructions = []
    private final String watName

    WasmInstructionEmitter(String watName) {
        this.watName = watName
    }

    void i32Const(int value) {
        out.write((byte) 0x41)
        WasmModuleWriter.writeU32(out, value)
        instructions << "i32.const ${value}"
    }

    void localGet(int index, String name) {
        out.write((byte) 0x20)
        WasmModuleWriter.writeU32(out, index)
        instructions << "local.get \$${name ?: index}"
    }

    void localSet(int index, String name) {
        out.write((byte) 0x21)
        WasmModuleWriter.writeU32(out, index)
        instructions << "local.set \$${name ?: index}"
    }

    void call(int functionIndex, String watTarget) {
        out.write((byte) 0x10)
        WasmModuleWriter.writeU32(out, functionIndex)
        instructions << "call ${watTarget}"
    }

    void callImport(RuntimeImport runtimeImport) {
        call(runtimeImport.functionIndex, "\$${runtimeImport.name}")
    }

    void binary(String op) {
        int opcode
        String watOp
        switch (op) {
            case '+': opcode = 0x6a; watOp = 'i32.add'; break
            case '-': opcode = 0x6b; watOp = 'i32.sub'; break
            case '*': opcode = 0x6c; watOp = 'i32.mul'; break
            case '/': opcode = 0x6d; watOp = 'i32.div_s'; break
            default: throw new IllegalStateException("Unsupported binary op ${op}")
        }
        out.write((byte) opcode)
        instructions << watOp
    }

    void loadI32() {
        out.write((byte) 0x28)
        WasmModuleWriter.writeU32(out, 2)
        WasmModuleWriter.writeU32(out, 0)
        instructions << 'i32.load'
    }

    void storeI32() {
        out.write((byte) 0x36)
        WasmModuleWriter.writeU32(out, 2)
        WasmModuleWriter.writeU32(out, 0)
        instructions << 'i32.store'
    }

    void ifOp(boolean hasResult) {
        out.write((byte) 0x04)
        out.write(hasResult ? WasmModuleWriter.VAL_I32 : (byte) 0x40)
        instructions << (hasResult ? 'if (result i32)' : 'if')
    }

    void elseOp() {
        out.write((byte) 0x05)
        instructions << 'else'
    }

    void i32Eq() {
        out.write((byte) 0x46)
        instructions << 'i32.eq'
    }

    void select() {
        out.write((byte) 0x1b)
        instructions << 'select'
    }

    void drop() {
        out.write((byte) 0x1a)
        instructions << 'drop'
    }

    void returnOp() {
        out.write((byte) 0x0f)
        instructions << 'return'
    }

    void end() {
        out.write((byte) 0x0b)
        instructions << 'end'
    }

    byte[] toByteArray() {
        return out.toByteArray()
    }
}

class WasmFunctionBody {
    final byte[] binary
    final String watName
    final String watSignature
    final List<String> localDecls
    final List<String> watInstructions

    WasmFunctionBody(byte[] binary,
                     String watName,
                     String watSignature,
                     List<String> localDecls,
                     List<String> watInstructions) {
        this.binary = binary
        this.watName = watName
        this.watSignature = watSignature
        this.localDecls = localDecls
        this.watInstructions = watInstructions
    }
}

class ExportSpec {
    final String name
    final byte kind
    final int index
    final FunctionContext context

    ExportSpec(String name, byte kind, int index, FunctionContext context) {
        this.name = name
        this.kind = kind
        this.index = index
        this.context = context
    }
}

class FunctionContext {
    final String fqn
    final FunctionDecl decl
    final ResolvedSymbols symbols
    final String pkg
    final String watName
    final List<String> paramNames
    final List<WasmValueType> paramValueTypes
    final List<Byte> paramTypes
    final WasmValueType returnValueType
    final GlyphValueKind returnKind
    int typeIndex
    int functionIndex

    FunctionContext(String fqn, FunctionDecl decl, ResolvedSymbols symbols, String pkg) {
        this.fqn = fqn
        this.decl = decl
        this.symbols = symbols
        this.pkg = pkg
        this.watName = "\$${fqn.replace('.', '_')}"
        this.paramNames = decl.params.collect { it.name }
        this.paramValueTypes = decl.params.collect { WasmValueType.fromTypeName(it.type) }
        this.paramTypes = paramValueTypes.collect { WasmModuleWriter.VAL_I32 }
        this.returnValueType = WasmValueType.fromTypeName(decl.returnType)
        this.returnKind = returnValueType.kind
    }

    String getWatSignature() {
        String params = decl.params.collect { "(param \$${it.name} i32)" }.join(' ')
        String returns = returnKind == GlyphValueKind.VOID ? '' : ' (result i32)'
        return params ? " ${params}${returns}" : returns
    }
}

enum GlyphValueKind {
    VOID,
    I32,
    STRING,
    POINTER
}

class WasmValueType {
    final GlyphValueKind kind
    final String recordType
    final String arrayElementType
    final String mapKeyType
    final String mapValueType

    WasmValueType(GlyphValueKind kind,
                  String recordType = null,
                  String arrayElementType = null,
                  String mapKeyType = null,
                  String mapValueType = null) {
        this.kind = kind
        this.recordType = recordType
        this.arrayElementType = arrayElementType
        this.mapKeyType = mapKeyType
        this.mapValueType = mapValueType
    }

    static WasmValueType i32() {
        new WasmValueType(GlyphValueKind.I32)
    }

    static WasmValueType stringType() {
        new WasmValueType(GlyphValueKind.STRING)
    }

    static WasmValueType pointer(String recordType = null) {
        new WasmValueType(GlyphValueKind.POINTER, recordType)
    }

    static WasmValueType arrayPointer(String elementType) {
        new WasmValueType(GlyphValueKind.POINTER, null, elementType?.trim())
    }

    static WasmValueType mapPointer(String keyType, String valueType) {
        new WasmValueType(GlyphValueKind.POINTER, null, null, keyType?.trim(), valueType?.trim())
    }

    static WasmValueType fromTypeName(String typeName) {
        if (!typeName) {
            return pointer(null)
        }
        String normalized = typeName.trim()
        boolean nullable = normalized.endsWith('?')
        if (nullable) {
            normalized = normalized.substring(0, normalized.length() - 1).trim()
        }
        if (normalized.startsWith('[') && normalized.endsWith(']')) {
            String inner = normalized.substring(1, normalized.length() - 1).trim()
            if (inner.contains(':')) {
                int idx = inner.indexOf(':')
                String key = inner.substring(0, idx).trim()
                String value = inner.substring(idx + 1).trim()
                return mapPointer(key, value)
            }
            return arrayPointer(inner)
        }
        switch (normalized.toLowerCase()) {
            case '':
                return pointer(null)
            case 'void':
                return new WasmValueType(GlyphValueKind.VOID)
            case 'int':
            case 'bool':
                return i32()
            case 'string':
                return stringType()
            default:
                return pointer(normalized)
        }
    }

    static String normalizeRecordName(String typeName) {
        if (!typeName) {
            return typeName
        }
        String normalized = typeName.trim()
        if (normalized.endsWith('?')) {
            normalized = normalized.substring(0, normalized.length() - 1).trim()
        }
        normalized
    }
}

class LocalBindings {
    private final Map<String, LocalBinding> bindings = [:]
    private final List<LocalBinding> ordered = []
    private int nextIndex
    private int paramCount
    private int tempCounter = 0

    static LocalBindings fromParams(FunctionContext ctx) {
        LocalBindings locals = new LocalBindings()
        ctx.paramNames.eachWithIndex { String name, int idx ->
            WasmValueType type = ctx.paramValueTypes[idx]
            LocalBinding binding = new LocalBinding(name, idx, type)
            locals.bindings[name] = binding
            locals.ordered << binding
        }
        locals.paramCount = ctx.paramNames.size()
        locals.nextIndex = locals.paramCount
        return locals
    }

    LocalBinding declare(String name, WasmValueType type) {
        LocalBinding binding = new LocalBinding(name, nextIndex++, type)
        bindings[name] = binding
        ordered << binding
        return binding
    }

    LocalBinding declareTemp(WasmValueType type, String baseName) {
        String name = "_${baseName}${tempCounter++}"
        LocalBinding binding = new LocalBinding(name, nextIndex++, type)
        bindings[name] = binding
        ordered << binding
        return binding
    }

    boolean has(String name) {
        bindings.containsKey(name)
    }

    LocalBinding binding(String name) {
        bindings[name]
    }

    int getLocalCount() {
        nextIndex - paramCount
    }

    List<String> localDecls() {
        ordered.findAll { it.index >= paramCount }
                .collect { "(local \$${it.name} i32)" }
    }
}

class LocalBinding {
    final String name
    final int index
    final WasmValueType type

    LocalBinding(String name, int index, WasmValueType type) {
        this.name = name
        this.index = index
        this.type = type
    }
}

class WasmStringPool {
    private final LinkedHashMap<String, Integer> offsets = new LinkedHashMap<>()
    private int cursor = 0

    int intern(String value) {
        if (offsets.containsKey(value)) {
            return offsets[value]
        }
        byte[] bytes = (value + "\u0000").getBytes('UTF-8')
        int offset = cursor
        cursor += bytes.length
        offsets[value] = offset
        return offset
    }

    List<byte[]> buildSegments() {
        List<byte[]> segments = []
        offsets.each { String value, Integer offset ->
            byte[] bytes = (value + "\u0000").getBytes('UTF-8')
            ByteArrayOutputStream segment = new ByteArrayOutputStream()
            segment.write((byte) 0x00)
            ByteArrayOutputStream init = new ByteArrayOutputStream()
            init.write((byte) 0x41)
            WasmModuleWriter.writeU32(init, offset)
            init.write((byte) 0x0b)
            segment.write(init.toByteArray())
            WasmModuleWriter.writeVec(segment, [bytes])
            segments << segment.toByteArray()
        }
        segments
    }

    List<StringPoolEntry> getEntries() {
        offsets.collect { key, value ->
            new StringPoolEntry(value, (key + "\u0000").getBytes('UTF-8'))
        }
    }
}

class StringPoolEntry {
    final int offset
    final byte[] bytes

    StringPoolEntry(int offset, byte[] bytes) {
        this.offset = offset
        this.bytes = bytes
    }
}

class WasmTypeSectionBuilder {
    private final Map<String, Integer> indexes = [:]
    final List<byte[]> entries = []

    int register(byte[] params, byte[] results) {
        String key = "${params.encodeHex().toString()}::${results.encodeHex().toString()}"
        if (indexes.containsKey(key)) {
            return indexes[key]
        }
        ByteArrayOutputStream type = new ByteArrayOutputStream()
        type.write((byte) 0x60)
        WasmModuleWriter.writeVec(type, params.collect { [(byte) it] as byte[] })
        WasmModuleWriter.writeVec(type, results.collect { [(byte) it] as byte[] })
        entries << type.toByteArray()
        int idx = entries.size() - 1
        indexes[key] = idx
        return idx
    }
}
