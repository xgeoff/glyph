package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.ArrayAllocExpr
import biz.digitalindustry.glyph.core.ast.AssignStmt
import biz.digitalindustry.glyph.core.ast.BinaryOp
import biz.digitalindustry.glyph.core.ast.Block
import biz.digitalindustry.glyph.core.ast.BoolLiteral
import biz.digitalindustry.glyph.core.ast.CallExpr
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
import biz.digitalindustry.glyph.core.ast.Mutability
import biz.digitalindustry.glyph.core.ast.RecordFieldPattern
import biz.digitalindustry.glyph.core.ast.RecordPattern
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.LambdaExpr
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.ElvisExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarRef
import biz.digitalindustry.glyph.core.ast.TypeAliasDecl
import biz.digitalindustry.glyph.core.ast.SumTypeDecl
import biz.digitalindustry.glyph.core.ast.VarPattern
import biz.digitalindustry.glyph.core.ast.WildcardPattern
import biz.digitalindustry.glyph.core.ast.LiteralPattern
import biz.digitalindustry.glyph.core.ast.VariantPattern
import biz.digitalindustry.glyph.core.ast.CapturedVar
import biz.digitalindustry.glyph.core.SourcePos

import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Collections

class TypeChecker {
    private final Map<String, RecordDecl> recordDefs = [:]
    private final Map<String, FunctionDecl> functionDefs = [:]
    private final Map<String, TypeAliasDecl> aliasDefs = [:]
    private final Map<String, SumTypeDecl> sumTypeDefs = [:]
    private final Map<String, VariantInfo> variantConstructors = [:]
    private final Map<String, TypeRef> aliasCache = [:]
    private final SymbolResolver resolver
    private final Deque<Map<String, Mutability>> mutabilityStack = new ArrayDeque<>()
    private final Deque<LambdaCaptureContext> lambdaStack = new ArrayDeque<>()

    TypeChecker(ProjectIndex index = null) {
        this.resolver = new SymbolResolver(index ?: new ProjectIndex())
    }

    void check(Program program) {
        ResolvedSymbols symbols = resolver.resolve(program)
        recordDefs.clear()
        recordDefs.putAll(symbols.records)
        functionDefs.clear()
        functionDefs.putAll(symbols.functions)
        aliasDefs.clear()
        aliasDefs.putAll(symbols.aliases)
        sumTypeDefs.clear()
        sumTypeDefs.putAll(symbols.sumTypes)
        program.sumTypes.each { SumTypeDecl sumType ->
            sumTypeDefs[sumType.name] = sumType
        }
        variantConstructors.clear()
        aliasCache.clear()
        program.records.each { record ->
            record.fields.each { field ->
                resolveType(field.type, field.pos)
            }
        }
        program.typeAliases.each { TypeAliasDecl alias ->
            aliasDefs[alias.name] = alias
        }
        aliasDefs.values().each { validateAlias(it) }
        sumTypeDefs.values().each { registerSumType(it) }

        program.functions.each { checkFunction(it) }
    }

    private void validateAlias(TypeAliasDecl alias) {
        if (alias == null) {
            return
        }
        resolveAlias(alias.name, alias.pos ?: SourcePos.UNKNOWN, new LinkedHashSet<String>())
    }

    private void registerSumType(SumTypeDecl sumType) {
        if (sumType == null) {
            return
        }
        if (!sumType.variants || sumType.variants.isEmpty()) {
            throw new IllegalArgumentException("Sum type ${sumType.name} must declare at least one variant${pos(sumType.pos)}")
        }
        Set<String> seenVariants = new LinkedHashSet<>()
        sumType.variants.each { variant ->
            if (!seenVariants.add(variant.name)) {
                throw new IllegalArgumentException("Variant ${variant.name} already defined in ${sumType.name}${pos(variant.pos)}")
            }
            if (variantConstructors.containsKey(variant.name) || functionDefs.containsKey(variant.name)) {
                throw new IllegalArgumentException("Constructor ${variant.name} already defined${pos(variant.pos)}")
            }
            List<TypeRef> fieldTypes = variant.fields.collect { resolveType(it.type, it.pos) }
            variantConstructors[variant.name] = new VariantInfo(sumType.name, variant.name, fieldTypes, variant.pos ?: sumType.pos)
        }
    }

    private void checkFunction(FunctionDecl fn) {
        Map<String, TypeRef> env = [:]
        Map<String, Mutability> mutability = [:]
        fn.params.each { param ->
            env[param.name] = resolveType(param.type, param.pos)
            mutability[param.name] = Mutability.VAR
        }
        TypeRef declaredReturnType = resolveType(fn.returnType, fn.pos)
        boolean sawReturn = false
        mutabilityStack.push(mutability)
        try {
            fn.body.statements.each { stmt ->
                switch (stmt) {
                    case VarDecl:
                        handleVarDecl(stmt, env, mutability, null); break
                    case AssignStmt:
                        handleAssign(stmt, env, mutability, null); break
                    case PrintStmt:
                        handlePrint(stmt, env); break
                    case ExprStmt:
                        inferExpr(stmt.expr, env); break
                    case ReturnStmt:
                        sawReturn = true
                        handleReturn(stmt, declaredReturnType, env); break
                    case RecordDecl:
                        throw new IllegalStateException("Record declarations are only allowed at top level${pos(stmt.pos)}")
                    default:
                        throw new IllegalStateException("Unknown statement type: ${stmt?.class?.simpleName}")
                }
            }
        } finally {
            mutabilityStack.pop()
        }
        if (!isVoid(declaredReturnType)) {
            if (!blockGuaranteesReturn(fn.body, declaredReturnType, env, true)) {
                if (sawReturn) {
                    throw new IllegalArgumentException("Not all code paths return a value in function ${fn.name}: expected ${format(declaredReturnType)}${pos(fn.pos)}")
                }
                throw new IllegalArgumentException("Missing return in function ${fn.name}: expected ${format(declaredReturnType)}${pos(fn.pos)}")
            }
        }
    }

