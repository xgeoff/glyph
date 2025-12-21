package biz.digitalindustry.glyph.core

import groovy.transform.Canonical

@Canonical
class SourcePos {
    int line
    int column
    String file

    static final SourcePos UNKNOWN = new SourcePos(-1, -1, null)
}
