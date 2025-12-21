package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.SourcePos
import biz.digitalindustry.glyph.core.ast.BinaryOp
import biz.digitalindustry.glyph.core.ast.Block
import biz.digitalindustry.glyph.core.ast.BoolLiteral
import biz.digitalindustry.glyph.core.ast.Expr
import biz.digitalindustry.glyph.core.ast.AssignStmt
import biz.digitalindustry.glyph.core.ast.ExprStmt
import biz.digitalindustry.glyph.core.ast.FieldAccess
import biz.digitalindustry.glyph.core.ast.SafeFieldAccess
import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.PackageDecl
import biz.digitalindustry.glyph.core.ast.ImportDecl
import biz.digitalindustry.glyph.core.ast.IfExpr
import biz.digitalindustry.glyph.core.ast.IntLiteral
import biz.digitalindustry.glyph.core.ast.IndexAccess
import biz.digitalindustry.glyph.core.ast.MapAllocExpr
import biz.digitalindustry.glyph.core.ast.MapEntryExpr
import biz.digitalindustry.glyph.core.ast.MatchCase
import biz.digitalindustry.glyph.core.ast.MatchExpr
import biz.digitalindustry.glyph.core.ast.Mutability
import biz.digitalindustry.glyph.core.ast.NullLiteral
import biz.digitalindustry.glyph.core.ast.Param
import biz.digitalindustry.glyph.core.ast.PrintStmt
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordField
import biz.digitalindustry.glyph.core.ast.RecordLiteral
import biz.digitalindustry.glyph.core.ast.ReturnStmt
import biz.digitalindustry.glyph.core.ast.Statement
import biz.digitalindustry.glyph.core.ast.StringLiteral
import biz.digitalindustry.glyph.core.ast.TernaryExpr
import biz.digitalindustry.glyph.core.ast.ElvisExpr
import biz.digitalindustry.glyph.core.ast.ArrayAllocExpr
import biz.digitalindustry.glyph.core.ast.MapLiteralExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
import biz.digitalindustry.glyph.core.ast.VarRef
import biz.digitalindustry.glyph.core.ast.CallExpr
import java.nio.file.Files
import java.nio.file.Path

class GlyphParser {
    private static final String BUNDLED_GRAMMAR_RESOURCE = '/grammar/glyph.peg'

    static Program parse(Path sourceFile, Path grammarPath = null) {
        if (grammarPath) {
            ensureGrammar(grammarPath)
        } else {
            ensureBundledGrammar()
        }
        String source = Files.readString(sourceFile)
        return new Parser(source, sourceFile.toString()).parseProgram()
    }

    private static void ensureGrammar(Path grammarPath) {
        if (!Files.exists(grammarPath)) {
            throw new IllegalArgumentException("Grammar file not found at ${grammarPath}")
        }
    }

    private static void ensureBundledGrammar() {
        if (GlyphParser.class.getResource(BUNDLED_GRAMMAR_RESOURCE) == null) {
            throw new IllegalStateException("Bundled grammar resource ${BUNDLED_GRAMMAR_RESOURCE} not found on classpath")
        }
    }
}

class Parser {
    private final List<Token> tokens
    private final String sourceName
    private int pos = 0
    private Token previous = null

    Parser(String src, String sourceName) {
        this.tokens = new Lexer(src).tokenize()
        this.sourceName = sourceName
    }

    private Token getCurrent() {
        return (pos < tokens.size()) ? tokens[pos] : new Token(TokenType.EOF, '', -1, -1)
    }

    Program parseProgram() {
        PackageDecl packageDecl = null
        if (match(TokenType.PACKAGE)) {
            if (packageDecl != null) {
                throw new IllegalArgumentException("Duplicate package declaration${pos(previous)}")
            }
            packageDecl = parsePackageDecl()
            consumeTerminators()
        }
        List<ImportDecl> imports = []
        while (match(TokenType.IMPORT)) {
            imports.add(parseImportDecl())
            consumeTerminators()
        }
        List<RecordDecl> records = []
        List<FunctionDecl> functions = []
        while (!check(TokenType.EOF)) {
            if (check(TokenType.PACKAGE) || check(TokenType.IMPORT)) {
                throw new IllegalArgumentException("package/import declarations must appear at the top of the file${pos(current)}")
            }
            if (check(TokenType.RECORD)) {
                records.add(parseRecordDecl())
            } else {
                functions.add(parseFunction())
            }
        }
        return new Program(packageDecl, imports, records, functions)
    }

