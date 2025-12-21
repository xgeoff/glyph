package biz.digitalindustry.glyph.core.ast

import groovy.transform.Canonical
import biz.digitalindustry.glyph.core.SourcePos

@Canonical
class Program {
    PackageDecl packageDecl
    List<ImportDecl> imports = []
    List<RecordDecl> records = []
    List<FunctionDecl> functions = []
}

@Canonical
class PackageDecl {
    String name
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class ImportDecl {
    String path
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class RecordDecl implements Statement {
    String name
    List<RecordField> fields = []
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class RecordField {
    String name
    String type
    Mutability mutability
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class FunctionDecl {
    String name
    List<Param> params = []
    String returnType
    Block body
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class Param {
    String name
    String type // may be null for future inference/extensions
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class Block {
    List<Statement> statements
    SourcePos pos = SourcePos.UNKNOWN
}

interface Statement {
    SourcePos getPos()
}

@Canonical
class VarDecl implements Statement {
    String name
    String type
    Mutability mutability
    Expr value
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class PrintStmt implements Statement {
    Expr expr
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class ExprStmt implements Statement {
    Expr expr
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class ReturnStmt implements Statement {
    Expr expr
    SourcePos pos = SourcePos.UNKNOWN
}

enum Mutability { CONST, VAL, VAR }

interface Expr {
    SourcePos getPos()
}

@Canonical
class IntLiteral implements Expr {
    long value
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class StringLiteral implements Expr {
    String value
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class NullLiteral implements Expr {
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class BoolLiteral implements Expr {
    boolean value
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class BinaryOp implements Expr {
    String op
    Expr left
    Expr right
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class VarRef implements Expr {
    String name
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class CallExpr implements Expr {
    String callee
    List<Expr> arguments = []
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class LambdaExpr implements Expr {
    List<Param> params = []
    Block body
    String returnType
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class IfExpr implements Expr {
    Expr condition
    Block thenBlock
    Block elseBlock
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class TernaryExpr implements Expr {
    Expr condition
    Expr ifTrue
    Expr ifFalse
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class ElvisExpr implements Expr {
    Expr left
    Expr right
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class MatchCase {
    Expr key
    Expr value
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class MatchExpr implements Expr {
    Expr target
    List<MatchCase> cases = []
    Expr elseExpr
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class RecordLiteral implements Expr {
    String typeName
    Map<String, Expr> fields = [:]
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class FieldAccess implements Expr {
    Expr target
    String field
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class SafeFieldAccess implements Expr {
    Expr target
    String field
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class IndexAccess implements Expr {
    Expr target
    Expr index
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class ArrayAllocExpr implements Expr {
    String elementType
    Expr size
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class MapAllocExpr implements Expr {
    String keyType
    String valueType
    Expr capacity
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class MapLiteralExpr implements Expr {
    String keyType
    String valueType
    List<MapEntryExpr> entries = []
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class MapEntryExpr {
    Expr key
    Expr value
    SourcePos pos = SourcePos.UNKNOWN
}

@Canonical
class AssignStmt implements Statement {
    Expr target
    Expr value
    SourcePos pos = SourcePos.UNKNOWN
}
