package biz.digitalindustry.glyph.core

class RuntimeImport {
    final String name
    final byte[] params
    final byte[] results
    int typeIndex = -1
    int functionIndex = -1

    RuntimeImport(String name, byte[] params, byte[] results) {
        this.name = name
        this.params = params ?: ([] as byte[])
        this.results = results ?: ([] as byte[])
    }
}

class RuntimeImports {
    static final String MODULE = 'env'

    static List<RuntimeImport> all(byte valI32) {
        byte[] none = [] as byte[]
        byte[] single = [valI32] as byte[]
        byte[] doubleVals = [valI32, valI32] as byte[]
        byte[] tripleVals = [valI32, valI32, valI32] as byte[]

        return [
                new RuntimeImport('print_i32', single, none),
                new RuntimeImport('print_str', single, none),
                new RuntimeImport('glyph_malloc', single, single),
                new RuntimeImport('array_get', doubleVals, single),
                new RuntimeImport('array_set', tripleVals, none),
                new RuntimeImport('map_new', none, single),
                new RuntimeImport('map_get', doubleVals, single),
                new RuntimeImport('map_put', tripleVals, none),
                new RuntimeImport('str_eq', doubleVals, single)
        ]
    }
}