    private void handleVarDecl(VarDecl stmt,
                               Map<String, TypeRef> env,
                               Map<String, Mutability> mutability,
                               Set<String> declaredHere) {
        TypeRef exprType = inferExpr(stmt.value, env)
        TypeRef declared = stmt.type ? resolveType(stmt.type, stmt.pos) : exprType
        if (!isAssignable(exprType, declared)) {
            throw new IllegalArgumentException("Type mismatch for ${stmt.name}: expected ${format(declared)} but found ${format(exprType)}${pos(stmt.pos)}")
        }
        env[stmt.name] = declared
        mutability[stmt.name] = stmt.mutability
        if (declaredHere != null) {
            declaredHere.add(stmt.name)
        }
        if (stmt.mutability == Mutability.CONST && !isConstLiteral(stmt.value)) {
            throw new IllegalArgumentException("const ${stmt.name} must be a literal${pos(stmt.pos)}")
        }
    }

    private void handleAssign(AssignStmt stmt,
                              Map<String, TypeRef> env,
                              Map<String, Mutability> mutability,
                              Set<String> declaredHere) {
        if (stmt.target instanceof VarRef) {
            VarRef ref = stmt.target as VarRef
            TypeRef valueType = inferExpr(stmt.value, env)
            if (!env.containsKey(ref.name)) {
                env[ref.name] = valueType
                mutability[ref.name] = Mutability.VAR
                if (declaredHere != null) {
                    declaredHere.add(ref.name)
                }
                return
            }
            if (mutability[ref.name] in [Mutability.VAL, Mutability.CONST]) {
                throw new IllegalArgumentException("Cannot reassign ${ref.name}${pos(stmt.pos)}")
            }
            TypeRef expected = env[ref.name]
            if (!isAssignable(valueType, expected)) {
                throw new IllegalArgumentException("Assignment type mismatch for ${ref.name}: expected ${format(expected)} but found ${format(valueType)}${pos(stmt.pos)}")
            }
            return
        }
        if (stmt.target instanceof FieldAccess) {
            FieldAccess access = stmt.target as FieldAccess
            TypeRef targetType = inferExpr(access.target, env)
            if (!(targetType instanceof RecordType)) {
                throw new IllegalArgumentException("Field access on non-record type${pos(access.pos)}")
            }
            RecordDecl record = recordDefs[(targetType as RecordType).name]
            def field = record?.fields?.find { it.name == access.field }
            if (!field) {
                throw new IllegalArgumentException("Unknown field ${access.field} on record ${record?.name}${pos(access.pos)}")
            }
            if (field.mutability == Mutability.VAL) {
                throw new IllegalArgumentException("Cannot assign to immutable field ${access.field}${pos(access.pos)}")
            }
            TypeRef valueType = inferExpr(stmt.value, env)
            TypeRef expected = resolveType(field.type, access.pos)
            if (!isAssignable(valueType, expected)) {
                throw new IllegalArgumentException("Assignment type mismatch for field ${access.field}: expected ${format(expected)} but found ${format(valueType)}${pos(access.pos)}")
            }
            return
        }
        if (stmt.target instanceof IndexAccess) {
            IndexAccess access = stmt.target as IndexAccess
            TypeRef targetType = inferExpr(access.target, env)
            TypeRef indexType = inferExpr(access.index, env)
            if (targetType instanceof ArrayType) {
                if (!isInt(indexType)) {
                    throw new IllegalArgumentException("Array index must be int but found ${format(indexType)}${pos(access.pos)}")
                }
                TypeRef valueType = inferExpr(stmt.value, env)
                if (!isAssignable(valueType, (targetType as ArrayType).element)) {
                    throw new IllegalArgumentException("Array assignment type mismatch${pos(access.pos)}")
                }
                return
            }
            if (targetType instanceof MapType) {
                MapType map = targetType as MapType
                if (indexType != map.key) {
                    throw new IllegalArgumentException("Map key type mismatch: expected ${format(map.key)} but found ${format(indexType)}${pos(access.pos)}")
                }
                TypeRef valueType = inferExpr(stmt.value, env)
                if (!isAssignable(valueType, map.value)) {
                    throw new IllegalArgumentException("Map value type mismatch: expected ${format(map.value)} but found ${format(valueType)}${pos(access.pos)}")
                }
                return
            }
            throw new IllegalArgumentException("Index access on non-collection${pos(access.pos)}")
        }
        throw new IllegalArgumentException("Invalid assignment target${pos(stmt.pos)}")
    }

    private void handlePrint(PrintStmt stmt, Map<String, TypeRef> env) {
        TypeRef exprType = inferExpr(stmt.expr, env)
        if (!isPrintable(exprType)) {
            throw new IllegalArgumentException("print only supports int, bool, or string but found ${format(exprType)}${pos(stmt.pos)}")
        }
    }

    private boolean isPrintable(TypeRef type) {
        if (type instanceof PrimitiveType) {
            return ((PrimitiveType) type).type in [Type.INT, Type.STRING, Type.BOOL]
        }
        if (type instanceof NullableType) {
            return isPrintable((type as NullableType).inner)
        }
        if (type instanceof NullType) {
            return true
        }
        return false
    }

