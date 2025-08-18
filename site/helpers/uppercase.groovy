package scaffold.basic.helpers

import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.Options

return { Object context, Options options ->
    context?.toString()?.toUpperCase()
} as Helper