    private PackageDecl parsePackageDecl() {
        Token keyword = previous
        String name = parseQualifiedName("package declaration")
        return new PackageDecl(name, pos(keyword))
    }

    private ImportDecl parseImportDecl() {
        Token keyword = previous
        String name = parseQualifiedName("import statement")
        return new ImportDecl(name, pos(keyword))
    }

    private RecordDecl parseRecordDecl() {
        consume(TokenType.RECORD, "Expected 'record'")
        Token start = previous
        String name = consume(TokenType.IDENT, 'Expected record name').lexeme
        consume(TokenType.LBRACE, "Expected '{' after record name")
        List<RecordField> fields = []
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            Mutability mutability = Mutability.VAR
            if (match(TokenType.VAL)) {
                mutability = Mutability.VAL
            }
            TypedToken typeTok = parseType()
            Token fieldName = consume(TokenType.IDENT, 'Expected field name')
            fields.add(new RecordField(fieldName.lexeme, typeTok.typeName, mutability, pos(typeTok.token)))
            consumeTerminators()
        }
        consume(TokenType.RBRACE, "Expected '}' after record fields")
        return new RecordDecl(name, fields, pos(start))
    }

    private String parseQualifiedName(String context) {
        Token first = consume(TokenType.IDENT, "Expected identifier in ${context}")
        StringBuilder builder = new StringBuilder(first.lexeme)
        while (match(TokenType.DOT)) {
            Token next = consume(TokenType.IDENT, "Expected identifier after '.' in ${context}")
            builder.append('.').append(next.lexeme)
        }
        return builder.toString()
    }

    private FunctionDecl parseFunction() {
        consume(TokenType.FUN, "Expected 'fun'")
        Token start = previous
        TypedToken returnTypeTok = parseType()
        String name = consume(TokenType.IDENT, 'Expected function name').lexeme
        consume(TokenType.LPAREN, "Expected '(' after function name")
        List<Param> params = []
        if (!check(TokenType.RPAREN)) {
            params = parseParams()
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters")
        Block body = parseBlock()
        return new FunctionDecl(name, params, returnTypeTok.typeName, body, pos(start))
    }

    private List<Param> parseParams() {
        List<Param> params = []
        params.add(parseParam())
        while (match(TokenType.COMMA)) {
            params.add(parseParam())
        }
        return params
    }

    private Param parseParam() {
        TypedToken typeTok = parseType()
        Token nameTok = consume(TokenType.IDENT, 'Expected parameter name')
        return new Param(nameTok.lexeme, typeTok.typeName, pos(typeTok.token))
    }

    private TypedToken parseType() {
        if (match(TokenType.LBRACKET)) {
            Token start = previous
            if (match(TokenType.COLON)) {
                consume(TokenType.RBRACKET, "Expected ']' after [:]")
                return applyNullable(new TypedToken('[:]', start))
            }
            TypedToken first = parseSimpleType()
            if (match(TokenType.COLON)) {
                TypedToken second = parseSimpleType()
                consume(TokenType.RBRACKET, "Expected ']' after map type")
                return applyNullable(new TypedToken("[${first.typeName}:${second.typeName}]", start))
            }
            consume(TokenType.RBRACKET, "Expected ']' after array type")
            return applyNullable(new TypedToken("[${first.typeName}]", start))
        }
        return applyNullable(parseSimpleType())
    }

    private TypedToken applyNullable(TypedToken token) {
        if (match(TokenType.QUESTION)) {
            return new TypedToken(token.typeName + "?", token.token)
        }
        return token
    }

    private TypedToken parseSimpleType() {
        if (match(TokenType.VOID)) return new TypedToken('void', previous)
        if (match(TokenType.INT_TYPE)) return new TypedToken('int', previous)
        if (match(TokenType.LONG_TYPE)) return new TypedToken('long', previous)
        if (match(TokenType.FLOAT_TYPE)) return new TypedToken('float', previous)
        if (match(TokenType.DOUBLE_TYPE)) return new TypedToken('double', previous)
        if (match(TokenType.CHAR_TYPE)) return new TypedToken('char', previous)
        if (match(TokenType.BYTES_TYPE)) return new TypedToken('bytes', previous)
        if (match(TokenType.STRING_TYPE)) return new TypedToken('string', previous)
        if (match(TokenType.BOOL_TYPE)) return new TypedToken('bool', previous)
        if (match(TokenType.IDENT)) return new TypedToken(previous.lexeme, previous)
        throw new IllegalArgumentException('Expected type')
    }

    private Block parseBlock() {
        Token start = consume(TokenType.LBRACE, "Expected '{'")
        List<Statement> statements = []
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            statements.add(parseStatement())
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return new Block(statements, pos(start))
    }

    private Statement parseStatement() {
        Statement stmt
        if (match(TokenType.VAL)) {
            stmt = parseVarDecl(Mutability.VAL, true)
        } else if (match(TokenType.VAR)) {
            stmt = parseVarDecl(Mutability.VAR, true)
        } else if (match(TokenType.CONST)) {
            stmt = parseVarDecl(Mutability.CONST, true)
        } else if (match(TokenType.PRINT)) {
            stmt = parsePrint()
        } else if (match(TokenType.RETURN)) {
            stmt = parseReturn()
        } else {
            stmt = parseExprOrAssignStmt()
        }
        consumeTerminators()
        return stmt
    }

    private Statement parseExprOrAssignStmt() {
        Expr expr = parseExpr()
        if (match(TokenType.EQUAL)) {
            if (!(expr instanceof VarRef || expr instanceof FieldAccess || expr instanceof IndexAccess)) {
                throw new IllegalArgumentException("Invalid assignment target at ${pos(previous)}")
            }
            Expr value = parseExpr()
            return new AssignStmt(expr, value, expr.pos ?: SourcePos.UNKNOWN)
        }
        return new ExprStmt(expr, expr.pos ?: SourcePos.UNKNOWN)
    }

    private Statement parseVarDecl(Mutability mutability, boolean keywordConsumed) {
        Token start = keywordConsumed ? previous : current
        String declaredType = null
        Token nameTok
        boolean typeFirst = false
        if (isTypeStart(current.type)) {
            if (current.type == TokenType.IDENT) {
                typeFirst = peekNextType() == TokenType.IDENT
            } else if (current.type != TokenType.IDENT || peekNextType() != TokenType.COLON) {
                typeFirst = true
            }
        }
        if (typeFirst) {
            TypedToken tt = parseType()
            declaredType = tt.typeName
            nameTok = consume(TokenType.IDENT, 'Expected variable name')
        } else {
            nameTok = consume(TokenType.IDENT, 'Expected variable name')
            if (match(TokenType.COLON)) {
                TypedToken tt = parseType()
                declaredType = tt.typeName
            }
        }
        consume(TokenType.EQUAL, "Expected '=' after variable name")
        Expr expr = parseExpr()
        return new VarDecl(nameTok.lexeme, declaredType, mutability, expr, pos(start))
    }

    private Statement parsePrint() {
        Token start = consume(TokenType.LPAREN, "Expected '(' after print")
        Expr expr = parseExpr()
        consume(TokenType.RPAREN, "Expected ')' after print expression")
        return new PrintStmt(expr, pos(start))
    }

    private Statement parseReturn() {
        Token start = previous
        if (check(TokenType.SEMICOLON) || check(TokenType.RBRACE) || check(TokenType.EOF)) {
            return new ReturnStmt(null, pos(start))
        }
        Expr expr = parseExpr()
        return new ReturnStmt(expr, pos(start))
    }

    private Statement parseExprStmt() {
        Expr expr = parseExpr()
        return new ExprStmt(expr, expr.pos ?: SourcePos.UNKNOWN)
    }

    private Expr parseExpr() {
        return parseTernary()
    }

    private Expr parseTernary() {
        Expr expr = parseMatchOrIf()
        if (match(TokenType.QUESTION)) {
            Token qTok = previous
            if (match(TokenType.COLON)) {
                Expr right = parseTernary()
                return new ElvisExpr(expr, right, pos(qTok))
            }
            Expr ifTrue = parseTernary()
            consume(TokenType.COLON, "Expected ':' in ternary expression")
            Expr ifFalse = parseTernary()
            return new TernaryExpr(expr, ifTrue, ifFalse, pos(qTok))
        }
        return expr
    }

    private Expr parseMatchOrIf() {
        if (match(TokenType.IF)) {
            return parseIfExpr()
        }
        if (match(TokenType.MATCH)) {
            return parseMatchExpr()
        }
        return parseSum()
    }

    private Expr parseSum() {
        Expr expr = parseTerm()
        while (match(TokenType.PLUS) || match(TokenType.MINUS)) {
            Token opTok = previous
            String op = opTok.lexeme
            Expr right = parseTerm()
            expr = new BinaryOp(op, expr, right, pos(opTok))
        }
        return expr
    }

    private Expr parseTerm() {
        Expr expr = parseFactor()
        while (match(TokenType.STAR) || match(TokenType.SLASH)) {
            Token opTok = previous
            String op = opTok.lexeme
            Expr right = parseFactor()
            expr = new BinaryOp(op, expr, right, pos(opTok))
        }
        return expr
    }

    private Expr parseFactor() {
        Expr expr = parsePrimary()
        while (true) {
            if (match(TokenType.SAFE_DOT)) {
                Token nameTok = consume(TokenType.IDENT, 'Expected field name')
                expr = new SafeFieldAccess(expr, nameTok.lexeme, pos(nameTok))
            } else if (match(TokenType.DOT)) {
                Token nameTok = consume(TokenType.IDENT, 'Expected field name')
                expr = new FieldAccess(expr, nameTok.lexeme, pos(nameTok))
            } else if (match(TokenType.LBRACKET)) {
                Expr index = parseExpr()
                consume(TokenType.RBRACKET, "Expected ']' after index")
                expr = new IndexAccess(expr, index, pos(previous))
            } else {
                break
            }
        }
        return expr
    }

    private Expr parsePrimary() {
        if (match(TokenType.INT_LITERAL)) {
            Token tok = previous
            return new IntLiteral(Long.parseLong(tok.lexeme), pos(tok))
        }
        if (match(TokenType.BOOL_LITERAL)) {
            Token tok = previous
            return new BoolLiteral(Boolean.parseBoolean(tok.lexeme), pos(tok))
        }
        if (match(TokenType.NULL)) {
            Token tok = previous
            return new NullLiteral(pos(tok))
        }
        if (match(TokenType.STRING_LITERAL)) {
            Token tok = previous
            return new StringLiteral(tok.lexeme, pos(tok))
        }
        if (match(TokenType.LBRACKET)) {
            return parseCollectionExpr(previous)
        }
        if (match(TokenType.IDENT)) {
            Token tok = previous
            if (match(TokenType.LPAREN)) {
                List<Expr> args = []
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpr())
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RPAREN, "Expected ')' after arguments")
                return new CallExpr(tok.lexeme, args, pos(tok))
            }
            if (check(TokenType.LBRACE) && isTypeIdentifier(tok)) {
                return parseRecordLiteral(tok)
            }
            return new VarRef(tok.lexeme, pos(tok))
        }
        if (match(TokenType.LPAREN)) {
            Expr expr = parseExpr()
            consume(TokenType.RPAREN, "Expected ')'")
            return expr
        }
        throw new IllegalArgumentException("Unexpected token: ${current.lexeme}")
    }

    private Expr parseRecordLiteral(Token nameTok) {
        consume(TokenType.LBRACE, "Expected '{' after record name")
        Map<String, Expr> fields = [:]
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            Token field = consume(TokenType.IDENT, 'Expected field name')
            consume(TokenType.EQUAL, "Expected '=' in record literal")
            Expr value = parseExpr()
            fields[field.lexeme] = value
            if (match(TokenType.COMMA)) {
                consumeTerminators()
                continue
            }
            consumeTerminators()
        }
        consume(TokenType.RBRACE, "Expected '}' after record literal")
        return new RecordLiteral(nameTok.lexeme, fields, pos(nameTok))
    }

    private Expr parseCollectionExpr(Token startTok) {
        if (match(TokenType.COLON)) {
            consume(TokenType.RBRACKET, "Expected ']' after [:]")
            consume(TokenType.LPAREN, "Expected '(' after map type")
            Expr capacity = parseExpr()
            consume(TokenType.RPAREN, "Expected ')' after map capacity")
            return new MapAllocExpr('string', 'string', capacity, pos(startTok))
        }
        TypedToken first = parseSimpleType()
        if (match(TokenType.COLON)) {
            TypedToken second = parseSimpleType()
            consume(TokenType.RBRACKET, "Expected ']' after map type")
            if (match(TokenType.LBRACE)) {
                List<MapEntryExpr> entries = []
                while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                    Expr key = parseExpr()
                    Token colon = consume(TokenType.COLON, "Expected ':' in map entry")
                    Expr value = parseExpr()
                    entries.add(new MapEntryExpr(key, value, pos(colon)))
                    if (match(TokenType.COMMA)) {
                        consumeTerminators()
                        continue
                    }
                    consumeTerminators()
                }
                consume(TokenType.RBRACE, "Expected '}' after map literal")
                return new MapLiteralExpr(first.typeName, second.typeName, entries, pos(startTok))
            }
            consume(TokenType.LPAREN, "Expected '(' after map type")
            Expr capacity = parseExpr()
            consume(TokenType.RPAREN, "Expected ')' after map capacity")
            return new MapAllocExpr(first.typeName, second.typeName, capacity, pos(startTok))
        }
        consume(TokenType.RBRACKET, "Expected ']' after array type")
        consume(TokenType.LPAREN, "Expected '(' after array type")
        Expr size = parseExpr()
        consume(TokenType.RPAREN, "Expected ')' after array size")
        return new ArrayAllocExpr(first.typeName, size, pos(startTok))
    }

    private Expr parseIfExpr() {
        Token start = previous
        Expr condition = parseExpr()
        Block thenBlock = parseBlock()
        Block elseBlock = null
        if (match(TokenType.ELSE)) {
            elseBlock = parseBlock()
        }
        return new IfExpr(condition, thenBlock, elseBlock, pos(start))
    }

    private Expr parseMatchExpr() {
        Token start = previous
        Expr target = parseExpr()
        consume(TokenType.LBRACE, "Expected '{' after match target")
        List<MatchCase> cases = []
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            Expr key = parseExpr()
            Token eq = consume(TokenType.EQUAL, "Expected '=' in match case")
            Expr value = parseExpr()
            cases.add(new MatchCase(key, value, pos(eq)))
            consumeTerminators()
        }
        consume(TokenType.RBRACE, "Expected '}' after match cases")
        consume(TokenType.ELSE, "Expected 'else' after match block")
        Expr elseExpr = parseExpr()
        return new MatchExpr(target, cases, elseExpr, pos(start))
    }

    private void consumeTerminators() {
        while (match(TokenType.SEMICOLON)) {
            // consume optional semicolons
        }
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance()
        Token token = current
        String location = sourceName ? " in ${sourceName}" : ""
        SourcePos position = pos(token)
        throw new IllegalArgumentException("Parse error${location} at ${position}: ${message} (found ${token.type})")
    }

    private boolean check(TokenType type) {
        return current.type == type
    }

    private boolean isTypeStart(TokenType type) {
        switch (type) {
            case TokenType.VOID:
            case TokenType.INT_TYPE:
            case TokenType.LONG_TYPE:
            case TokenType.FLOAT_TYPE:
            case TokenType.DOUBLE_TYPE:
            case TokenType.CHAR_TYPE:
            case TokenType.BYTES_TYPE:
            case TokenType.STRING_TYPE:
            case TokenType.BOOL_TYPE:
            case TokenType.IDENT:
            case TokenType.LBRACKET:
                return true
            default:
                return false
        }
    }

    private Token advance() {
        previous = tokens[pos]
        return tokens[pos++]
    }

    private TokenType peekNextType() {
        if (pos + 1 >= tokens.size()) return TokenType.EOF
        return tokens[pos + 1].type
    }

    private SourcePos pos(Token token) {
        if (token == null) return SourcePos.UNKNOWN
        return new SourcePos(token.line, token.column, sourceName)
    }

    private boolean isTypeIdentifier(Token token) {
        if (token == null || token.lexeme == null || token.lexeme.isEmpty()) {
            return false
        }
        char first = token.lexeme.charAt(0)
        return Character.isUpperCase(first)
    }
}

