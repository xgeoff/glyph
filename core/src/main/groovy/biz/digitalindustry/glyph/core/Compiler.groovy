package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program

interface Compiler {
    void compile(Program program)
}

class NoopCompiler implements Compiler {
    public static final NoopCompiler INSTANCE = new NoopCompiler()

    @Override
    void compile(Program program) {
        // Placeholder until backends are wired in.
    }
}
