package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.TypeAliasDecl
import biz.digitalindustry.glyph.core.ast.SumTypeDecl
import groovy.transform.Canonical

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

class ProjectIndexer {

    static ProjectIndex index(Path rootDir, Path grammarPath = null, Collection<Path> libraryDirs = Collections.emptyList()) {
        if (rootDir == null) {
            throw new IllegalArgumentException('rootDir must not be null')
        }
        Path normalizedRoot = rootDir.toAbsolutePath().normalize()
        Map<String, FunctionDecl> functions = [:]
        Map<String, RecordDecl> records = [:]
        Map<Path, Program> programs = [:]
        Map<String, TypeAliasDecl> aliases = [:]
        Map<String, SumTypeDecl> sumTypes = [:]

        indexDirectory(normalizedRoot, grammarPath, functions, records, aliases, sumTypes, programs)

        libraryDirs?.each { Path libDir ->
            if (!libDir) {
                return
            }
            Path normalized = libDir.toAbsolutePath().normalize()
            indexDirectory(normalized, grammarPath, functions, records, aliases, sumTypes, programs)
        }

        return new ProjectIndex(functions.asImmutable(), records.asImmutable(), programs.asImmutable(), aliases.asImmutable(), sumTypes.asImmutable())
    }

    private static void indexDirectory(Path dir,
                                       Path grammarPath,
                                       Map<String, FunctionDecl> functions,
                                       Map<String, RecordDecl> records,
                                       Map<String, TypeAliasDecl> aliases,
                                       Map<String, SumTypeDecl> sumTypes,
                                       Map<Path, Program> programs) {
        if (dir == null || !Files.exists(dir)) {
            return
        }
        Stream<Path> stream = Files.walk(dir)
        try {
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.gly') }
                    .forEach { Path file ->
                        Path resolved = file.toAbsolutePath().normalize()
                        if (programs.containsKey(resolved)) {
                            return
                        }
                        Program program = GlyphParser.parse(resolved, grammarPath)
                        programs[resolved] = program
                        String pkg = packageName(program)
                        program.typeAliases.each { TypeAliasDecl alias ->
                            String fqn = qualify(pkg, alias.name)
                            if (aliases.containsKey(fqn)) {
                                throw new IllegalStateException("Duplicate type alias '${fqn}' found in ${file}")
                            }
                            aliases[fqn] = alias
                        }
                        program.sumTypes.each { SumTypeDecl sumType ->
                            String fqn = qualify(pkg, sumType.name)
                            if (sumTypes.containsKey(fqn)) {
                                throw new IllegalStateException("Duplicate sum type '${fqn}' found in ${file}")
                            }
                            sumTypes[fqn] = sumType
                        }
                        program.records.each { RecordDecl record ->
                            String fqn = qualify(pkg, record.name)
                            if (records.containsKey(fqn)) {
                                throw new IllegalStateException("Duplicate record '${fqn}' found in ${file}")
                            }
                            records[fqn] = record
                        }
                        program.functions.each { FunctionDecl fn ->
                            String fqn = qualify(pkg, fn.name)
                            if (functions.containsKey(fqn)) {
                                throw new IllegalStateException("Duplicate function '${fqn}' found in ${file}")
                            }
                            functions[fqn] = fn
                        }
                    }
        } finally {
            stream?.close()
        }
    }

    private static String packageName(Program program) {
        program?.packageDecl?.name ?: ''
    }

    static String qualify(String pkg, String name) {
        (pkg ?: '').isEmpty() ? name : "${pkg}.${name}"
    }
}

@Canonical
class ProjectIndex {
    Map<String, FunctionDecl> functionsByFqn = [:]
    Map<String, RecordDecl> recordsByFqn = [:]
    Map<Path, Program> programsByFile = [:]
    Map<String, TypeAliasDecl> typeAliasesByFqn = [:]
    Map<String, SumTypeDecl> sumTypesByFqn = [:]

    FunctionDecl findFunction(String fqn) {
        functionsByFqn[fqn]
    }

    RecordDecl findRecord(String fqn) {
        recordsByFqn[fqn]
    }

    TypeAliasDecl findTypeAlias(String fqn) {
        typeAliasesByFqn[fqn]
    }

    SumTypeDecl findSumType(String fqn) {
        sumTypesByFqn[fqn]
    }

    Collection<FunctionDecl> functionsInPackage(String pkg) {
        String normalized = pkg ?: ''
        functionsByFqn.findAll { key, _ -> packagePart(key) == normalized }.values()
    }

    Collection<RecordDecl> recordsInPackage(String pkg) {
        String normalized = pkg ?: ''
        recordsByFqn.findAll { key, _ -> packagePart(key) == normalized }.values()
    }

    Collection<TypeAliasDecl> typeAliasesInPackage(String pkg) {
        String normalized = pkg ?: ''
        typeAliasesByFqn.findAll { key, _ -> packagePart(key) == normalized }.values()
    }

    Collection<SumTypeDecl> sumTypesInPackage(String pkg) {
        String normalized = pkg ?: ''
        sumTypesByFqn.findAll { key, _ -> packagePart(key) == normalized }.values()
    }

    private static String packagePart(String fqn) {
        int idx = fqn.lastIndexOf('.')
        return idx == -1 ? '' : fqn.substring(0, idx)
    }
}
