package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.ArrayAllocExpr
import biz.digitalindustry.glyph.core.ast.AssignStmt
import biz.digitalindustry.glyph.core.ast.BinaryOp
import biz.digitalindustry.glyph.core.ast.Block
import biz.digitalindustry.glyph.core.ast.BoolLiteral
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
import biz.digitalindustry.glyph.core.ast.MatchCase
import biz.digitalindustry.glyph.core.ast.MatchExpr
import biz.digitalindustry.glyph.core.ast.Pattern
import biz.digitalindustry.glyph.core.ast.RecordPattern
import biz.digitalindustry.glyph.core.ast.SumTypeDecl
import biz.digitalindustry.glyph.core.ast.RecordFieldPattern
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.ElvisExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarRef
import biz.digitalindustry.glyph.core.ast.CallExpr
import biz.digitalindustry.glyph.core.ast.VarPattern
import biz.digitalindustry.glyph.core.ast.WildcardPattern
import biz.digitalindustry.glyph.core.ast.LiteralPattern
import biz.digitalindustry.glyph.core.ast.VariantPattern
import biz.digitalindustry.glyph.core.ast.LambdaExpr

import java.util.Collections
import java.util.Objects

interface Interpreter {
    void eval(Program program)
}

class SimpleInterpreter implements Interpreter {
    public static final SimpleInterpreter INSTANCE = new SimpleInterpreter()
    private Map<String, FunctionDecl> activeFunctions = [:]
    private Map<String, VariantRuntimeInfo> activeVariants = [:]

    @Override
    void eval(Program program) {
        eval(program, new ProjectIndex())
    }

    void eval(Program program, ProjectIndex index) {
        ProjectIndex effectiveIndex = index ?: new ProjectIndex()
        SymbolResolver resolver = new SymbolResolver(effectiveIndex)
        ResolvedSymbols symbols = resolver.resolve(program)
        new TypeChecker(effectiveIndex).check(program)
        Map<String, FunctionDecl> functions = symbols.functions
        Map<String, RecordDecl> recordDefs = symbols.records
        Map<String, SumTypeDecl> sumTypes = new LinkedHashMap<>(symbols.sumTypes ?: [:])
        program.sumTypes.each { sumType -> sumTypes[sumType.name] = sumType }
        Map<String, VariantRuntimeInfo> variants = buildVariantRuntimeInfo(sumTypes, functions)
        FunctionDecl mainFn = functions['main']
        if (!mainFn) {
            throw new IllegalStateException('main function not found')
        }
        this.activeFunctions = functions
        this.activeVariants = variants
        try {
            invokeFunction(mainFn, Collections.emptyList(), recordDefs)
        } finally {
            this.activeFunctions = [:]
            this.activeVariants = [:]
        }
    }

    private Map<String, VariantRuntimeInfo> buildVariantRuntimeInfo(Map<String, SumTypeDecl> sumTypes,
                                                                    Map<String, FunctionDecl> functions) {
        Map<String, VariantRuntimeInfo> variants = [:]
        sumTypes?.values()?.each { SumTypeDecl sumType ->
            sumType?.variants?.each { variant ->
                if (variants.containsKey(variant.name) || functions.containsKey(variant.name)) {
                    throw new IllegalStateException("Constructor ${variant.name} already defined")
                }
                variants[variant.name] = new VariantRuntimeInfo(sumType.name,
                        variant.name,
                        variant.fields?.collect { it.name } ?: Collections.emptyList())
            }
        }
        return variants
    }

    private Object invokeFunction(FunctionDecl fn, List<Object> args, Map<String, RecordDecl> recordDefs) {
        if (!fn) {
            throw new IllegalStateException('Attempted to invoke null function')
        }
        if (fn.params.size() != args.size()) {
            throw new IllegalStateException("Function ${fn.name} expects ${fn.params.size()} argument(s) but received ${args.size()}")
        }
        Map<String, Object> env = [:]
        fn.params.eachWithIndex { param, idx ->
            env[param.name] = args[idx]
        }
        try {
            return evalBlockValue(fn.body, env, recordDefs)
        } catch (ReturnSignal rs) {
            return rs.value
        }
    }