    private void handleReturn(ReturnStmt stmt, TypeRef declared, Map<String, TypeRef> env) {
        if (declared instanceof PrimitiveType && (declared as PrimitiveType).type == Type.VOID) {
            if (stmt.expr != null) {
                throw new IllegalArgumentException("void function cannot return a value${pos(stmt.pos)}")
            }
            return
        }
        if (stmt.expr == null) {
            throw new IllegalArgumentException("Function must return ${format(declared)} but returned nothing${pos(stmt.pos)}")
        }
        TypeRef exprType = inferExpr(stmt.expr, env)
        if (!isAssignable(exprType, declared)) {
            throw new IllegalArgumentException("Return type mismatch: expected ${format(declared)} but found ${format(exprType)}${pos(stmt.pos)}")
        }
    }

    private TypeRef inferExpr(Expr expr, Map<String, TypeRef> env) {
        switch (expr) {
            case IntLiteral:
                return new PrimitiveType(Type.INT)
            case BoolLiteral:
                return new PrimitiveType(Type.BOOL)
            case StringLiteral:
                return new PrimitiveType(Type.STRING)
            case NullLiteral:
                return new NullType()
            case VarRef:
                if (!env.containsKey(expr.name)) {
                    throw new IllegalStateException("Undefined variable ${expr.name}${pos(expr.pos)}")
                }
                trackCapture(expr.name, expr.pos)
                return env[expr.name]
            case RecordLiteral:
                return inferRecordLiteral(expr, env)
            case FieldAccess:
                return inferFieldAccess(expr, env)
            case SafeFieldAccess:
                return inferSafeFieldAccess(expr, env)
            case IndexAccess:
                return inferIndexAccess(expr, env)
            case ArrayAllocExpr:
                return inferArrayAlloc(expr, env)
            case MapAllocExpr:
                return inferMapAlloc(expr, env)
            case MapLiteralExpr:
                return inferMapLiteral(expr, env)
            case IfExpr:
                return inferIfExpr(expr, env)
            case TernaryExpr:
                return inferTernary(expr, env)
            case ElvisExpr:
                return inferElvis(expr, env)
            case MatchExpr:
                return inferMatch(expr, env)
            case CallExpr:
                return inferCall(expr, env)
            case BinaryOp:
                return inferBinary(expr, env)
            case LambdaExpr:
                return inferLambdaExpr(expr as LambdaExpr, env, currentMutability())
            default:
                throw new IllegalStateException("Unknown expression type: ${expr?.class?.simpleName}")
        }
    }

    private TypeRef inferRecordLiteral(RecordLiteral expr, Map<String, TypeRef> env) {
        RecordDecl record = recordDefs[expr.typeName]
        if (!record) {
            throw new IllegalArgumentException("Unknown record ${expr.typeName}${pos(expr.pos)}")
        }
        if (expr.fields.size() != record.fields.size()) {
            throw new IllegalArgumentException("Record ${expr.typeName} requires ${record.fields.size()} fields${pos(expr.pos)}")
        }
        record.fields.each { field ->
            if (!expr.fields.containsKey(field.name)) {
                throw new IllegalArgumentException("Missing field ${field.name} in ${record.name}${pos(expr.pos)}")
            }
            TypeRef expected = resolveType(field.type, field.pos)
            TypeRef actual = inferExpr(expr.fields[field.name], env)
            if (!isAssignable(actual, expected)) {
                throw new IllegalArgumentException("Field ${field.name} expects ${format(expected)} but found ${format(actual)}${pos(expr.pos)}")
            }
        }
        return new RecordType(record.name)
    }

    private TypeRef inferFieldAccess(FieldAccess expr, Map<String, TypeRef> env) {
        TypeRef target = inferExpr(expr.target, env)
        if (!(target instanceof RecordType)) {
            throw new IllegalArgumentException("Cannot access field on non-record type ${format(target)}${pos(expr.pos)}")
        }
        RecordDecl record = recordDefs[(target as RecordType).name]
        def field = record?.fields?.find { it.name == expr.field }
        if (!field) {
            throw new IllegalArgumentException("Field ${expr.field} not found on type ${(target as RecordType).name}${pos(expr.pos)}")
        }
        return resolveType(field.type, expr.pos)
    }

    private TypeRef inferSafeFieldAccess(SafeFieldAccess expr, Map<String, TypeRef> env) {
        TypeRef target = inferExpr(expr.target, env)
        boolean wasNullable = false
        if (target instanceof NullableType) {
            wasNullable = true
            target = (target as NullableType).inner
        }
        if (!(target instanceof RecordType)) {
            throw new IllegalArgumentException("Safe field access on non-record type ${format(target)}${pos(expr.pos)}")
        }
        RecordDecl record = recordDefs[(target as RecordType).name]
        def field = record?.fields?.find { it.name == expr.field }
        if (!field) {
            throw new IllegalArgumentException("Field ${expr.field} not found on type ${(target as RecordType).name}${pos(expr.pos)}")
        }
        TypeRef fieldType = resolveType(field.type, expr.pos)
        return wasNullable ? new NullableType(fieldType) : fieldType
    }

    private TypeRef inferIndexAccess(IndexAccess expr, Map<String, TypeRef> env) {
        TypeRef target = inferExpr(expr.target, env)
        TypeRef index = inferExpr(expr.index, env)
        if (target instanceof ArrayType) {
            if (!isInt(index)) {
                throw new IllegalArgumentException("Array index must be int but found ${format(index)}${pos(expr.pos)}")
            }
            return (target as ArrayType).element
        }
        if (target instanceof MapType) {
            MapType map = target as MapType
            if (!isAssignable(index, map.key) || !isAssignable(map.key, index)) {
                throw new IllegalArgumentException("Map key type mismatch: expected ${format(map.key)} but found ${format(index)}${pos(expr.pos)}")
            }
            return map.value
        }
        throw new IllegalArgumentException("Index access on non-collection${pos(expr.pos)}")
    }

