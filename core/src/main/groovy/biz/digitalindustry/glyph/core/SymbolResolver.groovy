package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.ImportDecl
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.SourcePos

class SymbolResolver {

    private final ProjectIndex index

    SymbolResolver(ProjectIndex index) {
        this.index = index ?: new ProjectIndex()
    }

    ResolvedSymbols resolve(Program program) {
        if (program == null) {
            throw new IllegalArgumentException('Program must not be null')
        }
        String pkg = program.packageDecl?.name ?: ''
        Map<String, FunctionDecl> functions = [:]
        Map<String, RecordDecl> records = [:]

        addPackageSymbols(pkg, functions, records)
        program.records.each { RecordDecl record -> records[record.name] = record }
        program.functions.each { FunctionDecl fn -> functions[fn.name] = fn }
        program.imports.each { ImportDecl decl ->
            addImportSymbol(decl, functions, records)
        }
        return new ResolvedSymbols(pkg, functions.asImmutable(), records.asImmutable())
    }

    private void addPackageSymbols(String pkg, Map<String, FunctionDecl> functions, Map<String, RecordDecl> records) {
        index.recordsInPackage(pkg).each { RecordDecl record ->
            records.putIfAbsent(record.name, record)
        }
        index.functionsInPackage(pkg).each { FunctionDecl fn ->
            functions.putIfAbsent(fn.name, fn)
        }
    }

    private void addImportSymbol(ImportDecl decl, Map<String, FunctionDecl> functions, Map<String, RecordDecl> records) {
        String fqName = decl.path
        FunctionDecl fn = index.findFunction(fqName)
        RecordDecl record = index.findRecord(fqName)
        if (!fn && !record) {
            throw new IllegalArgumentException("Symbol not found: ${fqName}${formatPos(decl.pos)}")
        }
        String simpleName = simpleName(fqName)
        if (fn) {
            if (functions.containsKey(simpleName)) {
                throw new IllegalArgumentException("Symbol '${simpleName}' already defined${formatPos(decl.pos)}")
            }
            functions[simpleName] = fn
        } else {
            if (records.containsKey(simpleName)) {
                throw new IllegalArgumentException("Symbol '${simpleName}' already defined${formatPos(decl.pos)}")
            }
            records[simpleName] = record
        }
    }

    private static String simpleName(String fqName) {
        int idx = fqName.lastIndexOf('.')
        return idx == -1 ? fqName : fqName.substring(idx + 1)
    }

    private static String formatPos(SourcePos pos) {
        if (!pos || pos == SourcePos.UNKNOWN) {
            return ''
        }
        String filePart = pos.file ? "${pos.file}:" : ""
        String colPart = pos.column > 0 ? ":${pos.column}" : ""
        return " [${filePart}${pos.line}${colPart}]"
    }
}

class ResolvedSymbols {
    final String packageName
    final Map<String, FunctionDecl> functions
    final Map<String, RecordDecl> records

    ResolvedSymbols(String packageName,
                    Map<String, FunctionDecl> functions,
                    Map<String, RecordDecl> records) {
        this.packageName = packageName ?: ''
        this.functions = functions ?: [:]
        this.records = records ?: [:]
    }

    FunctionDecl function(String name) {
        functions[name]
    }

    RecordDecl record(String name) {
        records[name]
    }
}