    private Object evalExpr(Expr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        switch (expr) {
            case IntLiteral:
                return expr.value
            case BoolLiteral:
                return expr.value
            case StringLiteral:
                return expr.value
            case NullLiteral:
                return null
            case VarRef:
                if (!env.containsKey(expr.name)) {
                    throw new IllegalStateException("undefined variable ${expr.name}")
                }
                return env[expr.name]
            case RecordLiteral:
                return evalRecordLiteral(expr, env, recordDefs)
            case FieldAccess:
                return evalFieldAccess(expr, env, recordDefs)
            case SafeFieldAccess:
                return evalSafeFieldAccess(expr, env, recordDefs)
            case IndexAccess:
                return evalIndexAccess(expr, env, recordDefs)
            case ArrayAllocExpr:
                return evalArrayAlloc(expr, env, recordDefs)
            case MapAllocExpr:
                return evalMapAlloc(expr, env, recordDefs)
            case MapLiteralExpr:
                return evalMapLiteral(expr, env, recordDefs)
            case IfExpr:
                return evalIf(expr, env, recordDefs)
            case TernaryExpr:
                return evalTernary(expr, env, recordDefs)
            case ElvisExpr:
                Object left = evalExpr(expr.left, env, recordDefs)
                return left != null ? left : evalExpr(expr.right, env, recordDefs)
            case MatchExpr:
                return evalMatch(expr, env, recordDefs)
            case CallExpr:
                return evalCall(expr as CallExpr, env, recordDefs)
            case LambdaExpr:
                return evalLambda(expr as LambdaExpr, env)
            case BinaryOp:
                Object left = evalExpr(expr.left, env, recordDefs)
                Object right = evalExpr(expr.right, env, recordDefs)
                switch (expr.op) {
                    case '+':
                    case '-':
                    case '*':
                    case '/':
                        if (!(left instanceof Number) || !(right instanceof Number)) {
                            throw new IllegalArgumentException("Operator ${expr.op} expects integers")
                        }
                        long l = ((Number) left).longValue()
                        long r = ((Number) right).longValue()
                        switch (expr.op) {
                            case '+': return l + r
                            case '-': return l - r
                            case '*': return l * r
                            case '/':
                                if (r == 0L) throw new IllegalArgumentException('division by zero')
                                return l / r
                        }
                        break
                    case '<':
                    case '<=':
                    case '>':
                    case '>=':
                        if (!(left instanceof Number) || !(right instanceof Number)) {
                            throw new IllegalArgumentException("Operator ${expr.op} expects integers")
                        }
                        long lc = ((Number) left).longValue()
                        long rc = ((Number) right).longValue()
                        switch (expr.op) {
                            case '<': return lc < rc
                            case '<=': return lc <= rc
                            case '>': return lc > rc
                            case '>=': return lc >= rc
                        }
                        break
                    case '==':
                        return Objects.equals(left, right)
                    case '!=':
                        return !Objects.equals(left, right)
                    default:
                        throw new IllegalStateException("Unknown operator ${expr.op}")
                }
            default:
                throw new IllegalStateException("Unknown expression type: ${expr?.class?.simpleName}")
        }
    }

    private Object evalIf(IfExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object cond = evalExpr(expr.condition, env, recordDefs)
        if (!(cond instanceof Boolean)) {
            throw new IllegalArgumentException('if condition must be bool')
        }
        if (cond) {
            return evalBlockValue(expr.thenBlock, env, recordDefs)
        }
        if (expr.elseBlock != null) {
            return evalBlockValue(expr.elseBlock, env, recordDefs)
        }
        return null
    }

    private Object evalTernary(TernaryExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object cond = evalExpr(expr.condition, env, recordDefs)
        if (!(cond instanceof Boolean)) {
            throw new IllegalArgumentException('ternary condition must be bool')
        }
        return cond ? evalExpr(expr.ifTrue, env, recordDefs) : evalExpr(expr.ifFalse, env, recordDefs)
    }