    private TypeRef inferCall(CallExpr expr, Map<String, TypeRef> env) {
        if (env.containsKey(expr.callee)) {
            TypeRef calleeType = env[expr.callee]
            if (!(calleeType instanceof LambdaType)) {
                throw new IllegalArgumentException("${expr.callee} is not callable${pos(expr.pos)}")
            }
            LambdaType lambda = calleeType as LambdaType
            if (lambda.parameters.size() != expr.arguments.size()) {
                throw new IllegalArgumentException("Callable ${expr.callee} expects ${lambda.parameters.size()} argument(s) but received ${expr.arguments.size()}${pos(expr.pos)}")
            }
            lambda.parameters.eachWithIndex { ParameterType param, int idx ->
                TypeRef argType = inferExpr(expr.arguments[idx], env)
                if (!isAssignable(argType, param.type)) {
                    throw new IllegalArgumentException("Argument ${idx + 1} for ${expr.callee} expects ${format(param.type)} but found ${format(argType)}${pos(expr.pos)}")
                }
            }
            return lambda.returnType
        }
        FunctionDecl fn = functionDefs[expr.callee]
        if (!fn) {
            VariantInfo variant = variantConstructors[expr.callee]
            if (!variant) {
                throw new IllegalArgumentException("Unknown function ${expr.callee}${pos(expr.pos)}")
            }
            if (variant.fieldTypes.size() != expr.arguments.size()) {
                throw new IllegalArgumentException("Constructor ${expr.callee} expects ${variant.fieldTypes.size()} argument(s) but received ${expr.arguments.size()}${pos(expr.pos)}")
            }
            variant.fieldTypes.eachWithIndex { TypeRef fieldType, int idx ->
                TypeRef argType = inferExpr(expr.arguments[idx], env)
                if (!isAssignable(argType, fieldType)) {
                    throw new IllegalArgumentException("Argument ${idx + 1} for ${expr.callee} expects ${format(fieldType)} but found ${format(argType)}${pos(expr.pos)}")
                }
            }
            return new SumTypeRef(variant.sumTypeName)
        }
        if (fn.params.size() != expr.arguments.size()) {
            throw new IllegalArgumentException("Function ${expr.callee} expects ${fn.params.size()} argument(s) but received ${expr.arguments.size()}${pos(expr.pos)}")
        }
        fn.params.eachWithIndex { param, idx ->
            TypeRef paramType = resolveType(param.type, param.pos)
            TypeRef argType = inferExpr(expr.arguments[idx], env)
            if (!isAssignable(argType, paramType)) {
                throw new IllegalArgumentException("Argument ${idx + 1} for ${expr.callee} expects ${format(paramType)} but found ${format(argType)}${pos(expr.pos)}")
            }
        }
        return resolveType(fn.returnType, fn.pos)
    }

    private TypeRef inferArrayAlloc(ArrayAllocExpr expr, Map<String, TypeRef> env) {
        TypeRef sizeType = inferExpr(expr.size, env)
        if (!isInt(sizeType)) {
            throw new IllegalArgumentException("Array size must be int but found ${format(sizeType)}${pos(expr.pos)}")
        }
        return new ArrayType(resolveType(expr.elementType, expr.pos))
    }

    private TypeRef inferMapAlloc(MapAllocExpr expr, Map<String, TypeRef> env) {
        TypeRef capType = inferExpr(expr.capacity, env)
        if (!isInt(capType)) {
            throw new IllegalArgumentException("Map capacity must be int but found ${format(capType)}${pos(expr.pos)}")
        }
        return new MapType(resolveType(expr.keyType, expr.pos), resolveType(expr.valueType, expr.pos))
    }

    private TypeRef inferMapLiteral(MapLiteralExpr expr, Map<String, TypeRef> env) {
        TypeRef keyType = resolveType(expr.keyType, expr.pos)
        TypeRef valueType = resolveType(expr.valueType, expr.pos)
        expr.entries.each { entry ->
            TypeRef k = inferExpr(entry.key, env)
            TypeRef v = inferExpr(entry.value, env)
            if (!isAssignable(k, keyType)) {
                throw new IllegalArgumentException("Map entry key type mismatch: expected ${format(keyType)} but found ${format(k)}${pos(entry.pos)}")
            }
            if (!isAssignable(v, valueType)) {
                throw new IllegalArgumentException("Map entry value type mismatch: expected ${format(valueType)} but found ${format(v)}${pos(entry.pos)}")
            }
        }
        return new MapType(keyType, valueType)
    }


    private boolean blockGuaranteesReturn(Block block,
                                          TypeRef expectedReturn,
                                          Map<String, TypeRef> env,
                                          boolean allowImplicitReturn) {
        if (!block?.statements || isVoid(expectedReturn)) {
            return false
        }
        List statements = block.statements
        for (int i = 0; i < statements.size(); i++) {
            def stmt = statements[i]
            boolean isLast = i == statements.size() - 1
            if (stmt instanceof ReturnStmt) {
                return true
            }
            if (stmt instanceof ExprStmt) {
                if (exprGuaranteesReturn(stmt.expr, expectedReturn, env)) {
                    return true
                }
                if (allowImplicitReturn && isLast) {
                    ensureImplicitReturn(stmt.expr, expectedReturn, env)
                    return true
                }
            }
        }
        return false
    }