class Lexer {
    private final String src
    private final List<Token> tokens = []
    private int index = 0
    private int line = 1
    private int column = 1

    Lexer(String src) {
        this.src = src
    }

    List<Token> tokenize() {
        while (!isAtEnd()) {
            skipWhitespace()
            if (isAtEnd()) break
            char ch = peek()
            if (Character.isLetter(ch) || ch == '_') {
                tokens.add(scanIdentifier())
            } else if (Character.isDigit(ch)) {
                tokens.add(scanNumber())
            } else {
                switch (ch) {
                    case '{':
                        tokens.add(single(TokenType.LBRACE, '{' as String)); break
                    case '}':
                        tokens.add(single(TokenType.RBRACE, '}' as String)); break
                    case '(':
                        tokens.add(single(TokenType.LPAREN, '(' as String)); break
                    case ')':
                        tokens.add(single(TokenType.RPAREN, ')' as String)); break
                    case '[':
                        tokens.add(single(TokenType.LBRACKET, '[' as String)); break
                    case ']':
                        tokens.add(single(TokenType.RBRACKET, ']' as String)); break
                    case '+':
                        tokens.add(single(TokenType.PLUS, '+' as String)); break
                    case '-':
                        tokens.add(single(TokenType.MINUS, '-' as String)); break
                    case '*':
                        tokens.add(single(TokenType.STAR, '*' as String)); break
                    case '/':
                        if (peekNext() == '/') {
                            advance(); advance()
                            skipLineComment()
                        } else {
                            tokens.add(single(TokenType.SLASH, '/' as String))
                        }
                        break
                    case '=':
                        tokens.add(single(TokenType.EQUAL, '=' as String)); break
                    case ':':
                        tokens.add(single(TokenType.COLON, ':' as String)); break
                    case ',':
                        tokens.add(single(TokenType.COMMA, ',' as String)); break
                    case ';':
                        tokens.add(single(TokenType.SEMICOLON, ';' as String)); break
                    case '?':
                        if (peekNext() == '.') {
                            int startLine = line
                            int startCol = column
                            advance()
                            advance()
                            tokens.add(new Token(TokenType.SAFE_DOT, '?.', startLine, startCol))
                        } else {
                            tokens.add(single(TokenType.QUESTION, '?' as String))
                        }
                        break
                    case '.':
                        tokens.add(single(TokenType.DOT, '.' as String)); break
                    case '"':
                        tokens.add(scanString()); break
                    default:
                        throw new IllegalArgumentException("Unexpected character '${ch}' at ${index}")
                }
            }
        }
        tokens.add(new Token(TokenType.EOF, '', line, column))
        return tokens
    }

