package scaffold.basic.helpers

import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.Options
import java.time.format.DateTimeFormatter
import java.time.LocalDate

return { Object context, Options options ->
    def pattern = options.param(0, "yyyy-MM-dd")
    def formatter = DateTimeFormatter.ofPattern(pattern)
    def date = LocalDate.parse(context.toString())
    formatter.format(date)
} as Helper
