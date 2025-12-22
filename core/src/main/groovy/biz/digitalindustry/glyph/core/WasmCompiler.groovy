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
import biz.digitalindustry.glyph.core.ast.LambdaExpr
import biz.digitalindustry.glyph.core.ast.CapturedVar
import biz.digitalindustry.glyph.core.ast.MapAllocExpr
import biz.digitalindustry.glyph.core.ast.MapLiteralExpr
import biz.digitalindustry.glyph.core.ast.MatchCase
import biz.digitalindustry.glyph.core.ast.MatchExpr
import biz.digitalindustry.glyph.core.ast.Pattern
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordFieldPattern
import biz.digitalindustry.glyph.core.ast.RecordPattern
import biz.digitalindustry.glyph.core.ast.SumTypeDecl
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.SafeFieldAccess
import biz.digitalindustry.glyph.core.ast.Statement
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.VarPattern
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarRef
import biz.digitalindustry.glyph.core.ast.VariantPattern
import biz.digitalindustry.glyph.core.ast.WildcardPattern
import biz.digitalindustry.glyph.core.ast.LiteralPattern
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

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
    private final SumTypeRegistry sumTypeRegistry
    private final List<FunctionContext> functionContexts
    private final Map<FunctionDecl, FunctionContext> contextByDecl
    private final Map<LambdaExpr, FunctionContext> contextByLambda
    private final FunctionContext entryContext
    private final ClosureSignatureRegistry closureSignatures = new ClosureSignatureRegistry()
    private final boolean needsTable
    private int lambdaCounter = 0

    WasmModuleWriter(ProjectIndex index, Program entryProgram) {
        this.index = index
        this.entryProgram = entryProgram
        this.resolver = new SymbolResolver(index)
        this.sumTypeRegistry = new SumTypeRegistry()
        Tuple2<List<FunctionContext>, Map<FunctionDecl, FunctionContext>> tuple = buildFunctionContexts()
        this.functionContexts = tuple.v1
        this.contextByDecl = tuple.v2
        this.contextByLambda = registerLambdaContexts()
        this.needsTable = !contextByLambda.isEmpty()
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

        if (needsTable) {
            writeTableSection(out)
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

        if (needsTable) {
            writeElementSection(out)
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
        sb.append('(module\n')
        runtimeImports.each {
            sb.append(formatImportWat(it)).append('\n')
        }
        sb.append('  (memory $memory 1)\n')
        collectExports().each { ExportSpec spec ->
            if (spec.kind == 0x02) {
                sb.append("  (export \"${spec.name}\" (memory \$memory))\n")
            } else {
                sb.append("  (export \"${spec.name}\" (func ${spec.context.watName}))\n")
            }
        }

        emitFunctionBodies().each { body ->
            sb.append("  (func ${body.watName}${body.watSignature}\n")
            body.localDecls.each { sb.append("    ${it}\n") }
            body.watInstructions.each { sb.append("    ${it}\n") }
            sb.append('  )\n')
        }

        stringPool.entries.each { entry ->
            String escaped = entry.bytes.collect { String.format("\\%02x", it & 0xff) }.join('')
            sb.append("  (data (i32.const ${entry.offset}) \"${escaped}\")\n")
        }
        sb.append(')')
        return sb.toString()
    }

    private List<ExportSpec> collectExports() {
        List<ExportSpec> specs = []
        specs << new ExportSpec('memory', (byte) 0x02, 0, null)
        functionContexts.findAll { !it.isLambda() }.each { ctx ->
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
            if (ctx.closureSignature != null) {
                ctx.closureSignature.typeIndex = ctx.typeIndex
            }
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
                    contextByLambda,
                    recordLayouts,
                    runtimeImportMap,
                    sumTypeRegistry
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
            sumTypeRegistry.registerSymbols(symbols, program)
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

    private Map<LambdaExpr, FunctionContext> registerLambdaContexts() {
        Map<LambdaExpr, FunctionContext> lambdaMap = [:]
        List<FunctionContext> lambdaContexts = []
        functionContexts.each { FunctionContext ctx ->
            lambdaContexts.addAll(collectLambdaContexts(ctx, lambdaMap))
        }
        functionContexts.addAll(lambdaContexts)
        return lambdaMap
    }

    private List<FunctionContext> collectLambdaContexts(FunctionContext owner,
                                                        Map<LambdaExpr, FunctionContext> lambdaMap) {
        List<FunctionContext> contexts = []
        LambdaCollector.collect(owner) { LambdaExpr expr ->
            ClosureLayout layout = buildClosureLayout(expr)
            ClosureSignature signature = closureSignatures.signatureFor(expr)
            String name = "${owner.fqn}\$lambda${lambdaCounter++}"
            FunctionContext lambdaCtx = FunctionContext.lambdaContext(
                    name,
                    expr,
                    owner.symbols,
                    owner.pkg,
                    owner,
                    layout,
                    signature
            )
            lambdaMap[expr] = lambdaCtx
            contexts << lambdaCtx
            contexts.addAll(collectLambdaContexts(lambdaCtx, lambdaMap))
        }
        return contexts
    }

    private ClosureLayout buildClosureLayout(LambdaExpr expr) {
        if (!expr?.captures) {
            return ClosureLayout.empty()
        }
        int offset = 0
        List<CapturedFieldLayout> fields = []
        expr.captures.each { CapturedVar cap ->
            offset = HeapLayout.align(offset)
            WasmValueType type = wasmValueForCapture(cap.type)
            fields << new CapturedFieldLayout(cap.name, offset, type)
            offset += HeapLayout.POINTER_SIZE
        }
        offset = HeapLayout.align(offset)
        return new ClosureLayout(fields, offset)
    }

    private WasmValueType wasmValueForCapture(TypeRef typeRef) {
        if (typeRef == null) {
            return WasmValueType.pointer(null)
        }
        if (typeRef instanceof PrimitiveType) {
            PrimitiveType primitive = typeRef as PrimitiveType
            switch (primitive.type) {
                case Type.INT:
                case Type.BOOL:
                case Type.LONG:
                case Type.FLOAT:
                case Type.DOUBLE:
                case Type.CHAR:
                    return WasmValueType.i32()
                case Type.STRING:
                    return WasmValueType.stringType()
                default:
                    return WasmValueType.pointer(null)
            }
        }
        if (typeRef instanceof NullableType) {
            return WasmValueType.pointer(null)
        }
        if (typeRef instanceof RecordType) {
            return WasmValueType.pointer((typeRef as RecordType).name)
        }
        if (typeRef instanceof ArrayType) {
            return WasmValueType.arrayPointer(typeName((typeRef as ArrayType).element))
        }
        if (typeRef instanceof MapType) {
            MapType map = typeRef as MapType
            return WasmValueType.mapPointer(typeName(map.key), typeName(map.value))
        }
        if (typeRef instanceof LambdaType) {
            LambdaType lambda = typeRef as LambdaType
            List<String> params = lambda.parameters.collect { typeName(it.type) }
            String ret = typeName(lambda.returnType) ?: 'void'
            ClosureSignature signature = closureSignatures.signatureForTypes(params, ret)
            return WasmValueType.closure(signature)
        }
        return WasmValueType.pointer(null)
    }

    private static String typeName(TypeRef ref) {
        if (ref == null) return ''
        if (ref instanceof PrimitiveType) {
            return (ref as PrimitiveType).type.name().toLowerCase()
        }
        if (ref instanceof NullableType) {
            return "${typeName((ref as NullableType).inner)}?"
        }
        if (ref instanceof RecordType) {
            return (ref as RecordType).name
        }
        if (ref instanceof ArrayType) {
            return "[${typeName((ref as ArrayType).element)}]"
        }
        if (ref instanceof MapType) {
            MapType map = ref as MapType
            return "[${typeName(map.key)}:${typeName(map.value)}]"
        }
        if (ref instanceof LambdaType) {
            LambdaType lambda = ref as LambdaType
            String params = lambda.parameters.collect { typeName(it.type) }.join(', ')
            return "(${params}) -> ${typeName(lambda.returnType)}"
        }
        return ''
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
        functionContexts.eachWithIndex { ctx, idx ->
            ctx.functionIndex = importCount + idx
            ctx.tableIndex = idx
        }
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

    private void writeTableSection(ByteArrayOutputStream out) {
        writeSection(out, 4) {
            writeU32(it, 1)
            it.write((byte) 0x70)
            it.write((byte) 0x00)
            writeU32(it, functionContexts.size())
        }
    }

    private void writeElementSection(ByteArrayOutputStream out) {
        writeSection(out, 9) {
            ByteArrayOutputStream segment = new ByteArrayOutputStream()
            segment.write((byte) 0x00)
            segment.write((byte) 0x41)
            writeU32(segment, 0)
            segment.write((byte) 0x0b)
            List<byte[]> functionIndices = functionContexts.collect { encU32(it.functionIndex) }
            writeVec(segment, functionIndices)
            writeVec(it, [segment.toByteArray()])
        }
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
    private static final int CLOSURE_FUNC_OFFSET = 0
    private static final int CLOSURE_ENV_OFFSET = HeapLayout.POINTER_SIZE
    private static final int CLOSURE_SIZE = HeapLayout.POINTER_SIZE * 2
    private final FunctionContext context
    private final WasmStringPool stringPool
    private final Map<FunctionDecl, FunctionContext> contextByDecl
    private final Map<LambdaExpr, FunctionContext> lambdaContexts
    private final RecordLayoutCache recordLayouts
    private final Map<String, RuntimeImport> runtimeImports
    private final SumTypeRegistry sumTypeRegistry

    WasmFunctionEmitter(FunctionContext context,
                        WasmStringPool stringPool,
                        Map<FunctionDecl, FunctionContext> contextByDecl,
                        Map<LambdaExpr, FunctionContext> lambdaContexts,
                        RecordLayoutCache recordLayouts,
                        Map<String, RuntimeImport> runtimeImports,
                        SumTypeRegistry sumTypeRegistry) {
        this.context = context
        this.stringPool = stringPool
        this.contextByDecl = contextByDecl
        this.lambdaContexts = lambdaContexts
        this.recordLayouts = recordLayouts
        this.runtimeImports = runtimeImports
        this.sumTypeRegistry = sumTypeRegistry
    }

    WasmFunctionBody emitBody() {
        WasmInstructionEmitter emitter = new WasmInstructionEmitter(context.watName)
        LocalBindings locals = LocalBindings.fromParams(context)

        context.statements().each { Statement stmt ->
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
            case NullLiteral:
                emitter.i32Const(0)
                return WasmValueType.pointer(null)
            case VarRef:
                return emitVariableLoad(expr.name, emitter, locals, expr.pos?.line)
            case BinaryOp:
                return emitBinary(expr as BinaryOp, emitter, locals)
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
            case LambdaExpr:
                return emitLambda(expr as LambdaExpr, emitter, locals)
            default:
                throw new IllegalStateException("Unsupported expression ${expr?.class?.simpleName}")
        }
    }

    private WasmValueType emitCall(CallExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        if (locals.has(expr.callee)) {
            LocalBinding binding = locals.binding(expr.callee)
            if (binding.type?.closureSignature) {
                return emitClosureCall(binding, expr.arguments, emitter, locals)
            }
        }
        VariantRuntime variant = sumTypeRegistry.variant(expr.callee)
        if (variant) {
            return emitVariantConstructor(expr, variant, emitter, locals)
        }
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

    private WasmValueType emitLambda(LambdaExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        FunctionContext lambdaCtx = lambdaContexts[expr]
        if (!lambdaCtx) {
            throw new IllegalStateException("Lambda context not registered in ${context.fqn}")
        }
        ClosureLayout layout = lambdaCtx.closureLayout ?: ClosureLayout.empty()
        RuntimeImport malloc = runtimeImports['glyph_malloc']

        LocalBinding envPtr = locals.declareTemp(WasmValueType.pointer(null), '_lambdaEnv')
        if (layout.totalSize > 0) {
            emitter.i32Const(layout.totalSize)
            emitter.callImport(malloc)
            emitter.localSet(envPtr.index, envPtr.name)
            layout.fields.each { CapturedFieldLayout field ->
                emitter.localGet(envPtr.index, envPtr.name)
                if (field.offset != 0) {
                    emitter.i32Const(field.offset)
                    emitter.binary('+')
                }
                emitVariableLoad(field.name, emitter, locals, null)
                emitter.storeI32()
            }
        } else {
            emitter.i32Const(0)
            emitter.localSet(envPtr.index, envPtr.name)
        }

        emitter.i32Const(CLOSURE_SIZE)
        emitter.callImport(malloc)
        LocalBinding closurePtr = locals.declareTemp(WasmValueType.closure(lambdaCtx.closureSignature), '_closure')
        emitter.localSet(closurePtr.index, closurePtr.name)

        emitter.localGet(closurePtr.index, closurePtr.name)
        emitter.i32Const(lambdaCtx.tableIndex)
        emitter.storeI32()

        emitter.localGet(closurePtr.index, closurePtr.name)
        if (CLOSURE_ENV_OFFSET != 0) {
            emitter.i32Const(CLOSURE_ENV_OFFSET)
            emitter.binary('+')
        }
        emitter.localGet(envPtr.index, envPtr.name)
        emitter.storeI32()

        emitter.localGet(closurePtr.index, closurePtr.name)
        return WasmValueType.closure(lambdaCtx.closureSignature)
    }

    private WasmValueType emitClosureCall(LocalBinding binding,
                                          List<Expr> arguments,
                                          WasmInstructionEmitter emitter,
                                          LocalBindings locals) {
        ClosureSignature signature = binding.type?.closureSignature
        if (signature == null) {
            throw new IllegalStateException("Closure signature missing for ${binding.name} in ${context.fqn}")
        }

        emitter.localGet(binding.index, binding.name)
        if (CLOSURE_ENV_OFFSET != 0) {
            emitter.i32Const(CLOSURE_ENV_OFFSET)
            emitter.binary('+')
        }
        emitter.loadI32()

        arguments.each { emitExpr(it, emitter, locals) }

        emitter.localGet(binding.index, binding.name)
        emitter.loadI32()
        emitter.callIndirect(signature.typeIndex)
        return signature.returnType
    }

    private WasmValueType emitVariableLoad(String name,
                                           WasmInstructionEmitter emitter,
                                           LocalBindings locals,
                                           Integer line) {
        if (locals.has(name)) {
            LocalBinding binding = locals.binding(name)
            emitter.localGet(binding.index, binding.name)
            return binding.type
        }
        if (context.isLambda() && context.closureLayout?.hasField(name)) {
            return emitCaptureLoad(name, emitter, locals)
        }
        ensureLocal(name, locals, line)
        LocalBinding binding = locals.binding(name)
        emitter.localGet(binding.index, binding.name)
        return binding.type
    }

    private WasmValueType emitCaptureLoad(String name,
                                          WasmInstructionEmitter emitter,
                                          LocalBindings locals) {
        if (!context.isLambda() || !context.closureLayout) {
            throw new IllegalStateException("Capture ${name} not available in ${context.fqn}")
        }
        CapturedFieldLayout field = context.closureLayout.field(name)
        LocalBinding env = locals.binding(context.envParamName)
        emitter.localGet(env.index, env.name)
        if (field.offset != 0) {
            emitter.i32Const(field.offset)
            emitter.binary('+')
        }
        emitter.loadI32()
        return field.type
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

    private WasmValueType emitBinary(BinaryOp expr, WasmInstructionEmitter emitter, LocalBindings locals) {
        WasmValueType leftType = emitExpr(expr.left, emitter, locals)
        WasmValueType rightType = emitExpr(expr.right, emitter, locals)
        switch (expr.op) {
            case '+':
            case '-':
            case '*':
            case '/':
                emitter.binary(expr.op)
                return WasmValueType.i32()
            case '<':
                emitter.i32Lt()
                return WasmValueType.i32()
            case '<=':
                emitter.i32Le()
                return WasmValueType.i32()
            case '>':
                emitter.i32Gt()
                return WasmValueType.i32()
            case '>=':
                emitter.i32Ge()
                return WasmValueType.i32()
            case '==':
                emitEquality(leftType, rightType, emitter, false)
                return WasmValueType.i32()
            case '!=':
                emitEquality(leftType, rightType, emitter, true)
                return WasmValueType.i32()
            default:
                throw new IllegalStateException("Unsupported operator ${expr.op} in ${context.fqn}")
        }
    }

    private WasmValueType emitVariantConstructor(CallExpr expr,
                                                 VariantRuntime variant,
                                                 WasmInstructionEmitter emitter,
                                                 LocalBindings locals) {
        int expectedArgs = variant.fieldTypes.size()
        if (expr.arguments.size() != expectedArgs) {
            throw new IllegalStateException("Constructor ${variant.variantName} expects ${expectedArgs} argument(s) but received ${expr.arguments.size()} in ${context.fqn}")
        }
        RuntimeImport malloc = runtimeImports['glyph_malloc']
        emitter.i32Const(variant.totalSize)
        emitter.callImport(malloc)
        WasmValueType pointerType = WasmValueType.pointer(variant.sumTypeName)
        LocalBinding ptr = locals.declareTemp(pointerType, '_variant')
        emitter.localSet(ptr.index, ptr.name)

        // tag
        emitter.localGet(ptr.index, ptr.name)
        emitter.i32Const(variant.tag)
        emitter.storeI32()

        variant.fieldTypes.eachWithIndex { String typeName, int idx ->
            emitter.localGet(ptr.index, ptr.name)
            int offset = HeapLayout.POINTER_SIZE * (idx + 1)
            if (offset != 0) {
                emitter.i32Const(offset)
                emitter.binary('+')
            }
            emitExpr(expr.arguments[idx], emitter, locals)
            emitter.storeI32()
        }

        emitter.localGet(ptr.index, ptr.name)
        return pointerType
    }

    private void emitEquality(WasmValueType leftType,
                              WasmValueType rightType,
                              WasmInstructionEmitter emitter,
                              boolean negate) {
        if (isStringType(leftType) && isStringType(rightType)) {
            emitter.callImport(runtimeImports['str_eq'])
            if (negate) {
                emitter.i32Eqz()
            }
            return
        }
        if (negate) {
            emitter.i32Ne()
        } else {
            emitter.i32Eq()
        }
    }

    private WasmValueType emitMatch(MatchExpr expr, WasmInstructionEmitter emitter, LocalBindings locals) {
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
            if (elseExpr) {
                return emitExpr(elseExpr, emitter, locals)
            }
            throw new IllegalStateException("match expression missing fallback in ${context.fqn}")
        }
        MatchCase current = cases[idx]
        boolean lastCase = idx == cases.size() - 1 && elseExpr == null
        if (lastCase && isCatchAllPattern(current.pattern)) {
            LocalBindings branchLocals = locals.fork()
            emitPatternBindings(current.pattern, temp, targetType, emitter, branchLocals)
            return emitExpr(current.value, emitter, branchLocals)
        }
        emitPatternCondition(current.pattern, temp, targetType, emitter, locals)
        emitter.ifOp(true)
        LocalBindings branchLocals = locals.fork()
        emitPatternBindings(current.pattern, temp, targetType, emitter, branchLocals)
        WasmValueType caseType = emitExpr(current.value, emitter, branchLocals)
        emitter.elseOp()
        WasmValueType nextType = emitMatchChain(cases, idx + 1, temp, targetType, elseExpr, emitter, locals)
        emitter.end()
        return caseType ?: nextType
    }

    private void emitPatternCondition(Pattern pattern,
                                      LocalBinding temp,
                                      WasmValueType targetType,
                                      WasmInstructionEmitter emitter,
                                      LocalBindings locals) {
        switch (pattern) {
            case WildcardPattern:
            case VarPattern:
                emitter.i32Const(1)
                return
            case LiteralPattern:
                emitter.localGet(temp.index, temp.name)
                WasmValueType literalType = emitExpr((pattern as LiteralPattern).literal, emitter, locals)
                if (isStringType(targetType) && isStringType(literalType)) {
                    emitter.callImport(runtimeImports['str_eq'])
                } else {
                    emitter.i32Eq()
                }
                return
            case RecordPattern:
                emitRecordPatternCondition(pattern as RecordPattern, temp, targetType, emitter, locals)
                return
            case VariantPattern:
                emitVariantPatternCondition(pattern as VariantPattern, temp, targetType, emitter, locals)
                return
            default:
                throw new IllegalStateException("Unsupported pattern ${pattern?.class?.simpleName} in ${context.fqn}")
        }
    }

    private void emitRecordPatternCondition(RecordPattern pattern,
                                            LocalBinding temp,
                                            WasmValueType targetType,
                                            WasmInstructionEmitter emitter,
                                            LocalBindings locals) {
        if (!targetType.recordType) {
            throw new IllegalStateException("Record pattern ${pattern.typeName} requires record target in ${context.fqn}")
        }
        emitter.localGet(temp.index, temp.name)
        emitter.i32Eqz()
        emitter.ifOp(true)
        emitter.i32Const(0)
        emitter.elseOp()
        emitter.i32Const(1)
        RecordLayout layout = layoutForRecord(pattern.typeName)
        pattern.fields.each { RecordFieldPattern fieldPattern ->
            Pattern inner = fieldPattern.pattern
            if (inner instanceof LiteralPattern) {
                FieldLayout fieldLayout = layout.field(fieldPattern.field)
                emitLoadRecordField(temp, fieldLayout, emitter)
                WasmValueType literalType = emitExpr((inner as LiteralPattern).literal, emitter, locals)
                if (isStringType(fieldLayout.type) && isStringType(literalType)) {
                    emitter.callImport(runtimeImports['str_eq'])
                } else {
                    emitter.i32Eq()
                }
                emitter.i32And()
            }
            // wildcard/var patterns don't add extra conditions
        }
        emitter.end()
    }

    private void emitVariantPatternCondition(VariantPattern pattern,
                                             LocalBinding temp,
                                             WasmValueType targetType,
                                             WasmInstructionEmitter emitter,
                                             LocalBindings locals) {
        VariantRuntime variant = resolveVariantPattern(pattern, targetType)
        emitter.localGet(temp.index, temp.name)
        emitter.i32Eqz()
        emitter.ifOp(true)
        emitter.i32Const(0)
        emitter.elseOp()
        emitter.localGet(temp.index, temp.name)
        emitter.loadI32()
        emitter.i32Const(variant.tag)
        emitter.i32Eq()
        pattern.fields.eachWithIndex { Pattern inner, int idx ->
            if (isCatchAllPattern(inner)) {
                return
            }
            WasmValueType fieldType = WasmValueType.fromTypeName(variant.fieldTypes[idx])
            LocalBinding fieldTemp = locals.declareTemp(fieldType, "_variantField${idx}")
            emitLoadVariantField(temp, idx, emitter)
            emitter.localSet(fieldTemp.index, fieldTemp.name)
            emitPatternCondition(inner, fieldTemp, fieldType, emitter, locals)
            emitter.i32And()
        }
        emitter.end()
    }

    private void emitPatternBindings(Pattern pattern,
                                     LocalBinding temp,
                                     WasmValueType targetType,
                                     WasmInstructionEmitter emitter,
                                     LocalBindings locals) {
        switch (pattern) {
            case VarPattern:
                LocalBinding binding = ensurePatternBinding((pattern as VarPattern).name, targetType, locals)
                emitter.localGet(temp.index, temp.name)
                emitter.localSet(binding.index, binding.name)
                return
            case RecordPattern:
                emitRecordPatternBindings(pattern as RecordPattern, temp, emitter, locals)
                return
            case VariantPattern:
                emitVariantPatternBindings(pattern as VariantPattern, temp, targetType, emitter, locals)
                return
            case WildcardPattern:
            case LiteralPattern:
                return
            default:
                throw new IllegalStateException("Unsupported pattern ${pattern?.class?.simpleName} in ${context.fqn}")
        }
    }

    private void emitRecordPatternBindings(RecordPattern pattern,
                                           LocalBinding temp,
                                           WasmInstructionEmitter emitter,
                                           LocalBindings locals) {
        RecordLayout layout = layoutForRecord(pattern.typeName)
        pattern.fields.each { RecordFieldPattern fieldPattern ->
            Pattern inner = fieldPattern.pattern
            if (inner instanceof VarPattern) {
                FieldLayout fieldLayout = layout.field(fieldPattern.field)
                LocalBinding binding = ensurePatternBinding((inner as VarPattern).name, fieldLayout.type, locals)
                emitLoadRecordField(temp, fieldLayout, emitter)
                emitter.localSet(binding.index, binding.name)
            }
        }
    }

    private void emitVariantPatternBindings(VariantPattern pattern,
                                            LocalBinding temp,
                                            WasmValueType targetType,
                                            WasmInstructionEmitter emitter,
                                            LocalBindings locals) {
        VariantRuntime variant = resolveVariantPattern(pattern, targetType)
        pattern.fields.eachWithIndex { Pattern inner, int idx ->
            WasmValueType fieldType = WasmValueType.fromTypeName(variant.fieldTypes[idx])
            LocalBinding fieldTemp = locals.declareTemp(fieldType, "_variantFieldBind${idx}")
            emitLoadVariantField(temp, idx, emitter)
            emitter.localSet(fieldTemp.index, fieldTemp.name)
            emitPatternBindings(inner, fieldTemp, fieldType, emitter, locals)
        }
    }

    private LocalBinding ensurePatternBinding(String name,
                                              WasmValueType type,
                                              LocalBindings locals) {
        if (locals.has(name)) {
            return locals.binding(name)
        }
        return locals.declare(name, type)
    }

    private void emitLoadRecordField(LocalBinding base,
                                     FieldLayout layout,
                                     WasmInstructionEmitter emitter) {
        emitter.localGet(base.index, base.name)
        if (layout.offset != 0) {
            emitter.i32Const(layout.offset)
            emitter.binary('+')
        }
        emitter.loadI32()
    }

    private void emitLoadVariantField(LocalBinding base,
                                      int fieldIndex,
                                      WasmInstructionEmitter emitter) {
        emitter.localGet(base.index, base.name)
        int offset = HeapLayout.POINTER_SIZE * (fieldIndex + 1)
        if (offset != 0) {
            emitter.i32Const(offset)
            emitter.binary('+')
        }
        emitter.loadI32()
    }

    private VariantRuntime resolveVariantPattern(VariantPattern pattern,
                                                 WasmValueType targetType) {
        VariantRuntime variant = sumTypeRegistry.variant(pattern.variantName)
        if (!variant) {
            throw new IllegalStateException("Unknown variant ${pattern.variantName} in ${context.fqn}")
        }
        String expected = pattern.typeName ?: targetType?.recordType
        String normalized = WasmValueType.normalizeRecordName(expected)
        if (normalized && normalized != variant.sumTypeName) {
            throw new IllegalStateException("Pattern ${pattern.variantName} does not match ${normalized} in ${context.fqn}")
        }
        return variant
    }

    private boolean isCatchAllPattern(Pattern pattern) {
        pattern instanceof WildcardPattern || pattern instanceof VarPattern
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
    
    void callIndirect(int typeIndex) {
        out.write((byte) 0x11)
        WasmModuleWriter.writeU32(out, typeIndex)
        out.write((byte) 0x00)
        instructions << "call_indirect (type ${typeIndex})"
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

    void i32Ne() {
        out.write((byte) 0x47)
        instructions << 'i32.ne'
    }

    void i32Lt() {
        out.write((byte) 0x48)
        instructions << 'i32.lt_s'
    }

    void i32Gt() {
        out.write((byte) 0x4a)
        instructions << 'i32.gt_s'
    }

    void i32Le() {
        out.write((byte) 0x4c)
        instructions << 'i32.le_s'
    }

    void i32Ge() {
        out.write((byte) 0x4e)
        instructions << 'i32.ge_s'
    }

    void i32Eqz() {
        out.write((byte) 0x45)
        instructions << 'i32.eqz'
    }

    void i32And() {
        out.write((byte) 0x71)
        instructions << 'i32.and'
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
    final LambdaExpr lambda
    final ResolvedSymbols symbols
    final String pkg
    final String watName
    final List<String> paramNames
    final List<WasmValueType> paramValueTypes
    final List<Byte> paramTypes
    final WasmValueType returnValueType
    final GlyphValueKind returnKind
    final ClosureLayout closureLayout
    final ClosureSignature closureSignature
    final String envParamName
    final FunctionContext parent
    int typeIndex
    int functionIndex
    int tableIndex = -1

    FunctionContext(String fqn, FunctionDecl decl, ResolvedSymbols symbols, String pkg) {
        this.fqn = fqn
        this.decl = decl
        this.lambda = null
        this.symbols = symbols
        this.pkg = pkg
        this.watName = "\$${fqn.replace('.', '_')}"
        this.paramNames = decl.params.collect { it.name }
        this.paramValueTypes = decl.params.collect { WasmValueType.fromTypeName(it.type) }
        this.paramTypes = paramValueTypes.collect { WasmModuleWriter.VAL_I32 }
        this.returnValueType = WasmValueType.fromTypeName(decl.returnType)
        this.returnKind = returnValueType.kind
        this.closureLayout = null
        this.closureSignature = null
        this.envParamName = null
        this.parent = null
    }

    private FunctionContext(String fqn,
                            LambdaExpr lambda,
                            ResolvedSymbols symbols,
                            String pkg,
                            FunctionContext parent,
                            ClosureLayout closureLayout,
                            ClosureSignature closureSignature) {
        this.fqn = fqn
        this.decl = null
        this.lambda = lambda
        this.symbols = symbols
        this.pkg = pkg
        this.parent = parent
        this.closureLayout = closureLayout
        this.closureSignature = closureSignature
        this.envParamName = '_env'
        this.watName = "\$${fqn.replace('.', '_')}"
        List<String> names = [envParamName]
        names.addAll(lambda.params.collect { it.name })
        this.paramNames = names
        List<WasmValueType> valueTypes = [WasmValueType.pointer(null)]
        valueTypes.addAll(lambda.params.collect { WasmValueType.fromTypeName(it.type) })
        this.paramValueTypes = valueTypes
        this.paramTypes = paramValueTypes.collect { WasmModuleWriter.VAL_I32 }
        this.returnValueType = WasmValueType.fromTypeName(lambda.resolvedReturnType ?: 'void')
        this.returnKind = returnValueType.kind
    }

    String getWatSignature() {
        String params = paramNames.collect { "(param \$${it} i32)" }.join(' ')
        String returns = returnKind == GlyphValueKind.VOID ? '' : ' (result i32)'
        return params ? " ${params}${returns}" : returns
    }

    static FunctionContext lambdaContext(String fqn,
                                         LambdaExpr lambda,
                                         ResolvedSymbols symbols,
                                         String pkg,
                                         FunctionContext parent,
                                         ClosureLayout layout,
                                         ClosureSignature signature) {
        return new FunctionContext(fqn, lambda, symbols, pkg, parent, layout, signature)
    }

    boolean isLambda() {
        return lambda != null
    }

    List<Statement> statements() {
        if (lambda != null) {
            return lambda.body?.statements ?: []
        }
        return decl.body?.statements ?: []
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
    final ClosureSignature closureSignature

    WasmValueType(GlyphValueKind kind,
                  String recordType = null,
                  String arrayElementType = null,
                  String mapKeyType = null,
                  String mapValueType = null,
                  ClosureSignature closureSignature = null) {
        this.kind = kind
        this.recordType = recordType
        this.arrayElementType = arrayElementType
        this.mapKeyType = mapKeyType
        this.mapValueType = mapValueType
        this.closureSignature = closureSignature
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

    static WasmValueType closure(ClosureSignature signature) {
        new WasmValueType(GlyphValueKind.POINTER, null, null, null, null, signature)
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

    boolean isClosure() {
        return closureSignature != null
    }
}

class SumTypeRegistry {
    private final Map<String, SumTypeRuntime> sumTypes = [:]
    private final Map<String, VariantRuntime> variants = [:]

    void registerSymbols(ResolvedSymbols symbols, Program program) {
        symbols?.sumTypes?.values()?.each { registerSumType(it) }
        program?.sumTypes?.each { registerSumType(it) }
    }

    private void registerSumType(SumTypeDecl decl) {
        if (!decl || sumTypes.containsKey(decl.name)) {
            return
        }
        List<VariantRuntime> infos = []
        decl.variants?.eachWithIndex { variant, int idx ->
            if (variants.containsKey(variant.name)) {
                throw new IllegalStateException("Constructor ${variant.name} already defined")
            }
            VariantRuntime runtime = new VariantRuntime(decl.name, variant.name, idx, variant.fields?.collect { it.type } ?: [])
            variants[variant.name] = runtime
            infos << runtime
        }
        sumTypes[decl.name] = new SumTypeRuntime(decl.name, infos)
    }

    VariantRuntime variant(String name) {
        variants[name]
    }
}

class SumTypeRuntime {
    final String name
    final List<VariantRuntime> variants

    SumTypeRuntime(String name, List<VariantRuntime> variants) {
        this.name = name
        this.variants = variants ?: Collections.emptyList()
    }
}

class VariantRuntime {
    final String sumTypeName
    final String variantName
    final int tag
    final List<String> fieldTypes
    final int totalSize

    VariantRuntime(String sumTypeName, String variantName, int tag, List<String> fieldTypes) {
        this.sumTypeName = sumTypeName
        this.variantName = variantName
        this.tag = tag
        this.fieldTypes = fieldTypes ?: Collections.emptyList()
        this.totalSize = HeapLayout.POINTER_SIZE * (1 + this.fieldTypes.size())
    }
}

class LocalBindings {
    private final Map<String, LocalBinding> bindings
    private final List<LocalBinding> ordered
    private final AtomicInteger nextIndex
    private final AtomicInteger tempCounter
    private final int paramCount

    private LocalBindings(Map<String, LocalBinding> bindings,
                          List<LocalBinding> ordered,
                          AtomicInteger nextIndex,
                          AtomicInteger tempCounter,
                          int paramCount) {
        this.bindings = bindings
        this.ordered = ordered
        this.nextIndex = nextIndex
        this.tempCounter = tempCounter
        this.paramCount = paramCount
    }

    static LocalBindings fromParams(FunctionContext ctx) {
        Map<String, LocalBinding> bindings = new LinkedHashMap<>()
        List<LocalBinding> ordered = []
        ctx.paramNames.eachWithIndex { String name, int idx ->
            WasmValueType type = ctx.paramValueTypes[idx]
            LocalBinding binding = new LocalBinding(name, idx, type)
            bindings[name] = binding
            ordered << binding
        }
        int paramCount = ctx.paramNames.size()
        AtomicInteger nextIndex = new AtomicInteger(paramCount)
        AtomicInteger tempCounter = new AtomicInteger(0)
        return new LocalBindings(bindings, ordered, nextIndex, tempCounter, paramCount)
    }

    LocalBindings fork() {
        return new LocalBindings(new LinkedHashMap<>(bindings), ordered, nextIndex, tempCounter, paramCount)
    }

    LocalBinding declare(String name, WasmValueType type) {
        LocalBinding binding = new LocalBinding(name, nextIndex.getAndIncrement(), type)
        bindings[name] = binding
        ordered << binding
        return binding
    }

    LocalBinding declareTemp(WasmValueType type, String baseName) {
        String name = "_${baseName}${tempCounter.getAndIncrement()}"
        LocalBinding binding = new LocalBinding(name, nextIndex.getAndIncrement(), type)
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
        nextIndex.get() - paramCount
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

class ClosureSignature {
    final List<String> parameterTypeNames
    final String returnTypeName
    final List<WasmValueType> params
    final WasmValueType returnType
    int typeIndex = -1

    ClosureSignature(List<String> parameterTypeNames, String returnTypeName) {
        this.parameterTypeNames = parameterTypeNames.collect { it?.trim() ?: '' }
        this.returnTypeName = (returnTypeName ?: 'void').trim()
        this.params = this.parameterTypeNames.collect { WasmValueType.fromTypeName(it) }
        this.returnType = WasmValueType.fromTypeName(this.returnTypeName)
    }

    String key() {
        return "${parameterTypeNames.join(',')}->${returnTypeName}"
    }
}

class ClosureSignatureRegistry {
    private final Map<String, ClosureSignature> signatures = [:]

    ClosureSignature signatureFor(LambdaExpr expr) {
        List<String> params = expr.params.collect {
            if (!it.type) {
                throw new IllegalStateException("Lambda parameter type is required${expr.pos ? " at line ${expr.pos.line}" : ''}")
            }
            it.type.trim()
        }
        String returnName = (expr.resolvedReturnType ?: 'void').trim()
        return signatureForTypes(params, returnName)
    }

    ClosureSignature signatureForTypes(List<String> params, String returnType) {
        String key = "${params.collect { it?.trim() ?: '' }.join(',')}->${(returnType ?: 'void').trim()}"
        return signatures.computeIfAbsent(key) { new ClosureSignature(params, returnType) }
    }
}

class ClosureLayout {
    private static final ClosureLayout EMPTY = new ClosureLayout([], 0)
    final List<CapturedFieldLayout> fields
    private final Map<String, CapturedFieldLayout> fieldsByName
    final int totalSize

    ClosureLayout(List<CapturedFieldLayout> fields, int totalSize) {
        this.fields = fields ?: []
        this.totalSize = totalSize
        this.fieldsByName = this.fields.collectEntries { [(it.name): it] }
    }

    static ClosureLayout empty() {
        return EMPTY
    }

    boolean hasField(String name) {
        fieldsByName.containsKey(name)
    }

    CapturedFieldLayout field(String name) {
        CapturedFieldLayout layout = fieldsByName[name]
        if (!layout) {
            throw new IllegalStateException("Unknown captured field ${name}")
        }
        return layout
    }
}

class CapturedFieldLayout {
    final String name
    final int offset
    final WasmValueType type

    CapturedFieldLayout(String name, int offset, WasmValueType type) {
        this.name = name
        this.offset = offset
        this.type = type
    }
}

class LambdaCollector {
    static void collect(FunctionContext ctx, Closure<LambdaExpr> consumer) {
        collectStatements(ctx?.statements(), consumer)
    }

    private static void collectStatements(List<Statement> statements, Closure<LambdaExpr> consumer) {
        statements?.each { visitStatement(it, consumer) }
    }

    private static void visitStatement(Statement stmt, Closure<LambdaExpr> consumer) {
        switch (stmt) {
            case VarDecl:
                visitExpr(stmt.value, consumer)
                break
            case AssignStmt:
                visitExpr(stmt.target, consumer)
                visitExpr(stmt.value, consumer)
                break
            case PrintStmt:
                visitExpr(stmt.expr, consumer)
                break
            case ExprStmt:
                visitExpr(stmt.expr, consumer)
                break
            case ReturnStmt:
                visitExpr(stmt.expr, consumer)
                break
            case Block:
                collectStatements(stmt.statements, consumer)
                break
            default:
                break
        }
    }

    private static void visitExpr(Expr expr, Closure<LambdaExpr> consumer) {
        if (expr == null) return
        if (expr instanceof LambdaExpr) {
            consumer.call(expr as LambdaExpr)
            collectStatements((expr as LambdaExpr).body?.statements, consumer)
            return
        }
        switch (expr) {
            case BinaryOp:
                visitExpr(expr.left, consumer)
                visitExpr(expr.right, consumer)
                break
            case CallExpr:
                expr.arguments.each { visitExpr(it, consumer) }
                break
            case RecordLiteral:
                expr.fields.values().each { visitExpr(it, consumer) }
                break
            case FieldAccess:
            case SafeFieldAccess:
                visitExpr(expr.target, consumer)
                break
            case ArrayAllocExpr:
                visitExpr(expr.size, consumer)
                break
            case IndexAccess:
                visitExpr(expr.target, consumer)
                visitExpr(expr.index, consumer)
                break
            case MapAllocExpr:
                visitExpr(expr.capacity, consumer)
                break
            case MapLiteralExpr:
                expr.entries.each {
                    visitExpr(it.key, consumer)
                    visitExpr(it.value, consumer)
                }
                break
            case TernaryExpr:
                visitExpr(expr.condition, consumer)
                visitExpr(expr.ifTrue, consumer)
                visitExpr(expr.ifFalse, consumer)
                break
            case ElvisExpr:
                visitExpr(expr.left, consumer)
                visitExpr(expr.right, consumer)
                break
            case MatchExpr:
                visitExpr(expr.target, consumer)
                expr.cases.each {
                    visitPattern(it.pattern, consumer)
                    visitExpr(it.value, consumer)
                }
                visitExpr(expr.elseExpr, consumer)
                break
            case IfExpr:
                visitExpr(expr.condition, consumer)
                collectStatements(expr.thenBlock?.statements, consumer)
                collectStatements(expr.elseBlock?.statements, consumer)
                break
            default:
                break
        }
    }

    private static void visitPattern(Pattern pattern, Closure<LambdaExpr> consumer) {
        if (pattern == null) return
        switch (pattern) {
            case LiteralPattern:
                visitExpr(pattern.literal, consumer)
                break
            case RecordPattern:
                pattern.fields.each { visitPattern(it.pattern, consumer) }
                break
            default:
                break
        }
    }
}