    private boolean exprGuaranteesReturn(Expr expr,
                                         TypeRef expectedReturn,
                                         Map<String, TypeRef> env) {
        if (expr instanceof IfExpr) {
            IfExpr ifExpr = expr as IfExpr
            if (ifExpr.elseBlock == null) {
                return false
            }
            return blockGuaranteesReturn(ifExpr.thenBlock, expectedReturn, env, false) &&
                    blockGuaranteesReturn(ifExpr.elseBlock, expectedReturn, env, false)
        }
        return false
    }

    private void ensureImplicitReturn(Expr expr, TypeRef expectedReturn, Map<String, TypeRef> env) {
        TypeRef exprType = inferExpr(expr, env)
        if (!isAssignable(exprType, expectedReturn)) {
            throw new IllegalArgumentException("Implicit return type mismatch: expected ${format(expectedReturn)} but found ${format(exprType)}${pos(expr.pos)}")
        }
    }

    private TypeRef inferIfExpr(IfExpr expr, Map<String, TypeRef> env) {
        TypeRef cond = inferExpr(expr.condition, env)
        if (!isBool(cond)) {
            throw new IllegalArgumentException("if condition must be bool but found ${format(cond)}${pos(expr.pos)}")
        }
        TypeRef thenType = inferBlockResult(expr.thenBlock, env)
        if (expr.elseBlock == null) {
            return new PrimitiveType(Type.VOID)
        }
        TypeRef elseType = inferBlockResult(expr.elseBlock, env)
        if (!isAssignable(thenType, elseType) || !isAssignable(elseType, thenType)) {
            throw new IllegalArgumentException("if branches must return same type but found ${format(thenType)} and ${format(elseType)}${pos(expr.pos)}")
        }
        return thenType
    }

    private TypeRef inferTernary(TernaryExpr expr, Map<String, TypeRef> env) {
        TypeRef cond = inferExpr(expr.condition, env)
        if (!isBool(cond)) {
            throw new IllegalArgumentException("ternary condition must be bool but found ${format(cond)}${pos(expr.pos)}")
        }
        TypeRef t = inferExpr(expr.ifTrue, env)
        TypeRef f = inferExpr(expr.ifFalse, env)
        if (!isAssignable(t, f) || !isAssignable(f, t)) {
            throw new IllegalArgumentException("ternary branches must match but found ${format(t)} and ${format(f)}${pos(expr.pos)}")
        }
        return t
    }

    private TypeRef inferElvis(ElvisExpr expr, Map<String, TypeRef> env) {
        TypeRef l = inferExpr(expr.left, env)
        TypeRef r = inferExpr(expr.right, env)
        if (l instanceof NullType) {
            return r
        }
        if (l instanceof NullableType && isAssignable(r, (l as NullableType).inner)) {
            return r
        }
        if (!isAssignable(l, r) || !isAssignable(r, l)) {
            throw new IllegalArgumentException("elvis branches must match but found ${format(l)} and ${format(r)}${pos(expr.pos)}")
        }
        return l
    }

    private TypeRef inferMatch(MatchExpr expr, Map<String, TypeRef> env) {
        TypeRef target = inferExpr(expr.target, env)
        TypeRef branchType = null
        boolean hasCatchAll = expr.elseExpr != null
        expr.cases.each { MatchCase c ->
            Map<String, TypeRef> bindings = [:]
            validatePattern(c.pattern, target, bindings, env)
            Map<String, TypeRef> branchEnv = new LinkedHashMap<>(env)
            bindings.each { name, type ->
                branchEnv[name] = type
            }
            TypeRef caseType = inferExpr(c.value, branchEnv)
            branchType = mergeBranchType(branchType, caseType, c.pos)
            if (!hasCatchAll && isCatchAllPattern(c.pattern)) {
                hasCatchAll = true
            }
        }
        if (!hasCatchAll) {
            throw new IllegalArgumentException("match expression requires a wildcard case or else branch${pos(expr.pos)}")
        }
        if (expr.elseExpr != null) {
            TypeRef elseType = inferExpr(expr.elseExpr, env)
            branchType = mergeBranchType(branchType, elseType, expr.pos)
        } else if (branchType == null) {
            branchType = new PrimitiveType(Type.VOID)
        }
        return branchType
    }

    private boolean isCatchAllPattern(Pattern pattern) {
        pattern instanceof WildcardPattern || pattern instanceof VarPattern
    }

    private TypeRef mergeBranchType(TypeRef current, TypeRef candidate, SourcePos where) {
        if (candidate == null) {
            return current
        }
        if (current == null) {
            return candidate
        }
        if (!isAssignable(candidate, current) || !isAssignable(current, candidate)) {
            throw new IllegalArgumentException("match branches must return same type but found ${format(current)} and ${format(candidate)}${pos(where)}")
        }
        return current
    }

    private void validatePattern(Pattern pattern,
                                 TypeRef targetType,
                                 Map<String, TypeRef> bindings,
                                 Map<String, TypeRef> env) {
        switch (pattern) {
            case WildcardPattern:
                return
            case VarPattern:
                String name = (pattern as VarPattern).name
                if (bindings.containsKey(name)) {
                    throw new IllegalArgumentException("Pattern variable '${name}' already bound${pos(pattern.pos)}")
                }
                bindings[name] = targetType
                return
            case LiteralPattern:
                TypeRef litType = literalType((pattern as LiteralPattern).literal)
                if (!isAssignable(litType, targetType) || !isAssignable(targetType, litType)) {
                    throw new IllegalArgumentException("Pattern literal type ${format(litType)} does not match ${format(targetType)}${pos(pattern.pos)}")
                }
                return
            case RecordPattern:
                validateRecordPattern(pattern as RecordPattern, targetType, bindings, env)
                return
            case VariantPattern:
                validateVariantPattern(pattern as VariantPattern, targetType, bindings, env)
                return
            default:
                throw new IllegalStateException("Unknown pattern type ${pattern?.class?.simpleName}")
        }
    }