    private Object evalMatch(MatchExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object target = evalExpr(expr.target, env, recordDefs)
        for (MatchCase c in expr.cases) {
            Map<String, Object> bindings = matchPattern(c.pattern, target, recordDefs)
            if (bindings != null) {
                Map<String, Object> branchEnv = new LinkedHashMap<>(env)
                bindings.each { k, v -> branchEnv[k] = v }
                return evalExpr(c.value, branchEnv, recordDefs)
            }
        }
        if (expr.elseExpr != null) {
            return evalExpr(expr.elseExpr, env, recordDefs)
        }
        return null
    }

    private Object evalBlockValue(Block block, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object lastValue = null
        block.statements.each { stmt ->
            switch (stmt) {
                case VarDecl:
                    env[stmt.name] = evalExpr(stmt.value, env, recordDefs)
                    lastValue = null
                    break
                case AssignStmt:
                    applyAssign(stmt, env, recordDefs)
                    lastValue = null
                    break
                case PrintStmt:
                    println evalExpr(stmt.expr, env, recordDefs)
                    lastValue = null
                    break
                case ExprStmt:
                    lastValue = evalExpr(stmt.expr, env, recordDefs)
                    break
                case ReturnStmt:
                    Object value = stmt.expr != null ? evalExpr(stmt.expr, env, recordDefs) : null
                    throw new ReturnSignal(value)
                default:
                    throw new IllegalStateException("Unknown statement type: ${stmt?.class?.simpleName}")
            }
        }
        return lastValue
    }

    private void applyAssign(AssignStmt stmt, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        if (stmt.target instanceof VarRef) {
            env[(stmt.target as VarRef).name] = evalExpr(stmt.value, env, recordDefs)
            return
        }
        if (stmt.target instanceof FieldAccess) {
            FieldAccess access = stmt.target as FieldAccess
            Object target = evalExpr(access.target, env, recordDefs)
            if (!(target instanceof RecordInstance)) {
                throw new IllegalArgumentException('field assignment on non-record')
            }
            RecordInstance rec = target as RecordInstance
            if (rec.immutableFields.contains(access.field)) {
                throw new IllegalArgumentException("field ${access.field} is immutable")
            }
            rec.fields[access.field] = evalExpr(stmt.value, env, recordDefs)
            return
        }
        if (stmt.target instanceof IndexAccess) {
            IndexAccess access = stmt.target as IndexAccess
            Object target = evalExpr(access.target, env, recordDefs)
            Object index = evalExpr(access.index, env, recordDefs)
            Object value = evalExpr(stmt.value, env, recordDefs)
            if (target instanceof List) {
                int idx = (index as Number).intValue()
                target[idx] = value
                return
            }
            if (target instanceof Map) {
                target[index] = value
                return
            }
            throw new IllegalArgumentException('index assignment on non-collection')
        }
        throw new IllegalArgumentException('invalid assignment target')
    }

    private Object evalCall(CallExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        if (env.containsKey(expr.callee)) {
            Object candidate = env[expr.callee]
            if (candidate instanceof ClosureValue) {
                List<Object> args = expr.arguments.collect { arg -> evalExpr(arg, env, recordDefs) }
                return invokeClosure(candidate as ClosureValue, args, recordDefs)
            }
        }
        VariantRuntimeInfo constructor = activeVariants[expr.callee]
        if (constructor) {
            List<Object> args = expr.arguments.collect { arg -> evalExpr(arg, env, recordDefs) }
            if (args.size() != constructor.fieldNames.size()) {
                throw new IllegalStateException("Constructor ${expr.callee} expects ${constructor.fieldNames.size()} argument(s) but received ${args.size()}")
            }
            return new VariantInstance(constructor.sumTypeName, constructor.variantName, args)
        }
        FunctionDecl fn = activeFunctions[expr.callee]
        if (!fn) {
            throw new IllegalStateException("Unknown function ${expr.callee}")
        }
        List<Object> args = expr.arguments.collect { arg -> evalExpr(arg, env, recordDefs) }
        return invokeFunction(fn, args, recordDefs)
    }

