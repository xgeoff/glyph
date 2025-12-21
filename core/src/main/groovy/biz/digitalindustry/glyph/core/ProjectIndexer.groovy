package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.FunctionDecl
import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.RecordDecl
import groovy.transform.Canonical

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

class ProjectIndexer {

    static ProjectIndex index(Path rootDir, Path grammarPath = null) {
        if (rootDir == null) {
            throw new IllegalArgumentException('rootDir must not be null')
        }
        Path normalizedRoot = rootDir.toAbsolutePath().normalize()
        Map<String, FunctionDecl> functions = [:]
        Map<String, RecordDecl> records = [:]
        Map<Path, Program> programs = [:]

        Stream<Path> stream = Files.walk(normalizedRoot)
        try {
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.gly') }
                    .forEach { Path file ->
                        Path resolved = file.toAbsolutePath().normalize()
                        Program program = GlyphParser.parse(resolved, grammarPath)
                        programs[resolved] = program
                        String pkg = packageName(program)
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

        return new ProjectIndex(functions.asImmutable(), records.asImmutable(), programs.asImmutable())
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

    FunctionDecl findFunction(String fqn) {
        functionsByFqn[fqn]
    }

    RecordDecl findRecord(String fqn) {
        recordsByFqn[fqn]
    }

    Collection<FunctionDecl> functionsInPackage(String pkg) {
        String normalized = pkg ?: ''
        functionsByFqn.findAll { key, _ -> packagePart(key) == normalized }.values()
    }

    Collection<RecordDecl> recordsInPackage(String pkg) {
        String normalized = pkg ?: ''
        recordsByFqn.findAll { key, _ -> packagePart(key) == normalized }.values()
    }

    private static String packagePart(String fqn) {
        int idx = fqn.lastIndexOf('.')
        return idx == -1 ? '' : fqn.substring(0, idx)
    }
}