    private void validateRecordPattern(RecordPattern pattern,
                                       TypeRef targetType,
                                       Map<String, TypeRef> bindings,
                                       Map<String, TypeRef> env) {
        TypeRef base = (targetType instanceof NullableType) ? (targetType as NullableType).inner : targetType
        if (!(base instanceof RecordType)) {
            throw new IllegalArgumentException("Pattern ${pattern.typeName} cannot match ${format(targetType)}${pos(pattern.pos)}")
        }
        String recordName = (base as RecordType).name
        if (recordName != pattern.typeName) {
            throw new IllegalArgumentException("Pattern ${pattern.typeName} does not match ${recordName}${pos(pattern.pos)}")
        }
        RecordDecl record = recordDefs[recordName]
        pattern.fields.each { RecordFieldPattern fieldPattern ->
            def field = record.fields.find { it.name == fieldPattern.field }
            if (!field) {
                throw new IllegalArgumentException("Unknown field ${fieldPattern.field} on ${recordName}${pos(fieldPattern.pos)}")
            }
            TypeRef fieldType = resolveType(field.type, field.pos)
            if (fieldPattern.pattern instanceof RecordPattern) {
                throw new IllegalArgumentException("Nested record patterns are not supported${pos(fieldPattern.pattern.pos)}")
            }
            validatePattern(fieldPattern.pattern, fieldType, bindings, env)
        }
    }

    private void validateVariantPattern(VariantPattern pattern,
                                        TypeRef targetType,
                                        Map<String, TypeRef> bindings,
                                        Map<String, TypeRef> env) {
        TypeRef base = (targetType instanceof NullableType) ? (targetType as NullableType).inner : targetType
        if (!(base instanceof SumTypeRef)) {
            throw new IllegalArgumentException("Pattern ${pattern.variantName} cannot match ${format(targetType)}${pos(pattern.pos)}")
        }
        String sumTypeName = (base as SumTypeRef).name
        if (pattern.typeName && pattern.typeName != sumTypeName) {
            throw new IllegalArgumentException("Pattern ${pattern.typeName}.${pattern.variantName} does not match ${sumTypeName}${pos(pattern.pos)}")
        }
        VariantInfo info = variantConstructors[pattern.variantName]
        if (!info || info.sumTypeName != sumTypeName) {
            throw new IllegalArgumentException("Variant ${pattern.variantName} not found on ${sumTypeName}${pos(pattern.pos)}")
        }
        if (pattern.fields.size() != info.fieldTypes.size()) {
            throw new IllegalArgumentException("Variant ${pattern.variantName} expects ${info.fieldTypes.size()} field(s) but found ${pattern.fields.size()}${pos(pattern.pos)}")
        }
        pattern.fields.eachWithIndex { Pattern fieldPattern, int idx ->
            validatePattern(fieldPattern, info.fieldTypes[idx], bindings, env)
        }
    }

    private TypeRef literalType(Expr expr) {
        switch (expr) {
            case IntLiteral:
                return new PrimitiveType(Type.INT)
            case StringLiteral:
                return new PrimitiveType(Type.STRING)
            case BoolLiteral:
                return new PrimitiveType(Type.BOOL)
            case NullLiteral:
                return new NullType()
            default:
                throw new IllegalArgumentException("Unsupported literal in pattern${pos(expr?.pos)}")
        }
    }

    private TypeRef inferBlockResult(biz.digitalindustry.glyph.core.ast.Block block, Map<String, TypeRef> env) {
        if (!block?.statements) {
            return new PrimitiveType(Type.VOID)
        }
        def last = block.statements.last()
        if (last instanceof ExprStmt) {
            return inferExpr(last.expr, env)
        }
        return new PrimitiveType(Type.VOID)
    }

    private TypeRef inferBinary(BinaryOp expr, Map<String, TypeRef> env) {
        TypeRef lt = inferExpr(expr.left, env)
        TypeRef rt = inferExpr(expr.right, env)
        switch (expr.op) {
            case '+':
            case '-':
            case '*':
            case '/':
                ensureNumericBinary(expr, lt, rt)
                return new PrimitiveType(Type.INT)
            case '<':
            case '>':
            case '<=':
            case '>=':
                ensureNumericBinary(expr, lt, rt)
                return new PrimitiveType(Type.BOOL)
            case '==':
            case '!=':
                if (lt instanceof NullType) {
                    ensureNullableComparison(expr, rt)
                    return new PrimitiveType(Type.BOOL)
                }
                if (rt instanceof NullType) {
                    ensureNullableComparison(expr, lt)
                    return new PrimitiveType(Type.BOOL)
                }
                if (!isAssignable(lt, rt) || !isAssignable(rt, lt)) {
                    throw new IllegalArgumentException("Binary op ${expr.op} operands differ: ${format(lt)} vs ${format(rt)}${pos(expr.pos)}")
                }
                if (!(isInt(lt) || isBool(lt) || isString(lt))) {
                    throw new IllegalArgumentException("Binary op ${expr.op} not supported for ${format(lt)}${pos(expr.pos)}")
                }
                return new PrimitiveType(Type.BOOL)
            default:
                throw new IllegalArgumentException("Unknown operator ${expr.op}${pos(expr.pos)}")
        }
    }

