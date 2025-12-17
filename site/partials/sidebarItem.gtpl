<%
def entry = item ?: [:]
def normalizedBase = ''
if (binding.hasVariable('baseUrl') && baseUrl) {
    normalizedBase = baseUrl.toString()
}
def sanitizedBase = normalizedBase.replaceAll(/\/+$/, '')
if (sanitizedBase == '/') {
    sanitizedBase = ''
}
def relativePath = (entry.path ?: '').replaceAll(/^\/+/, '')
def pagePath = relativePath.endsWith('.html') || relativePath.isEmpty() ? relativePath : "${relativePath}.html"
def href = sanitizedBase ? "${sanitizedBase}/${pagePath}" : "/${pagePath}"
href = href.replaceAll(/\/+/, '/').replaceAll(/^$/, '/')
%>
<% if (entry.type == 'directory') { %>
<li class="folder">
    <span>${entry.name}</span>
    <ul>
<% (entry.children ?: []).each { child -> %>
        ${partial('sidebarItem', [item: child])}
<% } %>
    </ul>
</li>
<% } else if (entry.type == 'file') { %>
<li class="file"><a href="${href}">${entry.name}</a></li>
<% } %>
