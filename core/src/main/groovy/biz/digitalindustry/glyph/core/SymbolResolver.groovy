package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.ImportDecl
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.TypeAliasDecl
import biz.digitalindustry.glyph.core.ast.SumTypeDecl
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
        Map<String, TypeAliasDecl> aliases = [:]
        Map<String, SumTypeDecl> sumTypes = [:]

        addPackageSymbols(pkg, functions, records, aliases, sumTypes)
        program.typeAliases.each { TypeAliasDecl alias -> aliases[alias.name] = alias }
        program.records.each { RecordDecl record -> records[record.name] = record }
        program.sumTypes.each { SumTypeDecl sumType -> sumTypes[sumType.name] = sumType }
        program.functions.each { FunctionDecl fn -> functions[fn.name] = fn }
        program.imports.each { ImportDecl decl ->
            addImportSymbol(decl, functions, records, aliases, sumTypes)
        }
        return new ResolvedSymbols(pkg,
                functions.asImmutable(),
                records.asImmutable(),
                aliases.asImmutable(),
                sumTypes.asImmutable())
    }

    private void addPackageSymbols(String pkg,
                                   Map<String, FunctionDecl> functions,
                                   Map<String, RecordDecl> records,
                                   Map<String, TypeAliasDecl> aliases,
                                   Map<String, SumTypeDecl> sumTypes) {
        index.recordsInPackage(pkg).each { RecordDecl record ->
            records.putIfAbsent(record.name, record)
        }
        index.functionsInPackage(pkg).each { FunctionDecl fn ->
            functions.putIfAbsent(fn.name, fn)
        }
        index.typeAliasesInPackage(pkg).each { TypeAliasDecl alias ->
            aliases.putIfAbsent(alias.name, alias)
        }
        index.sumTypesInPackage(pkg).each { SumTypeDecl sumType ->
            sumTypes.putIfAbsent(sumType.name, sumType)
        }
    }

    private void addImportSymbol(ImportDecl decl,
                                 Map<String, FunctionDecl> functions,
                                 Map<String, RecordDecl> records,
                                 Map<String, TypeAliasDecl> aliases,
                                 Map<String, SumTypeDecl> sumTypes) {
        String fqName = decl.path
        FunctionDecl fn = index.findFunction(fqName)
        RecordDecl record = index.findRecord(fqName)
        TypeAliasDecl alias = index.findTypeAlias(fqName)
        SumTypeDecl sumType = index.findSumType(fqName)
        if (!fn && !record && !alias && !sumType) {
            throw new IllegalArgumentException("Symbol not found: ${fqName}${formatPos(decl.pos)}")
        }
        String simpleName = simpleName(fqName)
        if (fn) {
            if (functions.containsKey(simpleName)) {
                throw new IllegalArgumentException("Symbol '${simpleName}' already defined${formatPos(decl.pos)}")
            }
            functions[simpleName] = fn
            return
        }
        if (record) {
            if (records.containsKey(simpleName)) {
                throw new IllegalArgumentException("Symbol '${simpleName}' already defined${formatPos(decl.pos)}")
            }
            records[simpleName] = record
            return
        }
        if (aliases.containsKey(simpleName)) {
            throw new IllegalArgumentException("Symbol '${simpleName}' already defined${formatPos(decl.pos)}")
        }
        if (alias) {
            aliases[simpleName] = alias
            return
        }
        if (sumType) {
            if (sumTypes.containsKey(simpleName)) {
                throw new IllegalArgumentException("Symbol '${simpleName}' already defined${formatPos(decl.pos)}")
            }
            sumTypes[simpleName] = sumType
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
    final Map<String, TypeAliasDecl> aliases
    final Map<String, SumTypeDecl> sumTypes

    ResolvedSymbols(String packageName,
                    Map<String, FunctionDecl> functions,
                    Map<String, RecordDecl> records,
                    Map<String, TypeAliasDecl> aliases,
                    Map<String, SumTypeDecl> sumTypes) {
        this.packageName = packageName ?: ''
        this.functions = functions ?: [:]
        this.records = records ?: [:]
        this.aliases = aliases ?: [:]
        this.sumTypes = sumTypes ?: [:]
    }

    FunctionDecl function(String name) {
        functions[name]
    }

    RecordDecl record(String name) {
        records[name]
    }

    TypeAliasDecl alias(String name) {
        aliases[name]
    }

    SumTypeDecl sumType(String name) {
        sumTypes[name]
    }
}