    private LambdaType inferLambdaExpr(LambdaExpr expr,
                                       Map<String, TypeRef> env,
                                       Map<String, Mutability> mutability) {
        Map<String, TypeRef> baseEnv = new LinkedHashMap<>(env)
        Map<String, Mutability> baseMut = new LinkedHashMap<>(mutability ?: [:])
        LambdaCaptureContext ctx = new LambdaCaptureContext(baseEnv, baseMut)
        lambdaStack.push(ctx)
        Map<String, TypeRef> lambdaEnv = new LinkedHashMap<>(baseEnv)
        Map<String, Mutability> lambdaMut = new LinkedHashMap<>(baseMut)
        expr.params.each { param ->
            TypeRef paramType = resolveType(param.type, param.pos)
            lambdaEnv[param.name] = paramType
            lambdaMut[param.name] = Mutability.VAR
            ctx.locals.add(param.name)
        }
        mutabilityStack.push(lambdaMut)
        TypeRef declaredReturn = expr.returnType ? resolveType(expr.returnType, expr.pos) : null
        TypeRef explicitReturn = null
        try {
            expr.body.statements.each { stmt ->
                switch (stmt) {
                    case VarDecl:
                        handleVarDecl(stmt, lambdaEnv, lambdaMut, ctx.locals); break
                    case AssignStmt:
                        handleAssign(stmt, lambdaEnv, lambdaMut, ctx.locals); break
                    case PrintStmt:
                        handlePrint(stmt, lambdaEnv); break
                    case ExprStmt:
                        inferExpr(stmt.expr, lambdaEnv); break
                    case ReturnStmt:
                        if (declaredReturn) {
                            handleReturn(stmt, declaredReturn, lambdaEnv)
                        } else {
                            TypeRef returnType = stmt.expr != null ? inferExpr(stmt.expr, lambdaEnv) : new PrimitiveType(Type.VOID)
                            if (stmt.expr == null) {
                                throw new IllegalArgumentException("Lambda cannot return without a value when no return type is declared${pos(stmt.pos)}")
                            }
                            if (explicitReturn == null) {
                                explicitReturn = returnType
                            } else if (!isAssignable(returnType, explicitReturn)) {
                                throw new IllegalArgumentException("Lambda return types differ: expected ${format(explicitReturn)} but found ${format(returnType)}${pos(stmt.pos)}")
                            }
                        }
                        break
                    case RecordDecl:
                        throw new IllegalStateException("Record declarations are not allowed inside lambdas${pos(stmt.pos)}")
                    default:
                        throw new IllegalStateException("Unknown statement type: ${stmt?.class?.simpleName}")
                }
            }
        } finally {
            mutabilityStack.pop()
            lambdaStack.pop()
        }
        TypeRef implicitReturn = inferBlockResult(expr.body, lambdaEnv)
        TypeRef effectiveReturn = declaredReturn ?: (explicitReturn ?: implicitReturn)
        if (!isVoid(effectiveReturn)) {
            if (!blockGuaranteesReturn(expr.body, effectiveReturn, lambdaEnv, true)) {
                throw new IllegalArgumentException("Lambda is missing return of ${format(effectiveReturn)}${pos(expr.pos)}")
            }
        }
        expr.captures = ctx.captured.collect { name, type -> new CapturedVar(name, type) }
        expr.resolvedReturnType = format(effectiveReturn)
        List<ParameterType> parameters = expr.params.collect {
            new ParameterType(it.name, resolveType(it.type, it.pos))
        }
        return new LambdaType(parameters, effectiveReturn)
    }

    private void ensureNumericBinary(BinaryOp expr, TypeRef lt, TypeRef rt) {
        if (!isAssignable(lt, rt) || !isAssignable(rt, lt)) {
            throw new IllegalArgumentException("Binary op ${expr.op} operands differ: ${format(lt)} vs ${format(rt)}${pos(expr.pos)}")
        }
        if (!isInt(lt)) {
            throw new IllegalArgumentException("Binary op ${expr.op} expects int operands but got ${format(lt)}${pos(expr.pos)}")
        }
    }

    private void ensureNullableComparison(BinaryOp expr, TypeRef other) {
        if (!(other instanceof NullableType)) {
            throw new IllegalArgumentException("Cannot compare null with ${format(other)}${pos(expr.pos)}")
        }
    }

    private TypeRef resolveType(String name, SourcePos p) {
        return resolveTypeInternal(name, p, new LinkedHashSet<String>())
    }

    private TypeRef resolveTypeInternal(String name, SourcePos p, Set<String> visiting) {
        if (name == null) {
            throw new IllegalArgumentException("Missing type${pos(p)}")
        }
        if (name == '[:]' ) {
            return new MapType(new PrimitiveType(Type.STRING), new PrimitiveType(Type.STRING))
        }
        if (name.endsWith('?')) {
            String inner = name.substring(0, name.length() - 1)
            return new NullableType(resolveTypeInternal(inner, p, visiting))
        }
        if (name.startsWith('[') && name.endsWith(']')) {
            String inner = name.substring(1, name.length() - 1)
            if (inner.contains(':')) {
                int idx = inner.indexOf(':')
                String key = inner.substring(0, idx)
                String value = inner.substring(idx + 1)
                return new MapType(resolveTypeInternal(key, p, visiting), resolveTypeInternal(value, p, visiting))
            }
            return new ArrayType(resolveTypeInternal(inner, p, visiting))
        }
        switch (name) {
            case 'int': return new PrimitiveType(Type.INT)
            case 'bool': return new PrimitiveType(Type.BOOL)
            case 'string': return new PrimitiveType(Type.STRING)
            case 'long': return new PrimitiveType(Type.LONG)
            case 'float': return new PrimitiveType(Type.FLOAT)
            case 'double': return new PrimitiveType(Type.DOUBLE)
            case 'char': return new PrimitiveType(Type.CHAR)
            case 'bytes': return new PrimitiveType(Type.BYTES)
            case 'void': return new PrimitiveType(Type.VOID)
        }
        if (recordDefs.containsKey(name)) {
            return new RecordType(name)
        }
        if (sumTypeDefs.containsKey(name)) {
            return new SumTypeRef(name)
        }
        if (aliasDefs.containsKey(name)) {
            return resolveAlias(name, p, visiting)
        }
        throw new IllegalArgumentException("Unknown type ${name}${pos(p)}")
    }

