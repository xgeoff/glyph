import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.Options

return { Object context, Options options ->
    def pagesDir = options.context.model('pagesDir') as File
    if (!pagesDir?.exists() || !pagesDir.directory) {
        return []
    }

    def toSidebarItems
    toSidebarItems = { File dir, File root ->
        def items = []

        dir.eachFile { file ->
            if (file.directory) {
                def children = toSidebarItems(file, root)
                if (children) {
                    items << [
                            type    : 'directory',
                            name    : file.name,
                            children: children
                    ]
                }
            } else if (file.name ==~ /(?i).*\.md|\.html?$/) {
                def relativePath = root.toPath().relativize(file.toPath()).toString().replace(File.separator, "/")
                def name = file.name.replaceFirst(/(?i)\.(md|html?)$/, "")

                items << [
                        type: 'file',
                        name: name,
                        path: relativePath
                ]
            }
        }

        items.sort { a, b ->
            a.type <=> b.type ?: a.name.toLowerCase() <=> b.name.toLowerCase()
        }
    }

    toSidebarItems(pagesDir, pagesDir)
} as Helper