    private ClosureValue evalLambda(LambdaExpr expr, Map<String, Object> env) {
        Map<String, Object> captured = [:]
        expr.captures.each { cap ->
            captured[cap.name] = env[cap.name]
        }
        return new ClosureValue(expr, captured)
    }

    private Object invokeClosure(ClosureValue closure, List<Object> args, Map<String, RecordDecl> recordDefs) {
        LambdaExpr lambda = closure.lambda
        if (lambda.params.size() != args.size()) {
            throw new IllegalStateException("Callable expects ${lambda.params.size()} argument(s) but received ${args.size()}")
        }
        Map<String, Object> frame = [:]
        frame.putAll(closure.captured)
        lambda.params.eachWithIndex { param, idx ->
            frame[param.name] = args[idx]
        }
        try {
            return evalBlockValue(lambda.body, frame, recordDefs)
        } catch (ReturnSignal rs) {
            return rs.value
        }
    }

    private Object evalRecordLiteral(RecordLiteral expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        RecordDecl record = recordDefs[expr.typeName]
        if (!record) {
            throw new IllegalArgumentException("Unknown record ${expr.typeName}")
        }
        Map<String, Object> values = [:]
        Set<String> immut = [] as Set<String>
        record.fields.each { field ->
            if (!expr.fields.containsKey(field.name)) {
                throw new IllegalArgumentException("Missing field ${field.name}")
            }
            values[field.name] = evalExpr(expr.fields[field.name], env, recordDefs)
            if (field.mutability == biz.digitalindustry.glyph.core.ast.Mutability.VAL) {
                immut.add(field.name)
            }
        }
        return new RecordInstance(record.name, values, immut)
    }

    private Object evalFieldAccess(FieldAccess expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object target = evalExpr(expr.target, env, recordDefs)
        if (!(target instanceof RecordInstance)) {
            throw new IllegalArgumentException('field access on non-record')
        }
        return (target as RecordInstance).fields[expr.field]
    }

    private Object evalSafeFieldAccess(SafeFieldAccess expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object target = evalExpr(expr.target, env, recordDefs)
        if (target == null) {
            return null
        }
        if (!(target instanceof RecordInstance)) {
            throw new IllegalArgumentException('safe field access on non-record')
        }
        return (target as RecordInstance).fields[expr.field]
    }

    private Object evalIndexAccess(IndexAccess expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object target = evalExpr(expr.target, env, recordDefs)
        Object index = evalExpr(expr.index, env, recordDefs)
        if (target instanceof List) {
            return target[(index as Number).intValue()]
        }
        if (target instanceof Map) {
            return target[index]
        }
        throw new IllegalArgumentException('index access on non-collection')
    }

    private Object evalArrayAlloc(ArrayAllocExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Object sizeVal = evalExpr(expr.size, env, recordDefs)
        int size = (sizeVal as Number).intValue()
        List<Object> out = []
        for (int i = 0; i < size; i++) {
            out.add(null)
        }
        return out
    }

    private Object evalMapAlloc(MapAllocExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        evalExpr(expr.capacity, env, recordDefs)
        return [:]
    }

    private Object evalMapLiteral(MapLiteralExpr expr, Map<String, Object> env, Map<String, RecordDecl> recordDefs) {
        Map<Object, Object> map = [:]
        expr.entries.each { entry ->
            Object key = evalExpr(entry.key, env, recordDefs)
            Object value = evalExpr(entry.value, env, recordDefs)
            map[key] = value
        }
        return map
    }