    private Token scanIdentifier() {
        int start = index
        int startLine = line
        int startCol = column
        while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) advance()
        String text = src.substring(start, index)
        switch (text) {
            case 'fun': return new Token(TokenType.FUN, text, startLine, startCol)
            case 'void': return new Token(TokenType.VOID, text, startLine, startCol)
            case 'int': return new Token(TokenType.INT_TYPE, text, startLine, startCol)
            case 'long': return new Token(TokenType.LONG_TYPE, text, startLine, startCol)
            case 'float': return new Token(TokenType.FLOAT_TYPE, text, startLine, startCol)
            case 'double': return new Token(TokenType.DOUBLE_TYPE, text, startLine, startCol)
            case 'char': return new Token(TokenType.CHAR_TYPE, text, startLine, startCol)
            case 'bytes': return new Token(TokenType.BYTES_TYPE, text, startLine, startCol)
            case 'string': return new Token(TokenType.STRING_TYPE, text, startLine, startCol)
            case 'bool': return new Token(TokenType.BOOL_TYPE, text, startLine, startCol)
            case 'val': return new Token(TokenType.VAL, text, startLine, startCol)
            case 'var': return new Token(TokenType.VAR, text, startLine, startCol)
            case 'const': return new Token(TokenType.CONST, text, startLine, startCol)
            case 'print': return new Token(TokenType.PRINT, text, startLine, startCol)
            case 'return': return new Token(TokenType.RETURN, text, startLine, startCol)
            case 'if': return new Token(TokenType.IF, text, startLine, startCol)
            case 'else': return new Token(TokenType.ELSE, text, startLine, startCol)
            case 'match': return new Token(TokenType.MATCH, text, startLine, startCol)
            case 'record': return new Token(TokenType.RECORD, text, startLine, startCol)
            case 'null': return new Token(TokenType.NULL, text, startLine, startCol)
            case 'package': return new Token(TokenType.PACKAGE, text, startLine, startCol)
            case 'import': return new Token(TokenType.IMPORT, text, startLine, startCol)
            case 'true': return new Token(TokenType.BOOL_LITERAL, text, startLine, startCol)
            case 'false': return new Token(TokenType.BOOL_LITERAL, text, startLine, startCol)
            default: return new Token(TokenType.IDENT, text, startLine, startCol)
        }
    }

    private Token scanNumber() {
        int start = index
        int startLine = line
        int startCol = column
        while (!isAtEnd() && Character.isDigit(peek())) advance()
        String text = src.substring(start, index)
        return new Token(TokenType.INT_LITERAL, text, startLine, startCol)
    }

    private Token scanString() {
        Token startTok = single(TokenType.STRING_LITERAL, '"' as String)
        int start = index
        while (!isAtEnd() && peek() != '"') advance()
        if (isAtEnd()) throw new IllegalArgumentException('Unterminated string literal')
        String text = src.substring(start, index)
        advance() // closing quote
        return new Token(TokenType.STRING_LITERAL, text, startTok.line, startTok.column)
    }

    private void skipWhitespace() {
        while (!isAtEnd()) {
            switch (peek()) {
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    advance()
                    break
                default:
                    return
            }
        }
    }

    private void skipLineComment() {
        while (!isAtEnd() && peek() != '\n') advance()
    }

    private Token single(TokenType type, String ch) {
        int startLine = line
        int startCol = column
        advance()
        return new Token(type, ch, startLine, startCol)
    }

    private char peek() {
        return src.charAt(index)
    }

    private char peekNext() {
        return (index + 1 >= src.length()) ? '\u0000' : src.charAt(index + 1)
    }

    private char advance() {
        char ch = src.charAt(index++)
        if (ch == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return ch
    }

    private boolean isAtEnd() {
        return index >= src.length()
    }
}

class Token {
    final TokenType type
    final String lexeme
    final int line
    final int column

    Token(TokenType type, String lexeme, int line, int column) {
        this.type = type
        this.lexeme = lexeme
        this.line = line
        this.column = column
    }
}

enum TokenType {
    FUN, VOID, INT_TYPE, LONG_TYPE, FLOAT_TYPE, DOUBLE_TYPE, CHAR_TYPE, BYTES_TYPE, STRING_TYPE, BOOL_TYPE,
    VAL, VAR, CONST, PRINT, RETURN, IF, ELSE, MATCH, RECORD, NULL, PACKAGE, IMPORT,
    IDENT, INT_LITERAL, STRING_LITERAL, BOOL_LITERAL,
    LBRACE, RBRACE, LPAREN, RPAREN, LBRACKET, RBRACKET, DOT, SAFE_DOT,
    PLUS, MINUS, STAR, SLASH, EQUAL, COLON, COMMA, SEMICOLON, QUESTION,
    EOF
}

class TypedToken {
    final String typeName
    final Token token

    TypedToken(String typeName, Token token) {
        this.typeName = typeName
        this.token = token
    }
}
