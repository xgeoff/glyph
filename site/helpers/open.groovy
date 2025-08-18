package scaffold.basic.helpers

import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.Options
import com.github.jknack.handlebars.Handlebars

return { Object tagArg, Options options ->
    String tag = String.valueOf(tagArg ?: "").trim()
    String cls = (options.hash['class'] ?: "").toString().trim()
    String classAttr = cls ? " class=\"${Handlebars.Utils.escapeExpression(cls)}\"" : ""

    new Handlebars.SafeString("<${tag}${classAttr}>")
} as Helper