    private Map<String, Object> matchPattern(Pattern pattern,
                                             Object value,
                                             Map<String, RecordDecl> recordDefs) {
        switch (pattern) {
            case WildcardPattern:
                return [:]
            case VarPattern:
                Map<String, Object> single = [:]
                single[(pattern as VarPattern).name] = value
                return single
            case LiteralPattern:
                Object expected = literalValue((pattern as LiteralPattern).literal)
                return Objects.equals(value, expected) ? [:] : null
            case RecordPattern:
                return matchRecordPattern(pattern as RecordPattern, value, recordDefs)
            case VariantPattern:
                return matchVariantPattern(pattern as VariantPattern, value, recordDefs)
            default:
                throw new IllegalStateException("Unsupported pattern ${pattern?.class?.simpleName}")
        }
    }

    private Map<String, Object> matchRecordPattern(RecordPattern pattern,
                                                   Object value,
                                                   Map<String, RecordDecl> recordDefs) {
        if (!(value instanceof RecordInstance)) {
            return null
        }
        RecordInstance rec = value as RecordInstance
        if (rec.name != pattern.typeName) {
            return null
        }
        Map<String, Object> bindings = [:]
        for (RecordFieldPattern fieldPattern : pattern.fields) {
            Object fieldValue = rec.fields[fieldPattern.field]
            if (!rec.fields.containsKey(fieldPattern.field)) {
                return null
            }
            Map<String, Object> nested = matchPattern(fieldPattern.pattern, fieldValue, recordDefs)
            if (nested == null) {
                return null
            }
            bindings.putAll(nested)
        }
        return bindings
    }

    private Map<String, Object> matchVariantPattern(VariantPattern pattern,
                                                    Object value,
                                                    Map<String, RecordDecl> recordDefs) {
        if (!(value instanceof VariantInstance)) {
            return null
        }
        VariantInstance instance = value as VariantInstance
        VariantRuntimeInfo info = activeVariants[instance.variant]
        if (!info) {
            return null
        }
        if (pattern.typeName && pattern.typeName != info.sumTypeName) {
            return null
        }
        if (info.sumTypeName != instance.sumType || pattern.variantName != instance.variant) {
            return null
        }
        if (pattern.fields.size() != instance.values.size()) {
            return null
        }
        Map<String, Object> bindings = [:]
        for (int i = 0; i < pattern.fields.size(); i++) {
            Pattern child = pattern.fields[i]
            Object fieldValue = instance.values[i]
            Map<String, Object> nested = matchPattern(child, fieldValue, recordDefs)
            if (nested == null) {
                return null
            }
            bindings.putAll(nested)
        }
        return bindings
    }

    private Object literalValue(Expr expr) {
        switch (expr) {
            case IntLiteral:
                return expr.value
            case StringLiteral:
                return expr.value
            case BoolLiteral:
                return expr.value
            case NullLiteral:
                return null
            default:
                throw new IllegalArgumentException("Invalid literal pattern")
        }
    }
}

class ReturnSignal extends RuntimeException {
    final Object value

    ReturnSignal(Object value) {
        this.value = value
    }
}

class RecordInstance {
    final String name
    final Map<String, Object> fields
    final Set<String> immutableFields

    RecordInstance(String name, Map<String, Object> fields, Set<String> immutableFields) {
        this.name = name
        this.fields = fields
        this.immutableFields = immutableFields
    }
}

class VariantRuntimeInfo {
    final String sumTypeName
    final String variantName
    final List<String> fieldNames

    VariantRuntimeInfo(String sumTypeName, String variantName, List<String> fieldNames) {
        this.sumTypeName = sumTypeName
        this.variantName = variantName
        this.fieldNames = fieldNames ?: Collections.emptyList()
    }
}

class VariantInstance {
    final String sumType
    final String variant
    final List<Object> values

    VariantInstance(String sumType, String variant, List<Object> values) {
        this.sumType = sumType
        this.variant = variant
        this.values = values ?: Collections.emptyList()
    }
}

class ClosureValue {
    final LambdaExpr lambda
    final Map<String, Object> captured

    ClosureValue(LambdaExpr lambda, Map<String, Object> captured) {
        this.lambda = lambda
        this.captured = captured ?: [:]
    }
}