    private TypeRef resolveAlias(String name, SourcePos p, Set<String> visiting) {
        if (aliasCache.containsKey(name)) {
            return aliasCache[name]
        }
        if (!aliasDefs.containsKey(name)) {
            throw new IllegalArgumentException("Unknown type ${name}${pos(p)}")
        }
        if (visiting.contains(name)) {
            throw new IllegalArgumentException("Circular type alias '${name}'${pos(p)}")
        }
        visiting.add(name)
        TypeAliasDecl alias = aliasDefs[name]
        TypeRef resolved = resolveTypeInternal(alias.targetType, alias.pos ?: p, visiting)
        aliasCache[name] = resolved
        visiting.remove(name)
        return resolved
    }

    private boolean isVoid(TypeRef type) {
        type instanceof PrimitiveType && (type as PrimitiveType).type == Type.VOID
    }

    private boolean isConstLiteral(Expr expr) {
        expr instanceof IntLiteral || expr instanceof StringLiteral || expr instanceof BoolLiteral || expr instanceof NullLiteral
    }

    private boolean isInt(TypeRef t) {
        t instanceof PrimitiveType && (t as PrimitiveType).type == Type.INT
    }

    private boolean isBool(TypeRef t) {
        t instanceof PrimitiveType && (t as PrimitiveType).type == Type.BOOL
    }

    private boolean isString(TypeRef t) {
        t instanceof PrimitiveType && (t as PrimitiveType).type == Type.STRING
    }

    private String format(TypeRef t) {
        if (t instanceof PrimitiveType) return (t as PrimitiveType).type.name().toLowerCase()
        if (t instanceof NullType) return "null"
        if (t instanceof NullableType) return "${format((t as NullableType).inner)}?"
        if (t instanceof RecordType) return (t as RecordType).name
        if (t instanceof SumTypeRef) return (t as SumTypeRef).name
        if (t instanceof ArrayType) return "[${format((t as ArrayType).element)}]"
        if (t instanceof MapType) return "[${format((t as MapType).key)}:${format((t as MapType).value)}]"
        return t.toString()
    }

    private boolean isAssignable(TypeRef actual, TypeRef expected) {
        if (expected instanceof NullableType) {
            if (actual instanceof NullType) return true
            if (actual instanceof NullableType) {
                return isAssignable((actual as NullableType).inner, (expected as NullableType).inner)
            }
            return isAssignable(actual, (expected as NullableType).inner)
        }
        if (actual instanceof NullableType) {
            return false
        }
        if (actual instanceof NullType) {
            return false
        }
        return actual == expected
    }

    private Map<String, Mutability> currentMutability() {
        mutabilityStack.peek() ?: [:]
    }

    private void trackCapture(String name, SourcePos sourcePos) {
        if (lambdaStack.isEmpty()) {
            return
        }
        Iterator<LambdaCaptureContext> iterator = lambdaStack.iterator()
        while (iterator.hasNext()) {
            LambdaCaptureContext ctx = iterator.next()
            if (ctx.locals.contains(name)) {
                return
            }
            if (!ctx.outerEnv.containsKey(name)) {
                continue
            }
            if (!ctx.captured.containsKey(name)) {
                Mutability mut = ctx.outerMut[name] ?: Mutability.VAR
                if (mut == Mutability.VAR) {
                    throw new IllegalArgumentException("Lambda cannot capture mutable variable ${name}${pos(sourcePos)}")
                }
                ctx.captured[name] = ctx.outerEnv[name]
            }
        }
    }

    private String pos(SourcePos p) {
        if (p == null || p.line <= 0) return ""
        String filePart = p.file ? "${p.file}:" : ""
        String colPart = p.column > 0 ? ":${p.column}" : ""
        return " [${filePart}${p.line}${colPart}]"
    }

    private static class LambdaCaptureContext {
        final Map<String, TypeRef> outerEnv
        final Map<String, Mutability> outerMut
        final Set<String> locals = new LinkedHashSet<>()
        final Map<String, TypeRef> captured = new LinkedHashMap<>()

        LambdaCaptureContext(Map<String, TypeRef> outerEnv, Map<String, Mutability> outerMut) {
            this.outerEnv = outerEnv ?: [:]
            this.outerMut = outerMut ?: [:]
        }
    }

    private static class VariantInfo {
        final String sumTypeName
        final String constructorName
        final List<TypeRef> fieldTypes
        final SourcePos pos

        VariantInfo(String sumTypeName, String constructorName, List<TypeRef> fieldTypes, SourcePos pos) {
            this.sumTypeName = sumTypeName
            this.constructorName = constructorName
            this.fieldTypes = fieldTypes ?: Collections.emptyList()
            this.pos = pos ?: SourcePos.UNKNOWN
        }
    }
}
